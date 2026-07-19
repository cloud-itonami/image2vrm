# Character Maker Design — VRM Base Model + Runtime Customization

## Architecture

```
┌─────────────────────────────────────────────────────────────┐
│ R2 Storage (CDN)                                            │
│  base/                                                      │
│    body_female_v1.vrm     ← Base body (VRoid/custom, 30K+) │
│    body_male_v1.vrm                                         │
│  hair/                                                      │
│    long_straight.vrm      ← Hair mesh presets               │
│    short_wavy.vrm                                           │
│    bob.vrm                                                  │
│    ponytail.vrm                                             │
│    ...                                                      │
│  clothing/                                                  │
│    tank_top.vrm           ← Clothing mesh presets           │
│    t_shirt.vrm                                              │
│    hoodie.vrm                                               │
│    dress.vrm                                                │
│    ...                                                      │
│  textures/                                                  │
│    skin_fair.png          ← Skin tone textures              │
│    skin_medium.png                                          │
│    eye_blue.png           ← Iris textures                   │
│    eye_brown.png                                            │
│    ...                                                      │
└─────────────────────────────────────────────────────────────┘
           ↓ fetch
┌─────────────────────────────────────────────────────────────┐
│ Browser (Three.js + @pixiv/three-vrm)                       │
│                                                             │
│  1. Load base VRM body                                      │
│  2. Apply face blendshapes from CharacterDef sliders        │
│  3. Swap hair mesh (load preset VRM, attach to head bone)   │
│  4. Swap clothing mesh (load preset VRM, replace body parts)│
│  5. Tint materials (skin/hair/eye color via uniform)        │
│  6. Real-time PBR rendering (orbit camera)                  │
│                                                             │
│  UI: Game-style tab panels                                  │
│  ┌──────┬──────┬──────┬──────┬──────┐                      │
│  │ Face │ Hair │ Body │Cloth │ Color│                      │
│  └──────┴──────┴──────┴──────┴──────┘                      │
│  ┌─────────────────────────────────┐                        │
│  │ [slider] Jaw Width      ──●──  │                        │
│  │ [slider] Chin Shape     ──●──  │                        │
│  │ [slider] Eye Size       ──●──  │                        │
│  │ [slider] Nose Length    ──●──  │                        │
│  │ ...                            │                        │
│  └─────────────────────────────────┘                        │
│  [Export VRM] [Apply Photo] [Reset]                          │
└─────────────────────────────────────────────────────────────┘
```

## Base VRM Requirements

### Body
- **Topology**: Quad-dominant, subdivision-ready
- **Vertex count**: 30K-50K (game quality)
- **UV**: Non-overlapping, single UV set
- **Skeleton**: VRM 1.0 humanoid (55 bones minimum)
  - Hips → Spine → Chest → UpperChest → Neck → Head
  - Shoulders → UpperArm → LowerArm → Hand → 15 finger bones per hand
  - UpperLeg → LowerLeg → Foot → Toes
  - Jaw, LeftEye, RightEye
- **Blendshapes**: 52+ ARKit compatible
  - Face shape morphs (jaw, chin, cheek, forehead, nose, etc.)
  - Expression morphs (52 ARKit)
- **Materials**: Separated by body part
  - Skin (with SSS support)
  - Eye (iris, sclera, with clearcoat)
  - Mouth (teeth, tongue, gum)
  - Eyelash
  - Nails

### Hair Presets (separate meshes, attach to head bone)
- Each hair preset = separate GLB/VRM
- Skinned to head bone (follows head rotation)
- Material: anisotropic specular, alpha-tested for strand edges
- 10-20 presets: short/medium/long × straight/wavy/curly + special styles

### Clothing Presets (separate meshes, replace body regions)
- Each clothing preset = separate GLB/VRM
- Skinned to body skeleton (follows pose)
- Handles: opacity masking of covered body parts
- 10-15 presets: casual/formal/sport

## Runtime Customization (Browser)

