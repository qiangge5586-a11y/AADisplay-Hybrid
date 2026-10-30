package io.github.nitsuya.aa.display.model
fun main() {
    val hu = DisplayProfile(1920,1080,240)
    check(ProjectionResolution.resolve(hu, ResolutionOverride()) == EffectiveDisplayProfile(1920,1080,240))
    val custom = ProjectionResolution.resolve(hu, ResolutionOverride(1600,900,200))
    check(custom == EffectiveDisplayProfile(1600,900,200))
    val panes = ProjectionResolution.paneSizes(custom,0.5f,20,false)
    check(panes.primaryWidth + panes.secondaryWidth + 20 == 1600)
    check(ProjectionResolution.paneSizes(custom,0.5f,20,true) == PaneSizes(1600,900,1600,900))
    check(ProjectionResolution.mapTouch(PointF(50f,25f),100,50,200,100) == PointF(100f,50f))
    check(runCatching { ResolutionOverride(1920,0,240).normalized() }.isFailure)
    check(runCatching { ResolutionOverride(5000,1080,240).normalized() }.isFailure)
    println("ProjectionResolution checks passed")
}
