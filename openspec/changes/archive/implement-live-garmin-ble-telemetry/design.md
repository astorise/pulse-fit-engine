# Design

## Goal
Replace the demo data path with a production-like telemetry path that can run directly on an Android phone paired with a Garmin watch.

## Architecture
- `MainViewModel` consumes a `GarminRealtimeSource` stream and keeps the latest sample per metric type.
- `BluetoothGarminRealtimeSource` owns Bluetooth state, bonded-device discovery, GATT lifecycle, notification setup, raw frame logging, and MultiLink negotiation.
- `HeuristicGarminRealtimeDecoder` turns incoming payloads into domain samples using a layered decode strategy.

## Reverse-Engineered Transport Notes
- Garmin's MultiLink service uses UUID family `6a4e28xx-667b-11e3-949a-0800200c9a66`.
- Read characteristics in the `281x` family are paired with write characteristics in the `282x` family.
- A tested Fenix 7 Pro redirected negotiation from `2810/2820` to `2812/2822`.
- Confirmed service IDs in use were registration `4`, heart rate `6`, stress `13`, SpO2 `19`, and respiration `21`.
- Service `26` was requested as active time but did not open successfully on the tested device.

## Reverse-Engineered Payload Notes
- Heart rate: byte 1
- Stress: first little-endian unsigned short
- SpO2: first byte when not equal to an invalid sentinel payload
- Respiration: first byte

## Decoder Layers
1. Standard BLE Heart Rate and Running Speed and Cadence services
2. Garmin MLR fragmented payload reassembly
3. Garmin Smart/EventSharing/AlertNotification/LiveSession/DataNotification/Metric protobuf heuristics
4. Unknown payload summaries written to the raw frame log

## Validation
- Build locally with `clean assembleDebug`
- Install on a real Android phone
- Verify live values from a Garmin watch and inspect the raw frame log when unsupported payloads appear
