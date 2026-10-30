# Android integration contract

This folder contains Android-specific integration sources that are intentionally excluded from the JVM-only core build.

## Shizuku dependencies

In the upstream `:aa-display` module, add the current compatible Shizuku API/provider artifacts (verify the versions against the upstream project's dependency catalog before pinning):

```kotlin
implementation("dev.rikka.shizuku:api:<verified-version>")
implementation("dev.rikka.shizuku:provider:<verified-version>")
```

Register the provider in the application manifest, using the app's actual applicationId:

```xml
<provider
    android:name="rikka.shizuku.ShizukuProvider"
    android:authorities="${applicationId}.shizuku"
    android:multiprocess="false"
    android:enabled="true"
    android:exported="true"
    android:permission="android.permission.INTERACT_ACROSS_USERS_FULL" />
```

Copy `src/main/kotlin/io/github/nitsuya/aa/display/shizuku/ShizukuAccessController.kt` into the Android module's matching Kotlin source package. Create one application/service-scoped instance, register listeners once, and call `destroy()` when the owner is disposed.

## Capability rules

- `GRANTED_SHELL` means UID 2000. It is not root and does not authorize arbitrary system-server or Android Auto injection.
- `GRANTED_ROOT` means UID 0, but feature availability must still be checked per operation.
- Shizuku absent/denied must not crash the base application.
- Features that depend on LSPosed hooks must remain marked unavailable until the hook is independently detected.

## Display backend contract

The Android implementation of `ProjectionDisplayBackend.apply(profile)` must run on the existing serialized display-control thread and treat these as one transaction:

1. Compute both pane sizes from the effective profile.
2. Resize both VirtualDisplays.
3. Recreate/resize backing Surface or Texture buffers.
4. Update input coordinate transforms to the exact new buffer sizes.
5. Commit the new effective profile only after all operations succeed; otherwise restore the last known-good dimensions.

Do not use `wm size` or `wm density` to emulate a virtual-display override; those commands alter the phone's physical display configuration.
