# Architektur – OpenRGB Remote für Android

*[English](ARCHITECTURE.md) · Deutsch*

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
│   ├── effects/                 ✅ Kotlin/JVM – Effekt-Renderer, LED-Geometrie, Audio-Analyse (FFT), EffectLoop
│   ├── data/                    ✅ Android-Lib – Room, DataStore, ConnectionManager (mehrere PCs), Scan, Hilt
│   └── designsystem/            ✅ Android-Lib – Material-3-Theme, Gerätesymbole, LED-Darstellung und Farbwähler
├── app/                         ✅ Android-App (Geräte, Detail, Farbe, Modi, Profile, Effekte, Service, PCs, Scan, Einstellungen, Onboarding, Widget, Kacheln, Zeitpläne)
│   └── src/main/kotlin/dev/eno2/openrgbremote/
│       ├── feature/onboarding/  Drei-Schritte-Onboarding inkl. Anleitung „SDK-Server starten“
│       ├── feature/devices/     Geräteübersicht, Detailansicht (Zonen/LEDs), Farbsteuerung
│       ├── feature/profiles/    Statische und eigene Profile, Import/Export
│       ├── feature/effects/     Dynamische Effekte und Hardware-Modi
│       ├── feature/settings/    Server, Scan, Bildrate, Sicherheitshinweis, Info
│       ├── feature/quick/       Favoriten-Editor, Zeitplan-Liste und -Editor
│       ├── service/             EffectService (Foreground), EffectLauncher
│       └── quick/               QuickActionRunner, Glance-Widget, Quick-Settings-Kacheln, Zeitplan-Alarme
├── tools/capture_fixtures.py    Schneidet echte Serverpakete als Test-Fixtures mit
└── docs/ARCHITECTURE.md (+ .de.md)
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
  Namen lokalisiert), eigene in Room (`profiles`).
* **Dynamische Profile** tragen zusätzlich eine `ProfileAnimation` (Effekt, Tempo, Richtung). Beim
  Anwenden startet der Effekt über den `EffectLauncher` (damit startet auch der Foreground-Service)
  mit Palette und Helligkeit des Profils; Geräte ohne Per-LED-Modus bekommen die erste Farbe. Ein
  laufender Effekt wird nahtlos übernommen, ein statisches Profil stoppt ihn vorher. Vorschauen
  animieren mit dem Effekt-Renderer; Standbilder (Widget, Auswahllisten) zeigen ein Bild bzw. bei
  zeitbasierten Effekten die Palette. Den Musik-Effekt gibt es nicht als Profil: Widget, Kacheln
  und Zeitpläne können nicht nach der Mikrofon-Berechtigung fragen. „Als Profil speichern“ in den
  Effekt-Einstellungen legt eines aus den aktuellen Einstellungen an.
* **Profil löschen** (`DeleteProfile`) leert auch Favoriten-Slots und entfernt Zeitpläne, die es
  verwenden, damit nichts auf ein fehlendes Profil zeigt.
* **JSON-Austauschformat** (`JsonProfileCodec`): `format`, `schemaVersion`, `profiles[]` mit
  `name`, `type` (`static` oder `dynamic`), `layout`, `brightness`, `colors` als Hex und bei
  dynamischen Profilen `effect` (Effekt-ID klein geschrieben), `speed`, `reverse`. Unbekannte Felder
  werden ignoriert, eine höhere `schemaVersion` wird abgelehnt, Einträge mit unbekanntem `type`
  oder `effect` werden übersprungen. Schon die erste App-Version übersprang unbekannte Typen,
  deshalb brauchten dynamische Profile keine neue Schema-Version. Import und Export laufen über den
  System-Dateidialog, die App braucht dafür keine Speicherberechtigung.
* **Favoriten** liegen in Room (`favorite_colors`, Schema 2 mit Auto-Migration).
* **Stabile Geräte-Identität:** Unique-IDs (v6) gelten nur für eine Serversitzung, Indizes (v0–5)
  verschieben sich. Profile referenzieren Geräte daher über `serverId + name + location + serial`
  und werden bei jeder Verbindung neu aufgelöst.
