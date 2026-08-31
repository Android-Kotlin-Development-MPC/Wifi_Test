# WiFi Signal Monitor

A minimal Android app that continuously monitors the currently connected Wi-Fi network:
SSID, BSSID, RSSI (instant + 30s rolling average), link speed, frequency/band, channel,
IP address, and a live signal-strength bar indicator.

- **Package**: `com.wifisignal.monitor`
- **minSdk**: 26 (Android 8.0) · **targetSdk/compileSdk**: 34
- **Language**: Kotlin, ViewBinding, Coroutines

---

## 1. Prerequisites

| Tool | Version | Notes |
|---|---|---|
| JDK | 17+ | `java -version` should report 17 |
| Android SDK | API 34 platform + Build-Tools 34+ | Installed via Android Studio or `sdkmanager` |
| Gradle | 8.14 | Not required manually — the wrapper (`./gradlew`) downloads it |

### macOS setup (Homebrew)

```bash
brew install --cask temurin@17          # JDK
brew install --cask android-studio      # includes SDK manager, or use cmdline-tools
```

If you use `sdkmanager` directly:

```bash
sdkmanager "platforms;android-34" "build-tools;34.0.0" "platform-tools"
```

### Point the project at your SDK

`local.properties` in the repo root tells Gradle where your SDK lives:

```properties
sdk.dir=/Users/<you>/Library/Android/sdk
```

This file is machine-specific — do not commit it.

---

## 2. Building

```bash
./gradlew assembleDebug        # debug APK
./gradlew assembleRelease      # release APK (unsigned unless configured)
```

Output locations:

- Debug: `app/build/outputs/apk/debug/app-debug.apk`
- Release: `app/build/outputs/apk/release/app-release-unsigned.apk`

Other useful tasks:

```bash
./gradlew clean                # remove build outputs
./gradlew lint                 # run Android lint
./gradlew tasks                # list everything available
```

---

## 3. Installing on a device

1. Enable **Developer options** and **USB debugging** on the phone.
2. Connect via USB and verify:

   ```bash
   adb devices
   ```

3. Install:

   ```bash
   adb install app/build/outputs/apk/debug/app-debug.apk
   ```

   Or rebuild-and-install in one step:

   ```bash
   ./gradlew installDebug
   ```

To view logs while running:

```bash
adb logcat | grep -i wifisignal
```

---

## 4. Runtime permissions

Reading Wi-Fi details (especially SSID/BSSID) requires **location permission** on Android 8+.
The app prompts for `ACCESS_FINE_LOCATION` on first launch — grant it, otherwise the app will
show *"Location permission required to read Wi-Fi info"*.

Optionally grant from the shell instead:

```bash
adb shell pm grant com.wifisignal.monitor android.permission.ACCESS_FINE_LOCATION
```

Also make sure **Location services** are enabled system-wide (Settings → Location), otherwise
SSID may still come back as `<unknown ssid>`.

---

## 5. Project structure

```
.
├── app/
│   ├── build.gradle.kts               # app module config + dependencies
│   ├── proguard-rules.pro
│   └── src/main/
│       ├── AndroidManifest.xml        # permissions + launcher activity
│       ├── java/com/wifisignal/monitor/
│       │   └── MainActivity.kt        # all app logic (polling, UI updates)
│       └── res/
│           ├── drawable/ic_launcher_foreground.xml
│           ├── layout/activity_main.xml
│           ├── mipmap-anydpi-v26/ic_launcher.xml
│           ├── values/                # strings, colors, themes
│           └── values-night/themes.xml
├── gradle/wrapper/                    # wrapper jar + properties
├── build.gradle.kts                   # root plugin declarations (AGP 8.7.0, Kotlin 1.9.22)
├── settings.gradle.kts
└── local.properties                   # (local only) SDK path
```

## 6. Troubleshooting

- **`SDK location not found`** — create/fix `local.properties` (see section 1).
- **Wrapper download fails** — check network, or edit the URL in
  `gradle/wrapper/gradle-wrapper.properties` to a mirror you can reach.
- **`Unsupported class file major version`** — you're running an old JDK; use JDK 17.
- **SSID shows `<unknown ssid>`** — grant location permission *and* enable system Location.
