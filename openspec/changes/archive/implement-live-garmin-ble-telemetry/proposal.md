# Proposal: Replace simulated telemetry with live Garmin BLE telemetry

## Why
The dashboard was only useful as a prototype while it depended on simulated data. The project needed to consume live measurements from a real Garmin watch, even though the transport and payloads were proprietary and undocumented.

## What Changes
- Replace the simulated telemetry source with a BLE-backed source
- Discover a bonded Garmin watch and keep reconnecting automatically
- Negotiate Garmin's proprietary MultiLink transport
- Decode confirmed real-time payloads for heart rate, stress, SpO2, and respiration
- Keep heuristic decoders for standard BLE and Garmin protobuf payloads
- Persist raw frames so the protocol mapping can continue to improve
- Update the dashboard flow and permission handling for live transport

## Impact
- Makes the Android app display real measurements from supported Garmin watches
- Captures the current reverse-engineered protocol knowledge in code instead of notes
- Leaves room to extend unsupported services and metric mappings later