### Face Sliders (blendshape weights, 0-1)
```
Face Shape:
  jawWidth        [===●======] 0.4
  jawLength       [=====●====] 0.5
  chinShape       [==●=======] 0.3
  cheekboneWidth  [=====●====] 0.55
  faceLength      [======●===] 0.6

Eyes:
  eyeSize         [=======●==] 0.7
  eyeWidth        [======●===] 0.6
  eyeSpacing      [=====●====] 0.5
  eyeTilt         [=●========] 0.1
  eyeDepth        [=====●====] 0.5

Nose:
  noseLength      [====●=====] 0.4
  noseWidth       [===●======] 0.35
  bridgeHeight    [====●=====] 0.45
  tipShape        [======●===] 0.6

Mouth:
  lipWidth        [=====●====] 0.55
  upperLip        [=====●====] 0.5
  lowerLip        [=====●====] 0.55
```

### Color Pickers
- Skin tone: HSL wheel or preset swatches
- Eye color: HSL wheel
- Hair color: HSL wheel + highlight color
- Lip color: HSL wheel

### Hair Style Selector (grid of previews)
```
┌─────┐ ┌─────┐ ┌─────┐ ┌─────┐
│short│ │ bob │ │ long│ │pony │
│     │ │     │ │     │ │     │
└─────┘ └─────┘ └─────┘ └─────┘
┌─────┐ ┌─────┐ ┌─────┐ ┌─────┐
│curly│ │ bun │ │braid│ │pixie│
└─────┘ └─────┘ └─────┘ └─────┘
```

### Clothing Selector (grid of previews)
```
┌─────┐ ┌─────┐ ┌─────┐ ┌─────┐
│tank │ │ tee │ │dress│ │hoody│
└─────┘ └─────┘ └─────┘ └─────┘
```

## Technology Stack

| Layer | Technology | Role |
|-------|-----------|------|
| **3D Rendering** | Three.js + @pixiv/three-vrm | VRM loading, blendshape, material tinting |
| **UI Framework** | Vanilla JS (no framework) | Sliders, tabs, color pickers |
| **Base Models** | VRM 1.0 (GLB + VRM extensions) | Body, hair, clothing meshes |
| **Storage** | Backblaze B2 | CDN delivery of VRM assets |
| **Photo Input** | Murakumo qwen3.5-4b VL | Photo → CharacterDef extraction |
| **Export** | @pixiv/three-vrm-exporter | Modified VRM → download/B2 upload |

## Photo → Character Pipeline

```
Photo upload
  → qwen3.5-4b VL analysis
    → CharacterDef JSON {face sliders, colors, hair preset, clothing preset}
      → Load base VRM
      → Apply blendshape weights
      → Select hair preset
      → Tint materials
      → Real-time preview
      → User adjusts sliders
      → Export VRM
```

## VRM Source Options

1. **VRoid Studio** (free, CC0 base model available)
   - Export base body as VRM
   - Highest quality option
   - Blendshapes included

2. **Ready Player Me** (API, free tier)
   - `POST /v2/avatars` with photo
   - Returns GLB with blendshapes
   - Limited customization

3. **CC0 VRM Models**
   - Vita (VRM Consortium sample)
   - AvatarSample (three-vrm test model)

4. **Custom** (Blender + VRM addon)
   - Full control
   - Most work

## Phase Plan

### P1: Base VRM + Color Tinting (immediate)
- Use CC0 sample VRM as base body
- Three.js + @pixiv/three-vrm viewer
- Skin/hair/eye color tinting via material uniforms
- Tab UI with color pickers
- Deploy to image2vrm.etzhayyim.com

### P2: Face Blendshapes (next)
- Map CharacterDef face params → VRM blendshape weights
- Slider UI for each face parameter
- Real-time deformation preview

### P3: Hair/Clothing Swap (then)
- Hair preset meshes in B2
- Clothing preset meshes in B2
- Load + attach to skeleton

### P4: Photo → Auto-fill (then)
- qwen3.5-4b extracts CharacterDef from photo
- Auto-set all sliders
- User fine-tunes

### P5: Export + VTuber (final)
- Export customized VRM
- Feed to VTuber pipeline (Design B/C)
- MediaPipe tracking → blendshape animation
