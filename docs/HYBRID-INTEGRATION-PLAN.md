# Hybrid integration plan

## Profile model

Keep two profiles:

- `DisplayProfile`: the dimensions and density negotiated by the head unit.
- `EffectiveDisplayProfile`: the dimensions and density actually used to render the virtual displays.

An override value of zero means Auto. Validate custom values before applying them:

- Width: 640–4096
- Height: 360–4096
- Density: 120–640 DPI

Do not overwrite the negotiated head-unit profile when a user changes the render override.

## Resolution update flow

1. Receive the head-unit profile on initial connection.
2. Resolve Auto/custom override into an effective profile.
3. Compute both pane dimensions from the effective profile and current split/fullscreen mode.
4. Resize both VirtualDisplays.
5. Resize/recreate backing Surface or Texture buffers to match.
6. Update touch and pointer coordinate transforms using the effective pane dimensions.
7. On reconnect, receive the new negotiated profile and re-resolve the saved override.
8. If the backend rejects a resize, roll back to the last accepted effective profile and report the failure.

## Shizuku policy

- Observe binder received/dead events.
- Request Shizuku permission only when a privileged operation is actually needed.
- Treat binder unavailable, permission denied, and service failure as separate states.
- Never treat Shizuku availability as proof that LSPosed hooks are active.
- Keep baseline app functionality available when Shizuku is absent.

## LSPosed boundary

Some original AADisplay behavior depends on hooks inside Android Auto or system_server. Shizuku cannot provide that injection capability. Put hook-dependent features behind an explicit capability check and explain when LSPosed is required.

## Integration points in upstream AADisplay

Likely files to review during integration:

- `SplitDisplayController.kt`
- `SplitVdLifecycle.kt`
- `VdDensityPin.kt`
- `VdMetricsMirror.kt`
- `CoreApi.kt`
- `CoreManagerService.kt`
- `DisplayProfileSettle.kt`
- `SplitInputRecents.kt`
- `AaMainFragment.kt`
- `MainActivity.kt`
- `ICoreManager.aidl`
- `DisplayDpiStore.kt`
- `activity_main.xml`
- `AndroidManifest.xml`

## Acceptance criteria

- Changing custom resolution does not alter the phone's physical display size or density.
- Both panes, their buffers, and input mapping use one consistent effective profile.
- Auto mode always tracks the latest head-unit profile.
- Saved custom values survive process restart and reconnect.
- Failed resize operations restore the last known-good state.
- Shizuku-only mode never claims support for operations it cannot perform.
- Build and real-device validation are required before publishing an APK.
