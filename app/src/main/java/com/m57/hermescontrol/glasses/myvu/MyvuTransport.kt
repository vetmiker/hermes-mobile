package com.m57.hermescontrol.glasses.myvu

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.os.RemoteException
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.UUID

private const val TRANSPORT_TAG = "HermesMyvuTransport"

/** Values observed from the installed stock MYVU international launcher. */
object MyvuProtocol {
    const val STOCK_PACKAGE = "com.upuphone.star.launcher.intl"
    const val COMMON_SERVICE_ACTION = "com.upuphone.xr.interconnect.CommonService"
    const val LAUNCHER_RECEIVER = "com.upuphone.star.launcher"
    const val MESSAGE_TRANSPORT_SERVICE = 2

    private const val AGGREGATE_DESCRIPTOR = "com.upuphone.xr.interconnect.common.ICommonAggregate"
    internal const val CLIENT_DESCRIPTOR = "com.upuphone.xr.interconnect.common.IClient"
    private const val TRANSPORT_DESCRIPTOR = "com.upuphone.xr.interconnect.common.IMessageTransport"
    private const val AGGREGATE_QUERY_TRANSACTION = 1
    private const val AGGREGATE_REGISTER_TRANSACTION = 2
    private const val TRANSPORT_SEND_MESSAGE_TRANSACTION = 5

    internal fun queryMessageTransport(aggregate: IBinder): IBinder? =
        transact(
            "queryMessageTransport",
            aggregate,
            AGGREGATE_DESCRIPTOR,
            AGGREGATE_QUERY_TRANSACTION,
            { data -> data.writeInt(MESSAGE_TRANSPORT_SERVICE) },
            { reply -> reply.readStrongBinder() },
        )

    internal fun registerClient(
        aggregate: IBinder,
        packageName: String,
        client: IBinder,
    ) {
        transact(
            "registerClient",
            aggregate,
            AGGREGATE_DESCRIPTOR,
            AGGREGATE_REGISTER_TRANSACTION,
            { data ->
                data.writeStrongBinder(client)
                data.writeString(packageName)
            },
            {},
        )
    }

    internal fun sendMessage(
        transport: IBinder,
        receiverPackage: String,
        senderPackage: String,
        payload: String,
    ): String? =
        transact(
            "sendMessage:$senderPackage->$receiverPackage",
            transport,
            TRANSPORT_DESCRIPTOR,
            TRANSPORT_SEND_MESSAGE_TRANSACTION,
            { data ->
                data.writeInt(1)
                data.writeString(UUID.randomUUID().toString())
                data.writeString(senderPackage)
                data.writeString(receiverPackage)
                data.writeString(payload)
                data.writeByteArray(null)
                data.writeString(null)
                data.writeInt(1)
                data.writeInt(0)
                data.writeStrongBinder(null)
            },
            { reply -> reply.readString() },
        )

    private fun <T> transact(
        operation: String,
        binder: IBinder,
        descriptor: String,
        transaction: Int,
        write: (Parcel) -> Unit,
        read: (Parcel) -> T,
    ): T {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(descriptor)
            write(data)
            if (!binder.transact(transaction, data, reply, 0)) {
                throw RemoteException("MYVU $operation transaction $transaction was rejected")
            }
            reply.readException()
            return read(reply).also {
                Log.i(TRANSPORT_TAG, "MYVU_TRANSPORT operation=$operation transaction=$transaction completed")
            }
        } finally {
            data.recycle()
            reply.recycle()
        }
    }
}

sealed interface MyvuTransportState {
    data object Idle : MyvuTransportState

    data object Binding : MyvuTransportState

    data object Ready : MyvuTransportState

    data class Failed(val reason: String) : MyvuTransportState

    data object Disconnected : MyvuTransportState
}

/**
 * Owns exactly one explicit stock-service bind. It deliberately does not
 * attempt to create or replace a MYVU glasses session; the stock app remains
 * the connection owner.
 */
