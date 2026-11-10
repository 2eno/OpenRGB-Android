# Architecture – OpenRGB Remote for Android

*English · [Deutsch](ARCHITECTURE.de.md)*

An unofficial Android client for the **OpenRGB SDK server** (TCP, default port 6742).
The app only controls RGB hardware on PCs in the network; there is no local server and no
local hardware control.

## 1. Key decisions

| Decision | Rationale |
|---|---|
| **Protocol as a plain Kotlin/JVM library** (`:openrgb-sdk`) | Testable on the JVM without Android dependencies (including real sockets), reusable, a clear boundary between bytes and app. |
| **Blocking `java.net.Socket` instead of an NIO selector/Ktor** | Exactly one connection per server, one reader, short writes. One IO thread per connection is simpler and just as fast. `TCP_NODELAY` avoids up to 200 ms of Nagle delay per frame. |
| **Clean architecture with three layers** | `domain` (plain Kotlin: entities, use cases, repository interfaces) ← `data` (Room, DataStore, SDK) ← `presentation` (Compose + ViewModels). Dependencies only point inwards. |
| **Feature packages instead of feature modules** | For a one-person project, 6 modules are a good compromise: build times stay short, and the boundaries are still checked by the compiler. |
| **Effects engine as plain Kotlin** (`:core:effects`) | Renderers are pure functions `(time, LED geometry, parameters) → colors`. That is deterministically testable and independent of Android. |
| **Hilt, coroutines/Flow, Room + DataStore** | As required. Room for structured data (servers, profiles, schedules), DataStore for settings. |

## 2. Module and project structure

```
OpenRGB-Android/
├── openrgb-sdk/                 ✅ Kotlin/JVM – protocol, client, reconnect, discovery
│   └── src/main/kotlin/dev/eno2/openrgbremote/sdk/
│       ├── protocol/            header, packet IDs, LE reader/writer, DeviceCodec, requests, responses
│       ├── model/               OpenRgbDevice, Zone, DeviceMode, Segment, Led, MatrixMap, RgbColor, DeviceType
│       ├── transport/           PacketTransport (interface), SocketTransport
│       ├── client/              OpenRgbClient, OpenRgbConnection (auto-reconnect), LedFrameStreamer, ConnectionError
│       └── discovery/           Ipv4Subnet, OpenRgbProbe, SubnetScanner
├── core/
│   ├── domain/                  ✅ Kotlin/JVM – server, status, DeviceKey, repository interfaces, use cases
│   ├── effects/                 ⏳ Kotlin/JVM – effect renderers, LED geometry, EffectRunner (clock)
│   ├── data/                    ✅ Android lib – Room, DataStore, ConnectionManager (multiple PCs), scan, Hilt
│   └── designsystem/            ✅ Android lib – Material 3 theme, device icons, LED rendering and color picker
├── app/                         ✅ Android app (devices, detail, color, modes, profiles, PCs, scan, settings, onboarding; rest ⏳)
│   └── src/main/kotlin/dev/eno2/openrgbremote/
│       ├── feature/onboarding/  three-step onboarding incl. “start the SDK server” guide
│       ├── feature/devices/     device overview, detail view (zones/LEDs), color control
│       ├── feature/profiles/    static and custom profiles, import/export
│       ├── feature/effects/     dynamic effects and hardware modes
│       ├── feature/settings/    servers, scan, schedules, security notice, about
│       ├── service/             EffectService (foreground), schedule worker
│       ├── widget/              Glance widgets
│       └── tile/                quick settings tiles
├── tools/capture_fixtures.py    captures real server packets as test fixtures
└── docs/ARCHITECTURE.md (+ .de.md)
```

✅ = implemented and tested, ⏳ = coming in the next steps.

Dependencies: `app → data, designsystem, domain, effects`; `data → domain, effects, openrgb-sdk`;
`effects → (nothing)`; `domain → (nothing)`. The UI only knows the SDK through domain models.

## 3. Protocol layer (`:openrgb-sdk`)

### Sources
All packet IDs, field orders and version boundaries come from `Documentation/OpenRGBSDK.md`
and `NetworkProtocol.h`. They were additionally checked against the serializers in the source
code (`RGBController.cpp`, `NetworkServer.cpp`, `NetworkClient.cpp`, `RGBController_Network.cpp`)
of **OpenRGB 1.1** and the server of **0.9**. The unit tests use **real packets from an
OpenRGB 1.0 server** for all protocol versions 0–6 (`tools/capture_fixtures.py`). Decoding and
re-encoding yields exactly the server bytes for every packet.

### Versions and fallback
| Version | Release | Relevant for the client |
|---|---|---|
| 0 | 0.3/0.4 | Does not answer `REQUEST_PROTOCOL_VERSION`; v0 is assumed after a timeout |
| 1 | 0.5 | Vendor string, `DEVICE_LIST_UPDATED` |
| 2 | 0.6 | Server profiles |
| 3 | 0.7/0.8 | Brightness in modes, `SaveMode` |
| 4 | 0.9 | Segments in zones |
| 5 | 1.0rc1/rc2 | Zone/controller flags, LED display names, rescan |
| 6 | 1.0/1.1 | Unique IDs, ACKs, `SIGNALUPDATE`, zone modes, server name/flags; `mode_value`/`led_value` dropped |

