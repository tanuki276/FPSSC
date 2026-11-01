# FPSSC

FPSSC is now primarily an **Android real-time game-vision research harness** inspired by the architecture of COSC and by public Windows Brawl Stars automation projects that separate perception, tracking, tactical decisions, and controls.

The Android implementation lives under `android/`.

## Android architecture

```text
MediaProjection
    ↓
ImageReader / latest frame
    ↓
VisionPipeline
    ↓
WorldState
  ├─ player
  ├─ enemies
  └─ projectiles
    ↓
TacticalPolicy
  ├─ approach
  ├─ kite
  ├─ projectile dodge
  └─ predicted aim
    ↓
ControlCommand
  ├─ move vector
  ├─ aim vector
  ├─ fire
  └─ super
    ↓
diagnostics / optional touch backend
```

The Android app includes an optional AccessibilityService touch backend. Capture and automatic control are separate: automatic control starts OFF and requires explicit activation and a saved touch profile. This is an experimental backend; recognition accuracy and actual game behavior have not been validated on a device.

## Why this structure

Public Windows projects commonly split the system into roughly the same pieces:

- screen capture / emulator integration
- object detection (often YOLO/OpenCV)
- tracking and state extraction
- tactical logic such as movement, aiming and dodging
- a separate control backend

FPSSC maps those layers onto Android instead of copying a Windows emulator input backend:

- Windows capture → Android `MediaProjection`
- desktop detector → `VisionPipeline`
- tactical module → `TacticalPolicy`
- mouse/keyboard/emulator controller → `ControlCommand` abstraction

This is also a natural extension of COSC, whose strongest reusable part is its fast capture → analyze → decide loop.

## Current Android prototype

`android/app/src/main/java/com/tanuki276/fpssc/`

- `MainActivity.java` — requests screen-capture permission
- `AgentService.java` — foreground MediaProjection capture loop, latest-frame processing, telemetry
- `AgentCore.java` — normalized coordinates, lightweight color/blob baseline detector, velocity estimation, projectile dodge, predicted aim, range management

The detector is intentionally a simple baseline. For more serious experiments, replace `VisionPipeline` with an ONNX/TFLite detector or a purpose-built OpenCV pipeline while leaving the rest of the agent unchanged.

## Build

Open the `android/` directory in Android Studio, or use a local Gradle installation compatible with Android Gradle Plugin 8.7.x.

Requirements:

- Android SDK 35
- Java 17
- Android 8.0+ (`minSdk 26`)

## Relationship to COSC

COSC is specialized for a small discrete action space. FPSSC generalizes the decision output to 2D vectors:

```text
move = (x, y)
aim  = (x, y)
fire = true/false
super = true/false
```

That representation is suitable for testing twin-stick-style action-game agents without hard-coding three buttons or a three-lane world model.

## Legacy prototype

The earlier .NET synthetic-arena prototype remains under `src/FPSSC/` for now as a reference implementation of the same capture → vision → policy → command separation. The Android implementation is the active direction.
## Development and validation

Use **JDK 17**, **Gradle 8.9**, and Android SDK 35. From the repository root:

```sh
# Compile the Android app and run Android lint.
gradle -p android assembleDebug lintDebug

# Run the real Android perception/policy core with small JVM bitmap fixtures.
bash scripts/test-core.sh

# Build and check the .NET reference implementation (requires .NET SDK 8).
dotnet run --project tests/FPSSC.Tests --configuration Release

# Start the .NET demo; Ctrl+C stops it.
dotnet run --project src/FPSSC
```

GitHub Actions runs both regression suites and Android build/lint on pushes and
pull requests. The JVM fixtures exercise the production `AgentCore` but do not
emulate Android services or MediaProjection. They are compiled separately and are
never packaged into the app.

Capture lifecycle and device checks:

- Start capture, accept consent, and confirm the ongoing capture notification.
- Press Start again during capture: the existing session should continue.
- Stop from the app or Android's screen-sharing controls: capture should end.
- Start again after stopping: tracking and latency estimates should start fresh.
- Test a device with padded image rows; conversion must handle a final row
  without trailing padding.

The current capture surface uses its initial dimensions. Rotation and Android 14+
app-only capture resizing still need device-specific validation and dynamic
surface resizing. Stop and restart capture after changing the capture size.
The baseline color detector also needs calibration for each test application.

## Experimental Android automatic control

1. Install the debug APK from a successful **Validate** Actions run (`FPSSC-debug-apk`).
2. Open FPSSC in landscape. Enable the FPSSC service in Android Accessibility settings.
3. Set the movement and attack joystick centers using fractions of the **full screen**:
   `x / screen width`, `y / screen height`. Set drag radius as a fraction of screen height.
   The suggested values are placeholders; save your actual layout.
4. Start capture and choose **entire screen**, not a single app. Turn automatic control ON.
5. Switch to Brawl Stars (`com.supercell.brawlstars`). Use a practice session to verify
   recognition and coordinates before relying on the output.
6. Stop using the notification's **停止** action, Android screen-sharing controls,
   or return to FPSSC and switch automatic control OFF.

Commands are gated to the foreground Brawl Stars package, landscape full-display
capture matching the display dimensions, and a maximum command age of 200 ms.
Input uses 90 ms gestures with at most one movement and one attack stroke; it is
an initial pulsed-control backend, not continuous twin-stick control. The service
reads window package changes and does not request UI-tree contents. Gesture
interruption disables automatic control; each capture teardown also disables it.
The detector is still a color baseline and is not trained for Brawl Stars skins,
maps, effects, menus, or match transitions. Menu navigation, matchmaking, ammo
management, and validated character-specific tactics are not implemented.

### Implementation references

- [scrcpy Controller](https://github.com/Genymobile/scrcpy/blob/master/server/src/main/java/com/genymobile/scrcpy/control/Controller.java): reviewed its separate control path and position/device handling. Its shell-level input backend is not directly usable by a normal Android application.
- [DXcam](https://github.com/ra1nty/DXcam): reviewed timestamped latest-frame processing and bounded buffering; FPSSC discards stale commands instead of building an input backlog.
- [Android AccessibilityService](https://developer.android.com/reference/android/accessibilityservice/AccessibilityService): Android's public gesture dispatch API is used for the optional backend.

These are design references, not vendored code or claims of equivalent functionality.
