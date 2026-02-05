# Repository Guidelines

## Project Structure & Module Organization
- Root Gradle project with a single Android application module: `app/`.
- Kotlin source: `app/src/main/java/com/orynnx/dlnalink/`.
- Android resources: `app/src/main/res/` (layouts, drawables, values).
- Manifest: `app/src/main/AndroidManifest.xml`.
- Unit tests: `app/src/test/`; instrumented tests: `app/src/androidTest/`.

## Build, Test, and Development Commands
- `.\gradlew.bat :app:assembleDebug` — builds a debug APK.
- `.\gradlew.bat :app:assembleRelease` — builds a release APK (unsigned).
- `.\gradlew.bat :app:testDebugUnitTest` — runs JVM unit tests.
- `.\gradlew.bat :app:connectedDebugAndroidTest` — runs instrumented tests on a device/emulator.
- `.\gradlew.bat :app:lint` — runs Android lint checks.

Ensure `JAVA_HOME` points to a JDK (project targets Java 11).

## Coding Style & Naming Conventions
- Indentation: 4 spaces; Kotlin standard style (no tabs).
- Kotlin types/classes: `PascalCase`; functions/variables: `camelCase`.
- Resource names: `lowercase_underscore` (e.g., `ic_cast`, `activity_main`).
- Keep Android components in their package (e.g., `Application`, services).

## Testing Guidelines
- Unit tests: JUnit in `app/src/test/`; name tests `*Test` (e.g., `DeviceParserTest`).
- Instrumented tests: AndroidX in `app/src/androidTest/`.
- Prefer small, focused tests; avoid network dependency where possible.

## Commit & Pull Request Guidelines
- Git history is not available in this workspace, so no enforced commit convention was detected.
- Suggested: concise subject line in present tense (e.g., “Fix UPnP service init”).
- PRs: include a clear summary, steps to verify, and screenshots for UI changes.

## Security & Configuration Tips
- Keep credentials out of source; use `local.properties` or environment variables.
- Verify network permissions and cleartext policy in `AndroidManifest.xml` when changing UPnP or HTTP behavior.
