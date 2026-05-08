<p align="center">
  <img src="screenshots/banner.png" alt="Streame" width="400"/>
</p>

# Streame - Media Hub for Android TV, Fire TV, Mobile, and Tablet

A media hub application for Android TV, Fire TV, phones, and tablets with a modern interface. Browse catalogs, sync your setup across profiles and devices, discover content, and play videos from your configured sources.

## Features

- **Catalog Management** - Built-in + custom Trakt/MDBList catalogs with ordering controls
- **Streame Cloud (Optional)** - QR sign-in with cloud sync for profiles, settings, addons, catalogs, watch state, and subtitles
- **Addon Support** - Connect compatible addons and community HTTP sources
- **Media Browser** - Browse and discover content with TMDB metadata
- **Beautiful UI** - Modern horizontal row browsing optimized for D-pad/remote
- **Trakt.tv Integration** - Sync watch history, watchlist, and in-progress items per profile
- **Watchlist** - Save items to watch later
- **Multi-profile** - Multiple user profiles per account
- **Subtitle & Audio** - Multiple tracks with language selection
- **Continue Watching** - Resume from where you left off
- **Auto-play** - Next episode auto-play with countdown

## Player

Powered by **ExoPlayer (Media3)** with **FFmpeg extension** for broad codec support.

**Video:** H.264, H.265/HEVC, VP9, AV1, Dolby Vision
**Audio:** AAC, AC3, EAC3, DTS, DTS-HD, TrueHD, Dolby Atmos
**Containers:** MKV, MP4, WebM, HLS, DASH
**Quality:** Up to 4K HDR

Note: If you mean **DTX**, Streame supports **DTS-family audio formats** (DTS/DTS-HD). Actual passthrough/decoding still depends on device + Android audio pipeline.

## Screenshots

| Home | Details |
|------|---------|
| ![Home TV](screenshots/home_v190.png) | ![Details TV](screenshots/details_v190.png) |

| Mobile Home | Mobile Details |
|-------------|----------------|
| ![Mobile Home](screenshots/mobile_details.webp) | ![Mobile Details](screenshots/mobile_home.webp) |

| Cast | Profiles |
|------|----------|
| ![Cast](screenshots/player.png) | ![Profiles](screenshots/profiles_v184.png) |

| Catalogs (Trakt + MDBList) |
|----------------------------|
| ![Catalogs](screenshots/catalogs_v13.png) |

## Download

### Direct Download
[Download Streame V1.1 APK](https://github.com/streame0/StreameV2/releases/download/v1.1/Streame-v1.1.apk) or browse the [Releases page](https://github.com/streame0/StreameV2/releases).

## Build And Run

Requirements:
- Android Studio or Android SDK command-line tools
- JDK 17
- Android SDK 35

Use the tracked Gradle wrapper:

```bash
./gradlew :app:assemblePlayDebug
./gradlew :app:assembleSideloadDebug
```

On Windows PowerShell or Command Prompt, use `gradlew.bat`:

```powershell
.\gradlew.bat :app:assemblePlayDebug
.\gradlew.bat :app:assembleSideloadDebug
```

Install a debug build on a connected Android TV, Fire TV, emulator, phone, or tablet:

```bash
./gradlew :app:installPlayDebug
./gradlew :app:installSideloadDebug
```

For network ADB devices:

```bash
adb connect <device-ip>:5555
adb install -r app/build/outputs/apk/sideload/debug/app-sideload-debug.apk
```

Build variants:
- `play`: Play Store build, self-update and Cloudstream runtime disabled.
- `sideload`: Direct APK build, self-update and Cloudstream runtime enabled.
- `debug`: development build.
- `staging`: release-like build signed with the debug keystore for upgrade testing.
- `release`: production build. If `keystore.properties` is missing, the build falls back to debug signing; use a real keystore for distribution.

## Local Configuration

Cloud sync, Google sign-in, and Supabase-backed auth need local secrets. Copy the defaults file and fill in real values:

```bash
cp secrets.defaults.properties secrets.properties
```

`secrets.properties` is ignored and should not be committed. Supabase Edge Functions live in `supabase/functions/`; deploy the proxy/auth functions there when using Streame Cloud or server-side TMDB/Trakt access.

For signed release builds, copy the keystore template and fill in local signing values:

```bash
cp keystore.properties.template keystore.properties
```

`keystore.properties` and keystore files are ignored and should stay private.

## Release Checks

The old GitHub workflow and CSV release gate were removed. Until a replacement CI/release pipeline is added, use this manual release checklist before publishing:

```bash
./gradlew :app:compilePlayDebugKotlin
./gradlew :app:assemblePlayRelease
./gradlew :app:assembleSideloadRelease
```

Smoke-test at least startup, profile switching, playback, stream fallback, subtitle/audio switching, addon add/remove, search, settings navigation, background sync, and repeated player open/close on the device classes you support. For Windows verification, also run `.\gradlew.bat :app:compilePlayDebugKotlin` from PowerShell or Command Prompt.

## Support

Join the community:
- Discord: https://discord.gg/bGBBGKFZVh

## License

This project is licensed under the Apache License 2.0 - see the [LICENSE](LICENSE) file for details.

## Privacy Policy

See [PRIVACY.md](PRIVACY.md) for our privacy policy.

## AI Disclosure

This application was developed with significant assistance from AI (Claude by Anthropic). If you have concerns about using AI-generated software, please do not use this application.

## Disclaimer

Streame is a media hub application that does not host, store, or distribute any content. It is a player interface that connects to user-configured addons and external services. Users are solely responsible for the addons they install and the content they access. The developers are not responsible for any misuse of this application.
