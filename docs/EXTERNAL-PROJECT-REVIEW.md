# External project review: Android Auto projection and smoothness

Reviewed on 2026-10-31. These repositories are reference material, not drop-in replacements. The target remains the existing AADisplay LSPosed/VirtualDisplay architecture and a 2019 Honda Civic factory wired Android Auto head unit.

## Projects reviewed

| Project | Useful ideas | Not a direct fit |
|---|---|---|
| [Nitsuya/AADisplay](https://github.com/Nitsuya/AADisplay) | The actual upstream implementation and lifecycle to preserve. Existing resize throttling and pane geometry are the baseline. | It still relies on LSPosed hooks for its system/Android Auto integration. |
| [iAlias/AASuite](https://github.com/iAlias/AASuite) | Strong surface ownership pattern: one long-lived SurfaceCallback router; create a MediaProjection VirtualDisplay once, then retarget with resize()+setSurface(); pure Kotlin geometry/touch tests; acquireLatestImage() and close every Image in a dedicated HandlerThread for its optional frame-processing path. | It is a separate MediaProjection-based navigation app, not an LSPosed module. Its fill renderer adds an ImageReader/GPU copy path and should not be introduced into the default fast path without profiling. MIT license; preserve attribution and license if code is reused. |
| [koalaauto/KoalaMirror](https://github.com/koalaauto/KoalaMirror) | Clear separation between system_server and Android Auto (gearhead) process responsibilities; version-aware capability detection; preserve unknown steering/rotary keys instead of swallowing them; graceful feature degradation. | It explicitly requires root/Zygisk and hooks privileged platform/gearhead behavior. It confirms Shizuku shell cannot create trusted display or inject into gearhead. Different architecture; not a no-root replacement. |
| [Baghdady92/DisplayMirror](https://github.com/Baghdady92/DisplayMirror) | Display lifecycle, Presentation/SurfaceView, persisted preferences, service ownership and reconnect-oriented UX. | It targets Android Automotive multi-display hardware, not a phone projecting through Android Auto; its ADB-over-TCP setup and display assumptions are not transferable. |
| [koalaauto/AADisplay-Beta](https://github.com/koalaauto/AADisplay-Beta) | Historical AADisplay fork and issue context. | Archived; prefer the pinned upstream source for implementation. |

## Performance decisions for this project

1. **Keep the original direct VirtualDisplay-to-HU Surface path as default.** Avoid ImageReader/Bitmap/Canvas copies in the hot path unless a measured feature requires them.
2. **One VirtualDisplay lifecycle per active projection session.** For Android 14+ MediaProjection, register callback before creating the display; reuse it via resize() and setSurface() rather than creating a second display on the same projection.
3. **One surface owner/router.** Surface attach/detach and mode switches must not register competing callbacks or lose an already-delivered surface.
4. **No-op unchanged geometry.** Compare actual width, height and density before resize. Rebuild Surface/Texture only when the backing buffer really changes.
5. **Latest-value debounce.** Keep upstream resize throttle and ratio debounce; rapid slider updates should apply the latest target, not queue every intermediate size.
6. **Separate transport and render geometry.** Never rewrite the HU-negotiated profile when the user changes render width/height/DPI.
7. **Touch must invert the exact render transform.** Aspect-fit bars reject touches; crop/fill maps through the crop rectangle. Split-pane touch uses each pane's effective buffer dimensions.
8. **Close frame resources deterministically.** If a future optional renderer uses ImageReader, use acquireLatestImage(), always close Image in finally, bound the queue, and keep GPU work off the main thread.
9. **Fail soft on unsupported privileged features.** Shizuku shell UID 2000 is not root and cannot replace LSPosed injection. Preserve stock AA behavior for unhandled key events.
10. **Measure before tuning.** Record frame pacing/dropped frames, resize duration/count, Surface recreation, CPU, thermal state, reconnects and touch latency on the actual Civic head unit. Do not force 60 FPS or guess the HU resolution.

## Next integration tasks

- Inspect the pinned upstream `SplitDisplayController` and `SplitVdLifecycle` and add a narrow adapter around their existing lifecycle; do not replace them with MediaProjection.
- Add a no-op resize filter at the exact point where pane buffer sizes are compared.
- Ensure both VirtualDisplays, backing buffers and input transforms update in one serialized operation with rollback.
- Add runtime diagnostics for negotiated/effective profiles and resize timings.
- Build and run on the phone and the physical 2019 Civic; CI unit tests alone cannot prove vehicle smoothness.

## Licensing and provenance

This document links to external projects and summarizes architectural observations. It does not copy their implementation source. If any source code is later reused, verify the exact repository license and preserve required copyright/license notices. AASuite declares MIT; AADisplay declares GPL-3.0; check each other repository's current LICENSE before copying code.
