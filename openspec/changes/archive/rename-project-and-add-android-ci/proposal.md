# Proposal: Rename the project and add Android CI

## Why
The repository needed a stable product identity and a repeatable build pipeline. Renaming the project made the package naming consistent, and GitHub Actions made APK generation reproducible from any commit.

## What Changes
- Rename the project to `pulse-fit-engine`
- Move the Android namespace and application ID to `com.astor.pulsefitengine`
- Update user-facing app strings to `Pulse Fit Engine`
- Add a GitHub Actions workflow that builds and uploads the debug APK
- Ignore local Android and tooling artifacts that do not belong in version control

## Impact
- Stabilizes the installable package identity
- Gives the repository a build artifact on every important GitHub event
- Reduces noise from local machine state
