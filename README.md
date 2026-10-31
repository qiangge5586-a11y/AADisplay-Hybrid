# AADisplay Hybrid

Hybrid integration workspace for the pinned AADisplay Android Auto / CarPlay source.

## Current implementation

- Custom Android Auto virtual render resolution (width × height), with 0 × 0 meaning automatic head-unit-negotiated size.
- Resolution settings UI, Binder API, persistence, live apply, and reconnect re-apply.
- Head-unit negotiated profile remains separate from the render-buffer override.
- Split virtual-display resize uses rollback protection: cache dimensions are committed only after both pane resizes succeed.
- LSPosed remains required for the upstream system_server / Android Auto hooks. This project does not include Shizuku.

## Build verification

The GitHub Actions integration workflow overlays the hybrid resolution store, Binder, service, split lifecycle, and settings UI onto the pinned upstream source, then builds debug and minified release APK candidates.

Artifacts are build outputs, not proof of physical-device compatibility.

## Important compatibility and safety notes

- Do not use `wm size` or `wm density` as a substitute for virtual-display configuration; those commands alter the phone's physical display configuration.
- The negotiated HU profile is retained for host geometry and touch mapping. The configured render resolution is applied separately.
- The two split panes must retain their ratio/orientation/fullscreen-specific buffer geometry.
- Preserve upstream GPL-3.0 license and notices in all redistributed builds.

## Target validation

- Phone: OnePlus Ace 5 / ColorOS 16
- Head unit: 2019 Honda Civic factory head unit
- Link: factory USB wired Android Auto

## Remaining before a release APK

- Complete integration tests for resolution changes while connected, DPI changes, reconnects, and resize rollback.
- Run physical-device validation for display geometry, touch mapping, stability, and frame pacing.
- Build and inspect a minified release APK; publish only after validation.

## License

See the upstream GPL-3.0 license and preserve all source notices.
