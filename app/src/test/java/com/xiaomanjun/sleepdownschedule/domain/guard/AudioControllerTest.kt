package com.xiaomanjun.sleepdownschedule.domain.guard

import org.junit.Assert.*
import org.junit.Test

class AudioControllerTest {
    private class FakeAudio : AudioBackend {
        var media = 8; var alarm = 6; var ringer = 2; var pauses = 0; var sets = 0
        override fun volume(stream: Int) = if (stream == 3) media else alarm
        override fun maximum(stream: Int) = 15
        override fun setVolume(stream: Int, value: Int) { sets++; if (stream == 3) media = value else alarm = value }
        override fun ringMode() = ringer
        override fun setRingMode(value: Int) { ringer = value }
        override fun pause() { pauses++ }
        override fun capabilities() = CapabilityReport(emptyList())
    }
    private val speaker = RouteEvidence("speaker", false, false, "speaker")
    private fun resolution(policy: SoundPolicy? = SoundPolicy()) = ResolvedPolicy(policy, emptyList(), false, null)
    @Test fun snapshotPrecedesMutationAndVolumeRestores() {
        val audio = FakeAudio(); val controller = AudioController(audio)
        var saved = GuardRuntime()
        val state = controller.reconcile(saved, resolution(), speaker, { next ->
            if (next.media.containsKey("speaker") && !saved.media.containsKey("speaker")) assertEquals(8, audio.media)
            saved = next
        }) { fail(it) }
        assertEquals(0, audio.media); assertEquals(6, audio.alarm)
        controller.reconcile(state, resolution(null), speaker, { saved = it }) { fail(it) }
        assertEquals(8, audio.media); assertEquals(2, audio.ringer)
    }
    @Test fun failedSnapshotDoesNotWriteSystemVolume() {
        val audio = FakeAudio()
        AudioController(audio).reconcile(GuardRuntime(), resolution(), speaker, { throw IllegalStateException("disk full") }) { }
        assertEquals(8, audio.media); assertEquals(2, audio.ringer)
    }
    @Test fun restorationDoesNotClobberManualChanges() {
        val audio = FakeAudio(); val controller = AudioController(audio)
        val state = controller.reconcile(GuardRuntime(), resolution(), speaker, {}) { fail(it) }
        audio.media = 3; audio.ringer = 1
        controller.reconcile(state, resolution(null), speaker, {}) { fail(it) }
        assertEquals(3, audio.media); assertEquals(1, audio.ringer)
    }
    @Test fun headphoneSnapshotNeverRestoresOntoSpeaker() {
        val audio = FakeAudio()
        val state = GuardRuntime(media = mapOf("headset" to OwnedVolume(12, 0)))
        AudioController(audio).reconcile(state, resolution(null), speaker, {}) { fail(it) }
        assertEquals(8, audio.media)
    }
    @Test fun uncertainRouteDoesNotRestoreOldVolume() {
        val audio = FakeAudio().apply { media = 0 }
        val state = GuardRuntime(media = mapOf("unknown" to OwnedVolume(12, 0)))
        AudioController(audio).reconcile(state, resolution(null), RouteEvidence("unknown", false, false, "unknown"), {}) { fail(it) }
        assertEquals(0, audio.media)
    }
    @Test fun lockCorrectsVolumeIncrease() {
        val audio = FakeAudio(); val controller = AudioController(audio)
        val state = controller.reconcile(GuardRuntime(), resolution(), speaker, {}) { fail(it) }
        audio.media = 10
        controller.reconcile(state, resolution(), speaker, {}) { fail(it) }
        assertEquals(0, audio.media)
    }
    @Test fun unlockedTemplateHonorsManualIncrease() {
        val audio = FakeAudio().apply { media = 0 }; val controller = AudioController(audio)
        val resolved = resolution(SoundPolicy(lockVolume = false))
        val state = controller.reconcile(GuardRuntime(), resolved, speaker, {}) { fail(it) }
        audio.media = 5
        controller.reconcile(state, resolved, speaker, {}) { fail(it) }
        assertEquals(5, audio.media)
    }
    @Test fun disconnectPausesButNeverResumes() {
        val audio = FakeAudio()
        AudioController(audio).reconcile(GuardRuntime(previousTrusted = true), resolution(), speaker, {}) { fail(it) }
        assertEquals(1, audio.pauses); assertEquals(0, audio.media)
    }
}
