package com.m57.hermescontrol.glasses

/** Native mode state remains alive across Activity recreation, but process death is inactive. */
object GlassesModeControllerProvider {
    val controller = GlassesModeController()
}