* **Room** (Schema 5, exportiert nach `core/data/schemas`, Auto-Migrationen 1→…→5): `servers`,
  `favorite_colors`, `profiles` (Schema 5 ergänzt `effect`, `effect_speed`, `effect_reverse`; ein der
  App unbekannter Effekt macht das Profil statisch), `schedules` (Minute des Tages,
  Wochentags-Bitmaske, Aktion als Text wie `profile:-1` oder `off`; unlesbare Zeilen werden
  übersprungen statt abzustürzen). `DatabaseMigrationTest` aktualisiert echte Datenbanken jedes
  früheren Schemas und prüft die Daten.
* **DataStore:** Onboarding-Status, Sicherheitshinweis, Ziel-FPS, die vier Favoriten-Slots und die
  letzten Parameter jedes Effekts (geschrieben eine Sekunde nach der letzten Änderung, ein
  Schieberegler-Zug ist also ein Schreibvorgang).

## 5. Effekt-Engine (`:core:effects`)

* **Renderer** (`EffectRenderer`): reine Funktion `(Effekt, Effektzeit, LedLayout, Parameter,
  AudioLevels) → Farben`. Gleiche Eingaben ergeben immer dieselben Farben; die animierte Vorschau in
  der App nutzt denselben Code wie die Geräte. Rauschen ist deterministisch (Value-Noise), daher
  brauchen Feuer und Polarlicht keinen Zustand.
* **Effektzeit** statt Uhrzeit: Die Schleife addiert `Δt × Geschwindigkeit`. Ändert man die
  Geschwindigkeit im laufenden Effekt, läuft die Animation flüssig weiter statt zu springen.
* **`LedLayout`**: normalisierte Position jeder LED. Jede Zone für sich 0..1 (wie bei Profilen),
  Matrix-Zonen mit x/y aus der Matrix-Map. Feuer brennt auf Tastaturen von unten, der Musik-Effekt
  wird dort zum Balkendiagramm.
* **Effekte:** Regenbogenwelle, Farbwechsel, Atmen, Feuer, Polarlicht, Puls, Farbverlauf und Musik.
  Einstellbar sind Geschwindigkeit (0,25–3×), Richtung (wo sichtbar), Farben (bis 8) und Helligkeit.
* **`EffectLoop`**: Frame-Takt mit Ziel-FPS (30/45/60, Einstellung). Fällt das Rendern zurück, werden
  Takte übersprungen statt nachgeholt. Backpressure übernimmt der konfluierende `LedFrameStreamer`
  pro Gerät (mit ACK-Fenster ab Protokoll 6). Gemessen werden tatsächliche FPS und Renderzeit.
* **`AudioAnalyzer`**: Hann-Fenster, FFT (1024, 50 % Überlappung), 16 logarithmische Bänder von
  40 Hz bis 12 kHz, auf 0 dBFS normiert, automatische Verstärkung mit Untergrenze, Noise-Gate bei
  −62 dBFS sowie Attack/Release-Glättung.

### Ausführung (`EffectEngine`, `EffectService`)

* `EffectEngine` (Datenschicht) verbindet die Schleife mit allen verbundenen Geräten mit
  Per-LED-Modus. Ziele werden nur neu aufgebaut, wenn sich die **Struktur** ändert (Gerät
  kommt/geht, Zone ändert ihre Größe), nicht bei jedem zurückgemeldeten Frame.
* Ein neuer Start im laufenden Effekt übernimmt Effekt, Parameter und Ziele ohne Neustart.
* Solange ein Effekt läuft, hält die Engine ein **Keep-Alive** am `ConnectionManager`: Die
  Verbindungen bleiben auch im Hintergrund offen.
* Das Mikrofon wird nur aufgenommen, solange der Musik-Effekt läuft. Die Audiodaten verlassen das
  Gerät nie; der Effekt bekommt nur 16 Pegelwerte.
