package com.m57.hermescontrol.ui.common

import androidx.lifecycle.ViewModel
import com.m57.hermescontrol.data.remote.NetworkResult
import com.m57.hermescontrol.data.ws.ChangeEvents
import com.m57.hermescontrol.data.ws.WsEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * Regression guard for issue #784: `refreshOnChange` must react to gateway
 * change events WITHOUT touching the HermesWsClient singleton — constructing a
 * ViewModel that wires it must be safe in a plain unit-test JVM (no mockkObject
 * of HermesWsClient, no static mocks). This used to poison the whole suite with
 * ExceptionInInitializerError at MockKStub.kt:106.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RefreshOnChangeTest {
    private val testDispatcher = StandardTestDispatcher()

    private class TestViewModel(events: Flow<WsEvent.ChangeEvent>) : ViewModel() {
        val state = MutableStateFlow("initial")
        var refreshCalls = 0

        val refreshJob =
            refreshOnChange(
                eventType = ChangeEvents.CRON,
                events = events,
                apiCall = {
                    refreshCalls += 1
                    NetworkResult.Success("refreshed")
                },
                onSuccess = { state.value = it },
            )
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun testRefreshOnChange_updatesOnMatchingEvent() =
        runTest(testDispatcher) {
            val events = MutableSharedFlow<WsEvent.ChangeEvent>()
            val viewModel = TestViewModel(events)
            try {
                // Let the init coroutine run so the collector actually subscribes
                // before the event fires (replay=0 — events before subscription
                // are dropped, matching production where VMs subscribe at
                // construction and events arrive afterwards).
                advanceUntilIdle()
                events.emit(WsEvent.ChangeEvent(ChangeEvents.CRON))
                advanceUntilIdle()
                assertEquals("refreshed", viewModel.state.value)
                assertEquals(1, viewModel.refreshCalls)
            } finally {
                viewModel.refreshJob.cancelAndJoin()
            }
        }

    @Test
    fun testRefreshOnChange_ignoresOtherEventTypes() =
        runTest(testDispatcher) {
            val events = MutableSharedFlow<WsEvent.ChangeEvent>()
            val viewModel = TestViewModel(events)
            try {
                advanceUntilIdle()
                events.emit(WsEvent.ChangeEvent(ChangeEvents.SESSIONS))
                advanceUntilIdle()
                assertEquals("initial", viewModel.state.value)
                assertEquals(0, viewModel.refreshCalls)
            } finally {
                viewModel.refreshJob.cancelAndJoin()
            }
        }
}
