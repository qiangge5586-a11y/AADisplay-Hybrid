package io.github.nitsuya.aa.display.performance

/**
 * Conservative render policy for wired Android Auto head units.
 * These are recommendations, not a forced display refresh rate: the HU negotiates transport
 * timing and the Android app must not override it blindly.
 */
data class ProjectionPerformanceProfile(
    val resizeThrottleMs: Long = 120L,
    val ratioSettleMs: Long = 180L,
    val skipUnchangedBuffers: Boolean = true,
    val reuseSurfacesWhenPossible: Boolean = true,
    val avoidPhysicalDisplayOverrides: Boolean = true,
) {
    init {
        require(resizeThrottleMs in 80L..500L)
        require(ratioSettleMs in 100L..600L)
    }

    companion object {
        /** Stable starting point for older OEM head units; tune only after frame-time profiling. */
        val CIVIC_2019_WIRED = ProjectionPerformanceProfile()
    }
}

/** Tracks the last applied buffer geometry so resize calls are issued only for real changes. */
data class BufferGeometry(val width: Int, val height: Int, val densityDpi: Int) {
    init { require(width > 0 && height > 0 && densityDpi > 0) }
}

class ResizeChangeFilter {
    private var primary: BufferGeometry? = null
    private var secondary: BufferGeometry? = null

    data class Changes(val primaryChanged: Boolean, val secondaryChanged: Boolean) {
        val any: Boolean get() = primaryChanged || secondaryChanged
    }

    @Synchronized
    fun compareAndCommit(nextPrimary: BufferGeometry, nextSecondary: BufferGeometry): Changes {
        val p = primary != nextPrimary
        val s = secondary != nextSecondary
        if (p || s) {
            primary = nextPrimary
            secondary = nextSecondary
        }
        return Changes(p, s)
    }

    @Synchronized
    fun reset() { primary = null; secondary = null }
}

/**
 * Latest-value coalescer for rapid slider/ratio changes. The Android Handler integration
 * should post one delayed apply and replace the pending profile while the delay is active.
 */
class LatestProfileCoalescer {
    @Volatile private var pending: Any? = null
    fun submit(profile: Any) { pending = profile }
    @Suppress("UNCHECKED_CAST")
    fun <T> take(): T? = pending.also { pending = null } as T?
}
