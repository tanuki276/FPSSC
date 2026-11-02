# Reviewed implementations

These are code-level reviews, not endorsements that every public bot is complete.
No source or model weights were copied into FPSSC in this revision.

| Repository and reviewed revision | Files inspected | Finding and decision |
| --- | --- | --- |
| [Jooi025/BrawlStarsBot](https://github.com/Jooi025/BrawlStarsBot/tree/a5a797b28f1aa662db8364515bfd8342e66557a6) (MIT) | `modules/detection.py`, `modules/bot.py`, `modules/screendetect.py`, `constants.py`, `LICENSE` | Uses actual Ultralytics YOLO inference, per-class confidence thresholds and class-specific position offsets. Perception, tactics and screen-state logic are separate. Its screen-state recognizer uses normalized sample positions, and controls use emulator keyboard/mouse mappings. Those mappings do not automatically locate Android touch joysticks. FPSSC now separates scene recognition, control-artwork localization and Android input; controls require repeated visual confirmation. We did not substitute guessed coordinates for recognition. Its desktop model has not been ported or validated on Android. |
| [ayzxu/brawlai](https://github.com/ayzxu/brawlai/tree/145aa03868e525af9804d813a02b525ae7dc1186) | `detector.py` | `_load_model` is a placeholder and `_generate_demo_detections` returns hardcoded objects. Rejected as a production perception implementation; no fake detection path is used in FPSSC live capture. |
| [fan-world-me/brawl-stars-bot](https://github.com/fan-world-me/brawl-stars-bot/tree/3b059f2efde67657784a0f56839b505c965040a2) | `src/control/adb_controller.py`, `src/detection/detector.py` | Controller uses fixed normalized joystick/button constants and ADB subprocesses. Detector returns no objects when the model file is absent. This does not solve standalone Android automatic layout recognition; neither its fixed coordinates nor its ADB backend was adopted. |
| [scrcpy](https://github.com/Genymobile/scrcpy/blob/master/server/src/main/java/com/genymobile/scrcpy/control/Controller.java) | `Controller.java` | Separates capture and control and carries display-aware coordinate mapping. FPSSC's gesture direction now accounts for display aspect ratio. scrcpy's privileged input backend is not used. |
| [DXcam](https://github.com/ra1nty/DXcam/blob/main/dxcam/dxcam.py) | `DXCamera` capture lifecycle and frame API | Latest-frame timestamps, bounded buffering and explicit lifecycle are relevant to avoiding input backlogs. FPSSC uses a bounded pending command and separate source/receipt timestamp deltas. |

## What changed in the rewrite

`ControlLocator` searches broad lower-left/lower-right display regions for blue/cyan
movement artwork and orange/red attack artwork. It checks connected-component
size, aspect ratio and fill; a white center rejects a super-button skull. It
calculates centers and drag distance from the detected objects. It is **a
classical vision heuristic**, not YOLO or an imported trained detector.

A profile is usable after three consistent observations. A changed scene,
resolution or conflicting position invalidates it. Short occlusions get a 1.5 s
grace period; prolonged loss requires reacquisition. The input backend receives
that frame's detected profile directly and never reads saved coordinates. The
UI has no manual coordinate/preset/save workflow.

The supplied screenshot is evaluated locally, without putting it in the public
repository. Separate synthetic tests move and resize the controls and check
missing attack, red attack, super-button rejection, stability, expiry, conflicting
positions, scene transitions and resize invalidation. This is still not a
substitute for device and recorded-sequence validation.

## Android continuation compatibility

Reviewed Android 11's [MotionEventInjector.prepareToContinueOldGesture](https://github.com/aosp-mirror/platform_frameworks_base/blob/android11-release/services/accessibility/java/com/android/server/accessibility/MotionEventInjector.java)
after emulator integration exposed gesture cancellation. Its initial-step pointer
count requires the first step of a continuation to contain only the previously
held pointers. `GestureEngine` starts a new attack pointer 1 ms after the continued
movement pointer, keeping both in the same 80 ms gesture. The Android integration
test asserts one movement DOWN, repeated attacks, two-pointer delivery and final
release; it does not merely check that dispatchGesture returns true.

## FSCSR 4.0 follow-up

The Android 11 MotionEventInjector source above also rejects an empty generated
event list. A stationary continued movement pointer with no attack can produce
zero events. When pacing attacks, GestureEngine uses a 2px out-and-back path
only for an otherwise stationary movement-only continuation; its endpoint and
pointer identity stay unchanged. The emulator regression covers these intervals.

The transparent UI uses Android's documented
[TYPE_ACCESSIBILITY_OVERLAY](https://developer.android.com/reference/android/view/WindowManager.LayoutParams#TYPE_ACCESSIBILITY_OVERLAY).
Controls and touch-through diagnostics are separate windows. No external
model weights or bot source have been imported for 4.0.