* **Manuelle Steuerung beendet den Effekt:** Farbe setzen, Profil anwenden oder einen Hardware-Modus
  wählen stoppt ihn. Sonst würde der nächste Frame die Änderung sofort überschreiben.
* `EffectService` (Foreground-Service, Typ `connectedDevice`, mit Mikrofon zusätzlich `microphone`)
  hält den Prozess am Leben und zeigt die Benachrichtigung mit „Stoppen“. Er folgt nur dem Zustand
  der Engine und beendet sich, sobald der Effekt endet.
* **Hardware-Effekte** der Geräte (`UpdateMode`) laufen ohne Handy und ohne Netzwerklast auf dem
  Controller. Der Effekte-Tab verweist darauf.

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
  Farbnamen zusätzlich zur Farbe, Kontrast nach WCAG AA, Haptik folgt der System-Einstellung für Berührungsfeedback. Die
  Screenshot-Tests rendern zusätzlich einen Screen mit Schriftgröße 2,0, um abgeschnittenen oder
  überlappenden Text zu finden.
* Lokalisierung: `values/strings.xml` (Englisch) und `values-de/strings.xml` (Deutsch).

## 7. Komfortfunktionen

* **Favoriten (Schnellaktionen):** vier Slots (Einstellungen → Favoriten), jeweils ein Profil, ein
  Effekt oder „Alles aus“. Gespeichert als Text (`profile:<id>`, `effect:<ID>`, `off`) im DataStore.
* **`QuickActionRunner`** führt eine Aktion auch bei geschlossener App aus: Er holt sich ein
  `ConnectionKeepAlive` (der `ConnectionManager` verbindet dann wie im Vordergrund), wartet bis zu
  1,5 s auf den Verbindungsstart und bis zu 7 s, bis kein PC mehr verbindet, führt die Aktion aus und
  hält die Verbindung 1 s länger offen, damit der letzte Frame ankommt (das Keep-Alive wird auch
  bei Abbruch freigegeben). Bei Kacheln und Widget stoppt ein Tipp auf einen laufenden Effekt oder
  ein laufendes dynamisches Profil ihn wieder.
* **Prozessstart im Hintergrund:** Android startet den Prozess auch für einen Widget-Tipp, eine
  Kachel, einen Alarm oder nach dem Neustart, ganz ohne Activity. Der `ConnectionManager` startet
  deshalb im Zustand „Hintergrund“ und verbindet nur für ein Keep-Alive oder sobald die App sichtbar
  wird; sonst würde ein solcher Prozess Verbindungen öffnen, die nie geschlossen werden.
* **Widget** (Jetpack Glance, 2×2 Buttons mit Farbstreifen, laufender Effekt hervorgehoben). Der
  Tipp läuft in einem `ActionCallback`; aktualisiert wird bei jeder Änderung von Slots, Profilen
  oder Effektstatus.
* **Quick-Settings-Kacheln:** drei `TileService`s für Slot 1–3, mit dem Aktionsnamen als Label und
  aktivem Zustand, solange ihr Effekt läuft. Eine leere Kachel öffnet den Favoriten-Editor.
* **Start im Vordergrund:** Ab Android 12 darf die App den Effekt-Service aus dem Hintergrund nur in
  Ausnahmefällen starten. Ein Kachel-Klick zählt dazu; verweigert das System trotzdem
  (`ForegroundServiceStartNotAllowedException`), öffnet sich die App und startet den Effekt dort.
* **Zeitpläne** (Einstellungen → Zeitpläne): Uhrzeit, Wochentage und Aktion (Profil oder „Alles
  aus“). Sie nutzen `AlarmManager.setWindow(RTC_WAKEUP)` mit 10-Minuten-Fenster: keine Berechtigung
  für exakte Alarme nötig, und WorkManager würde die Uhrzeit gar nicht garantieren. Ein
  `BroadcastReceiver` (`goAsync`) führt die Aktion aus und plant das nächste Vorkommen; nach
  Neustart, Uhrzeit- oder Zeitzonenwechsel und App-Update werden alle Alarme neu aufgebaut. Der
  nächste Auslösezeitpunkt wird mit `ZonedDateTime` berechnet, Sommerzeit-Lücken verschieben die
  Zeit auf die nächste gültige Minute.
