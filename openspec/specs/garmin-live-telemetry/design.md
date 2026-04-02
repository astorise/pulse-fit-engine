# garmin-live-telemetry Design

## Overview
The live telemetry pipeline has two main runtime components:

- `BluetoothGarminRealtimeSource` handles Bluetooth discovery, GATT connection management, notification subscription, initial reads, MultiLink negotiation, and raw frame logging.
- `HeuristicGarminRealtimeDecoder` converts standard BLE frames and Garmin proprietary payloads into `GarminMetricSample` values that the dashboard can render.

## Confirmed Reverse-Engineered Findings

### MultiLink transport
- Garmin proprietary MultiLink service UUID: `6a4e2800-667b-11e3-949a-0800200c9a66`
- Candidate transport channels are exposed as paired read and write characteristics in the `281x/282x` UUID family.
- On the tested Fenix 7 Pro, the initial `2810/2820` pair redirected to `2812/2822`.

### Observed service IDs
- `4`: registration service
- `6`: real-time heart rate
- `13`: real-time stress
- `19`: real-time SpO2
- `21`: real-time respiration
- `26`: active time was requested but did not open successfully on the tested watch

### Observed payload interpretations
- Service `6` heart rate: the second payload byte maps to beats per minute
- Service `13` stress: the first two payload bytes form a little-endian unsigned value
- Service `19` SpO2: the first payload byte maps to the percentage when the watch returns a valid reading
- Service `21` respiration: the first payload byte maps to respirations per minute

## Decoder Strategy
The decoder is intentionally layered:

1. Standard BLE services are decoded first when the service UUID clearly matches a known profile such as Heart Rate or Running Speed and Cadence.
2. MLR fragmented payloads are reassembled when a notification arrives on the Garmin MLR data characteristic.
3. Proprietary protobuf payloads are parsed heuristically through the nested Garmin Smart, EventSharing, AlertNotification, LiveSession, DataNotification, Metric, and heart-rate message structure.
4. If no known format matches, the payload is still logged with a compact debug summary.

## Logging Strategy
Every observed frame is appended to an app-private file named `garmin-raw-frames.log`. This keeps enough evidence to refine payload mappings without requiring the user to keep the watch connected to a desktop capture session.

## Known Limitations
- The active time real-time service remains unsupported because the tested watch rejected service `26`.
- Several protobuf paths are heuristic because the repository does not embed Garmin's original `.proto` files.
- Metric coverage focuses on the values already mapped into the dashboard and verified from captured traffic.
