# Streame - Project Context

Streame is a modern media hub application for Android TV, Fire TV, mobile phones, and tablets. It provides a unified interface for browsing catalogs, syncing watch states, and playing media from various sources.

## Tech Stack

- **Platform:** Android (TV & Mobile)
- **Language:** Kotlin 2.1.0
- **UI Framework:** Jetpack Compose (BOM 2024.06.00) with `androidx.tv` components
- **Media Player:** Media3 (ExoPlayer) 1.3.1 with FFmpeg extension (Jellyfin)
- **Dependency Injection:** Hilt 2.54
- **Database:** Room 2.6.1
- **Networking:** Retrofit 2.9.0 + OkHttp 4.12.0
- **Architecture:** MVVM with Clean Architecture principles
- **Sync & Auth:** Supabase (Cloud sync), Trakt.tv integration
- **Static Analysis:** Detekt

## Project Structure

- `app/src/main/kotlin/com/streame/tv/`
    - `data/`: Room entities, DAOs, repositories, and domain models.
    - `di/`: Hilt modules for dependency injection.
    - `navigation/`: Compose navigation logic and route definitions.
    - `network/`: Retrofit services and API definitions (Trakt, TMDB, Supabase).
    - `ui/`: Compose screens, ViewModels, and UI components.
        - `screens/`: Feature-specific screens (Home, Details, Player, etc.).
        - `components/`: Reusable UI elements.
        - `theme/`: Styling, colors, and typography.
    - `updater/`: Update logic for the sideload distribution.
    - `worker/`: WorkManager workers for background sync and metadata fetching.
    - `MainActivity.kt`: Entry point handling global state and navigation.
- `benchmark/`: Performance benchmarks and baseline profiles.

## Build and Run

The project uses Gradle (Kotlin DSL). Use the provided `gradlew` wrapper.

### Build Variants

- **Flavors:**
    - `play`: Google Play Store compliant (no self-update, limited runtime).
    - `sideload`: Full feature set (self-update enabled, Cloudstream runtime).
- **Build Types:**
    - `debug`: Development build with logging and no obfuscation.
    - `staging`: Release-grade optimizations signed with debug key.
    - `release`: Production build with R8 optimization.

### Key Commands

```powershell
# Compile check
.\gradlew.bat :app:compilePlayDebugKotlin

# Build Debug APKs
.\gradlew.bat :app:assemblePlayDebug
.\gradlew.bat :app:assembleSideloadDebug

# Install on connected device
.\gradlew.bat :app:installPlayDebug
.\gradlew.bat :app:installSideloadDebug

# Run static analysis
.\gradlew.bat detekt
```

## Development Conventions

1.  **UI:** Always use Jetpack Compose for UI. For TV-specific features, leverage `androidx.tv:tv-material` and `androidx.tv:tv-foundation`.
2.  **State Management:** Use `ViewModel` with `StateFlow` and `collectAsStateWithLifecycle()` in Compose.
3.  **DI:** Use Hilt for all dependency injection. Avoid manual instantiation of complex objects.
4.  **Local Storage:** Use Room for structured data and DataStore for simple preferences.
5.  **Coroutines:** Use Kotlin Coroutines and Flow for asynchronous operations. Prefer `lifecycleScope` or `viewModelScope`.
6.  **Style:** Follow the Kotlin coding style. Run `detekt` before submitting changes.
7.  **Secrets:** Local secrets belong in `secrets.properties` (copied from `secrets.defaults.properties`). Never commit `secrets.properties`.
8.  **Signing:** Release signing configuration is managed via `keystore.properties` (copied from `keystore.properties.template`).

## Important Files

- `app/build.gradle.kts`: Main module configuration and dependencies.
- `README.md`: High-level overview and features.
- `secrets.defaults.properties`: Template for required API keys and secrets.
- `keystore.properties.template`: Template for signing configurations.
- `app/compose_stability_config.conf`: Configuration for Compose compiler stability.

---
*Note: This project was developed with significant assistance from AI (Claude). Keep this in mind when making architectural changes.*
