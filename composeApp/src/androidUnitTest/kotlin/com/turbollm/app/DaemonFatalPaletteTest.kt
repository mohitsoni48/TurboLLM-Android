package com.turbollm.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The fatal screen paints its own dark surface, so every text must stay readable on it whatever the system theme
 *  (the first version took the theme's light background and showed light text on it: nearly invisible). WCAG 2.x
 *  contrast ratios: 4.5 for body text, 3 for large text. */
class DaemonFatalPaletteTest {
    private val p = DaemonFatalPalette

    @Test
    fun theContrastFormulaMatchesWcagReferenceValues() {
        assertEquals(21.0, contrastRatio(0xFFFFFFFF, 0xFF000000), 0.001)
        assertEquals(1.0, contrastRatio(0xFF123456, 0xFF123456), 0.001)
        // Light text on a light surface, the defect seen on the emulator: far below any threshold.
        assertTrue(contrastRatio(0xFFEDEDEC, 0xFFF8F9FB) < 1.2)
    }

    @Test
    fun theTitleAndTheExplanationAreReadableOnTheSurface() {
        assertTrue(contrastRatio(p.TITLE, p.SURFACE) >= 7.0)
        assertTrue(contrastRatio(p.BODY, p.SURFACE) >= 7.0)
    }

    @Test
    fun theDetailMessageIsReadableOnTheSurface() {
        assertTrue(contrastRatio(p.DETAIL, p.SURFACE) >= 4.5)
    }

    @Test
    fun bothButtonsAreReadable() {
        assertTrue(contrastRatio(p.BUTTON_TEXT, p.BUTTON) >= 4.5)
        assertTrue(contrastRatio(p.OUTLINE_BUTTON_TEXT, p.SURFACE) >= 4.5)
        assertTrue(contrastRatio(p.BUTTON, p.SURFACE) >= 3.0)
    }

    @Test
    fun theSurfaceIsTheSplashsPinnedDarkColor() {
        assertEquals(0xFF0E0E10, p.SURFACE)
    }
}
