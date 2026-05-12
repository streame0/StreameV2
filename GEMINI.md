# Streame - Android Media Hub

A modern media hub application for Android TV, Fire TV, Mobile, and Tablet. Developed with significant assistance from AI.

## Project Overview

Streame is a multi-platform Android application designed as a central hub for media consumption. It integrates with various metadata providers (TMDB), tracking services (Trakt.tv), and cloud synchronization (Supabase) to provide a unified experience across devices.

- **Main Technologies:** Kotlin 2.1.0, Jetpack Compose, Compose for TV, Dagger Hilt, Room, DataStore, WorkManager, Media3 (ExoPlayer) with FFmpeg, Retrofit, Coil 3, Supabase, Sentry.
- **Architecture:** Follows MVVM and Clean Architecture patterns. The codebase is organized into layers: `data`, `domain` (logic), and `ui` (presentation).
- **Key Features:** Multi-profile support, Trakt.tv sync, Cloud sync via Supabase, Addon support (CloudStream runtime bridge), 4K HDR playback, and customized UI for both D-pad and touch input.

## Project Structure

- `app/src/main/kotlin/com/streame/tv/`:
    - `data/`: Local (Room), Remote (Retrofit/Supabase), and Repository implementations.
    - `domain/`: Business logic and models.
    - `ui/`: Compose screens, components, theme, and viewmodels.
    - `di/`: Dagger Hilt modules.
    - `navigation/`: App-wide navigation logic.
    - `network/`: Core networking configuration (OkHttp, DNS-over-HTTPS).
    - `worker/`: Background tasks using WorkManager (e.g., Trakt sync).
    - `updater/`: Self-update logic for the sideload flavor.
- `supabase/`: Contains Edge Functions and database migrations for the cloud backend.
- `benchmark/`: Baseline profile and performance benchmarking code.

## Building and Running

### Prerequisites
- JDK 17
- Android SDK 35

### Local Configuration
1.  **Secrets:** Copy `secrets.defaults.properties` to `secrets.properties` and fill in required keys (Supabase URL, TMDB keys, etc.).
2.  **Keystore:** (For release builds) Copy `keystore.properties.template` to `keystore.properties` and provide signing details.

### Common Gradle Commands
- **Assemble Debug Builds:**
    - Play: `./gradlew :app:assemblePlayDebug`
    - Sideload: `./gradlew :app:assembleSideloadDebug`
- **Install on Device:**
    - Play: `./gradlew :app:installPlayDebug`
    - Sideload: `./gradlew :app:installSideloadDebug`
- **Static Analysis:**
    - Run Detekt: `./gradlew detekt`

### Build Variants
- `play`: Standard build for Google Play Store; self-update and CloudStream runtime are disabled.
- `sideload`: Enhanced build for direct APK distribution; includes self-update and CloudStream plugin support.

## Development Conventions

- **Compose-First:** All new UI should be built using Jetpack Compose. Use `androidx.tv` components for TV-specific UI.
- **D-pad Support:** TV screens must be fully navigable via D-pad. Use `Modifier.focusRestorer()` and proper focus management.
- **Dependency Injection:** Use Hilt for all dependency injection.
- **Asynchronous Work:** Use Kotlin Coroutines and Flow. Prefer `collectAsStateWithLifecycle` in Composables.
- **Local-First:** Use Room for caching and offline support. Repositories should manage the sync between local and remote data.
- **Error Handling:** Log errors using `AppLogger`. Critical errors should be reported via Sentry/Crashlytics. Non-fatal UI errors are often swallowed in the global handler to prevent hard crashes on TV.
- **Stateless UI:** Keep Composables as stateless as possible by hoisting state to ViewModels.
- **Version Control:** Do not commit `secrets.properties` or `keystore.properties`.

## Key Files
- `app/build.gradle.kts`: Main project dependencies and build configuration.
- `app/src/main/kotlin/com/streame/tv/StreameApplication.kt`: Application initialization logic (Hilt, Coil, Crashlytics).
- `app/src/main/kotlin/com/streame/tv/MainActivity.kt`: Root activity and navigation entry point.
- `app/src/main/kotlin/com/streame/tv/navigation/AppNavigation.kt`: Navigation graph definition.
- `app/src/main/kotlin/com/streame/tv/data/repository/`: Core business logic entry points.
