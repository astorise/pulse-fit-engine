## Purpose
Describe the current live Garmin watch telemetry acquisition, decoding, and logging behavior implemented in the Android app.

## Requirements

### Requirement: Bonded Garmin watch discovery
The live telemetry source SHALL search the phone's bonded Bluetooth devices for Garmin watch candidates and keep retrying until a compatible device becomes available.

#### Scenario: No bonded Garmin watch is available
- **GIVEN** Bluetooth is enabled on the phone
- **AND** no bonded device matches the Garmin watch heuristics
- **WHEN** the live source attempts discovery
- **THEN** it SHALL publish a transport status explaining that no bonded Garmin watch was found
- **AND** it SHALL retry later instead of terminating permanently

#### Scenario: A bonded Garmin watch is available
- **GIVEN** a bonded Garmin watch is visible in the adapter's bonded devices set
- **WHEN** the live source attempts discovery
- **THEN** it SHALL select that watch as the data source
- **AND** it SHALL begin a BLE GATT connection attempt

### Requirement: GATT subscription bootstrap
The live telemetry source SHALL discover services, enable notifications or indications for supported characteristics, and perform initial reads for readable characteristics before beginning proprietary transport negotiation.

#### Scenario: Services are discovered successfully
- **GIVEN** the watch has connected over GATT
- **WHEN** service discovery succeeds
- **THEN** the source SHALL subscribe to characteristics that support notify or indicate
- **AND** it SHALL queue reads for characteristics that support read

### Requirement: Raw frame persistence
The live telemetry source SHALL append each observed BLE frame, together with decoder context, to an app-private raw frame log.

#### Scenario: A characteristic notification is received
- **GIVEN** the source is connected to a watch
- **WHEN** a characteristic payload is read or notified
- **THEN** the source SHALL append a timestamped line to the raw frame log
- **AND** that line SHALL include the service UUID, characteristic UUID, payload length, payload hex, and decoder summary when available

### Requirement: Garmin MultiLink negotiation
The live telemetry source SHALL negotiate Garmin's proprietary MultiLink transport by closing existing handles, opening the registration service, following channel redirections, and requesting known real-time services.

#### Scenario: The first candidate channel redirects to another channel
- **GIVEN** the MultiLink service exposes multiple `281x/282x` channel pairs
- **WHEN** the registration response reports that the selected pair is already in use and provides a redirected pair
- **THEN** the source SHALL switch to the redirected pair
- **AND** it SHALL continue the negotiation on that redirected pair

#### Scenario: The watch rejects one requested real-time service
- **GIVEN** registration succeeded for the MultiLink transport
- **WHEN** a requested real-time service returns an error
- **THEN** the source SHALL continue opening the remaining requested services
- **AND** it SHALL keep any already opened service handles active

### Requirement: Direct real-time service decoding
The decoder SHALL decode direct MultiLink real-time service payloads for heart rate, stress, SpO2, and respiration into dashboard samples.

#### Scenario: A heart rate service payload arrives
- **GIVEN** MultiLink service `6` has been opened
- **WHEN** a payload for service `6` is received
- **THEN** the decoder SHALL extract heart rate from the observed payload format
- **AND** it SHALL emit a `HEART_RATE` sample

#### Scenario: A SpO2 service payload arrives
- **GIVEN** MultiLink service `19` has been opened
- **WHEN** a payload for service `19` is received
- **THEN** the decoder SHALL extract the SpO2 percentage from the observed payload format when the reading is valid
- **AND** it SHALL emit an `SPO2` sample

### Requirement: Protobuf and standard BLE fallback decoding
The decoder SHALL also attempt standard BLE decoding and proprietary protobuf decoding for non-MultiLink payloads.

#### Scenario: A standard BLE heart rate measurement arrives
- **GIVEN** a characteristic notification is received from the standard Heart Rate service
- **WHEN** the decoder processes the payload
- **THEN** it SHALL decode the heart rate according to the BLE Heart Rate Measurement format

#### Scenario: A proprietary protobuf metric arrives
- **GIVEN** a payload contains Garmin Smart, EventSharing, AlertNotification, LiveSession, DataNotification, Metric, or heart-rate protobuf content
- **WHEN** the decoder processes the payload
- **THEN** it SHALL attempt to decode the nested protobuf structure into metric samples

### Requirement: Transport-only packet isolation
The live telemetry source SHALL treat MultiLink transport traffic as transport control and SHALL prevent those packets from overwriting dashboard state unless they contain a decoded service payload.

#### Scenario: A transport packet is received on an active MultiLink channel
- **GIVEN** the watch is connected and MultiLink negotiation is active
- **WHEN** a transport packet is received that only advances negotiation or routing state
- **THEN** the source SHALL log the packet
- **AND** it SHALL avoid publishing a misleading metric update from that packet alone
