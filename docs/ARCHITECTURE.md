# Architektur – OpenRGB Remote für Android

Inoffizieller Android-Client für den **OpenRGB SDK Server** (TCP, Standardport 6742).
Die App steuert ausschließlich RGB-Hardware an PCs im Netzwerk; es gibt keinen lokalen
Server und keine lokale Hardwaresteuerung.

## 1. Leitentscheidungen

| Entscheidung | Begründung |
|---|---|
| **Protokoll als reine Kotlin/JVM-Bibliothek** (`:openrgb-sdk`) | Ohne Android-Abhängigkeiten auf der JVM testbar (inkl. echter Sockets), wiederverwendbar, klare Grenze zwischen Bytes und App. |
| **Blocking `java.net.Socket` statt NIO-Selector/Ktor** | Pro Server genau eine Verbindung, ein Leser, kurze Schreibvorgänge. Ein IO-Thread pro Verbindung ist einfacher und genauso schnell. `TCP_NODELAY` verhindert bis zu 200 ms Nagle-Verzögerung pro Frame. |
| **Clean Architecture mit drei Schichten** | `domain` (reines Kotlin: Entities, Use-Cases, Repository-Interfaces) ← `data` (Room, DataStore, SDK) ← `presentation` (Compose + ViewModels). Abhängigkeiten zeigen nur nach innen. |
| **Feature-Pakete statt Feature-Module** | Für ein Ein-Personen-Projekt sind 6 Module ein guter Kompromiss: Build-Zeiten bleiben kurz, die Grenzen sind trotzdem vom Compiler geprüft. |
| **Effekt-Engine als reines Kotlin** (`:core:effects`) | Renderer sind reine Funktionen `(Zeit, LED-Geometrie, Parameter) → Farben`. Das ist deterministisch testbar und unabhängig von Android. |
| **Hilt, Coroutines/Flow, Room + DataStore** | Wie gefordert. Room für strukturierte Daten (Server, Profile, Zeitpläne), DataStore für Einstellungen. |

## 2. Modul- und Projektstruktur

```
OpenRGB-Android/
├── openrgb-sdk/                 ✅ Kotlin/JVM – Protokoll, Client, Reconnect, Discovery
│   └── src/main/kotlin/dev/eno2/openrgbremote/sdk/
│       ├── protocol/            Header, Paket-IDs, LE-Reader/Writer, DeviceCodec, Requests, Responses
│       ├── model/               OpenRgbDevice, Zone, DeviceMode, Segment, Led, MatrixMap, RgbColor, DeviceType
│       ├── transport/           PacketTransport (Interface), SocketTransport
│       ├── client/              OpenRgbClient, OpenRgbConnection (Auto-Reconnect), LedFrameStreamer, ConnectionError
│       └── discovery/           Ipv4Subnet, OpenRgbProbe, SubnetScanner
├── core/
│   ├── domain/                  ✅ Kotlin/JVM – Server, Status, DeviceKey, Repository-Interfaces, Use-Cases
│   ├── effects/                 ⏳ Kotlin/JVM – Effekt-Renderer, LED-Geometrie, EffectRunner (Taktgeber)
│   ├── data/                    ✅ Android-Lib – Room, DataStore, ConnectionManager (mehrere PCs), Scan, Hilt
│   └── designsystem/            ✅ Android-Lib – Material-3-Theme, Gerätesymbole, LED-Darstellung und Farbwähler
├── app/                         ✅ Android-App (Geräte, Detail, Farbe, Modi, Profile, PCs, Scan, Einstellungen, Onboarding; Rest ⏳)
│   └── src/main/kotlin/dev/eno2/openrgbremote/
│       ├── feature/onboarding/  Drei-Schritte-Onboarding inkl. Anleitung „SDK-Server starten“
│       ├── feature/devices/     Geräteübersicht, Detailansicht (Zonen/LEDs), Farbsteuerung
│       ├── feature/profiles/    Statische und eigene Profile, Import/Export
│       ├── feature/effects/     Dynamische Effekte und Hardware-Modi
│       ├── feature/settings/    Server, Scan, Zeitpläne, Sicherheitshinweis, Info
│       ├── service/             EffectService (Foreground), Zeitplan-Worker
│       ├── widget/              Glance-Widgets
│       └── tile/                Quick-Settings-Kacheln
├── tools/capture_fixtures.py    Schneidet echte Serverpakete als Test-Fixtures mit
└── docs/ARCHITECTURE.md
```

