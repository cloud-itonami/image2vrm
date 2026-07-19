# image2vrm — CLAUDE.md

## Identity

**image2vrm.etzhayyim.com** / **img2vrm1.etzhayyim.com** — VRM Character Maker on the KAMI Engine wgpu pipeline.

## Architecture

```
┌─────────────────────┐ ┌─────────────┐
│ KAMI Engine (wgpu)   │ │ Side Panel  │
│ WebGPU PBR/MToon     │ │ Expression  │
│ run_embed_vrm()      │ │ Pose        │
│ Morph: set_vrm_morph │ │ Parts       │
│ Camera: interactive  │ │ Presets     │
│ orbit/zoom/touch     │ │             │
└─────────────────────┘ └─────────────┘
         ↑                     │
         └─ VRM (10MB) from R2 ┘
            murakumo.etzhayyim.com/api/r2/avatar/base/body_v1.vrm
```

## Rendering pipeline

| Engine | Shader | Features |
|---|---|---|
| **KAMI Engine** (Rust WASM, wgpu WebGPU) | MToon (mtoon.wgsl) + PBR (pbr.wgsl) | Morph targets (CPU blend), interactive orbit camera, material auto-detect, humanoid bone control via `setVrmBoneRotation`, spring bone simulator, node constraint solver (Rotation / Aim / Roll), auto-blink (rendered Rust-side in the WASM RAF loop) |

The earlier dual-engine plan (a parallel three.js + `@pixiv/three-vrm` right pane) was retired on **2026-05-26** when the `@etzhayyim/kami-engine-sdk` removed all three.js code paths (ADR-2605264300, parent ADR-0031 from 2026-04-18 — "kami-engine VRM three.js-free topology"). The KAMI Engine wgpu path is now the sole renderer for VRM avatars across the religious-corp SDK + every consumer app.

## KAMI Engine SDK (wgpu)

### WASM Entry Points

| Function | Purpose |
|---|---|
| `run_embed_vrm(canvas_id, vrm_url)` | Fetch VRM → glTF parse → multi-primitive PBR/MToon render |
| `run_with_character(canvas_id, character_json)` | CharacterDef → kami-character mesh → PBR render |
| `run_embed(canvas_id, scene_json)` | IslandScene → batch render |

### JS API (wasm-bindgen exports)

| Function | Purpose |
|---|---|
| `set_vrm_morph(index, weight)` | Set morph target weight (0-1), triggers CPU blend + vertex re-upload |
| `set_vrm_morph_by_name(name, weight)` | Set morph by name substring match |
| `get_vrm_morph_names()` | Returns JSON array of morph target names |
| `reset_vrm_morphs()` | Reset all morph weights to 0 |
| `set_vrm_camera(yaw, pitch, distance)` | Set camera orbit position |

### Morph Target Pipeline

```
VRM GLB → gltf_loader::load_glb()
  → parse morph targets per primitive (position deltas)
  → store base_vertices + morph_deltas in VRM_MORPH_STATE (thread_local)
  → JS calls set_vrm_morph(idx, weight)
  → dirty flag set
  → render loop: CPU blend (base + Σ weight*delta) → write_buffer → GPU
```

### Camera Controls

- **Mouse drag**: orbit (yaw + pitch)
- **Mouse wheel**: zoom in/out (distance 0.5-6.0)
- **Touch**: drag orbit (mobile)
- **Auto-rotate**: stops on first interaction
- **Initial**: yaw=π (face front), pitch=0.2, distance=2.5, target=(0, 0.9, 0)

### Material Auto-Detection

glTF loader checks `extensions_used` for `VRMC_vrm` / `VRMC_materials_mtoon`:
- **VRM detected** → `sss_model=99` → MToon pipeline (mtoon.wgsl)
- **No VRM** → PBR pipeline (pbr.wgsl) with name-based material assignment:
  - `*SKIN*` / `*BODY*` / `*FACE*` → SSS (Burley subsurface)
  - `*EYE*` / `*IRIS*` → Clearcoat (cornea refraction)
  - `*HAIR*` → Anisotropic (Marschner specular)

### MToon Shader (mtoon.wgsl)

- 2-color step shading (lit ↔ shade, smoothstep transition)
- Shade color = base_color × 0.85 (from glTF loader)
- Parametric rim light (Fresnel-based, white)
- Hemisphere ambient (sky/ground interpolation)
- Double-sided rendering (hair/clothing)

## KAMI Engine Crates

| Crate | Role |
|---|---|
| `kami-render` | PBR + MToon pipelines, glTF loader (multi-primitive, textures, morph targets, RGB→RGBA) |
| `kami-character` | Parametric mesh generation (base_mesh, blendshape, hair, body, material, export) |
| `kami-skeleton` | Bone hierarchy, joint matrices, keyframe animation |
| `kami-web` | WASM entry (run_embed_vrm, run_with_character, morph/camera JS API) |

## VRM Base Model

