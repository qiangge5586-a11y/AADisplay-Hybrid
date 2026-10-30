package io.github.nitsuya.aa.display.performance

import io.github.nitsuya.aa.display.model.PointF

fun main() {
    // 16:9 source on a 4:3 target: FIT has top/bottom letterbox bars.
    val fit = ProjectionContentTransform.contentRect(1920,1080,800,600,ScaleMode.FIT)
    check(fit.width == 800f && fit.height == 450f && fit.top == 75f)
    check(ProjectionContentTransform.mapTargetToSource(PointF(400f,20f),1920,1080,800,600,ScaleMode.FIT)==null)
    val center = ProjectionContentTransform.mapTargetToSource(PointF(400f,300f),1920,1080,800,600,ScaleMode.FIT)
    check(center != null && center.x in 959f..961f && center.y in 539f..541f)
    // FILL covers target and maps the center crop; all target points remain valid.
    val fill = ProjectionContentTransform.contentRect(1920,1080,800,600,ScaleMode.FILL)
    check(fill.width == 1066.6666f || fill.width in 1066f..1067f)
    check(ProjectionContentTransform.mapTargetToSource(PointF(0f,0f),1920,1080,800,600,ScaleMode.FILL) != null)
    println("ProjectionContentTransform checks passed")
}
