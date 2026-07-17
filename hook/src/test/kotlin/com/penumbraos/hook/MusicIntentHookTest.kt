package com.penumbraos.hook

import org.junit.Assert.assertEquals
import org.junit.Test

class MusicIntentHookTest {
    @Test
    fun playCommandIsClaimedBeforeHumaneCanStripVerb() {
        assertEquals(
            "sympathy by the goo goo dolls",
            MusicIntentHook.classifyTrackCommand("Play Sympathy by the Goo Goo Dolls"),
        )
    }
}