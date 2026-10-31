# AADisplay Hybrid

Hybrid integration workspace for the pinned AADisplay Android Auto / CarPlay source.

## Current implementation

- Custom Android Auto virtual render resolution (width × height), with 0 × 0 meaning automatic head-unit-negotiated size.
- Resolution settings UI, Binder API, persistence, live apply, and reconnect re-apply.
- Head-unit negotiated profile remains separate from the render-buffer override.
- Split virtual-display resize uses rollback protection: cache dimensions are committed only after both pane resizes succeed.
- Shizuku access-state policy/controller scaffolding is present; it is not yet wired into the Android app UI or runtime service operations.
- LSPosed remains required for the upstream system_server / Android Auto hooks. Shizuku shell permission does not replace those hooks.

## Build verification

The GitHub Actions integration compile check overlays the hybrid resolution store, Binder, service, split lifecycle, and settings UI onto the pinned upstream source and builds `:aa-display:assembleDebug`.

Latest successful integration compile check:
https://github.com/qiangge5586-a11y/AADisplay-Hybrid/actions/runs/41111124327

The generated artifact is named `aadisplay-upstream-baseline-NOT-OPTIMIZED`. It is a compile/build-chain verification APK, not a release APK and not a claim of full Shizuku-only operation.

## Important compatibility and safety notes

- Shizuku can execute only operations allowed to the granted Shizuku identity (commonly shell UID 2000); it cannot independently inject hooks into `system_server` or Android Auto.
- Do not use `wm size` or `wm density` as a substitute for virtual-display configuration; those commands alter the phone's physical display configuration.
- The negotiated HU profile is retained for host geometry and touch mapping. The configured render resolution is applied separately.
- The two split panes must retain their ratio/orientation/fullscreen-specific buffer geometry.
- Preserve upstream GPL-3.0 license and notices in all redistributed builds.

## Target validation

- Phone: OnePlus Ace 5 / ColorOS 16
- Head unit: 2019 Honda Civic factory head unit
- Link: factory USB wired Android Auto

## Remaining before a release APK

- Wire Shizuku state/permission UX and only invoke supported shell-identity operations.
- Complete integration tests for resolution changes while connected, DPI changes, reconnects, and resize rollback.
- Run physical-device validation for display geometry, touch mapping, stability, and frame pacing.
- Build and inspect a minified release APK; publish only after validation.

## License

See the upstream GPL-3.0 license and preserve all source notices.
