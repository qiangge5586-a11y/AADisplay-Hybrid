package io.github.nitsuya.aa.display.performance

import io.github.nitsuya.aa.display.model.PointF

data class ContentRect(val left: Float, val top: Float, val width: Float, val height: Float) {
    init { require(width > 0f && height > 0f) }
}
enum class ScaleMode { FIT, FILL }

/** Geometry shared by renderer and input path: never calculate drawing and touch transforms separately. */
object ProjectionContentTransform {
    fun contentRect(sourceWidth: Int, sourceHeight: Int, targetWidth: Int, targetHeight: Int, mode: ScaleMode): ContentRect {
        require(sourceWidth > 0 && sourceHeight > 0 && targetWidth > 0 && targetHeight > 0)
        val sx = targetWidth.toFloat() / sourceWidth
        val sy = targetHeight.toFloat() / sourceHeight
        val scale = if (mode == ScaleMode.FIT) minOf(sx, sy) else maxOf(sx, sy)
        val drawnWidth = sourceWidth * scale
        val drawnHeight = sourceHeight * scale
        return ContentRect((targetWidth - drawnWidth) / 2f, (targetHeight - drawnHeight) / 2f, drawnWidth, drawnHeight)
    }

    /** FIT rejects letterbox taps; FILL maps taps through the centered crop. */
    fun mapTargetToSource(point: PointF, sourceWidth: Int, sourceHeight: Int, targetWidth: Int, targetHeight: Int, mode: ScaleMode): PointF? {
        val rect = contentRect(sourceWidth, sourceHeight, targetWidth, targetHeight, mode)
        if (mode == ScaleMode.FIT && (point.x < rect.left || point.x >= rect.left + rect.width ||
            point.y < rect.top || point.y >= rect.top + rect.height)) return null
        val x = ((point.x - rect.left) / rect.width * sourceWidth).coerceIn(0f, (sourceWidth - 1).toFloat())
        val y = ((point.y - rect.top) / rect.height * sourceHeight).coerceIn(0f, (sourceHeight - 1).toFloat())
        return PointF(x, y)
    }
}
