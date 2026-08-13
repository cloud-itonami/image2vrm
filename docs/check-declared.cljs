#!/usr/bin/env nbb
;; docs/check-declared.cljs — does anything this repo declares actually exist?
;;
;;   nbb docs/check-declared.cljs           # from the repo root
;;
;; This repo is a descriptor plus one small program. The descriptor claims a
;; great deal; the program does something else. Four things are measured, and
;; they fail independently:
;;
;;   1. hosts        — kotodama.jsonld routes the actor at etzhayyim.com
;;                     subdomains, and the shipped page fetches its only data
;;                     from one of them
;;   2. packages     — package.json depends on a `workspace:*` sibling left
;;                     behind when this app was extracted out of the
;;                     etzhayyim/root pnpm workspace
;;   3. capabilities — kotodama.jsonld advertises six capabilities. Is there
;;                     any code here that could implement each one?
;;   4. extraction   — migration.edn states a file count and a byte total for
;;                     what was extracted. Does the tree still match?
;;   5. licence docs — NOTICE binds the user to a rider document by filename.
;;                     Is that file here to be read?
;;
;; The README states all four as a dated measurement. A dated measurement
;; rots. This re-takes it.
;;
;; Hosts, packages and capabilities are all *extracted from the files that
;; declare them*, never hardcoded here, so a new route, a new dependency or a
;; new advertised capability comes under the check by itself.
;;
;; Exit codes are three-valued on purpose. "Could not measure" must not be
;; reachable from the same exit code as "measured, all fine":
;;
;;   0  everything declared is backed by something that exists
;;   1  at least one declared thing is absent          <- the state on 2026-08-13
;;   3  COULD NOT ANSWER — nothing was extracted (wrong directory?), the
;;      control host failed so DNS here proves nothing, or git could not
;;      describe the tree. Never report a pass from this state.

(ns check-declared
  (:require ["node:child_process" :as cp]
            ["node:dns/promises" :as dns]
            ["node:fs" :as fs]
            ["node:path" :as path]
            ["node:process" :as process]
            [clojure.string :as str]
            [promesa.core :as p]))

;; A host we do not control, used only to prove DNS works at all. If this
;; fails, every NXDOMAIN below is uninterpretable.
(def control-host "registry.npmjs.org")

