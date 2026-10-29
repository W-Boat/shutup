package com.xiaomanjun.sleepdownschedule.domain.guard

interface AudioBackend {
    fun volume(stream: Int): Int
    fun maximum(stream: Int): Int
    fun setVolume(stream: Int, value: Int)
    fun ringMode(): Int
    fun setRingMode(value: Int)
    fun pause()
    fun capabilities(): CapabilityReport
}

/** Ownership is persisted before every system write. No auto-resume. */
class AudioController(private val backend: AudioBackend) {
    fun reconcile(initial: GuardRuntime, resolved: ResolvedPolicy, route: RouteEvidence,
        save: (GuardRuntime) -> Unit, onFailure: (String) -> Unit): GuardRuntime {
        var state = initial
        fun persist(next: GuardRuntime) { save(next); state = next }
        fun attempt(action: () -> Unit) { try { action() } catch (e: Exception) { onFailure(e.message ?: "声音控制失败") } }
        val policy = resolved.policy
        val key = policy?.toString() + ":" + resolved.trustedRelease
        val changed = key != state.policyKey
        val cap = policy?.mediaCap?.takeUnless { resolved.trustedRelease }
        attempt {
            val owned = state.media[route.identity]
            val current = backend.volume(MEDIA)
            if (cap != null) {
                if (policy?.lockVolume == true || owned == null || changed) {
                    val target = PolicyResolver.cappedVolume(current, backend.maximum(MEDIA), cap)
                    if (target != current || owned == null) {
                        val record = OwnedVolume(owned?.original ?: current, target)
                        persist(state.copy(media = state.media + (route.identity to record)))
                        if (target != current) backend.setVolume(MEDIA, target)
                        check(backend.volume(MEDIA) <= target) { "系统未接受媒体音量限制" }
                    }
                }
            } else if (owned != null && route.identity != "unknown") {
                if (current == owned.applied) backend.setVolume(MEDIA, owned.original)
                persist(state.copy(media = state.media - route.identity))
            }
        }
        attempt {
            val wanted = when (policy?.ring) { RingPolicy.SILENT -> 0; RingPolicy.VIBRATE -> 1; else -> null }
            val current = backend.ringMode()
            val owned = state.ring
            if (wanted == null && owned != null) {
                if (current == owned.applied) backend.setRingMode(owned.original)
                persist(state.copy(ring = null))
            } else if (wanted != null && current != wanted && (owned == null || changed)) {
                persist(state.copy(ring = OwnedVolume(owned?.original ?: current, wanted)))
                backend.setRingMode(wanted)
                check(backend.ringMode() == wanted) { "系统不支持所选铃声／震动策略" }
            }
        }
        attempt {
            val current = backend.volume(ALARM)
            val owned = state.alarm
            if (policy?.alarms == AlarmPolicy.MUTE && (owned == null || policy.lockVolume || changed)) {
                if (current != 0) {
                    persist(state.copy(alarm = OwnedVolume(owned?.original ?: current, 0)))
                    backend.setVolume(ALARM, 0)
                    check(backend.volume(ALARM) == 0) { "系统未接受闹钟静音" }
                }
            } else if (policy?.alarms != AlarmPolicy.MUTE && owned != null) {
                if (current == owned.applied) backend.setVolume(ALARM, owned.original)
                persist(state.copy(alarm = null))
            }
        }
        if (policy != null && !resolved.trustedRelease && (policy.pausePlayback || initial.previousTrusted)) attempt { backend.pause() }
        attempt { persist(state.copy(previousRoute = route.identity, previousTrusted = resolved.trustedRelease, policyKey = key)) }
        return state
    }
    companion object { const val MEDIA = 3; const val ALARM = 4 }
}
