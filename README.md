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
| 2a. Protokoll-Modul `:openrgb-sdk` (v0–6, Reconnect, Backpressure, Scan) | ✅ 114 Tests |
| 2b. Verbindungen / Geräteliste (App) | ⏳ |
| 2c. Farbsteuerung | ⏳ |
| 2d. Profile | ⏳ |
| 2e. Effekt-Engine | ⏳ |
| 2f. UI-Feinschliff, Widgets, Kacheln | ⏳ |

## Bauen & Testen

Voraussetzung: JDK 17+ (der Gradle-Wrapper lädt Gradle 9.9 automatisch).

```bash
./gradlew :openrgb-sdk:test
```

### Tests gegen einen echten OpenRGB-Server

```bash
# PC mit OpenRGB: "SDK Server" starten (GUI) oder headless:
openrgb --server --server-port 6742

OPENRGB_TEST_SERVER=192.168.1.20:6742 ./gradlew :openrgb-sdk:test
```

Die Fixtures unter `openrgb-sdk/src/test/resources/fixtures/openrgb-1.0` wurden mit
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
