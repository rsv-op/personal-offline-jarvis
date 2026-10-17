package com.example.voiceassistant.ui

import androidx.compose.ui.graphics.Color

/** UI palette shared across screens. Kept in a separate file so compose UI components
 *  in any screen can reference the same colours. */
internal val JarvisCyan = Color(0xFF29E3F5)
internal val JarvisDeep = Color(0xFF05070D)
internal val JarvisPanel = Color(0xFF101827)
/** Magenta/violet used by the Heretic orb and waveform. Distinct from the primary
 *  cyan so the two ring layers read as a deliberate pair rather than a colour clash. */
internal val HereticMagenta = Color(0xFFD14BFF)
/** Softer mid-tone violet for the orb's plasma core; the rings use [JarvisCyan]
 *  and [HereticMagenta] at full saturation, the core uses this to keep contrast
 *  with the central sigil. */
internal val HereticCore = Color(0xFF6B2B8F)
