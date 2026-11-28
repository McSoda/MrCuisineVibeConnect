# MrCuisineVibeConnect (fork with a Home Assistant API)

🇫🇷 **[Version française](README.fr.md)**

This is a fork of **[Wuki60/MrCuisineVibeConnect](https://github.com/Wuki60/MrCuisineVibeConnect)**, the home of
**Toque**, a replacement interface for the Lidl **Monsieur Cuisine** kitchen robots.

**Everything we have in common is documented there:** what Toque does, the supported robots, the installation guides,
the community site, known problems and credits. Start with
[its README](https://github.com/Wuki60/MrCuisineVibeConnect#readme).

This page only describes the differences.

## Home Assistant integration

**[McSoda/toque-homeassistant](https://github.com/McSoda/toque-homeassistant)** reads this fork's API and shows the
robot in Home Assistant. Add it in HACS as a custom repository (category *Integration*). It does not work with the
upstream Toque, which has no such API.

## What differs

Based on Toque **0.12.0**. Our releases add a fourth number (**0.12.0.1** is the first one based on 0.12.0). Newer
upstream releases are not included until they are merged here.

| | This fork |
|---|---|
| **Home Assistant API** | A local, **read-only** HTTP/SSE API: robot state, guided recipe in progress, recipes and diagnostics. **Off by default**; turn it on in *Settings › Advanced › Home Assistant* (port 8765, access token). Nothing can be controlled remotely. Announced on the network as `_toque-ha._tcp` since 0.11.0.1 (before: `_toque._tcp`, which upstream 0.11.0 uses for Toque Compagnon). [Specification](docs/HOME_ASSISTANT_API.md) |
| **Cooking session** | The state of the guided recipe lives in `core` (`CookSession`): it survives a restart of the app and is published by the API. Toque Compagnon and its suspended session (`suspendedSession`, 0.12.0) are unchanged from upstream. |
| **Updates** | *Settings › Updates* reads **the releases of this repository**. |
| **APK** | `MCC-285-Toque-x.y.z.apk`, for the **Monsieur Cuisine Connect only**. We publish no Smart APK: Smart owners should use [upstream](https://github.com/Wuki60/MrCuisineVibeConnect/releases/latest). |
| **Tests** | A check of the Smart codec against the SDK's "manual" vectors, and the [real-robot test report for 0.10.0.1 and 0.9.0.1](docs/TESTS-0.9.0.1.md) (in French). We have tested on an SKMC 1200 only. |

## Installing this fork

Follow the [upstream installation guides](https://github.com/Wuki60/MrCuisineVibeConnect#readme), from **this**
repository, with these differences:

- **Windows, macOS and Linux**: the setup scripts of this repository (`tools\setup\toque-setup.ps1`,
  `tools/setup/toque-setup.sh`) download our APK from our
  [latest release](https://github.com/McSoda/MrCuisineVibeConnect/releases/latest). If the work folder already holds
  an upstream APK with a higher version number, the install step picks that one: delete it or pass our file.
- **Robot that already runs Toque**: from the repository folder,
  `powershell -ExecutionPolicy Bypass -File tools\install-robot.ps1 -Apk <folder>\MCC-285-Toque-x.y.z.apk`.
- [AGENTS.md](AGENTS.md) is the upstream file: where it names `Toque-x.y.z.apk` or the upstream releases, use ours.

## Questions and credits

Toque is the work of Wuki60 and its contributors ([MIT licence](LICENSE)). Independent project, not affiliated with
Lidl, SilverCrest or Hoyer; use at your own risk. For anything that is not specific to this fork, use the
[upstream issues](https://github.com/Wuki60/MrCuisineVibeConnect/issues).
