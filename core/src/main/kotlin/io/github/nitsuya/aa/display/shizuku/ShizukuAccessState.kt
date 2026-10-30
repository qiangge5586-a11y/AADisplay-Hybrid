package io.github.nitsuya.aa.display.shizuku

enum class ShizukuAccessState { SERVICE_UNAVAILABLE, PERMISSION_REQUIRED, GRANTED_SHELL, GRANTED_ROOT }

/** UID 2000 is shell/ADB, not root. */
object ShizukuAccessPolicy {
    fun resolve(binderAlive: Boolean, permissionGranted: Boolean, uid: Int?): ShizukuAccessState {
        if (!binderAlive) return ShizukuAccessState.SERVICE_UNAVAILABLE
        if (!permissionGranted) return ShizukuAccessState.PERMISSION_REQUIRED
        return when (uid) {
            0 -> ShizukuAccessState.GRANTED_ROOT
            2000 -> ShizukuAccessState.GRANTED_SHELL
            else -> ShizukuAccessState.SERVICE_UNAVAILABLE
        }
    }
}
