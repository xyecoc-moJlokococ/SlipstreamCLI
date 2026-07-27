# Vaydns / SlipstreamCLI

Multiplatform client around Slipstream DNS tunnel, S3 dead-drop (s3fu), and Xray.

## Shared UI (Compose Multiplatform)

**One Compose UI** (`VaydnsApp`) for Android, Windows/desktop, and iOS — same dark palette,
Home / Settings / Diagnostics / Profile editor, drawer, connect bar.

| Target | Module | UI | Tunnel |
|--------|--------|----|--------|
| **Android** | `:app` → `ComposeMainActivity` | Shared Compose | VpnService + proxy + native libs |
| **Windows / Linux / macOS** | `:desktop` + `:shared` desktop | Shared Compose window | UI + profile store (native host tunnel later) |
| **iPhone** | `:shared` iOS framework | Shared Compose (`MainViewController()`) | UI + in-memory store (Packet Tunnel later) |

Legacy Android View `MainActivity` remains in the project (not the launcher) for rollback.

## Layout

```
shared/     KMP core + Compose UI (commonMain)
app/        Android host (ComposeMainActivity + VPN)
desktop/    Desktop packaging / run task
iosApp/     Notes for Xcode embedding
```

### Shared (platform-free)

- `Config`, profiles, strings (`S` / `t`)
- SOCKS / reaper / recovery watches
- Compose screens + theme (`ui/`)
- `VaydnsPlatform` bridge (profiles, connect, clipboard, …)

## Build

### Android

```bash
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest
```

### Desktop GUI

```bash
./gradlew :desktop:run
# headless helpers still work via shared CLI paths if needed
./gradlew :shared:desktopTest
```

### iOS (macOS + Xcode)

```bash
./gradlew :shared:linkReleaseFrameworkIosArm64
# Embed VaydnsShared.framework and call MainViewController()
```

See `iosApp/README.md`.

## Tests

- `:shared:desktopTest` — pure multiplatform + UI-related models  
- `:app:testDebugUnitTest` — Android unit tests  
- `:desktop:test` — desktop platform smoke tests