Sequence: `REQUEST_PROTOCOL_VERSION(6)`, then `min(server, client)`, `SET_CLIENT_NAME`, from v6
also `SET_CLIENT_HOSTNAME` and `SET_CLIENT_FLAGS`, then the device list is loaded. If a version
answer only arrives after the fallback to v0 (slow Wi-Fi/VPN), the client reconnects instead of
continuing on wrong assumptions.

### Device IDs and `DEVICE_LIST_UPDATED`
* **v0–5:** `pkt_dev_id` is the list index. After `DEVICE_LIST_UPDATED` the indices are invalid
  immediately: commands are dropped until the list has been reloaded completely.
* **v6:** unique IDs. Only the ID diff is reconciled; new devices are loaded, removed ones dropped,
  unchanged ones keep their state (kept current via `SIGNALUPDATE`).
* Bursts during hardware detection are debounced (default 250 ms).
* A heartbeat (`REQUEST_CONTROLLER_COUNT` every 15 s) detects hanging connections, for example
  when the PC goes to standby, and reconciles missed list changes.

### Concurrency
One reader coroutine dispatches all incoming packets. Request/reply exchanges (version, count,
controller data) are serialized through a mutex because the protocol has no request IDs. LED
frames and mode commands bypass this mutex, so an effect never waits behind a list refresh.

### Backpressure for effects (`LedFrameStreamer`)
* **Conflation:** there is a single-slot mailbox per device. If the server is slow, it gets fewer
  frames instead of a growing backlog. This keeps latency bounded.
* **ACK window (v6):** at most N `UpdateLEDs` per device are unacknowledged at the same time
  (default 2). If an ACK is lost, a timeout resets the window.
* **v0–5:** without ACKs, the blocking socket write is the only feedback.
* Colors are always truncated or padded to exactly the LED count. Servers before 1.0 don’t check
  this; more colors than LEDs could cause a buffer overflow there.

### Error classes → understandable messages
`ConnectionErrorReason` distinguishes: unknown host, connection refused (server not started/wrong
port), timeout (PC off/wrong IP/firewall), unreachable (other network/VLAN), not an OpenRGB
server, protocol error and connection lost. The app maps every class to a localized text with a
concrete suggestion.

## 4. Data layer

* **ConnectionManager** (singleton, Hilt, plain Kotlin and tested on the JVM against the fake
  server) manages one `OpenRgbConnection` per PC that should currently be connected. A PC is
  connected while the app is *active* and it has “connect automatically” set or was connected
  manually. A single collector recomputes this on every change of the server list, the activity or
  the manual overrides, so no locks between UI and lifecycle are needed.
* **Active** means: app in the foreground (`ProcessLifecycleOwner`) or a keep-alive handle is held
  (from step 2e by the effect service). After 30 s in the background, connections are closed, which
  saves battery and server resources.
* When the network comes back (`ConnectivityManager` default network callback), the backoff is
  skipped. Without a network, every error is shown as “No network”.
* **Scan:** the IPv4 address and prefix come from the `LinkProperties` of the Wi-Fi/Ethernet
  network, or of the underlying network while a VPN is active.
* **LightingController:** colors always go out as a complete device frame through the
  `LedFrameStreamer` (one streamer per connection). Zone and single-LED changes are merged into
  the device’s current colors. That gives a single code path with conflation, and dragging in the
  color picker can send as often as it likes. Before the first frame the device is switched to its
  per-LED mode (“Direct”), because hardware effects ignore LED updates.
* Devices **without a per-LED mode** get the color through the color list of their active mode.
  That is a mode change that can trigger flash writes, so it never happens in the live preview but
  only on release.
* **Profiles:** a static profile is palette + distribution (gradient, blocks, alternating) +
  brightness. `ProfileRenderer` (plain Kotlin) produces the LED colors from it; the same function
  draws the preview in the app. Every zone shows the whole profile; matrix zones are colored column
  by column so gradients run left to right on keyboards. Devices without a per-LED mode get the
  first color. Preset profiles live in code (negative IDs, localized names), custom ones in Room
  (`profiles`, schema 3).
* **JSON exchange format** (`JsonProfileCodec`): `format`, `schemaVersion`, `profiles[]` with
  `name`, `type`, `layout`, `brightness`, `colors` as hex. Unknown fields are ignored, a higher
  `schemaVersion` is rejected, entries with an unknown `type` (later: dynamic profiles) are skipped.
  Import and export use the system file picker; the app needs no storage permission for this.
* **Favorites** are stored in Room (`favorite_colors`, schema 2 with auto-migration).
* **Stable device identity:** unique IDs (v6) are only valid for one server session, indices
  (v0–5) shift. Profiles therefore reference devices by `serverId + name + location + serial` and
  are resolved again on every connection.
* **Room:** `servers` ✅ (schema exported to `core/data/schemas`); to follow: `profiles`
  (header data + JSON payload, versioned), `favorite_colors`, `schedules`.
