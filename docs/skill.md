# Project Maintenance Guide

Use this guide when changing or reviewing this repository.

## Implementation workflow

- Read the root `README.md` and inspect the relevant code before making a change.
- Keep changes focused and follow the existing Android and Gradle project structure.
- Prefer clear user-facing behavior and avoid exposing internal implementation details in settings text.
- Before committing, review the complete diff and confirm that generated files and personal configuration are excluded.
- Build the affected variant when the Android SDK is available.
- Describe only behavior that has been verified. State the test scope when reporting manual validation, without recording personal identifiers.

## Privacy and release checks

- Do not commit credentials, account snapshots, notification contents, device serials, personal filesystem paths, or signing keys.
- Keep commit messages factual and free of private testing details.
- Before publishing, review tracked files and commit history for accidental credentials or personally identifying information.