;; Files that can *declare* something. Prose is deliberately excluded (no
;; ".md"): this measures what the repo declares in descriptors, manifests,
;; config and code — not what its documentation says about those
;; declarations. Including .md would make the README's own status table
;; register as a declaration of the very hosts it reports as missing.
(def declaration-exts #{".jsonld" ".json" ".ts" ".svelte" ".html" ".edn" ".js"})

;; Files that can *implement* something. A descriptor advertising a capability
;; is not evidence for it — kotodama.jsonld contains the word "autonomous"
;; precisely because it is the file making the claim. Evidence has to be code.
(def executable-exts #{".html" ".js" ".mjs" ".cjs" ".ts" ".svelte"})

(def skip-dirs #{"node_modules" ".svelte-kit" ".git" "dist" "build"})

;; capability (as advertised in kotodama.jsonld) -> patterns that would betray
;; an implementation. These are presence detectors: a capability is reported
;; absent only when *none* of its patterns appear in any executable file,
;; which is a defensible claim ("nothing here could do this"), unlike trying
;; to judge whether an implementation is complete.
;;
;; Note "3d-rendering" includes a Canvas-2D context. That is not padding: the
;; shipped renderer projects 3D points in software and paints them with the 2D
;; context, so it genuinely renders 3D — just not with the GPU the rest of the
;; repo describes. The renderer line printed below names which API was found,
;; so this row cannot be mistaken for "the declared WebGPU path exists".
(def capability-evidence
  {"avatar-generation"   [#"(?i)FileReader" #"(?i)createImageBitmap" #"(?i)getImageData"
                          #"(?i)type=[\"']file" #"(?i)\.glb\b" #"(?i)gltf" #"(?i)\.vrm\b"]
   "3d-rendering"        [#"(?i)navigator\.gpu" #"(?i)requestAdapter"
                          #"(?i)getContext\([\"']webgl" #"(?i)getContext\([\"']2d[\"']\)"
                          #"(?i)\bthree\b"]
   "vtuber"              [#"(?i)\.vrm\b" #"(?i)VRMC_vrm" #"(?i)morph" #"(?i)blendshape"
                          #"(?i)humanoid" #"(?i)springBone"]
   "llm-chat"            [#"(?i)v1/messages" #"(?i)completions" #"(?i)anthropic"
                          #"(?i)openai" #"(?i)\bprompt\b"]
   "tts-voice"           [#"(?i)speechSynthesis" #"(?i)SpeechSynthesisUtterance"
                          #"(?i)\btts\b" #"(?i)new\s+Audio"]
   "autonomous-behavior" [#"(?i)subscribeRepos" #"(?i)firehose" #"(?i)setInterval"
                          #"(?i)\bautonomous\b"]})

;; Which rendering API the shipped code actually reaches for, most capable
;; first. Reported on its own line because it is the single fact that most
;; distinguishes this repo's description from its contents.
(def renderer-apis
  [["WebGPU" #"(?i)navigator\.gpu|requestAdapter|getContext\([\"']webgpu"]
   ["WebGL"  #"(?i)getContext\([\"']webgl"]
   ["three.js" #"(?i)\bfrom\s+[\"']three[\"']|\bthree\.min\.js"]
   ["Canvas 2D" #"(?i)getContext\([\"']2d[\"']\)"]])

(defn walk
  "Every file under dir with one of exts, skipping build output and vendored trees."
  [dir exts]
  (reduce
   (fn [acc entry]
     (let [nm (.-name entry)
           full (path/join dir nm)]
       (cond
         (.isDirectory entry) (if (contains? skip-dirs nm) acc (into acc (walk full exts)))
         (contains? exts (path/extname nm)) (conj acc full)
         :else acc)))
   []
   (fs/readdirSync dir #js {:withFileTypes true})))

(defn- read-text [f]
  (try (str (fs/readFileSync f "utf8")) (catch :default _ nil)))

(defn- read-json [f]
  (try (js->clj (js/JSON.parse (read-text f)) :keywordize-keys false)
       (catch :default _ nil)))

(defn hosts-in
  "Hostnames under the project's own domain that this text names."
  [text]
  (set (map str/lower-case
            (re-seq #"(?i)(?:[a-z0-9](?:[a-z0-9-]*[a-z0-9])?\.)+etzhayyim\.com" text))))

(defn collect-hosts
  "host -> sorted set of repo-relative files that declare it."
  [root files]
  (reduce
   (fn [acc f]
     (let [rel (path/relative root f)]
       (reduce (fn [a h] (update a h (fnil conj (sorted-set)) rel))
               acc
               (hosts-in (or (read-text f) "")))))
   {}
   files))

(defn package-manifests
  "Every package.json in the tree, as [rel parsed]."
  [root files]
  (keep (fn [f]
          (when (= "package.json" (path/basename f))
            (when-let [j (read-json f)]
              [(path/relative root f) j])))
        files))

(defn collect-workspace-deps
  "package name -> sorted set of repo-relative package.json files depending on
   it via the `workspace:` protocol. These are the ones the extraction could
   have broken; a registry dependency is pnpm's problem, not this repo's."
  [manifests]
  (reduce
   (fn [acc [rel j]]
     (reduce
      (fn [a section]
        (reduce (fn [b [dep spec]]
                  (if (and (string? spec) (str/starts-with? spec "workspace:"))
                    (update b dep (fnil conj (sorted-set)) rel)
                    b))
                a
                (get j section)))
      acc
      ["dependencies" "devDependencies" "optionalDependencies"]))
   {}
   manifests))

(defn collect-capabilities
  "capability -> sorted set of repo-relative descriptors advertising it."
  [root files]
  (reduce
   (fn [acc f]
     (if-let [j (and (str/ends-with? f ".jsonld") (read-json f))]
       (let [rel (path/relative root f)]
         (reduce (fn [a c] (update a c (fnil conj (sorted-set)) rel))
                 acc
                 (filter string? (get-in j ["profile" "capabilities"]))))
       acc))
   {}
   files))

(defn evidence-for
  "Repo-relative executable files matching any detector for `cap`."
  [root exec-files cap]
  (let [pats (get capability-evidence cap)]
    (when (seq pats)
      (into (sorted-set)
            (keep (fn [f]
                    (let [t (or (read-text f) "")]
                      (when (some #(re-find % t) pats) (path/relative root f))))
                  exec-files)))))

(defn renderer-found
  "The most capable rendering API any executable file reaches for, or nil."
  [exec-files]
  (let [texts (map #(or (read-text %) "") exec-files)]
    (some (fn [[nm pat]] (when (some #(re-find pat %) texts) nm)) renderer-apis)))

(defn referenced-docs
  "doc filename -> sorted set of NOTICE-like files pointing at it.

   NOTICE conditions the licence grant on a rider document named by filename.
   A licence that cites a document nobody shipped cannot be complied with, so
   this is checked like any other declaration. Only NOTICE files are scanned:
   pulling filenames out of .md prose would make this README's own links to
   docs/ register as licence references."
  [root]
  (reduce
   (fn [acc nm]
     (let [f (path/join root nm)]
       (if-let [t (read-text f)]
         (reduce (fn [a d] (update a d (fnil conj (sorted-set)) nm))
                 acc
                 (map second (re-seq #"\b([A-Z][A-Za-z0-9_-]*\.md)\b" t)))
         acc)))
   {}
   (filter #(str/starts-with? % "NOTICE")
           (try (vec (fs/readdirSync root)) (catch :default _ [])))))

(defn resolves?
  "true / false, or :error when lookup failed for a reason that is not NXDOMAIN
   (so we never read a transient resolver fault as a missing host)."
  [h]
  (-> (dns/lookup h)
      (p/then (fn [_] true))
      (p/catch (fn [e]
                 (let [code (.-code e)]
                   (if (contains? #{"ENOTFOUND" "ENODATA"} code) false :error))))))

(defn tracked-files
  "Repo-relative paths git tracks, or nil when git could not answer."
  [root]
  (try
    (->> (cp/execFileSync "git" #js ["-c" "core.fsmonitor=false" "ls-files" "-z"]
                          ;; stderr ignored: outside a repo git writes "fatal: not a
                          ;; git repository", which would print above our own
                          ;; COULD-NOT-ANSWER line and read like the script crashed.
                          #js {:cwd root :encoding "utf8"
                               :stdio #js ["ignore" "pipe" "ignore"]})
         str
         (#(str/split % #" "))
         (remove str/blank?)
         vec)
    (catch :default _ nil)))

(defn extraction-claim
  "{:files n :bytes n :additions #{...}} from migration.edn, or nil.
   Read with regexes rather than an EDN parser so this script has no reader
   dependency and cannot fail closed on an unrelated syntax change."
  [root]
  (when-let [t (read-text (path/join root "migration.edn"))]
    (let [n (some-> (re-find #":tracked-files\s+(\d+)" t) second js/parseInt)
          b (some-> (re-find #":bytes\s+(\d+)" t) second js/parseInt)
          adds (some-> (re-find #":allowed-additions\s*\[([^\]]*)\]" t) second)]
      (when (and n b)
        {:files n
         :bytes b
         :additions (into (sorted-set) (map second (re-seq #"\"([^\"]+)\"" (or adds ""))))}))))

(defn- pad-left [s w] (str (str/join (repeat (max 0 (- w (count s))) " ")) s))

(defn -main []
  (let [root (process/cwd)
        decl-files (walk root declaration-exts)
        exec-files (walk root executable-exts)
        declared (collect-hosts root decl-files)
        hosts (sort (keys declared))
        manifests (package-manifests root decl-files)
        ws-deps (collect-workspace-deps manifests)
        ;; A `workspace:*` dep is satisfiable only if some package.json in this
        ;; repo declares that name. After extraction, none do.
        provided (set (keep (fn [[_ j]] (get j "name")) manifests))
        deps (sort (keys ws-deps))
        missing-deps (remove provided deps)
        caps-declared (collect-capabilities root decl-files)
        caps (sort (keys caps-declared))
        cap-rows (map (fn [c] [c (evidence-for root exec-files c)]) caps)
        ;; nil = no detector defined for a capability we have never seen before.
        ;; That is "could not measure", not "absent".
        unknown-caps (map first (filter (comp nil? second) cap-rows))
        missing-caps (map first (filter (fn [[_ e]] (and (some? e) (empty? e))) cap-rows))
        renderer (renderer-found exec-files)
        tracked (tracked-files root)
        claim (extraction-claim root)
        ref-docs (referenced-docs root)
        docs (sort (keys ref-docs))
        missing-docs (remove #(fs/existsSync (path/join root %)) docs)]
    (p/let [control (resolves? control-host)]
      (cond
        (not (true? control))
        (do (println (str "COULD NOT ANSWER: control host " control-host " did not resolve."))
            (println "DNS is not working here, so an NXDOMAIN below would prove nothing.")
            (println "Refusing to report on the declared hosts.")
            (process/exit 3))

        ;; Evidence floor: an empty scan must not look like a clean scan.
        (and (empty? hosts) (empty? deps) (empty? caps))
        (do (println (str "COULD NOT ANSWER: scanned " (count decl-files) " files under " root
                          " and extracted 0 hosts, 0 workspace deps and 0 capabilities."))
            (println "Either this is not the repo root, or the declarations are gone.")
            (process/exit 3))

        (empty? exec-files)
        (do (println (str "COULD NOT ANSWER: found 0 executable files under " root "."))
            (println "Capabilities cannot be checked for evidence that cannot be read.")
            (process/exit 3))

        :else
        (p/let [states (p/all (map resolves? hosts))]
          (let [rows (map vector hosts states)
                missing-hosts (filter (comp false? second) rows)
                errored (filter (comp #{:error} second) rows)
                w (apply max 1 (map count (concat hosts deps caps docs)))]
            (println (str "SCANNED\t" (count decl-files) " declaration files"
                          "\tEXECUTABLE\t" (count exec-files)
                          "\tHOSTS\t" (count hosts)
                          "\tWORKSPACE-DEPS\t" (count deps)
                          "\tCAPABILITIES\t" (count caps)))
            (println (str "control\t" control-host "\tresolves"))
            (println (str "renderer\t" (or renderer "NONE FOUND")
                          "\t(the repo's prose describes a WebGPU VRM pipeline)"))
            (println)

            (doseq [[h state] rows]
              (println (str (pad-left h w) "  "
                            (case state
                              true  "resolves"
                              false "NXDOMAIN"
                              "LOOKUP-ERROR")
                            "  <- " (str/join ", " (get declared h)))))

            (when (seq deps) (println))
            (doseq [d deps]
              (println (str (pad-left d w) "  "
                            (if (contains? provided d) "provided" "NOT-IN-WORKSPACE")
                            "  <- " (str/join ", " (get ws-deps d)))))

            (when (seq caps) (println))
            (doseq [[c ev] cap-rows]
              (println (str (pad-left c w) "  "
                            (cond
                              (nil? ev) "NO-DETECTOR"
                              (empty? ev) "NO-CODE-HERE"
                              :else (str "code in " (str/join ", " ev)))
                            "  <- " (str/join ", " (get caps-declared c)))))

            (when (seq docs) (println))
            (doseq [d docs]
              (println (str (pad-left d w) "  "
                            (if (fs/existsSync (path/join root d)) "present" "NOT-SHIPPED")
                            "  <- " (str/join ", " (get ref-docs d)))))

            (println)
            (let [extraction-state
                  (cond
                    (nil? tracked) [:unmeasurable "git could not list tracked files"]
                    (nil? claim) [:unmeasurable "migration.edn has no :tracked-files/:bytes"]
                    :else
                    (let [adds (:additions claim)
                          source (remove adds tracked)
                          total (reduce (fn [n f]
                                          (+ n (try (.-size (fs/statSync (path/join root f)))
                                                    (catch :default _ 0))))
                                        0 source)]
                      (if (and (= (count source) (:files claim)) (= total (:bytes claim)))
                        [:ok (str (count source) " files / " total " bytes"
                                  " + " (count adds) " allowed additions")]
                        [:drift (str "tree has " (count source) " files / " total " bytes;"
                                     " migration.edn claims " (:files claim) " / " (:bytes claim))])))]
              (println (str "extraction  " (case (first extraction-state)
                                             :ok "matches migration.edn"
                                             :drift "DRIFTED"
                                             "COULD-NOT-MEASURE")
                            "  <- " (second extraction-state)))
              (println)
              (cond
                (seq errored)
                (do (println (str "COULD NOT ANSWER: " (count errored)
                                  " host(s) failed to look up for a reason other than NXDOMAIN."))
                    (process/exit 3))

                (= :unmeasurable (first extraction-state))
                (do (println "COULD NOT ANSWER: the extraction claim could not be measured.")
                    (process/exit 3))

                (seq unknown-caps)
                (do (println (str "COULD NOT ANSWER: no detector for capability "
                                  (str/join ", " unknown-caps) "."))
                    (println "Add one to capability-evidence rather than reading this as absence.")
                    (process/exit 3))

                (or (seq missing-hosts) (seq missing-deps) (seq missing-caps)
                    (seq missing-docs) (= :drift (first extraction-state)))
                (do (println (str (count missing-hosts) " of " (count hosts)
                                  " declared hosts do not exist; "
                                  (count missing-deps) " of " (count deps)
                                  " workspace dependencies are not in this repo; "
                                  (count missing-caps) " of " (count caps)
                                  " advertised capabilities have no code here; "
                                  (count missing-docs) " of " (count docs)
                                  " licence documents were not shipped."))
                    (println "The README's status tables should say exactly this. If they do not, update them.")
                    (process/exit 1))

                :else
                (do (println "Everything declared is backed. The README's \"not live\" tables are stale — update them.")
                    (process/exit 0))))))))))

(-main)
