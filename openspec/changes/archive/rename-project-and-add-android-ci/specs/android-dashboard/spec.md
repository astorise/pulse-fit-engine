## ADDED Requirements

### Requirement: Pulse Fit Engine branding
The Android application SHALL use the `Pulse Fit Engine` product name and the `com.astor.pulsefitengine` application identity.

#### Scenario: Inspecting the installed app identity
- **GIVEN** the application has been built from the repository
- **WHEN** its launcher label and package identity are inspected
- **THEN** the launcher label SHALL be `Pulse Fit Engine`
- **AND** the application ID SHALL be `com.astor.pulsefitengine`
