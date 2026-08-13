# image2vrm

**Neither the image nor the VRM is here.** The name promises photo → VTuber
avatar. `kotodama.jsonld` advertises an autonomous character called Sofia who
chats in Japanese, speaks aloud, and reacts to the Bluesky firehose.
`CLAUDE.md` documents a WebGPU pipeline in confident detail — MToon and PBR
shaders, 57 morph targets, 54 humanoid bones, 22 spring-bone chains, a
264-asset parts library.

What actually runs in this repo is one 308-line HTML file that downloads a
pre-baked **Gaussian splat** cloud and paints it with **Canvas 2D**. It has no
file input, so no photograph can be given to it. It contains the string `.vrm`
nowhere. Its data origin does not resolve in DNS.

Read this repo as **a description of a system, plus a demo of a different
system** — not as the thing it is named after.

It declares itself `kind :app` (`README.edn`) and was extracted verbatim from
`etzhayyim/root` at `60-apps/etzhayyim-project-image2vrm` (`migration.edn`,
source revision `afe5f1d995`, 14 files / 40,171 bytes). That extraction is the
repo's only commit before this README.

## Status — measured 2026-08-13

Measured against tip `50247dc`, the extraction commit. Do not trust this table;
re-take it: **`nbb docs/check-declared.cljs`** (see
[the quickstart](docs/operator-quickstart.md)). Everything below is a line of
that program's output.

### The six advertised capabilities

`kotodama.jsonld` `profile.capabilities`. "No code here" means *no file in this
repo contains anything that could implement it* — not that the implementation
is partial.

| capability | implementing code in this repo |
|---|---|
| `avatar-generation` | **none** — no file input, no image decode, no mesh export |
| `3d-rendering` | present, but see below — it is Canvas 2D, not the declared WebGPU |
| `vtuber` | **none** — no `.vrm`, no morph target, no humanoid rig, no spring bone |
| `llm-chat` | **none** — no request to any model endpoint |
| `tts-voice` | **none** — no `speechSynthesis`, no audio path at all |
| `autonomous-behavior` | **none** — nothing subscribes to the firehose it declares |

### The hosts

| host | declared in | resolves? |
|---|---|---|
| `image2vrm.etzhayyim.com` | `kotodama.jsonld` routes, `worker.js` | **NXDOMAIN** |
| `img2vrm1.etzhayyim.com` | `kotodama.jsonld` routes, `@id` | **NXDOMAIN** |
| `murakumo.etzhayyim.com` | `public/index.html` — the SPA's only data origin | **NXDOMAIN** |
| `kami.etzhayyim.com` | `kotodama.jsonld` RACI *consulted* | **NXDOMAIN** |
| `yoro.etzhayyim.com` | `kotodama.jsonld` RACI *informed* | resolves |

A control lookup against `registry.npmjs.org` resolved in the same run, so
these NXDOMAINs are genuine absences and not a broken resolver here. The check
refuses to report at all if that control fails.

### The dependency

| package | required by | in this repo? |
|---|---|---|
| `@etzhayyim/kami-engine-sdk` | `svelte/package.json` `workspace:*` | **no** (404 on npm) |

`workspace:*` resolves only inside the pnpm workspace this app was lifted out
of. There is no `pnpm-workspace.yaml` and no lockfile here, so the Svelte app
cannot install, let alone build.

### The extraction

`migration.edn` claims 14 tracked files and 40,171 bytes for the extracted
tree, plus a whitelist of files allowed to exist on top of it. **The extracted
tree still matches byte for byte.** The check recomputes this from
`git ls-files` and `stat`, so it fails the moment anyone edits a source file
without saying so.

This README, the quickstart and the check script are themselves additions, so
they are declared in `migration.edn` `:identity :allowed-additions` — five
entries now, not the original two. Adding documentation without declaring it
would have falsified the repo's own identity record, which would be a strange
way to land a README about undeclared absences.

## Three renderers are described here, and they contradict each other

