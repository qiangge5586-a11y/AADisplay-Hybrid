# 2019 Honda Civic wired Android Auto performance plan

## Target

2019 Honda Civic factory head unit, wired USB Android Auto. The exact head-unit negotiated width, height, DPI and transport frame cadence must be read from the live session; do not hard-code a guessed resolution or force 60 Hz.

## Conservative baseline

- Keep the negotiated HU transport profile separate from the render override.
- Start at the existing 120 ms resize throttle and 180 ms ratio-settle debounce.
- Do not call VirtualDisplay.resize when buffer width/height are unchanged.
- Do not recreate Surface/Texture resources for a density-only update unless the Android backend requires it.
- Coalesce rapid slider changes and apply only the latest profile.
- Resize both panes and update touch transforms in the same serialized display-control operation.
- Never use `wm size` / `wm density`; those change the phone display, not the projection virtual displays.
- Do not force a 60 FPS mode or upscale beyond the HU's stable negotiated profile without measured evidence.

## Suggested tuning sequence

1. First test Auto mode (HU width/height/DPI unchanged) and capture baseline frame time, reconnect count, CPU, temperature and dropped frames.
2. Test one modest step down in pixel count (e.g. 80% of negotiated width and height, keeping aspect ratio) while preserving HU transport settings.
3. If still unstable, reduce render dimensions another step; do not change transport negotiation.
4. Change DPI separately from pixel dimensions. Large DPI changes can alter Android app layout and touch target geometry.
5. Keep the selected profile only if touch edges, keyboard, navigation, media controls and reconnect all pass.
6. Compare identical routes/apps and thermal conditions. Reject any setting that improves average FPS but worsens frame-time spikes or touch latency.

## Instrumentation required before claiming smooth operation

Record at minimum:
- negotiated and effective display profiles;
- each actual VirtualDisplay resize call and duration;
- Surface buffer dimensions and recreation count;
- frame presentation time / dropped-frame evidence where available;
- main-thread stalls and Binder call duration;
- reconnect and recovery counts;
- touch coordinate calibration at all four corners and center.

## Vehicle-specific caveat

The Civic trim name does not uniquely identify the infotainment firmware, panel resolution or AA projection mode. The app must discover the actual session profile at runtime. A final smoothness claim requires testing on the user's physical head unit and phone; CI cannot establish vehicle compatibility.
