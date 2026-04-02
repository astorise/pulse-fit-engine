## ADDED Requirements

### Requirement: Live transport status reporting
The dashboard SHALL expose the selected watch label, the current transport status, and the timestamp of the latest processed activity.

#### Scenario: Live transport is active
- **GIVEN** the phone is connected to a Garmin watch
- **WHEN** a telemetry update is processed
- **THEN** the dashboard SHALL show which watch is being used
- **AND** it SHALL show the latest transport status and activity timestamp

### Requirement: Bluetooth connect permission handling
The main activity SHALL request runtime Bluetooth connect permission on supported Android versions before the live source accesses bonded devices.

#### Scenario: Bluetooth permission must be granted
- **GIVEN** the app is running on Android 12 or later
- **AND** Bluetooth connect permission is missing
- **WHEN** the activity starts or resumes
- **THEN** the app SHALL request that permission
