package io.github.nitsuya.aa.display.model

/** User-selected render profile. Zero means follow the head-unit negotiation. */
data class ResolutionOverride(val width: Int = 0, val height: Int = 0, val densityDpi: Int = 0) {
    fun normalized(): ResolutionOverride {
        require(width == 0 || width in MIN_WIDTH..MAX_WIDTH) { "width out of range" }
        require(height == 0 || height in MIN_HEIGHT..MAX_HEIGHT) { "height out of range" }
        require(densityDpi == 0 || densityDpi in MIN_DPI..MAX_DPI) { "DPI out of range" }
        require((width == 0) == (height == 0)) { "width and height must both be auto or both be set" }
        return this
    }
    companion object {
        const val MIN_WIDTH = 640; const val MAX_WIDTH = 4096
        const val MIN_HEIGHT = 360; const val MAX_HEIGHT = 4096
        const val MIN_DPI = 120; const val MAX_DPI = 640
    }
}

/** HU-negotiated profile is immutable input; it is never overwritten by a render override. */
data class DisplayProfile(val width: Int, val height: Int, val densityDpi: Int) {
    init { require(width > 0 && height > 0 && densityDpi > 0) }
}
data class EffectiveDisplayProfile(val width: Int, val height: Int, val densityDpi: Int)
data class PaneSizes(val primaryWidth: Int, val primaryHeight: Int, val secondaryWidth: Int, val secondaryHeight: Int)
data class PointF(val x: Float, val y: Float)

object ProjectionResolution {
    fun resolve(hu: DisplayProfile, override: ResolutionOverride): EffectiveDisplayProfile {
        val o = override.normalized()
        return EffectiveDisplayProfile(if (o.width > 0) o.width else hu.width,
            if (o.height > 0) o.height else hu.height,
            if (o.densityDpi > 0) o.densityDpi else hu.densityDpi)
    }
    fun paneSizes(profile: EffectiveDisplayProfile, ratio: Float, dividerPx: Int, fullscreen: Boolean): PaneSizes {
        val w = profile.width.coerceAtLeast(1); val h = profile.height.coerceAtLeast(1)
        if (fullscreen) return PaneSizes(w, h, w, h)
        val r = ratio.coerceIn(0.2f, 0.8f); val gap = dividerPx.coerceAtLeast(0)
        return if (w >= h) {
            val usable = (w - gap).coerceAtLeast(2); val p = (usable * r).toInt().coerceIn(1, usable - 1)
            PaneSizes(p, h, usable - p, h)
        } else {
            val usable = (h - gap).coerceAtLeast(2); val p = (usable * r).toInt().coerceIn(1, usable - 1)
            PaneSizes(w, p, w, usable - p)
        }
    }
    fun mapTouch(point: PointF, viewWidth: Int, viewHeight: Int, vdWidth: Int, vdHeight: Int): PointF {
        require(viewWidth > 0 && viewHeight > 0 && vdWidth > 0 && vdHeight > 0)
        return PointF((point.x * vdWidth / viewWidth).coerceIn(0f, (vdWidth - 1).toFloat()),
            (point.y * vdHeight / viewHeight).coerceIn(0f, (vdHeight - 1).toFloat()))
    }
}
