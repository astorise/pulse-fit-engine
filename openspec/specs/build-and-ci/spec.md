## Purpose
Describe how the repository is expected to build locally and in GitHub Actions.

## Requirements

### Requirement: Local debug APK build
The repository SHALL produce a debug APK through the Gradle wrapper by running `assembleDebug`.

#### Scenario: Building locally
- **GIVEN** the Android SDK and a supported JDK are available
- **WHEN** a developer runs the Gradle wrapper for `assembleDebug`
- **THEN** the build SHALL complete successfully
- **AND** the debug APK SHALL be produced under `app/build/outputs/apk/debug/`

### Requirement: GitHub Actions debug build
The repository SHALL run a GitHub Actions workflow named `Android Build` for pushes to `main`, pull requests, and manual dispatches.

#### Scenario: A change is pushed to `main`
- **GIVEN** a new commit has been pushed to the `main` branch
- **WHEN** GitHub Actions evaluates the repository workflows
- **THEN** it SHALL start the `Android Build` workflow

### Requirement: CI debug APK artifact
The `Android Build` workflow SHALL set up Java 21, install Android API 36 build tooling, build the debug APK, and upload the APK as an artifact named `pulse-fit-engine-debug-apk`.

#### Scenario: The CI build completes successfully
- **GIVEN** the `Android Build` workflow has access to the repository contents
- **WHEN** the workflow finishes a successful build
- **THEN** it SHALL upload `app/build/outputs/apk/debug/app-debug.apk`
- **AND** the uploaded artifact name SHALL be `pulse-fit-engine-debug-apk`
