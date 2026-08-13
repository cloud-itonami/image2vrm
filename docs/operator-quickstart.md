# Operator quickstart

**Nothing in this repo can be deployed, and the one thing that runs will render
an empty scene.** This page is about establishing that for yourself in about a
minute, rather than discovering it after wiring up a deploy.

Read [the README](../README.md) for what the repo contains. This is the
procedure.

## 1. Re-take the measurement (~15 seconds)

From the repo root:

```bash
nbb docs/check-declared.cljs
```

You need [nbb](https://github.com/babashka/nbb) (`npm i -g nbb`) and working
DNS. Nothing else — no install, no build, no credentials, no network beyond
name lookups.

It prints one line per declared thing and exits:

| exit | meaning |
|---|---|
| `0` | everything the repo declares is backed by something that exists |
| `1` | at least one declared thing is absent — **the state on 2026-08-13** |
| `3` | could not answer; see below |

Expected output today ends with:

```
4 of 5 declared hosts do not exist; 1 of 1 workspace dependencies are not in
this repo; 5 of 6 advertised capabilities have no code here; 1 of 1 licence
documents were not shipped.
```

**Exit 3 is not a failure of the repo, it is a refusal to answer.** You will
get it if you run from the wrong directory (no declarations found), if
`registry.npmjs.org` does not resolve (then an NXDOMAIN here would prove
nothing about the repo), if `git` cannot list the tree, or if the descriptor
advertises a capability the script has no detector for. In that last case add
a detector to `capability-evidence` — do not read "no detector" as "no code".

## 2. Look at the thing that does run (~30 seconds)

The only executable artifact is a single self-contained HTML file. Serve the
directory and open it:

```bash
cd appview/etzhayyim-wasm-image2vrm-img2vrm1/public
python3 -m http.server 8080
# then open http://localhost:8080/
```

What you should see, and why:

- **A viewport that says `Loading 3DGS data...` forever.** The page requests
  `/api/r2/avatar/avt-mnftierd-jxre71/face.splat`. Served this way there is no
  worker to proxy it (`404`, verified), and the upstream origin
  `murakumo.etzhayyim.com` is NXDOMAIN regardless. So the status line top-left
  reads `Load error: HTTP 404`, while the canvas underneath goes on painting
  the placeholder text `Loading 3DGS data...` every frame — the two disagree
  because only the success path ever clears the placeholder.
- **The loading overlay never goes away**, because `display:none` is likewise
  only set on success. Its spinner is hidden and its caption changes to *"Using
  fallback renderer"*. That fallback **is** the renderer — there is no other
  one.
- **Eight broken thumbnails** at 0.3 opacity (`404`, verified), same cause.
- **A fully populated "Face Analysis" panel.** This is not a measurement of
  anything. It is a hardcoded constant in the page source, and it will show the
  same skin tone, eye colour, age range and face shape no matter what.
- **"Expression" does nothing.** The button has no click handler. `Reset` and
  `Auto Rotate` work.

To see the renderer actually draw, you must supply a `.splat` file yourself at
that path — 32 bytes per Gaussian (three `f32` position, two `f32` scale, then
RGBA as four `u8`). There is **no** UI for supplying one, and no way to supply
a photograph.

## 3. What you cannot do

| you might try | what happens |
|---|---|
| `cd svelte && pnpm install` | fails — depends on `@etzhayyim/kami-engine-sdk` at `workspace:*`, which is not in this repo, not on npm (404), and there is no `pnpm-workspace.yaml` or lockfile here |
| `wrangler deploy` | there is no `wrangler.toml`/`wrangler.jsonc`. `worker.js` needs `env.ASSETS` and `env.R2` bindings that nothing declares |
| open the deployed app | `image2vrm.etzhayyim.com` and `img2vrm1.etzhayyim.com` are NXDOMAIN |
| follow `CLAUDE.md` and call `run_embed_vrm()` | that API belongs to the KAMI Engine crates in `etzhayyim/root`, not to this repo |
| read the licence rider you accepted by using this | `CHARTER-RIDER.md` is referenced by `NOTICE` and is not here |

## 4. If you change anything

`docs/check-declared.cljs` doubles as the regression check for this README.
Run it after any edit:

- Editing a source file without updating `migration.edn` flips the extraction
  line to `DRIFTED` — it recomputes 14 files / 40,171 bytes from `git ls-files`
  and `stat`, and notices a single byte.
- Adding a route, a `workspace:*` dependency, or a `capabilities` entry brings
  it under the check automatically. Nothing is hardcoded in the script.
- Implementing something genuinely, or standing up a host, moves a line from
  red toward green. When the last one goes, the script exits `0` and says the
  README is stale.

That direction has been exercised, not assumed: with every declaration
satisfied the script exits `0`, and each of the five arms has been shown to
flip on its own.