✅ = umgesetzt und getestet, ⏳ = folgt in den nächsten Schritten.

Abhängigkeiten: `app → data, designsystem, domain, effects`; `data → domain, effects, openrgb-sdk`;
`effects → (nichts)`; `domain → (nichts)`. Die UI kennt das SDK nur über Domain-Modelle.

## 3. Protokollschicht (`:openrgb-sdk`)

### Quellen
Alle Paket-IDs, Feldreihenfolgen und Versionsgrenzen stammen aus `Documentation/OpenRGBSDK.md`
und `NetworkProtocol.h`. Zusätzlich wurden sie gegen die Serialisierer im Quellcode
(`RGBController.cpp`, `NetworkServer.cpp`, `NetworkClient.cpp`, `RGBController_Network.cpp`) von
**OpenRGB 1.1** sowie den Server von **0.9** geprüft. Die Unit-Tests verwenden **echte Pakete
eines OpenRGB-1.0-Servers** für alle Protokollversionen 0–6 (`tools/capture_fixtures.py`).
Dekodieren und erneutes Kodieren ergibt für jedes Paket exakt die Server-Bytes.

### Versionen und Fallback
| Version | Release | Wichtig für den Client |
|---|---|---|
| 0 | 0.3/0.4 | Antwortet nicht auf `REQUEST_PROTOCOL_VERSION`; nach Timeout wird v0 angenommen |
| 1 | 0.5 | Vendor-String, `DEVICE_LIST_UPDATED` |
| 2 | 0.6 | Server-Profile |
| 3 | 0.7/0.8 | Helligkeit in Modi, `SaveMode` |
| 4 | 0.9 | Segmente in Zonen |
| 5 | 1.0rc1/rc2 | Zonen-/Controller-Flags, LED-Anzeigenamen, Rescan |
| 6 | 1.0/1.1 | Unique-IDs, ACKs, `SIGNALUPDATE`, Zonen-Modi, Server-Name/-Flags; `mode_value`/`led_value` entfallen |

Ablauf: `REQUEST_PROTOCOL_VERSION(6)`, dann `min(Server, Client)`, `SET_CLIENT_NAME`, ab v6
zusätzlich `SET_CLIENT_HOSTNAME` und `SET_CLIENT_FLAGS`, danach wird die Geräteliste geladen.
Trifft eine Versionsantwort erst nach dem Fallback auf v0 ein (langsames WLAN/VPN), baut der
Client die Verbindung neu auf, statt mit falschen Annahmen weiterzuarbeiten.

### Geräte-IDs und `DEVICE_LIST_UPDATED`
* **v0–5:** `pkt_dev_id` ist der Listenindex. Nach `DEVICE_LIST_UPDATED` werden die Indizes sofort
  ungültig: Befehle werden verworfen, bis die Liste komplett neu geladen ist.
* **v6:** Unique-IDs. Es wird nur der ID-Diff abgeglichen; neue Geräte werden geladen, entfernte
  verworfen, unveränderte behalten ihren Zustand (über `SIGNALUPDATE` aktuell gehalten).
* Bursts während der Hardware-Erkennung werden entprellt (Standard 250 ms).
* Ein Heartbeat (`REQUEST_CONTROLLER_COUNT` alle 15 s) erkennt hängende Verbindungen, etwa wenn
  der PC in den Standby geht, und gleicht verpasste Listenänderungen ab.

### Nebenläufigkeit
Ein Leser-Coroutine verteilt alle eingehenden Pakete. Request/Reply-Abläufe (Version, Count,
Controller-Data) sind über einen Mutex serialisiert, weil das Protokoll keine Request-IDs hat.
LED-Frames und Modusbefehle umgehen diesen Mutex. Ein Effekt hängt so nie hinter einem
Listen-Refresh.

