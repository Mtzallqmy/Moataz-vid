package com.moatazvid.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CoreModelTest {
    @Test fun `half-open ranges do not overlap at boundary`() {
        val a = TimeRangeUs(TimeUs(0), TimeUs(1_000))
        val b = TimeRangeUs(TimeUs(1_000), TimeUs(2_000))
        assertFalse(a.overlaps(b))
    }

    @Test fun `2997 fps stays rational`() {
        assertEquals(30_000, Rational.FPS_29_97.numerator)
        assertEquals(1_001, Rational.FPS_29_97.denominator)
        assertTrue(Rational.FPS_29_97.asDouble() in 29.96..29.98)
    }

    @Test fun `ids are prefixed and independent`() {
        val generator = UlidIdGenerator()
        val project = generator.newId(IdKind.PROJECT)
        val clip = generator.newId(IdKind.CLIP)
        assertTrue(project.startsWith("prj_"))
        assertTrue(clip.startsWith("clp_"))
        assertFalse(project == clip)
    }
}

