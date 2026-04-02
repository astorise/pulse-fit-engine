## ADDED Requirements

### Requirement: Bonded Garmin BLE source
The application SHALL stream live telemetry from a bonded Garmin watch over BLE when such a watch is available.

#### Scenario: A supported Garmin watch is bonded
- **GIVEN** a bonded Garmin watch is visible to the phone
- **WHEN** the telemetry source starts
- **THEN** it SHALL attempt to connect to that watch over GATT

### Requirement: Proprietary MultiLink real-time decoding
The application SHALL negotiate Garmin's proprietary MultiLink transport and decode confirmed real-time payloads for heart rate, stress, SpO2, and respiration.

#### Scenario: A confirmed real-time payload arrives
- **GIVEN** MultiLink negotiation succeeded
- **WHEN** a payload for a confirmed service arrives
- **THEN** the source SHALL decode it into a metric sample that can be rendered on the dashboard

### Requirement: Reverse-engineering evidence retention
The application SHALL persist raw frame evidence so protocol mappings can be refined later.

#### Scenario: Any watch frame is observed
- **GIVEN** the source receives a BLE payload from the watch
- **WHEN** that payload is processed
- **THEN** the source SHALL append a human-readable raw frame line to the app-private log