* **Effekt-Service** (`EffectService`, Typ `connectedDevice`, bei Musik zusätzlich `microphone`)
  für laufende dynamische Effekte, mit Benachrichtigung inklusive Stopp-Aktion.

## 8. Sicherheit

Der OpenRGB SDK Server hat **keine Authentifizierung und keine Verschlüsselung**. Jeder im selben
Netz kann alle Geräte steuern und den Datenverkehr mitlesen. Die App zeigt deshalb im Onboarding
und in den Einstellungen den Hinweis, den Server nur im vertrauenswürdigen LAN, über ein VPN
(z. B. WireGuard/Tailscale) oder über einen SSH-Jump-Host zu nutzen und Port 6742 niemals ins
Internet freizugeben. Cleartext-Traffic betrifft nur rohes TCP, kein HTTP; eine
Network-Security-Config ist daher nicht nötig.

### SSH-Jump-Host

Wie `ssh -J` (oder die SSH-Verbindungen von virt-manager) meldet sich die App an einem SSH-Server
an und öffnet von dort einen `direct-tcpip`-Kanal zu OpenRGB (`SshTunnelTransportFactory`, JSch).
Das OpenRGB-Protokoll läuft dann verschlüsselt und erst nach einer Anmeldung, auch von außerhalb
des Heimnetzes, und OpenRGB kann an 127.0.0.1 gebunden bleiben (Standard ab 1.0). Auf dem Handy
wird kein lokaler Port geöffnet.

* **Anmeldung:** ein von der App erzeugter Ed25519-Schlüssel (empfohlen) oder ein Passwort. Das
  Formular bietet einen fertigen Befehl für den Jump-Host, der den öffentlichen Schlüssel mit
  `restrict,port-forwarding,permitopen="<OpenRGB-Adresse>",command="/bin/false"` einträgt: Der
  Schlüssel öffnet nur den Tunnel zu OpenRGB, keine Shell und keine anderen Ziele (gegen OpenSSH 9
  geprüft).
* **Geheimnisse:** privater Schlüssel und Passwörter werden mit AES-256-GCM und einem Schlüssel im
  Android Keystore verschlüsselt, in eigenen SharedPreferences abgelegt und vom Backup ausgenommen.
* **Host-Key:** Trust on first use. „Verbindung testen“ (und „Speichern“) zeigt den
  SHA256-Fingerabdruck im Format von `ssh-keygen -lf`; erst nach der Bestätigung wird der Schlüssel
  festgehalten und eine Anmeldung versucht. Ein später abweichender Schlüssel ist ein harter Fehler
  (`JUMP_HOST_KEY_CHANGED`) mit Warnung.
* **Keine Wiederholungsstürme:** Abgelehnte Anmeldung sowie unbestätigter oder geänderter Host-Key
  sind dauerhafte Fehler: Die Verbindung stoppt, statt es erneut zu versuchen, damit fail2ban das
  Handy nicht sperrt.
* **Algorithmen:** JSch nutzt auf jeder Plattform BouncyCastle für Ed25519 und Curve25519 (der JCE
  von Android fehlen sie), damit die JVM-Tests den Codepfad des Handys prüfen. Die Tests laufen
  gegen Apache MINA SSHD (Passwort, App-Schlüssel, Pinning, Fehlerklassen, Ed25519-Host-Key mit
  Curve25519-Schlüsseltausch) und optional gegen ein echtes OpenSSH mit OpenRGB dahinter.