### Backpressure für Effekte (`LedFrameStreamer`)
* **Conflation:** Pro Gerät gibt es einen Ein-Platz-Briefkasten. Ist der Server langsam, bekommt er
  weniger Frames statt eines wachsenden Rückstaus. Die Latenz bleibt dadurch begrenzt.
* **ACK-Fenster (v6):** Höchstens N `UpdateLEDs` pro Gerät sind gleichzeitig unbestätigt (Standard 2).
  Bei verlorenem ACK setzt ein Timeout das Fenster zurück.
* **v0–5:** Ohne ACKs ist der blockierende Socket-Write die einzige Rückmeldung.
* Farben werden immer exakt auf die LED-Anzahl gekürzt bzw. aufgefüllt. Server vor 1.0 prüfen das
  nicht, mehr Farben als LEDs könnten dort einen Pufferüberlauf auslösen.

### Fehlerklassen → verständliche Meldungen
`ConnectionErrorReason` unterscheidet: Host unbekannt, Verbindung abgelehnt (Server nicht
gestartet/falscher Port), Zeitüberschreitung (PC aus/falsche IP/Firewall), nicht erreichbar
(anderes Netz/VLAN), kein OpenRGB-Server, Protokollfehler und Verbindung verloren. Die App
bildet jede Klasse auf einen lokalisierten Text mit konkretem Lösungsvorschlag ab.

## 4. Datenschicht

* **ConnectionManager** (Singleton, Hilt, reines Kotlin und auf der JVM gegen den Fake-Server
  getestet) verwaltet eine `OpenRgbConnection` pro PC, der gerade verbunden sein soll. Ein PC ist
  verbunden, solange die App *aktiv* ist und er „automatisch verbinden“ hat oder manuell verbunden
  wurde. Ein einziger Collector berechnet das bei jeder Änderung der Serverliste, der Aktivität oder
  der manuellen Overrides neu, sodass keine Locks zwischen UI und Lifecycle nötig sind.
* **Aktiv** heißt: App im Vordergrund (`ProcessLifecycleOwner`) oder ein Keep-Alive-Handle ist
  gehalten (ab Schritt 2e vom Effekt-Service). Nach 30 s im Hintergrund werden Verbindungen
  geschlossen, was Akku und Server schont.
* Kehrt das Netzwerk zurück (`ConnectivityManager`-Default-Network-Callback), wird der Backoff
  übersprungen. Ohne Netzwerk wird jeder Fehler als „Kein Netzwerk“ angezeigt.
* **Scan:** Die IPv4-Adresse und das Präfix kommen aus `LinkProperties` des WLAN-/Ethernet-Netzes,
  bei aktivem VPN aus dem Netz darunter.
* **LightingController:** Farben gehen immer als vollständiger Geräte-Frame über den
  `LedFrameStreamer` (ein Streamer pro Verbindung). Zonen- und Einzel-LED-Änderungen werden in die
  aktuellen Farben des Geräts eingemischt. So gibt es einen einzigen Codepfad mit Conflation, und
  das Ziehen im Farbwähler kann beliebig oft senden. Vor dem ersten Frame wird das Gerät in seinen
  Per-LED-Modus („Direct“) geschaltet, weil Hardware-Effekte LED-Updates ignorieren.
* Geräte **ohne Per-LED-Modus** bekommen die Farbe über die Farbliste ihres aktiven Modus. Das ist
  ein Moduswechsel, der Flash-Schreibzugriffe auslösen kann, und passiert deshalb nie in der
  Live-Vorschau, sondern erst beim Loslassen.
* **Profile:** Ein statisches Profil ist Palette + Verteilung (Verlauf, Blöcke, abwechselnd) +
  Helligkeit. `ProfileRenderer` (reines Kotlin) erzeugt daraus die LED-Farben; dieselbe Funktion
  zeichnet die Vorschau in der App. Jede Zone zeigt das ganze Profil, Matrix-Zonen werden
  spaltenweise eingefärbt, damit Verläufe auf Tastaturen von links nach rechts laufen. Geräte ohne
  Per-LED-Modus bekommen die erste Farbe. Vorgefertigte Profile stehen im Code (negative IDs,
  Namen lokalisiert), eigene in Room (`profiles`, Schema 3).
