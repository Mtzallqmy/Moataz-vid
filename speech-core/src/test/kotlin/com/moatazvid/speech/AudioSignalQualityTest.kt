package com.moatazvid.speech

import kotlin.math.pow
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AudioSignalQualityTest {
    @Test
    fun `digital silence is rejected`() {
        assertFalse(AudioSignalQuality.isUsable(FloatArray(16_000)))
    }

    @Test
    fun `signal below minus sixty dBFS is rejected`() {
        val amplitude = 10.0.pow(-62.0 / 20.0).toFloat()
        assertFalse(AudioSignalQuality.isUsable(FloatArray(16_000) { amplitude }))
    }

    @Test
    fun `speech level signal above threshold is accepted`() {
        val amplitude = 10.0.pow(-30.0 / 20.0).toFloat()
        assertTrue(AudioSignalQuality.isUsable(FloatArray(16_000) { index -> if (index % 2 == 0) amplitude else -amplitude }))
    }
}
