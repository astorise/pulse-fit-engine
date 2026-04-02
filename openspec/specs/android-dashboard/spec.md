## Purpose
Describe the current Android application shell and dashboard behavior exposed by Pulse Fit Engine.

## Requirements

### Requirement: Pulse Fit Engine Android identity
The project SHALL build an Android launcher application named `Pulse Fit Engine`, use the Gradle root project name `pulse-fit-engine`, and install with the application ID `com.astor.pulsefitengine`.

#### Scenario: Launching the installed application
- **GIVEN** the project has been built and installed on an Android device
- **WHEN** the user opens the launcher entry for the app
- **THEN** Android SHALL launch `com.astor.pulsefitengine.MainActivity`
- **AND** the app label SHALL be `Pulse Fit Engine`

### Requirement: Runtime Bluetooth connect permission
The dashboard SHALL request `BLUETOOTH_CONNECT` at runtime on Android 12 and later before attempting to access a bonded Garmin watch.

#### Scenario: Permission is missing on Android 12+
- **GIVEN** the device runs Android 12 or later
- **AND** the app does not currently hold `BLUETOOTH_CONNECT`
- **WHEN** the main activity is created or resumed
- **THEN** the app SHALL request the missing permission

### Requirement: Featured telemetry dashboard
The dashboard SHALL render featured metric cards for heart rate, SpO2, speed, pace, power, distance, active calories, breathing rate, and stress.

#### Scenario: No telemetry has been received yet
- **GIVEN** the app has started but no samples have been decoded
- **WHEN** the dashboard is rendered
- **THEN** each featured metric card SHALL show a placeholder value
- **AND** each featured metric card SHALL indicate that no sample has been received yet

### Requirement: Latest sample aggregation
The dashboard SHALL keep the most recent sample for each metric type and update the source label, transport status, and last activity timestamp whenever new telemetry arrives.

#### Scenario: A new sample arrives for an already displayed metric
- **GIVEN** the dashboard already shows a sample for a metric type
- **WHEN** a newer sample for the same metric type is decoded
- **THEN** the dashboard SHALL replace the older sample with the newer one
- **AND** the dashboard SHALL refresh the transport status text and last activity timestamp
