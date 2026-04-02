# Pulse Fit Engine

## Context
Pulse Fit Engine is an Android Kotlin application that connects to a bonded Garmin wearable over Bluetooth Low Energy and renders live telemetry on a phone dashboard.

The current codebase combines three concerns:
- Android application shell and dashboard presentation
- GitHub Actions based debug APK builds
- Reverse-engineered Garmin transport and payload decoding

## Tech Stack
- Kotlin
- Android SDK 26+
- AndroidX ViewModel, StateFlow, RecyclerView, ViewBinding
- Kotlin coroutines
- GitHub Actions

## Documentation Conventions
- `openspec/specs/` describes the current shipped behavior of the repository.
- `openspec/changes/archive/` records reconstructed, already-implemented milestones so future work has historical context.
- Reverse-engineered protocol notes should separate confirmed device behavior from heuristics and known gaps.

## Protocol Context
- The app prefers a bonded Garmin watch and uses BLE GATT directly from the phone.
- The transport includes both standard BLE services and Garmin proprietary MultiLink channels.
- The app may persist raw BLE frames to app-private storage so decoder heuristics can be refined without reflashing the watch.
