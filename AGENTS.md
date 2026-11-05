# 斗兽棋 Jungle Chess — project guidance

Jungle Chess for PICO OS 6 Shared Space, Spatial SDK BOM 6.1.9, package `io.embodit.junglechess`, v0.1.0.
Design: `spatial-app-factory/apps/jungle-chess/design-package.json` (v1 = everything outside `v2[]`, profile A12),
sample `apps/jungle-chess/sample.html`. Started from Cloud Air (kejing-dev/CloudAir) skeleton A.

## Surfaces

- **Board volume** (default container `JungleBoard`, Volumetric 0.56 × 0.36 × 0.72 m, fixed world scale, no base
  panel): grass terrain board (`assets/models/board.glb`), 16 Tripo wooden animals on team bases, two trays in front,
  three attachment cards (seat card, two capture counts). No buttons in the volume.
- **Control window** (`JungleControl`, Planar 560 × 440 dp, `Placement.right(board)`): system TabBar pages
  对局 / 新局 / 规则 / 设置; confirmations and the result card open inside the page. Drag / close = system controls.

## Code map

| Path | What |
| --- | --- |
| `domain/model/Jungle.kt` | Side, Animal, Sq (0..62 = (row-1)*7+col), Terrain (rivers b4–c6 / e4–f6, traps, dens), Position (stable piece ids 0..15), Move |
| `domain/rules/JungleRules.kt` | legal moves, leaps, captures, status, `whyIllegal`, `jumpBlockers`, `denThreats`, perft |
| `domain/engine/Engine.kt` | α-β five levels (L1 depth 1 + safe filter + 50 % wander, L2 depth 2 top-3, L3 300 ms, L4 800 ms, L5 2 s / depth ≤ 7) |
| `domain/game/` | `GameController` (single source of truth: input, commit-then-animate, watchdog, AI, undo/redo, hint, save), `GameState` (UiState, phases S00–S17, save codec), `MotionPlan` |
| `ui/board/` | `BoardLayout` (all metres; D35/D16 tests use it), `BoardScene` (entities, animations, marks, drag), `SceneResources`, `BoardTextures`, `BoardView` (SpatialView, taps, drags, cards, ECS system) |
| `ui/control/ControlScreen.kt` | the four pages |
| `ui/window/` | `WindowCtl` (open / recall / reopen), `LevelGate` (D9 gate; Shared Space has no head pose → no-head fallback 600 ms) |
| `ui/i18n/Texts.kt` | all copy zh / en |
| `tools/asset-pipeline/` | `animals.mjs` (Tripo → ≤ 2k tris, calibrated), `board.mjs` (board / base / tray GLB), `sounds.mjs`, `icon.mjs`, `preview.mjs` (software renderer) — `npm install` there first |

## Build / test (macOS, kj-mbp-m5)

```bash
export JAVA_HOME=~/.pico/primer-cli/jdk/jdk-21.0.13+9/Contents/Home
./gradlew :app:assembleDebug :app:testDebugUnitTest && python3 tools/count_tests.py   # 44 tests
adb -s <serial> install -r app/build/outputs/apk/debug/app-debug.apk
adb -s <serial> shell monkey -p io.embodit.junglechess -c android.intent.category.LAUNCHER 1
```

Emulators are borrowed: `python3 -I bin/emu_borrow.py borrow jungle-chess` / `return jungle-chess` (factory repo).
Other stations' apps may still be open on a borrowed emulator; the board is easier to inspect after
`pico-cli capture camera move --device <serial> --text "move forward 1 meter, move down 0.15 meters"` (reset afterwards).

## Debug broadcast (debug builds, same paths as real input)

```
adb shell "am broadcast -a io.embodit.junglechess.DEBUG -p io.embodit.junglechess --es cmd '<command>'"
```

`tap a9` · `move a9 a8` · `drag a9 a8` · `none` · `undo` · `redo` · `hint on|off` · `new red|blue|random 1-5` · `again` ·
`menu` · `tab play|new|rules|settings` · `rulesdone` · `continue` · `confirm resign|restart|exit|none` · `resign` ·
`setup <63 chars + r|b> [blue]` (upper = red, lower = blue, `.` empty, row 1 first; letters E L T P W D C R) ·
`lang system|zh|en` · `sound on|off` · `volume 0-2` · `reduced on|off` · `lit on|off` · `pause on|off` (freeze the
animation; also stretches the watchdog) · `freeze on|off` (watchdog only) · `ftest on|off` (F↑ orientation card on d7) ·
`light <intensity>` · `window open|recall` · `state` (logs `STATE S0x …`) · `dump` (piece poses).

Log tag `JungleChess`: `GAME`, `TAB`, `SELECT`, `ILLEGAL`, `MOVE`, `ANIM`, `AI`, `ALERT`, `RESULT`, `UNDO`, `HINT`,
`WINDOW`, `SCENE`, `ANIMALS`, `MODELS`, `OVERFLOW`, `CMD`, `STATE`, `DUMP`.

## Screenshot list for QA (v1 states)

| State | How |
| --- | --- |
| S00 loading | force-stop + launch, shoot within ~1 s (bases before animals) |
| S01 first run / S12 rules | clear data → launch; `tab rules` |
| S02 new (with / without save) | `tab new` (a running game shows [继续上一盘] after relaunch with `menu`) |
| S03 idle (first-game lit, hint on) | `new red 1`; `hint on` |
| S04 selected / leap / blocked | `tap g7`; `setup` lion a5 + rat c5 then `tap a5` (red cross) |
| S05 moving / capture / rat topples elephant / trap crouch | `pause on` right after a `tap`; `setup` R a7 + e a6, `move a7 a6` |
| S06 AI thinking | `new red 5`, `move g7 g6` (2 s) |
| S08 den alert | `setup` with a blue piece on e9 or c9 / d8 |
| S09 result win / lose / resign | `setup` D d2 + r a3, `move d2 d1`; `resign` |
| S11 settings, S13 confirm | `tab settings`; `confirm resign|exit|restart` |
| S16 restored | play a move, force-stop, relaunch |
| S17 leap | `setup` L a5, `tap a5`, `tap d5` + `pause on` |
| modifiers | `lang en`, `reduced on`, `sound off` |

## Facts learned here

- Board / bases / trays are GLBs written by `board.mjs` (no `createBox`); marks are `createPlane` + unlit transparent
  Canvas textures laid flat with −90° about X (bitmap top = −Z = far edge).
- Tripo meshes have ~3–7k UV islands, so UV-aware simplification stalls near 150k triangles; `animals.mjs` simplifies on
  positions and re-assigns UVs per island (preview with `preview.mjs`). Leopard and cat come sideways (+X) and are
  turned 270°; everything faces −Z after calibration. Heights are footprint-limited (≤ 5.8 cm long, elephant 6.0).
- `PhysicallyBasedMaterial` has no UV transform, so the river is static (ripple ring on a rat entering instead).
- Shared Space gives no head pose; `LevelGate` exists but always ends on its no-head path.
