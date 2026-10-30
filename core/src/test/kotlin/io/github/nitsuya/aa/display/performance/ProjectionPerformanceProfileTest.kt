package io.github.nitsuya.aa.display.performance
fun main() {
    val profile = ProjectionPerformanceProfile.CIVIC_2019_WIRED
    check(profile.resizeThrottleMs == 120L)
    check(profile.ratioSettleMs == 180L)
    val filter = ResizeChangeFilter()
    val a = BufferGeometry(800,480,160)
    val b = BufferGeometry(400,480,160)
    check(filter.compareAndCommit(a,b).any)
    check(!filter.compareAndCommit(a,b).any)
    check(filter.compareAndCommit(BufferGeometry(640,480,160),b).primaryChanged)
    filter.reset()
    check(filter.compareAndCommit(a,b).any)
    val coalescer=LatestProfileCoalescer()
    coalescer.submit("old"); coalescer.submit("latest")
    check(coalescer.take<String>()=="latest")
    check(coalescer.take<String>()==null)
    println("ProjectionPerformanceProfile checks passed")
}