This is the part most likely to mislead someone arriving with a task.

| where | renderer described | status |
|---|---|---|
| `CLAUDE.md` | KAMI Engine, wgpu/WebGPU, MToon + PBR, `run_embed_vrm()` | crates are **not in this repo** — they live in `40-engine/kami-engine` in `etzhayyim/root` |
| `docs/character-maker-design.md` | Three.js + `@pixiv/three-vrm` | **retired 2026-05-26** by ADR-2605264300, per `CLAUDE.md` itself |
| `appview/…/public/index.html` | Canvas 2D, Gaussian splats | the only renderer that exists here |

`kotodama.jsonld` compounds it by describing the app as "KAMI Engine +
Three.js dual render" — the plan `CLAUDE.md` says was abandoned.

The shipped file says `Initializing WebGPU...` in its status line and carries a
`// === WebGPU 3DGS Renderer ===` banner comment. **There is no WebGPU call
anywhere in it** — not `navigator.gpu`, not `requestAdapter`. The only
graphics context it ever opens is `canvas.getContext("2d")`, and the sole
renderer function is named `renderFallback`. An adjacent comment is candid
about it: `// Use Canvas 2D for rendering (WebGPU would use kami-web WASM)`.

The check prints the rendering API it finds on its own line, so this cannot
silently drift back.

## What the shipped page does do

Worth stating plainly, because it is real work and the noise above obscures it:
it fetches a `.splat` file, reads 32-byte Gaussian records, sorts up to 30,000
of them back-to-front against an orbiting camera, projects each through a
perspective divide, and paints it as an alpha-blended circle — a competent
software splat rasteriser in about 70 lines.

Two panels beside it are **not** measurements. "Face Analysis" (skin/eye/hair
HSL, face shape, age range) is a hardcoded `FACE` constant; "Expression
Regions" prints `active` for all eleven regions unconditionally. No photograph
is analysed because none can be supplied.

Of the three viewer buttons, `Reset` and `Auto Rotate` are wired. **`Expression`
has no handler.** Mouse-wheel zoom is documented in `CLAUDE.md` but the wheel
handler only calls `preventDefault()`.

## If you are here to make the declarations true

In dependency order, because the later items are worthless without the earlier:

1. **Bring the renderer in, or change what the repo claims.** Either vendor
   `@etzhayyim/kami-engine-sdk` (with the workspace or lockfile that lets it
   install) or rewrite `CLAUDE.md` and `kotodama.jsonld` to describe the splat
   viewer that is actually here. Today a reader is told three incompatible
   things.
2. **Give it an image intake.** `image2vrm` cannot begin without one.
3. **Stand up an origin.** `murakumo.etzhayyim.com` serves the only bytes the
   page wants; while it is NXDOMAIN the viewer renders an empty scene and the
   turnaround thumbnails all fail to their 0.3-opacity error state.
4. **Add deployment config.** `worker.js` needs `env.ASSETS` and `env.R2`
   bindings and there is no `wrangler.toml` here to declare them.
5. **Retire or rebuild `docs/character-maker-design.md`.** It designs against a
   stack this repo's own `CLAUDE.md` says was abandoned.

Each of the first three flips a specific line of `docs/check-declared.cljs`
from red to green, so progress is measurable rather than asserted.

## Licence — the rider it binds you to is not here

`NOTICE` distributes this under Apache 2.0 **plus** an "etzhayyim Charter
Compliance Rider v3.1", and states that using or redistributing the software
constitutes acceptance of both. It names the rider by filename:

| document | referenced by | shipped? |
|---|---|---|
| `CHARTER-RIDER.md` | `NOTICE` | **no** |

There is no `CHARTER-RIDER.md` in this repo, and no `LICENSE` file either — the
tree carries additional licence terms you are told you have accepted and cannot
read. The check covers this as its fifth arm.

Separately, the VRoid-derived base model that `CLAUDE.md` describes as CC0 is
not in this repo, so nothing here grants or withholds anything about that asset.