class MyvuTransport(
    private val context: Context,
) {
    private val _state = MutableStateFlow<MyvuTransportState>(MyvuTransportState.Idle)
    val state: StateFlow<MyvuTransportState> = _state

    private var aggregate: IBinder? = null
    private var transport: IBinder? = null
    private var isBound = false
    private val client = ClientCallback()

    private val deathRecipient = IBinder.DeathRecipient(::onBinderDied)
    private var isDeathLinked = false
    private val connection =
        object : ServiceConnection {
            override fun onServiceConnected(
                name: ComponentName,
                service: IBinder,
            ) {
                Log.i(TRANSPORT_TAG, "MYVU_TRANSPORT connected component=$name")
                try {
                    MyvuProtocol.registerClient(service, context.packageName, client)
                    val messageTransport =
                        MyvuProtocol.queryMessageTransport(service)
                            ?: throw RemoteException("MYVU message transport unavailable")
                    aggregate = service
                    transport = messageTransport
                    service.linkToDeath(deathRecipient, 0)
                    isDeathLinked = true
                    _state.value = MyvuTransportState.Ready
                    Log.i(TRANSPORT_TAG, "MYVU_TRANSPORT ready transport=$messageTransport")
                } catch (error: Exception) {
                    transport = null
                    _state.value = MyvuTransportState.Failed("Stock MYVU binding failed")
                    Log.e(TRANSPORT_TAG, "MYVU_TRANSPORT binding failed", error)
                }
            }

            override fun onServiceDisconnected(name: ComponentName) {
                Log.w(TRANSPORT_TAG, "MYVU_TRANSPORT disconnected component=$name")
                onBinderDied()
            }
        }

    fun bind(): Boolean {
        if (isBound) return transport != null
        _state.value = MyvuTransportState.Binding
        val intent = Intent(MyvuProtocol.COMMON_SERVICE_ACTION).setPackage(MyvuProtocol.STOCK_PACKAGE)
        isBound = context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        Log.i(TRANSPORT_TAG, "MYVU_TRANSPORT bind requested accepted=$isBound")
        if (!isBound) _state.value = MyvuTransportState.Failed("Stock MYVU service is unavailable")
        return isBound
    }

    fun send(command: MyvuDisplayCommand): Result<String?> =
        runCatching {
            val current = transport ?: throw IllegalStateException("Stock MYVU transport is not ready")
            MyvuProtocol.sendMessage(current, command.receiverPackage, command.senderPackage, command.payload)
        }.onFailure {
            _state.value = MyvuTransportState.Failed("Stock MYVU display delivery failed")
        }

    fun unbind() {
        runCatching { if (isDeathLinked) aggregate?.unlinkToDeath(deathRecipient, 0) }
        isDeathLinked = false
        aggregate = null
        transport = null
        if (isBound) context.unbindService(connection)
        isBound = false
        _state.value = MyvuTransportState.Idle
    }

    private fun onBinderDied() {
        isDeathLinked = false
        aggregate = null
        transport = null
        _state.value = MyvuTransportState.Disconnected
    }

    private class ClientCallback : Binder() {
        init {
            attachInterface(null, MyvuProtocol.CLIENT_DESCRIPTOR)
        }

        override fun onTransact(
            code: Int,
            data: Parcel,
            reply: Parcel?,
            flags: Int,
        ): Boolean =
            when (code) {
                INTERFACE_TRANSACTION -> {
                    reply?.writeString(MyvuProtocol.CLIENT_DESCRIPTOR)
                    true
                }

                CONNECT_SUCCESS_TRANSACTION -> {
                    data.enforceInterface(MyvuProtocol.CLIENT_DESCRIPTOR)
                    Log.i(TRANSPORT_TAG, "MYVU_TRANSPORT client connectSuccess")
                    true
                }

                else -> super.onTransact(code, data, reply, flags)
            }

        private companion object {
            const val CONNECT_SUCCESS_TRANSACTION = 1
        }
    }
}
