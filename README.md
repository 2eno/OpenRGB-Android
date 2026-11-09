# OpenRGB Remote (Android)

Inoffizieller, moderner Android-Client für den [OpenRGB](https://openrgb.org) SDK Server.
Er steuert RGB-Beleuchtung an PCs im Netzwerk, auf denen OpenRGB mit aktiviertem SDK-Server
läuft (Standardport **6742**).

> ⚠️ **Sicherheit:** Der OpenRGB SDK Server hat keine Authentifizierung. Nutze ihn nur im
> vertrauenswürdigen LAN oder über ein VPN und gib Port 6742 nie ins Internet frei.

## Stand

| Schritt | Status |
|---|---|
| 1. Architektur & Projektstruktur | ✅ [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) |
| 2a. Protokoll-Modul `:openrgb-sdk` (v0–6, Reconnect, Backpressure, Scan) | ✅ |
| 2b. Verbindungen, Netzwerk-Scan, Geräteliste, Detailansicht, Onboarding (App) | ✅ |
| 2c. Farbsteuerung (Farbrad, HSV, Hex, Favoriten, Ziele, Live-Vorschau) und Hardware-Modi | ✅ |
| 2d. Profile (8 vorgefertigte, eigene, Duplizieren, JSON-Import/-Export, global oder pro Gerät) | ✅ |
| 2e. Effekt-Engine | ⏳ |
| 2f. UI-Feinschliff, Widgets, Kacheln | ⏳ |

## Screenshots

Gerendert von den Robolectric-Screenshot-Tests (`app/src/test/.../ScreenshotTest.kt`). Die Gerätedaten
stammen aus echten Paketen eines OpenRGB-1.0-Servers; die Farben sind zur Demonstration gesetzt,
weil Debug-Geräte alle LEDs als schwarz melden.

| Geräte | Detail (Tastatur-Matrix) | Onboarding: Verbinden | Fehlermeldung |
|---|---|---|---|
| ![](docs/screenshots/devices_phone_de.png) | ![](docs/screenshots/device_detail_keyboard_de.png) | ![](docs/screenshots/onboarding_3_connect_de.png) | ![](docs/screenshots/server_edit_failure_de.png) |

| Farbsteuerung | Hardware-Modus |
|---|---|
| ![](docs/screenshots/color_all_de.png) | ![](docs/screenshots/device_detail_modes_de.png) |

| Profile | Profil-Editor |
|---|---|
| ![](docs/screenshots/profiles_de.png) | ![](docs/screenshots/profile_edit_de.png) |

Tablet: ![](docs/screenshots/devices_tablet_de.png) ![](docs/screenshots/color_tablet_de.png)

## Bauen & Testen

Voraussetzungen: JDK 17+, Android SDK (Plattform 37) mit `ANDROID_HOME` oder `local.properties`.
Der Gradle-Wrapper lädt Gradle 9.9 automatisch.

```bash
./gradlew :app:assembleDebug                      # APK
./gradlew test :core:data:testDebugUnitTest :app:testDebugUnitTest   # alle Unit-Tests
./gradlew :app:lintDebug                          # Lint (Warnungen gelten als Fehler)
```

Die Screenshot-Tests schreiben PNGs nach `app/build/screenshots/`. Robolectric läuft offline:
seine Android-Laufzeit wird über Gradle aufgelöst (siehe `app/build.gradle.kts`).

Texte stehen in `tools/strings_app.py` (Englisch + Deutsch) und werden mit
`python3 tools/strings_app.py` nach `app/src/main/res/values*/strings.xml` generiert. So fehlt nie
ein Schlüssel in einer der Sprachen.

### Tests gegen einen echten OpenRGB-Server

```bash
# PC mit OpenRGB: "SDK Server" starten (GUI) oder headless:
openrgb --server --server-port 6742

OPENRGB_TEST_SERVER=192.168.1.20:6742 ./gradlew :openrgb-sdk:test
```

Die Fixtures unter `openrgb-sdk/src/testFixtures/resources/fixtures/openrgb-1.0` wurden mit
`tools/capture_fixtures.py` von einem OpenRGB-1.0-Server mit Debug-Geräten mitgeschnitten
(Anleitung im Skriptkopf).

## SDK-Beispiel

```kotlin
val client = OpenRgbClient("192.168.1.20")
client.connect()                                   // handelt Protokoll v0–6 aus
val keyboard = client.devices.value.first { it.type == DeviceType.KEYBOARD }
keyboard.directModeIndex()?.let { client.updateMode(keyboard.id, it, keyboard.modes[it]) }
client.updateLeds(keyboard.id, IntArray(keyboard.ledCount) { 0xFF0040 })
client.close()
```

Mit automatischem Reconnect: `OpenRgbConnection(host, port, scope).start()`.

## Lizenz-Hinweis

OpenRGB ist ein eigenständiges GPL-Projekt; diese App ist nicht damit verbunden. Das Protokoll
wurde anhand der öffentlichen SDK-Dokumentation implementiert; es wird kein OpenRGB-Code verwendet.
