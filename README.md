# OpenRGB Remote (Android)

*English · [Deutsch](README.de.md)*

An unofficial, modern Android client for the [OpenRGB](https://openrgb.org) SDK server.
It controls RGB lighting on PCs in your network that run OpenRGB with the SDK server enabled
(default port **6742**).

> ⚠️ **Security:** the OpenRGB SDK server has no authentication. Only use it in a trusted LAN
> or over a VPN, and never expose port 6742 to the internet.

## Status

| Step | Status |
|---|---|
| 1. Architecture & project structure | ✅ [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) |
| 2a. Protocol module `:openrgb-sdk` (v0–6, reconnect, backpressure, scan) | ✅ |
| 2b. Connections, network scan, device list, detail view, onboarding (app) | ✅ |
| 2c. Color control (color wheel, HSV, hex, favorites, targets, live preview) and hardware modes | ✅ |
| 2d. Profiles (8 presets, custom, duplicate, JSON import/export, global or per device) | ✅ |
| 2e. Effects engine (8 effects incl. music), foreground service, Effects tab | ✅ |
| 2f. Favorites widget, 3 quick settings tiles, schedules, accessibility check | ✅ |

## Screenshots

Rendered by the Robolectric screenshot tests (`app/src/test/.../ScreenshotTest.kt`). The device
data comes from real packets of an OpenRGB 1.0 server; the colors are set for demonstration,
because debug devices report all LEDs as black. The screenshots show the German UI.

| Devices | Detail (keyboard matrix) | Onboarding: connect | Error message |
|---|---|---|---|
| ![](docs/screenshots/devices_phone_de.png) | ![](docs/screenshots/device_detail_keyboard_de.png) | ![](docs/screenshots/onboarding_3_connect_de.png) | ![](docs/screenshots/server_edit_failure_de.png) |

| Color control | Hardware mode |
|---|---|
| ![](docs/screenshots/color_all_de.png) | ![](docs/screenshots/device_detail_modes_de.png) |

| Profiles | Profile editor |
|---|---|
| ![](docs/screenshots/profiles_de.png) | ![](docs/screenshots/profile_edit_de.png) |

| Effects | Effect settings |
|---|---|
| ![](docs/screenshots/effects_de.png) | ![](docs/screenshots/effect_settings_de.png) |

| Favorites (widget & tiles) | Schedules | Schedule editor | Large font (200 %) |
|---|---|---|---|
| ![](docs/screenshots/quick_actions_de.png) | ![](docs/screenshots/schedules_de.png) | ![](docs/screenshots/schedule_edit_de.png) | ![](docs/screenshots/quick_actions_en_large_font.png) |

Tablet: ![](docs/screenshots/devices_tablet_de.png) ![](docs/screenshots/color_tablet_de.png)

## Build & test

Requirements: JDK 17+, Android SDK (platform 37) with `ANDROID_HOME` or `local.properties`.
The Gradle wrapper downloads Gradle 9.9 automatically.

```bash
./gradlew :app:assembleDebug                      # APK
./gradlew test :core:data:testDebugUnitTest :app:testDebugUnitTest   # all unit tests
./gradlew :app:lintDebug                          # lint (warnings are errors)
```

The screenshot tests write PNGs to `app/build/screenshots/`. Robolectric runs offline: its
Android runtime is resolved through Gradle (see `app/build.gradle.kts`).

Strings live in `tools/strings_app.py` (English + German) and are generated into
`app/src/main/res/values*/strings.xml` with `python3 tools/strings_app.py`. That way no key is ever
missing in one of the languages.

### Tests against a real OpenRGB server

```bash
# PC with OpenRGB: start the "SDK Server" (GUI) or headless:
openrgb --server --server-port 6742

OPENRGB_TEST_SERVER=192.168.1.20:6742 ./gradlew :openrgb-sdk:test
```

The fixtures in `openrgb-sdk/src/testFixtures/resources/fixtures/openrgb-1.0` were captured with
`tools/capture_fixtures.py` from an OpenRGB 1.0 server with debug devices (instructions in the
script header).

## SDK example

```kotlin
val client = OpenRgbClient("192.168.1.20")
client.connect()                                   // negotiates protocol v0–6
val keyboard = client.devices.value.first { it.type == DeviceType.KEYBOARD }
keyboard.directModeIndex()?.let { client.updateMode(keyboard.id, it, keyboard.modes[it]) }
client.updateLeds(keyboard.id, IntArray(keyboard.ledCount) { 0xFF0040 })
client.close()
```

With automatic reconnect: `OpenRgbConnection(host, port, scope).start()`.

## License note

OpenRGB is an independent GPL project; this app is not affiliated with it. The protocol was
implemented from the public SDK documentation; no OpenRGB code is used.