* **JSON-Austauschformat** (`JsonProfileCodec`): `format`, `schemaVersion`, `profiles[]` mit
  `name`, `type`, `layout`, `brightness`, `colors` als Hex. Unbekannte Felder werden ignoriert,
  eine höhere `schemaVersion` wird abgelehnt, Einträge mit unbekanntem `type` (später: dynamische
  Profile) werden übersprungen. Import und Export laufen über den System-Dateidialog, die App
  braucht dafür keine Speicherberechtigung.
* **Favoriten** liegen in Room (`favorite_colors`, Schema 2 mit Auto-Migration).
* **Stabile Geräte-Identität:** Unique-IDs (v6) gelten nur für eine Serversitzung, Indizes (v0–5)
  verschieben sich. Profile referenzieren Geräte daher über `serverId + name + location + serial`
  und werden bei jeder Verbindung neu aufgelöst.
* **Room:** `servers` ✅ (Schema exportiert nach `core/data/schemas`); folgen: `profiles`
  (Kopfdaten + JSON-Payload, versioniert), `favorite_colors`, `schedules`.
* **DataStore:** Onboarding-Status ✅; folgen: Ziel-FPS, Haptik, zuletzt aktives Profil.
* **Profil-Format (JSON, kotlinx.serialization):** `type = static | dynamic | hardware`, Ziel
  `global | devices[]`, Parameter. Dasselbe Format dient für Import und Export, inklusive `schemaVersion`.

## 5. Effekt-Engine (geplant, `:core:effects`)

* `Effect.render(t: Double, layout: LedLayout, params: EffectParams, out: IntArray)` als reine Funktion.
* `LedLayout`: normalisierte LED-Positionen (Linear: 0..1, Matrix: x/y aus der Matrix-Map), sodass
  Wave, Aurora und Fire auf Keyboards zweidimensional laufen.
* `EffectRunner`: `ticker` mit Ziel-FPS (30/45/60), Frame-Budget-Messung, adaptive Senkung der FPS bei
  Überlast, Ausgabe über `LedFrameStreamer` je Gerät.
* Effekte: Rainbow Wave, Color Cycle, Breathing, Fire, Aurora, Pulse, Gradient Flow, Music-Reactive
  (`AudioRecord` + FFT; nur nach Laufzeitberechtigung, Foreground-Service-Typ `microphone`).
* Hardware-Modi des Geräts (`UpdateMode`) laufen ohne Netzwerklast auf dem Controller und sind der
  empfohlene Weg, wenn das Handy nicht dauerhaft streamen soll.

## 6. Präsentation

Jeder Screen ist ein zustandsloses `XyzScreen(state, actions)` plus eine dünne `XyzRoute`, die das
ViewModel anbindet. So lassen sich die Screens ohne Hilt in Screenshot-Tests rendern.
Profile und Effekte kommen als weitere Tabs hinzu, sobald sie umgesetzt sind.


* Compose + Material 3 mit Dynamic Color und Dark Mode, `NavigationSuiteScaffold`: Bottom-Bar am
  Telefon, Navigation-Rail auf Tablets, List-Detail-Layout für Geräte.
* Tabs: **Geräte, Profile, Effekte, Einstellungen**. Onboarding in drei Schritten:
  (1) Sicherheits- und Funktionshinweis, (2) SDK-Server am PC starten (Anleitung), (3) Scan oder manuelle Eingabe.
* Farbwähler: Farbrad + HSV-Slider + Hex-Eingabe + Favoriten, Live-Vorschau (gedrosselt auf die Ziel-FPS).
* Barrierefreiheit: `contentDescription`/`stateDescription` für TalkBack, Touch-Ziele ≥ 48 dp,
  Farbnamen zusätzlich zur Farbe, Kontrast nach WCAG AA, Haptik abschaltbar.
* Lokalisierung: `values/strings.xml` (Englisch) und `values-de/strings.xml` (Deutsch).

## 7. Komfortfunktionen (geplant)

