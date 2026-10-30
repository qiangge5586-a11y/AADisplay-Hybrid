package io.github.nitsuya.aa.display.resolution
import io.github.nitsuya.aa.display.model.*
private class MemoryStore(var value: ResolutionOverride = ResolutionOverride()) : ResolutionOverrideStore {
    var failWrite = false
    override fun read() = value
    override fun write(value: ResolutionOverride) { check(!failWrite); this.value = value }
}
private class RecordingBackend : ProjectionDisplayBackend {
    val applied = mutableListOf<EffectiveDisplayProfile>(); var accept = true
    override fun apply(profile: EffectiveDisplayProfile): Boolean { applied += profile; return accept }
}
fun main() {
    val store=MemoryStore(); val backend=RecordingBackend(); val c=ResolutionOverrideCoordinator(store,backend)
    check(c.snapshot()==null)
    c.onHostProfileChanged(DisplayProfile(1920,720,160))
    check(c.updateOverride(ResolutionOverride(1600,600,200)).effective == EffectiveDisplayProfile(1600,600,200))
    check(c.onHostProfileChanged(DisplayProfile(1280,480,140)).effective == EffectiveDisplayProfile(1600,600,200))
    check(c.updateOverride(ResolutionOverride()).effective == EffectiveDisplayProfile(1280,480,140))
    backend.accept=false; val old=store.value
    check(runCatching { c.updateOverride(ResolutionOverride(1920,800,240)) }.isFailure && store.value==old)
    backend.accept=true; store.failWrite=true; val count=backend.applied.size
    check(runCatching { c.updateOverride(ResolutionOverride(1600,600,200)) }.isFailure)
    check(backend.applied.size==count+2)
    check(backend.applied.last()==EffectiveDisplayProfile(1280,480,140))
    println("ResolutionOverrideCoordinator checks passed")
}
