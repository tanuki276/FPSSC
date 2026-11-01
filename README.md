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
preview/log sink
```

The current Android prototype deliberately stops at `ControlCommand`: it does not contain live-game system-wide touch injection, anti-cheat bypasses, or code intended to automate competitive online matches. This makes the perception and policy layers usable with recorded footage, test applications, synthetic arenas, or software you own/control.

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
