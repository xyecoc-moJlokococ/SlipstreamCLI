# iOS target

Shared module `:shared` builds `VaydnsShared.framework` with:

- Pure core (`Config`, SOCKS, recovery watches, …)
- **Compose Multiplatform UI** — call `MainViewController()` from Swift/UIKit

## Build (macOS + Xcode required)

```bash
cd SlipstreamCLI
./gradlew :shared:linkReleaseFrameworkIosArm64
# simulator:
./gradlew :shared:linkReleaseFrameworkIosSimulatorArm64
```

Typical output:

```
shared/build/bin/iosArm64/releaseFramework/VaydnsShared.framework
```

### Host app

```swift
import VaydnsShared
import UIKit

@main
class AppDelegate: UIResponder, UIApplicationDelegate {
    var window: UIWindow?
    func application(_ application: UIApplication,
                     didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]?) -> Bool {
        window = UIWindow(frame: UIScreen.main.bounds)
        window?.rootViewController = MainViewController()
        window?.makeKeyAndVisible()
        return true
    }
}
```

`IosPlatform` currently uses an in-memory profile store. Swap for App Group
UserDefaults / files for production. **Packet Tunnel Provider** (system VPN) is still
separate native work on top of this UI.

## Desktop

```bash
./gradlew :desktop:run   # Compose window — same UI
```
