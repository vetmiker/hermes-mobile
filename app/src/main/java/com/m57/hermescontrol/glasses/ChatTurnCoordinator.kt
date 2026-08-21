package com.m57.hermescontrol.glasses

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

enum class TurnSource { PHONE, VOICE, NOTIFICATION }

data class TurnRequest(
    val storedSessionId: String,
    val runtimeSessionId: String,
    val text: String,
    val source: TurnSource,
    val isStreaming: Boolean = false,
)

data class VoiceReservation internal constructor(
    val id: String,
    val storedSessionId: String,
    val runtimeSessionId: String,
)

data class TurnLease internal constructor(
    val id: String,
    val storedSessionId: String,
    val runtimeSessionId: String,
    val source: TurnSource,
)

data class TurnOutcome(
    val accepted: Boolean,
    val redirected: Boolean = false,
    val lease: TurnLease? = null,
)

interface TurnGateway {
    suspend fun submit(
        runtimeSessionId: String,
        text: String,
    )

    suspend fun redirect(
        runtimeSessionId: String,
        text: String,
    )
}

interface TurnStore {
    suspend fun persist(
        storedSessionId: String,
        text: String,
    )
}

/**
 * One process-scoped authority for all plain-text user turns. UI-only work
 * (attachments, slash parsing, rendering) deliberately remains in ChatViewModel.
 */
class ChatTurnCoordinator(
    private val gateway: TurnGateway,
    private val store: TurnStore,
) {
    private val mutex = Mutex()
    private var reservedVoice: VoiceReservation? = null
    private var activeLease: TurnLease? = null

    suspend fun reserveVoice(
        storedSessionId: String,
        runtimeSessionId: String,
        utteranceId: String,
    ): VoiceReservation =
        mutex.withLock {
            VoiceReservation(
                id = "$utteranceId:${UUID.randomUUID()}",
                storedSessionId = storedSessionId,
                runtimeSessionId = runtimeSessionId,
            ).also { reservedVoice = it }
        }

    suspend fun claimPhonePriority(
        storedSessionId: String,
        runtimeSessionId: String,
    ): Boolean =
        mutex.withLock {
            val reservation = reservedVoice
            if (reservation != null &&
                reservation.storedSessionId == storedSessionId &&
                reservation.runtimeSessionId == runtimeSessionId
            ) {
                reservedVoice = null
            }
            activeLease?.let {
                it.storedSessionId == storedSessionId && it.runtimeSessionId == runtimeSessionId
            } ?: true
        }

    suspend fun discardVoice(reservation: VoiceReservation) {
        mutex.withLock {
            if (reservedVoice == reservation) reservedVoice = null
        }
    }

    suspend fun commitVoice(
        reservation: VoiceReservation,
        text: String,
    ): TurnOutcome =
        mutex.withLock {
            if (reservedVoice != reservation) return@withLock TurnOutcome(false)
            reservedVoice = null
            submitLocked(
                TurnRequest(
                    reservation.storedSessionId,
                    reservation.runtimeSessionId,
                    text,
                    TurnSource.VOICE,
                ),
            )
        }

    suspend fun submit(request: TurnRequest): TurnOutcome =
        mutex.withLock {
            submitLocked(request)
        }

    private suspend fun submitLocked(request: TurnRequest): TurnOutcome {
        if (request.text.isBlank()) return TurnOutcome(false)
        if (request.source == TurnSource.PHONE) {
            reservedVoice
                ?.takeIf {
                    it.storedSessionId == request.storedSessionId &&
                        it.runtimeSessionId == request.runtimeSessionId
                }
                ?.also { reservedVoice = null }
        }
        val redirect = request.source == TurnSource.PHONE && request.isStreaming
        val existingLease = activeLease
        if (existingLease != null) {
            if (!redirect || existingLease.runtimeSessionId != request.runtimeSessionId) {
                return TurnOutcome(false)
            }
            gateway.redirect(request.runtimeSessionId, request.text)
            store.persist(request.storedSessionId, request.text)
            return TurnOutcome(accepted = true, redirected = true, lease = existingLease)
        }

        val lease =
            TurnLease(
                UUID.randomUUID().toString(),
                request.storedSessionId,
                request.runtimeSessionId,
                request.source,
            )
        activeLease = lease
        try {
            if (redirect) {
                gateway.redirect(request.runtimeSessionId, request.text)
            } else {
                gateway.submit(request.runtimeSessionId, request.text)
            }
            store.persist(request.storedSessionId, request.text)
            return TurnOutcome(accepted = true, redirected = redirect, lease = lease)
        } catch (error: Exception) {
            if (activeLease == lease) activeLease = null
            throw error
        }
    }

    suspend fun completeTerminalForRuntime(
        runtimeSessionId: String,
        terminalText: String,
    ): TurnLease? =
        mutex.withLock {
            val lease = activeLease ?: return@withLock null
            if (terminalText.isBlank() || lease.runtimeSessionId != runtimeSessionId) return@withLock null
            activeLease = null
            lease
        }

    suspend fun completeTerminal(
        lease: TurnLease,
        runtimeSessionId: String,
        terminalText: String,
    ): Boolean =
        mutex.withLock {
            if (
                terminalText.isBlank() ||
                activeLease != lease ||
                lease.runtimeSessionId != runtimeSessionId
            ) {
                return@withLock false
            }
            activeLease = null
            true
        }
}
