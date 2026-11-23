# MQTT / Home Assistant (Toque)

Read-only telemetry for Home Assistant (or any MQTT broker). **No commands** are accepted over MQTT.

## Behaviour

- Settings › **MQTT**: enable, host, port, user, password, TLS, topic prefix.
- Default prefix: `toque/<ANDROID_ID>/`.
- Topics (retain):
  - `…/status` — `online` / `offline` (LWT = `offline`)
  - `…/state` — JSON snapshot (see below)
- Publishes **only when the useful state changes** (no weight, no raw RPM). The countdown (`remainingSec`) is published every 5 s, or at once when it jumps, reaches `0` or becomes unknown; any other change is published immediately.
- On connect: publishes **Home Assistant MQTT Discovery** configs under `homeassistant/…/config` (retain), then entities appear under device **Toque** without YAML.
- Discovery **entity names** follow Toque’s UI language (`tr(…)` → `en` / `es` / `de` / `ca` / …). `unique_id`s stay stable (English). Changing the UI language republishes Discovery.

## State JSON fields

Focused on the running program and its end (no lid/bowl/motor/heater/link).

| Field | Meaning |
|---|---|
| `jobActive` / `jobKind` / `jobLabel` / `jobState` | Program |
| `remainingSec` | Seconds left (`0` when done) |
| `endReason` | Why it ended (empty while running) |
| `actualTempC` / `targetTempC` | Measured temperature (the probe returns nothing below 37 °C) / temperature **Toque asks for** (`0` outside a job or without heating; not the base's echo, which keeps its last target and differs from the request, e.g. 45 → 51) |
| `speedLevel` | Speed level the base says it received, 0–10 (the measured speed stays at 0 while running on the SKMC 1200) |
| `faultCode` / `faultMessage` | Alerts |

`jobState` values include `RUNNING`, `PAUSED`, `FINISHED`, `ABORTED`, … HA automations typically watch `jobState` → `FINISHED` or `cooking` → off.

## Files (MQTT / HA)

| Path | Role |
|---|---|
| `app/src/main/kotlin/app/toque/mqtt/MqttClient.kt` | Minimal MQTT 3.1.1 client (publish only) |
| `core/src/main/kotlin/app/toque/core/api/MqttState.kt` | State → JSON, and when to publish (tested in `core`) |
| `app/src/main/kotlin/app/toque/mqtt/MqttPublisher.kt` | On-change publish, LWT, reconnect |
| `app/src/main/kotlin/app/toque/mqtt/MqttDiscovery.kt` | HA MQTT Discovery payloads |
| `app/src/main/kotlin/app/toque/Prefs.kt` | `mqttEnabled`, host, port, user, password, TLS, prefix, deviceId |
| `app/src/main/kotlin/app/toque/ToqueApp.kt` | Starts publisher on main process |
| `app/src/main/kotlin/app/toque/ui/screens/SettingsScreen.kt` | MQTT settings UI |
| `app/src/main/assets/lang/{en,es,de}.json` | MQTT / Discovery strings |
