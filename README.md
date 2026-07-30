# Smugly

Multiplatform client around Slipstream DNS tunnel, S3 dead-drop (s3fu), and Xray.

## Shared UI (Compose Multiplatform)

**One Compose UI** (`SmuglyApp`) for Android, Windows/desktop, and iOS — same dark palette,
Home / Settings / Diagnostics / Profile editor, drawer, connect bar.

| Target | Module | UI | Tunnel |
|--------|--------|----|--------|
| **Android** | `:app` → `ComposeMainActivity` | Shared Compose | VpnService + proxy + native libs |
| **Windows** | `:desktop` + `:shared` desktop | Shared Compose window | Engine process + local mixed proxy + system proxy (S3 / Xray) |
| **Linux / macOS** | `:desktop` + `:shared` desktop | Shared Compose window | Local proxy only (no system-proxy integration) |
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
- `SmuglyPlatform` bridge (profiles, connect, clipboard, …)

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
# Embed SmuglyShared.framework and call MainViewController()
```

See `iosApp/README.md`.

## Tests

- `:shared:desktopTest` — pure multiplatform + UI-related models  
- `:app:testDebugUnitTest` — Android unit tests  
- `:desktop:test` — desktop platform smoke tests

## Windows client (system-proxy mode)

Connecting starts a native engine, fronts it with one local port that speaks both HTTP and
SOCKS5, and points the Windows proxy setting at that port:

```
Windows system proxy  ->  127.0.0.1:<listenPort>      MixedProxyServer  (HTTP CONNECT + SOCKS5)
                                    |  SOCKS5
                          127.0.0.1:<listenPort + 1>  engine process    (s3fu.exe / xray.exe)
                                    |
                                 tunnel
```

The HTTP front end is not optional: `ProxyServer=127.0.0.1:1080` with no scheme means WinINET
sends *every* scheme there as HTTP, so a SOCKS5-only listener is invisible to the system proxy.
Keeping the engine SOCKS5-only also means one code path for traffic counters, local auth and
connection limits regardless of protocol.

**Supported protocols:** S3 (`s3fu`) and Xray. Slipstream-over-DNS has no Windows build yet — it
needs picoquic compiled with MSVC + CMake (see `slipstream-rust/scripts/build_picoquic_windows.ps1`).
Selecting a Slipstream profile on desktop fails with an explicit message rather than silently
doing nothing.

### Build

```bash
# engines (WSL: s3fu cross-compiled via cargo-zigbuild; xray via the Windows Go toolchain)
bash _wsl_build_s3fu_windows.sh
GOOS=windows GOARCH=amd64 go build -trimpath -ldflags "-s -w" -o engines/xray.exe ./main   # in Xray-core/

# app runtime (Gradle must run in WSL: :shared needs an Android SDK)
bash _wsl_build_desktop_windows.sh
```

Then either run from the staged jars via `run-desktop-windows.cmd`, or build a real launcher:

```powershell
.\build-windows-exe.ps1        # jpackage --type app-image -> dist\Smugly\Smugly.exe
```

That produces a self-contained folder (launcher + trimmed JRE + jars + engines, ~275 MB) with no
installer and no admin rights — copy or delete it freely. An `.msi` would additionally need the
WiX toolset installed. Two launchers are generated:

- `Smugly.exe` — the GUI, windowless
- `Smugly-cli.exe` — same binary with a console attached, so the flags below print output

Engines are looked up in `SMUGLY_ENGINE_DIR`, next to the app (`dist\Smugly\app\engines`), in
`engines/`, or in `%USERPROFILE%\.smugly\engines`.

### System proxy safety

The proxy keys under `HKCU\...\Internet Settings` are shared with every other proxy tool
(Throne, v2rayN, Clash …) and none of them coordinate — last writer wins. So:

- the previous settings are snapshotted **to disk** before any change, and restored on disconnect,
  on window close, and on JVM shutdown;
- the existing bypass list (Steam CDNs and friends) is preserved, never replaced;
- if another tool held the proxy, connecting says so instead of silently taking over;
- local SOCKS/HTTP auth is **not offered on desktop** and is forced off: Windows has nowhere to
  put proxy credentials, so every system-routed request would come back 407.

A *force-kill* (Task Manager, power loss) is the one case a shutdown hook cannot cover. It is
handled on the next launch, and can be fixed without the GUI:

```
run-desktop-windows.cmd --restore-system-proxy
```

An engine orphaned the same way is reaped on the next connect via `%USERPROFILE%\.smugly\engines\<name>.pid`.

### Headless / diagnostics

```
run-desktop-windows.cmd --connect                 # run the tunnel with no window until Ctrl+C
run-desktop-windows.cmd --engines                 # which engine binaries were found
run-desktop-windows.cmd --show-system-proxy       # current setting + whether a restore is pending
run-desktop-windows.cmd --restore-system-proxy    # undo a hijack left by a force-kill
```

Set `SMUGLY_NO_SYSTEM_PROXY=1` to run as a plain local proxy and leave the machine's proxy
settings alone — useful when another tool owns them.

### Rendering / memory

The desktop app renders with Skia's **software** backend by default. Creating a GPU device is the
single biggest allocation in the process, and this UI does not need it:

| `skiko.renderApi` | working set | committed |
|---|---|---|
| SOFTWARE (default) | 121–136 MB | 124–139 MB |
| DIRECT3D | 223 MB | 279 MB |
| OPENGL | 234 MB | 253 MB |

GPU rendering buys smoother animation; switch back without rebuilding via
`set JAVA_TOOL_OPTIONS=-Dskiko.renderApi=DIRECT3D`.

The JVM itself accounts for ~85 MB of that (heap, metaspace, code cache, the CDS archive). The heap
is capped at 256 MB with SerialGC — by default the JVM committed a 254 MB heap for ~23 MB of live
objects, since the initial size is 1/16 of physical RAM.
