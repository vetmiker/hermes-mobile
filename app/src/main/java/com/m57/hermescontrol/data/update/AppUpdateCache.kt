package com.m57.hermescontrol.data.update

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process-wide holder of the latest update-check result shared by launch and
 * the manual About surface.
 */
object AppUpdateCache {
    private val _state = MutableStateFlow<AppUpdateState>(AppUpdateState.Idle)
    val state: StateFlow<AppUpdateState> = _state.asStateFlow()

    fun update(state: AppUpdateState) {
        _state.value = state
    }

    /** Test hook: clear update state between tests. */
    internal fun reset() {
        _state.value = AppUpdateState.Idle
    }
}