* **DataStore:** onboarding state ✅; to follow: target FPS, haptics, last active profile.
* **Profile format (JSON, kotlinx.serialization):** `type = static | dynamic | hardware`, target
  `global | devices[]`, parameters. The same format is used for import and export, including
  `schemaVersion`.

## 5. Effects engine (planned, `:core:effects`)

* `Effect.render(t: Double, layout: LedLayout, params: EffectParams, out: IntArray)` as a pure
  function.
* `LedLayout`: normalized LED positions (linear: 0..1, matrix: x/y from the matrix map), so that
  wave, aurora and fire run two-dimensionally on keyboards.
* `EffectRunner`: `ticker` with a target FPS (30/45/60), frame budget measurement, adaptive FPS
  reduction under load, output through `LedFrameStreamer` per device.
* Effects: rainbow wave, color cycle, breathing, fire, aurora, pulse, gradient flow, music-reactive
  (`AudioRecord` + FFT; only after the runtime permission, foreground service type `microphone`).
* The device’s hardware modes (`UpdateMode`) run on the controller without network load and are
  the recommended way if the phone shouldn’t stream permanently.

## 6. Presentation

Every screen is a stateless `XyzScreen(state, actions)` plus a thin `XyzRoute` that binds the
ViewModel. That way screens can be rendered in screenshot tests without Hilt. Profiles and effects
are added as further tabs once they are implemented.

* Compose + Material 3 with dynamic color and dark mode, `NavigationSuiteScaffold`: bottom bar on
  phones, navigation rail on tablets, list-detail layout for devices.
* Tabs: **Devices, Profiles, Effects, Settings**. Onboarding in three steps:
  (1) security and feature notice, (2) start the SDK server on the PC (guide), (3) scan or manual
  entry.
* Color picker: color wheel + HSV sliders + hex input + favorites, live preview (throttled to the
  target FPS).
* Accessibility: `contentDescription`/`stateDescription` for TalkBack, touch targets ≥ 48 dp,
  color names in addition to the color, WCAG AA contrast, haptics can be turned off.
* Localization: `values/strings.xml` (English) and `values-de/strings.xml` (German).

## 7. Convenience features (planned)

* **Foreground service** (`EffectService`, type `connectedDevice`, additionally `microphone` for
  music) for running dynamic effects, with a notification including a stop action.
* **Widgets** (Jetpack Glance) for favorite profiles; **quick settings tiles** (`TileService`),
  three freely assignable slots.
* **Schedules** via WorkManager. Execution is imprecise (± a few minutes), but no exact-alarm
  permission is needed.

## 8. Security

The OpenRGB SDK server has **no authentication and no encryption**. Anyone on the same network
can control all devices and read the traffic. The app therefore shows a notice in onboarding and
in the settings to only use the server in a trusted LAN or over a VPN (e.g. WireGuard/Tailscale)
and never to expose port 6742 to the internet. Only host and port are stored, no secrets.
Cleartext traffic only concerns raw TCP, not HTTP; a network security config is therefore not
needed.

## 9. Known limitations

* **Latency at high FPS:** every frame is a TCP packet (16 B header + 6 B + 4 B per LED). 60 FPS
  with 500 LEDs comes to ≈ 120 KB/s, uncritical for Wi-Fi. Noticeable are Wi-Fi jitter (the phone’s
  power saving), processing in the server and the update rate of the hardware itself; many
  controllers (e.g. RAM attached via SMBus) manage well under 30 updates/s. Conflation keeps
  latency bounded, at the cost of a lower effective frame rate.
* **Protocol < 6:** there are no ACKs, so backpressure is only possible through the TCP send
  buffer. The app only sees changes by other clients on the next reload.
* **OpenRGB 1.0 after a rescan:** the server briefly (1–3 s) ignores `UpdateLEDs` from index
  clients (v ≤ 5). With a 1.0 server the app speaks v6 and is not affected.
* **Device type ambiguity:** protocol 3 (0.7 vs. 0.8) and 5 (1.0rc1 vs. rc2) use different raw
  values for “unknown”. It is resolved in favor of the newer release, see `DeviceType`.
* **Servers with protocol 0** are only detected after a 2 s timeout.
* **Network scan:** IPv4 only, at most a /22 (larger networks: the /24 around your own address).
  Firewalls that silently drop connections make the scan slower; unresponsive addresses cost
  400 ms each (64 in parallel).
* **Background:** without a running effect, the app disconnects 30 s after you leave it. When you
  return, it takes one connection setup (usually < 1 s) until devices appear again.
  VPN targets have to be entered manually. The OpenRGB server does not offer mDNS.
* **Color control:** “Undo” only restores LED colors, not the previous hardware mode and not
  devices without a per-LED mode. Single LEDs can only be reached by tapping; TalkBack users
  control devices and zones. Diagonal mode directions (protocol 6 only) are shown but not offered
  for selection.
* **Multiple clients at the same time** (e.g. OpenRGB GUI effects and the app): “last writer
  wins”. Running effects of other clients overwrite the app.
