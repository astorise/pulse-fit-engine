## ADDED Requirements

### Requirement: Android dashboard shell
The application SHALL provide a launcher activity that hosts a metrics dashboard.

#### Scenario: Opening the app
- **GIVEN** the APK is installed on an Android device
- **WHEN** the user launches the app
- **THEN** the app SHALL open a dashboard screen instead of a placeholder activity

### Requirement: Featured metric cards
The dashboard SHALL expose a fixed set of featured metric cards with formatted values and placeholder text before live data is available.

#### Scenario: Rendering the initial dashboard
- **GIVEN** no live telemetry has been decoded yet
- **WHEN** the dashboard is displayed
- **THEN** each featured metric card SHALL render a title, protocol name, and placeholder value