- **Source**: VRoid Studio sample (CC0)
- **B2 Key**: `avatar/base/body_v1.vrm`
- **Size**: 10MB
- **Bones**: 54 humanoid (VRM 1.0)
- **Morph Targets**: 57 (Face mesh, 7 primitives)
- **Materials**: 13 (MToon)
- **Textures**: 19 (embedded, RGB→RGBA converted)
- **Spring Bones**: 22 chains (hair + clothing physics)

### Morph Target Map (57 targets)

| Range | Category | Key Targets |
|---|---|---|
| 0-5 | ALL (full face) | Neutral, Angry, Fun, Joy, Sorrow, Surprised |
| 6-10 | BRW (eyebrows) | Angry, Fun, Joy, Sorrow, Surprised |
| 11-24 | EYE | Natural, Close, Close_L/R, Angry, Fun, Joy, Joy_L/R, Sorrow, Surprised, HighlightHide, IrisHide, Spread |
| 25-43 | MTH (mouth) | Neutral, Close, Angry, Fun, Joy, Sorrow, Surprised, Up, Down, Small, Large, A, I, U, E, O, SkinFung, SkinFung_L/R |
| 44-56 | HA (teeth) | Fung1-3 + Low/Up variants, Hide, Short, ShortLow/Up |

## Character Presets

| Preset | Hair | Eyes | Skin | Expression |
|---|---|---|---|---|
| **Sofia** | #d4b87a (blonde) | #5588cc (blue) | #f0ddd1 (fair warm) | happy: 0.25 |
| **Kuro** | #1a1a1a (black) | #cc2233 (red) | #ede5df (pale cool) | relaxed: 0.3 |

## VRM Part Composition (Parts tab)

Side panel の **Parts** タブで VRM パーツの interactive 合成が可能。

### Part Categories (自動分類)

メッシュ名・マテリアル名からヒューリスティクスで分類:
- **Body**: body, skin
- **Hair**: hair, bangs
- **Face**: face, eye, mouth, brow, eyelash
- **Outfit**: cloth, shirt, pants, dress, shoe, tops, bottom
- **Accessory**: hat, glass, ribbon

### B2 Part Presets (264 assets, kami-character 生成)

**命名規則**: `avatar/parts/{category}_{style}_{color}.glb`

Hair styles (22) × Colors (8) = **176 files**:

| Styles | Key |
|---|---|
| Short (Straight/Wavy/Curly) | `short_straight`, `short_wavy`, `short_curly` |
| Medium (Straight/Wavy/Layered) | `medium_straight`, `medium_wavy`, `medium_layered` |
| Long (Straight/Wavy/Curly) | `long_straight`, `long_wavy`, `long_curly` |
| Ponytail (High/Low) | `ponytail_high`, `ponytail_low` |
| Bun (Top/Low) | `bun_top`, `bun_low` |
| Bob, Pixie, Buzz, Undercut, Mohawk | `bob`, `pixie`, `buzz`, `undercut`, `mohawk` |
| Afro (Short/Large) | `afro_short`, `afro_large` |
| Braids (Twin/Single) | `braids_twin`, `braids_single` |

Colors: `black`, `brown`, `blonde`, `red`, `pink`, `blue`, `silver`, `green`

Outfit styles (11) × Colors (8) = **88 files**:

| Styles | Key |
|---|---|
| Tank Top, T-Shirt, Blouse | `tank_top`, `tshirt`, `blouse` |
| Hoodie, Jacket | `hoodie`, `jacket` |
| Dress (Casual/Formal) | `dress_casual`, `dress_formal` |
| Suit (Casual/Formal) | `suit_casual`, `suit_formal` |
| Uniform (School/Military) | `uniform_school`, `uniform_military` |

Colors: `white`, `black`, `navy`, `red`, `pink`, `gray`, `beige`, `green`

### UI: Style × Color 独立選択

Parts タブで **Style** と **Color** を独立に選択。組み合わせキー `hair_{style}_{color}` で B2 からロード。キャッシュ済みパーツは即座に切り替え。

### Part Generation

```bash
cd 40-engine/kami-engine
RUSTC_WRAPPER="" cargo run --example gen_parts --release -- /tmp/avatar-parts
# → 264 GLB files (26MB total)
ls /tmp/avatar-parts/*.glb | xargs -P 8 -I{} sh -c \
  'npx wrangler r2 object put "murakumo-etzhayyim-ai/avatar/parts/$(basename {})" \
   --file="{}" --content-type="model/gltf-binary" --remote'
```

### kami-vrm SDK (Rust, `40-engine/kami-engine/kami-vrm/`)

VRM パーツ合成の Rust/WASM SDK。parse → decompose → compose → export パイプライン。

| Module | Purpose |
|---|---|
| `parse.rs` | VRM 1.0 / 0.x 自動検出パース |
| `part.rs` | VrmDocument → Body/Hair/Face/Outfit/Accessory 分解 |
| `compose.rs` | 複数パーツ → skeleton 統合 → buffer/mesh/material マージ |
| `export.rs` | VrmDocument → GLB (VRMC_vrm + springBone + MToon) |
| `humanoid.rs` | VRM ↔ kami-skeleton 変換 (55 bones) |

