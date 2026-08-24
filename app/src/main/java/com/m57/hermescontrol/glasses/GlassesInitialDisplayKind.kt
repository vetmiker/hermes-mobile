package com.m57.hermescontrol.glasses

/**
 * Classifies the content handed to MYVU when a session starts.
 *
 * The service uses this metadata instead of interpreting formatted display text,
 * so completed responses retain their dedicated scroll-capable response document.
 */
internal enum class GlassesInitialDisplayKind {
    NEUTRAL,
    COMPLETED_RESPONSE,
}