* **Foreground-Service** (`EffectService`, Typ `connectedDevice`, bei Musik zusätzlich `microphone`)
  für laufende dynamische Effekte, mit Benachrichtigung inklusive Stopp-Aktion.
* **Widgets** (Jetpack Glance) für Lieblingsprofile; **Quick-Settings-Kacheln** (`TileService`), drei
  frei belegbare Slots.
* **Zeitpläne** über WorkManager. Die Ausführung ist unpräzise (±einige Minuten), dafür ist keine
  Berechtigung für exakte Alarme nötig.

## 8. Sicherheit

Der OpenRGB SDK Server hat **keine Authentifizierung und keine Verschlüsselung**. Jeder im selben
Netz kann alle Geräte steuern und den Datenverkehr mitlesen. Die App zeigt deshalb im Onboarding
und in den Einstellungen den Hinweis, den Server nur im vertrauenswürdigen LAN oder über ein VPN
(z. B. WireGuard/Tailscale) zu nutzen und Port 6742 niemals ins Internet freizugeben. Gespeichert
werden nur Host und Port, keine Geheimnisse. Cleartext-Traffic betrifft nur rohes TCP, kein HTTP;
eine Network-Security-Config ist daher nicht nötig.

## 9. Bekannte Einschränkungen

* **Latenz bei hohen FPS:** Jeder Frame ist ein TCP-Paket (16 B Header + 6 B + 4 B pro LED). 60 FPS
  bei 500 LEDs ergeben ≈ 120 KB/s, für WLAN unkritisch. Spürbar sind WLAN-Jitter (Powersave des
  Handys), die Verarbeitung im Server und die Update-Rate der Hardware selbst; viele Controller
  (z. B. über SMBus angebundenes RAM) schaffen deutlich unter 30 Updates/s. Die Conflation hält die
  Latenz begrenzt, dafür sinkt die effektive Framerate.
* **Protokoll < 6:** Es gibt keine ACKs, deshalb ist Backpressure nur über den TCP-Sendepuffer
  möglich. Änderungen anderer Clients sieht die App erst beim nächsten Neuladen.
* **OpenRGB 1.0 nach Rescan:** Der Server ignoriert `UpdateLEDs` von Index-Clients (v ≤ 5) kurzzeitig
  (1–3 s). Mit einem 1.0-Server spricht die App v6 und ist nicht betroffen.
* **Gerätetyp-Mehrdeutigkeit:** Protokoll 3 (0.7 vs. 0.8) und 5 (1.0rc1 vs. rc2) verwenden für
  „Unbekannt“ unterschiedliche Rohwerte. Aufgelöst wird zugunsten des neueren Releases, siehe `DeviceType`.
* **Server mit Protokoll 0** werden erst nach 2 s Timeout erkannt.
* **Netzwerk-Scan:** nur IPv4, maximal ein /22 (größere Netze: das /24 um die eigene Adresse).
  Firewalls, die Verbindungen stumm verwerfen, verlängern den Scan; nicht antwortende Adressen
  kosten jeweils 400 ms (64 parallel).
* **Hintergrund:** Ohne laufenden Effekt trennt die App 30 s nach dem Verlassen. Beim Zurückkehren
  dauert es einen Verbindungsaufbau (meist < 1 s), bis Geräte wieder erscheinen.
  VPN-Ziele müssen manuell eingetragen werden. mDNS bietet der OpenRGB-Server nicht an.
* **Farbsteuerung:** „Rückgängig“ stellt nur LED-Farben wieder her, nicht den vorherigen
  Hardware-Modus und nicht Geräte ohne Per-LED-Modus. Einzelne LEDs sind nur per Antippen
  erreichbar; TalkBack-Nutzer steuern Geräte und Zonen. Diagonale Modus-Richtungen (nur
  Protokoll 6) werden angezeigt, aber nicht zur Auswahl angeboten.
* **Mehrere Clients gleichzeitig** (z. B. OpenRGB-GUI-Effekte und die App): Es gilt
  „der letzte Schreiber gewinnt“. Laufende Effekte anderer Clients überschreiben die App.
