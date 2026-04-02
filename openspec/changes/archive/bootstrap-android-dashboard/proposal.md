# Proposal: Bootstrap the Android dashboard shell

## Why
The project needed a runnable Android application before proprietary Garmin transport work could be integrated. A dashboard shell made it possible to validate UI structure, metric presentation, and APK installation flow early.

## What Changes
- Create a Kotlin Android application module
- Add a launcher activity and dashboard screen
- Add featured metric cards with formatting and placeholder states
- Seed the dashboard with a deterministic simulated telemetry source for initial development

## Impact
- Establishes the baseline Android structure for all later BLE and protocol work
- Makes it possible to test the UI and APK packaging on real phones before decoder work is complete
