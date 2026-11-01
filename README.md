# FPSSC

**FPSSC** is a Windows-oriented real-time game-automation research framework inspired by the capture → vision → decision → input architecture used in COSC.

This repository is intentionally designed for **offline experiments, synthetic arenas, recorded footage, and software you own/control**. It does not include live-game input injection, anti-cheat bypasses, or code intended to automate competitive online matches.

## What is included

- A low-allocation frame buffer
- A simple color/blob vision pipeline
- Player / enemy / projectile tracking primitives
- A tactical policy that can aim, approach, retreat, and dodge
- A synthetic arena used as an end-to-end test target
- Timing diagnostics for capture, vision, policy, and total loop latency
- Clean interfaces for future Windows capture backends and safe test input sinks

## Architecture

```text
Frame source
   ↓
Pixel frame
   ↓
Vision / detection
   ↓
World state
   ↓
Policy
   ↓
Control command
   ↓
Safe input sink / simulator
```

The important split is between generic runtime code and game-specific perception/policy code:

```text
FPSSC.Core
├─ IFrameSource
├─ IVisionPipeline
├─ IPolicy
├─ IInputSink
└─ AgentLoop

Demo
├─ SyntheticArena
├─ SyntheticVision
└─ ArenaPolicy
```

## Requirements

- .NET 8 SDK
- Windows, Linux, or macOS for the synthetic demo

The core is written in plain C# without external packages so the demo stays easy to inspect and benchmark.

## Run

```powershell
dotnet run --project src/FPSSC/FPSSC.csproj
```

You should see periodic telemetry similar to:

```text
tick=240 hp=5 score=8 capture=0.2ms vision=0.5ms policy=0.1ms total=1.0ms
```

## Safety boundary

FPSSC deliberately does **not** ship a system-wide `SendInput` implementation or a backend targeting a named commercial game. If you extend it, keep testing inside a simulator, replay viewer, accessibility prototype, or your own application.

## Roadmap

- Recorded-frame / video replay source
- Windows Graphics Capture source for read-only benchmarking
- Better multi-object tracking
- Kalman-style velocity estimation
- Configurable regions of interest
- Debug overlay and frame dumps
- Benchmark suite and regression tests

## Relationship to COSC

COSC proved that a small controller can get strong results from a fast loop and game-specific perception. FPSSC keeps that useful architecture but separates it into reusable interfaces so more complex 2D action-game experiments can be developed without hard-coding one title's controls or UI.
