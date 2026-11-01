# FPSSC

Android-only screen perception and experimental touch-control app for Brawl Stars.
Version **3.0.0** automatically locates visible movement and normal-attack controls.
There are no coordinate fields or save/calibration buttons.

## Install and start

1. Download `FPSSC-3.0.0.apk` from the [3.0.0 release](https://github.com/tanuki276/FPSSC/releases/tag/v3.0.0).
   See [release notes](docs/releases/3.0.0.md) for the test signing certificate and update compatibility.
2. Open FPSSC. The title should show **3.0.0**.
3. Use **操作サービスを有効にする** to enable FPSSC in Android Accessibility settings.
4. Press **画面共有を開始** in landscape and choose the entire screen.
5. Turn **自動操作** ON and switch to the Brawl Stars practice scene.
6. Once the player and visible joystick artwork are recognized, the app acquires
   movement/attack centers automatically. Positions must agree across three scans.
7. Stop from the persistent notification's **停止** action or FPSSC's **停止** button.

Automatic control starts OFF. Old saved coordinate preferences are ignored.
If the app cannot recognize the scene or buttons, diagnostics include separate movement/attack candidate counts. The last game-foreground input status and gesture sent/completed/cancelled counters remain visible when returning to FPSSC. **診断結果を共有** includes perception and input status.

## Implementation

- `AgentService`: full-display MediaProjection capture, direct downsampled RGBA processing,
  resource lifecycle, telemetry, scene/layout/input coordination.
- `AgentCore`: health-bar-based player/enemy detection, temporal tracking, moving
  projectile candidates and range/aim/dodge decisions.
- `ControlLocator`: image-derived joystick centers, three-observation confirmation,
  short-occlusion tolerance, layout expiry and automatic reacquisition.
- `TouchService` / `GestureEngine`: active-window-verified Android AccessibilityService input with a continued movement pointer; at most one pending
  command, foreground-package gating, full-display dimension checks, age limit,
  explicit stop, and aspect-correct gesture directions.
- `MainActivity`: start/stop, service setup, automatic-control switch and diagnostics.

Launcher/round/themed icons and a monochrome notification icon are included as
Android vector/adaptive resources. No bitmap downloads or external icon fonts are
required.

For source reviews and what was actually adopted, see
[Implementation references](docs/IMPLEMENTATION_REFERENCES.md).

## Validation

Requirements: JDK 17, Gradle 8.9, Android SDK 35; .NET SDK 8 for the C# reference.

```sh
bash scripts/test-core.sh
gradle -p android assembleDebug assembleRelease lintRelease
gradle -p android connectedDebugAndroidTest
dotnet run --project tests/FPSSC.Tests --configuration Release
```

For the optional local regression against the supplied practice screenshot:

```sh
bash scripts/test-core.sh /path/to/practice-screenshot.jpg
```

The image is not stored in the repository. This scene-specific regression checks
player/enemy recognition, no moving shots in the static scene, movement/fire
output and automatic movement/attack-button centers at production analysis size.
Synthetic tests cover relocated/scaled control artwork, red attack, false super,
missing buttons, temporal stability, occlusion expiry and scene/resize reset.
JVM Bitmap/Color fixtures are test-only and are never packaged into the APK.
GitHub Actions runs regression suites, Android build/lint, emulator touch integration and APK upload. On main, publication of 3.0.0 waits for all three jobs. The release build excludes the debug-only instrumentation activity.

## Current limits

This is still experimental, not a device-validated autonomous player. The supplied
static practice screenshot is recognized; real touch behavior, movement sequences,
skins/maps, effects and HUD occlusions still need device/sequence testing.

- The control locator expects visible blue/cyan movement and orange/red attack
  artwork in the lower-left/lower-right parts of a landscape display. It does not
  support arbitrary custom controls placed anywhere, invisible buttons or every
  theme. Undetected controls do not fall back to preset coordinates.
- Health bars anchor character positions; low/hidden HP bars may be missed.
- Movement uses a continued pointer in 80 ms slices, with separate attack strokes. Stale input, loss of focus and stopping release the pointer. Android gesture cancellation disables automatic control. Emulator integration is not proof of Brawl Stars compatibility.
- Menus, matchmaking, ammo management, character-specific tactics and dynamic
  tactics are not implemented. Android 14+ capture resize callbacks rebuild the analysis surface; on older devices restart capture after rotating/resizing.
- `queueExtra` estimates relative source queue growth. It does not measure unknown
  constant capture latency or subtract unrelated absolute clock epochs.

The .NET synthetic-arena reference remains under `src/FPSSC`; start it with
`dotnet run --project src/FPSSC` and stop with Ctrl+C.
