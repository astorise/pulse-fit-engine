## ADDED Requirements

### Requirement: GitHub-hosted debug build
The repository SHALL provide an automated GitHub Actions workflow that builds the Android debug APK.

#### Scenario: Building on GitHub
- **GIVEN** a push, pull request, or manual dispatch triggers the workflow
- **WHEN** the workflow runs
- **THEN** it SHALL build the debug APK
- **AND** it SHALL upload the APK as a GitHub artifact
