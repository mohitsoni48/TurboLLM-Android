package com.turbollm.app

import kotlin.math.pow

/** The fatal screen's own colors, as ARGB values (pure, so a JVM test pins their contrast). The screen paints its
 *  surface instead of taking the system theme's, so these stay readable in light and dark mode alike. The surface is
 *  the splash's pinned dark color (DaemonWebView.kt). */
internal object DaemonFatalPalette {
    const val SURFACE = 0xFF0E0E10
    const val TITLE = 0xFFEDEDEC
    const val BODY = 0xFFEDEDEC
    const val DETAIL = 0xFFB4B4B2
    const val BUTTON = 0xFFC8643F
    const val BUTTON_TEXT = 0xFF000000
    const val OUTLINE_BUTTON_TEXT = 0xFFEDEDEC
}

/** WCAG 2.x contrast ratio of two opaque ARGB colors, from 1 (identical) to 21 (black on white). */
internal fun contrastRatio(a: Long, b: Long): Double {
    val (hi, lo) = listOf(luminance(a), luminance(b)).sortedDescending()
    return (hi + 0.05) / (lo + 0.05)
}

private fun luminance(argb: Long): Double {
    fun channel(shift: Int): Double {
        val c = ((argb shr shift) and 0xFF) / 255.0
        return if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
    }
    return 0.2126 * channel(16) + 0.7152 * channel(8) + 0.0722 * channel(0)
}
