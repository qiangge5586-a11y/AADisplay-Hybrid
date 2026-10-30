# AADisplay Hybrid

Hybrid development workspace for AADisplay / Android Auto projection.

## Goals

- Shizuku-first permission assistance, with graceful fallback when Shizuku is unavailable.
- Optional LSPosed integration for capabilities that require in-process/system-server hooks.
- User-configurable virtual display width, height, and density (DPI).
- Keep the head-unit negotiated display profile separate from the effective render profile.
- Correct pane sizing, surface buffers, and touch-coordinate transforms after resolution changes.
- Improve stability and frame pacing without changing the phone's physical display resolution.

## Important compatibility note

Shizuku does not replace LSPosed hooks. A non-root Shizuku session can perform only operations allowed to its shell identity; it cannot independently inject hooks into Android Auto or `system_server`. Full parity with the original rooted/LSPosed behavior is therefore not promised in a Shizuku-only mode.

## Upstream

- Project: https://github.com/lagXkjy/AADisplay
- Branch: `AADisplay-Split-Lite-Carplay`
- Pinned upstream commit: `494dcc4909e8765e8798be4e7b8031fefd90a4eb`

## Current status

This repository is the hybrid integration workspace. The resolution model, Shizuku access policy, and resolution override coordinator have been prototyped and tested as pure Kotlin components. They still need to be integrated into the Android modules and validated on a physical phone/head unit before an APK can be called release-ready.

## Build

Once the upstream Android project and integration patches are present:

```bash
./gradlew :aa-display:assembleDebug
```

Requires JDK 17 and Android SDK 36.

## Device validation checklist

- [ ] Shizuku binder connected / disconnected / permission denied states
- [ ] Auto mode follows the head-unit negotiated profile
- [ ] Custom width, height, and DPI persist across process restart
- [ ] Resolution changes resize both virtual displays and their backing surfaces
- [ ] Touch and pointer coordinates remain correct in both panes
- [ ] Reconnect restores the selected effective profile
- [ ] No `wm size` / `wm density` mutation of the phone's physical display
- [ ] Validate on ColorOS 16 and a wired Android Auto head unit

## License

See the upstream project's license and preserve its notices when integrating upstream source.
