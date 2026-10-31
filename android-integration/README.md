# Android integration contract

This folder contains Android-specific integration sources that are intentionally excluded from the JVM-only core build.

## Display backend contract

The Android implementation of `ProjectionDisplayBackend.apply(profile)` must run on the existing serialized display-control thread and treat these as one transaction:

1. Compute both pane sizes from the effective profile.
2. Resize both VirtualDisplays.
3. Recreate/resize backing Surface or Texture buffers.
4. Update input coordinate transforms to the exact new buffer sizes.
5. Commit the new effective profile only after all operations succeed; otherwise restore the last known-good dimensions.

Do not use `wm size` or `wm density` to emulate a virtual-display override; those commands alter the phone's physical display configuration.