### Custom Upload

Parts タブ下部のドロップエリアに .vrm/.glb をドラッグ&ドロップで追加パーツをロード可能。

## Voice Synthesis + Emotion Analysis (Voice tab)

### TTS (Kokoro-82M via HeadTTS, in-browser neural TTS)

**Model**: Kokoro-82M (82M params, ~200MB, ONNX WASM)
**Library**: `@met4citizen/headtts` (CDN ESM import)
**Features**: phoneme-level timestamps + Oculus viseme output for lip-sync

| Voice (18 presets) | ID | Gender |
|---|---|---|
| Heart, Bella, Nicole, Sarah, Alloy, Nova, Sky, Jessica | `af_*` | Female (US) |
| Fenrir, Michael, Puck, Echo, Eric, Liam | `am_*` | Male (US) |
| Emma, Isabella | `bf_*` | Female (UK) |
| George, Daniel | `bm_*` | Male (UK) |

Lazy load: Voice タブ初回クリック時にモデルロード (IndexedDB キャッシュ)。

### Emotion Analysis (Hume-style 8 axis)

| Axis | Expression Mapping | Prosody Modulation |
|---|---|---|
| Joy | Fcl_ALL_Joy + Fcl_BRW_Joy | pitch +10%, rate +5% |
| Anger | Fcl_ALL_Angry + Fcl_BRW_Angry | pitch +5%, rate +20% |
| Sadness | Fcl_ALL_Sorrow + Fcl_BRW_Sorrow | pitch -10%, rate -15% |
| Surprise | Fcl_ALL_Surprised + eye spread | pitch +15%, rate +15% |
| Fear | Sorrow blend + eye spread | pitch +10%, vol -15% |
| Disgust | Angry blend + mouth small | pitch -5%, rate -10% |
| Contempt | Angry blend + mouth small | pitch -10%, rate -15% |
| Excitement | Fun + Joy blend | pitch +20%, rate +15% |

### Pipeline

```
Text input → keyword emotion analysis (instant, no LLM)
  → 8-axis emotion scores (0.0-1.0)
  → Emotion bars UI update
  → Auto-sync: emotion → VRM morph targets (expression + brows + eyes + mouth)
  → Speak: emotion → prosody modulation → Web Speech TTS + mouth viseme sync
```

### Viseme Lip Sync (Kokoro → VRM)

HeadTTS が返す Oculus viseme タイムスタンプで音素精度のリップシンク。

| Oculus Viseme | VRM Morph | Weight |
|---|---|---|
| aa | A (36) | 1.0 |
| I | I (37) | 1.0 |
| U | U (38) | 1.0 |
| E | E (39) | 1.0 |
| O | O (40) | 1.0 |
| PP (p/b/m) | - (lips closed) | 0 |
| FF, TH, CH, SS | I (37) | 0.3-0.5 |
| DD, nn | A (36) | 0.2-0.3 |
| kk, RR | O (40) | 0.3-0.4 |

`requestAnimationFrame` で viseme タイムラインを同期再生。音声終了時に自動リセット。

## File Layout

```
60-apps/etzhayyim-project-image2vrm/
  CLAUDE.md
  docs/character-maker-design.md
  wasm/etzhayyim-wasm-image2vrm-img2vrm1/
    src/app.ts                   ← deploy entry (CF best practice: wrangler bundles from src)
    kotodama.jsonld
    wrangler.jsonc               ← R2 + PDS_SERVICE bindings
```

## Deploy

```bash
# Standard deploy (src/app.ts — wrangler bundles automatically)
cd 60-apps/etzhayyim-project-image2vrm/wasm/etzhayyim-wasm-image2vrm-img2vrm1
etzhayyim deploy

# KAMI WASM rebuild + CDN deploy
cd 40-engine/kami-engine
RUSTC_WRAPPER="" wasm-pack build --target web kami-web
npx wrangler r2 object put "etzhayyim-cdn/kami-web/vrm2122/kami_web_bg.wasm" --file="kami-web/pkg/kami_web_bg.wasm" --content-type="application/wasm" --remote
npx wrangler r2 object put "etzhayyim-cdn/kami-web/vrm2122/kami_web.js" --file="kami-web/pkg/kami_web.js" --content-type="application/javascript" --remote
```

## Rules

- **src/app.ts = deploy entry**: `etzhayyim deploy` bundles from `src/app.ts` (CF best practice)
- **KAMI WASM CDN**: `https://cdn.etzhayyim.com/kami-web/vrm2122/` — rebuild with `wasm-pack` after engine changes
- **VRM B2 proxy**: `murakumo.etzhayyim.com/api/r2/avatar/base/body_v1.vrm` — CORS enabled, cached 1h
- **Morph targets**: CPU blend in render loop, dirty flag pattern. `set_vrm_morph` from JS triggers re-upload next frame
- **Camera**: Interactive orbit stored in thread_local RefCell, read each frame
- **MToon detection**: `sss_model == 99` in MaterialUniform selects MToon pipeline
