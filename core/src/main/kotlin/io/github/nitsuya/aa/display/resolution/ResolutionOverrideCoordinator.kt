package io.github.nitsuya.aa.display.resolution

import io.github.nitsuya.aa.display.model.*

interface ResolutionOverrideStore { fun read(): ResolutionOverride; fun write(value: ResolutionOverride) }
interface ProjectionDisplayBackend {
    /** Implement atomically: both VDs, backing surfaces and input transforms must match this profile. */
    fun apply(profile: EffectiveDisplayProfile): Boolean
}
data class ResolutionSnapshot(val host: DisplayProfile, val override: ResolutionOverride, val effective: EffectiveDisplayProfile)

/** Serialize calls on the app's display-control thread. Persist only after the backend accepts. */
class ResolutionOverrideCoordinator(private val store: ResolutionOverrideStore, private val backend: ProjectionDisplayBackend) {
    private var hostProfile: DisplayProfile? = null
    fun onHostProfileChanged(host: DisplayProfile): ResolutionSnapshot {
        val previousHost = hostProfile
        hostProfile = host
        return try {
            val value = store.read().normalized()
            val effective = ProjectionResolution.resolve(host, value)
            check(backend.apply(effective)) { "display backend rejected host profile" }
            ResolutionSnapshot(host, value, effective)
        } catch (t: Throwable) { hostProfile = previousHost; throw t }
    }
    fun updateOverride(candidate: ResolutionOverride): ResolutionSnapshot {
        val host = checkNotNull(hostProfile) { "host profile is not available yet" }
        val normalized = candidate.normalized(); val previous = store.read().normalized()
        val effective = ProjectionResolution.resolve(host, normalized)
        check(backend.apply(effective)) { "display backend rejected override" }
        try { store.write(normalized) } catch (t: Throwable) {
            backend.apply(ProjectionResolution.resolve(host, previous)); throw t
        }
        return ResolutionSnapshot(host, normalized, effective)
    }
    fun snapshot(): ResolutionSnapshot? {
        val host = hostProfile ?: return null; val value = store.read().normalized()
        return ResolutionSnapshot(host, value, ProjectionResolution.resolve(host, value))
    }
}