* **Grenzen:** kein ssh-agent, kein Import fremder Schlüssel, keine mehrstufigen Ketten und keine
  Einmalcode-Abfragen (2FA); der Netzwerk-Scan findet nur Server im lokalen Netz.

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
* **Musik-Effekt:** Android erlaubt nur das Mikrofon, nicht das Mithören anderer Apps (das ginge
  nur mit Bildschirmaufnahme-Zustimmung). Der Effekt reagiert daher auf den Raumklang; die
  Latenz liegt bei etwa 30–60 ms (Analysefenster) plus Netzwerk.
* **Effekte im Hintergrund:** Hersteller mit aggressivem Energiesparen (z. B. einige
  Xiaomi-/Huawei-Geräte) können auch Foreground-Services drosseln. Dann muss die App in den
  Akku-Einstellungen von der Optimierung ausgenommen werden.
* **Effekte in Profilen:** Die Geräteauswahl eines Effekts gehört nicht zum Profil; „Anwenden auf…“
  wählt die Geräte beim Anwenden. Musik-Profile gibt es nicht (Mikrofon-Berechtigung, siehe oben).
* **Zeitpläne sind nicht exakt:** Android kann sie zum Stromsparen (Doze) um bis zu 10 Minuten
  verzögern. Effekte und dynamische Profile können sie nicht starten: Aus einem Alarm im
  Hintergrund darf kein Foreground-Service starten, und ein Effekt hielte das Handy die ganze
  Nacht wach. Stattdessen „Alles aus“ oder ein statisches Profil verwenden.
* **Widget und Kacheln** brauchen einen PC, der in etwa 8 s erreichbar ist; sonst melden sie das per
  kurzem Toast (wie auch ein gelöschtes Profil). Manche Launcher aktualisieren das Widget erst mit kurzer Verzögerung.
* **Mehrere Clients gleichzeitig** (z. B. OpenRGB-GUI-Effekte und die App): Es gilt
  „der letzte Schreiber gewinnt“. Laufende Effekte anderer Clients überschreiben die App.

## 10. Tests und CI

* **Unit-Tests** (`./gradlew unitTests`, rund 300): Protokoll-Codec gegen Pakete, die von einem
  echten OpenRGB-1.0-Server für jede Protokollversion 0–6 mitgeschnitten wurden, inklusive Fuzzing
  (jede Kürzung und tausende veränderte Pakete dürfen nur mit `ProtocolException` scheitern);
  Client, Reconnect und Backpressure gegen einen Fake-Server im Prozess; Effekte; Use-Cases;
  ConnectionManager und LED-Streaming über echte Sockets; Room-Migrationen von jedem früheren
  Schema; ViewModels; Robolectric-Tests für das Glance-Widget und die Zeitplan-Alarme;
  Screenshot-Tests für jeden Screen (Handy, Tablet, Dark Mode, 200 % Schriftgröße).
* **Gegen einen echten Server** (`OPENRGB_TEST_SERVER=host:port`, sonst übersprungen): der
  SDK-Sitzungstest und `RealServerEndToEndTest`, der die Datenschicht der App (ConnectionManager,
  Profile, Effekt-Engine) laufen lässt und die Farben mit einem zweiten Client zurückliest.
* **CI** (`.github/workflows/build.yml`): Unit-Tests, Lint (Warnungen sind Fehler), Debug- und
  Release-APK als Artefakte bei jedem Push und Pull Request; ein Tag `v*` erzeugt ein GitHub-Release
  mit beiden APKs. Das Release wird mit dem Schlüssel aus den Repository-Secrets signiert
  (`RELEASE_KEYSTORE_BASE64`, `RELEASE_KEYSTORE_PASSWORD`, `RELEASE_KEY_ALIAS`,
  `RELEASE_KEY_PASSWORD`); ohne sie mit dem Debug-Schlüssel des Runners, das reicht zum Testen,
  ändert sich aber mit jedem Build. Debug-Builds haben die Application-ID
  `dev.eno2.openrgbremote.debug` und den Namen „OpenRGB Debug“ und lassen sich neben dem Release
  installieren.
