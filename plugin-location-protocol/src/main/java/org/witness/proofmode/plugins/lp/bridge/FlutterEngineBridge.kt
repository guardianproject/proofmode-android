package org.witness.proofmode.plugins.lp.bridge

import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withTimeout
import timber.log.Timber
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class FlutterEngineBridge(
    private val engine: FlutterEngine,
    private val readyTimeoutMs: Long = DEFAULT_READY_TIMEOUT_MS,
) : LPBridgeMessenger {

    private object NullResultSentinel

    companion object {
        internal const val DEFAULT_READY_TIMEOUT_MS = 30_000L
        private const val INVOKE_TIMEOUT_MS = 60_000L
    }

    internal enum class ReadinessState {
        PENDING_HANDSHAKE,
        READY,
    }

    private val readinessDeferred = CompletableDeferred<Unit>()
    private val readinessLock = Any()
    @Volatile
    private var readinessState = ReadinessState.PENDING_HANDSHAKE
    private val channel = MethodChannel(
        engine.dartExecutor.binaryMessenger,
        WalletBridgeChannels.CHANNEL_LOCATION_PROTOCOL_METHOD
    )

    init {
        Timber.d(
            "FlutterEngineBridge: initializing method channel %s",
            WalletBridgeChannels.CHANNEL_LOCATION_PROTOCOL_METHOD
        )
        channel.setMethodCallHandler(::handleIncomingMethodCall)
        Timber.d("FlutterEngineBridge: method call handler registered, waiting for Dart readiness signal")
    }

    override suspend fun awaitReady() {
        if (readinessDeferred.isCompleted) return

        Timber.i("FlutterEngineBridge: awaiting Dart readiness signal (timeout %d ms)...", readyTimeoutMs)
        try {
            withTimeout(readyTimeoutMs) {
                readinessDeferred.await()
            }
            Timber.i("FlutterEngineBridge: Dart readiness signal received")
        } catch (e: TimeoutCancellationException) {
            Timber.e("FlutterEngineBridge: timeout waiting for Dart readiness within %d ms", readyTimeoutMs)
            throw LPBridgeTimeoutException(
                "Flutter engine did not signal readiness within $readyTimeoutMs ms"
            )
        }
    }

    override suspend fun invokeMethod(method: String, arguments: Any?): Any? {
        return invokeMethodWithTimeout(method, arguments, INVOKE_TIMEOUT_MS)
    }

    private suspend fun invokeMethodWithTimeout(method: String, arguments: Any?, timeoutMs: Long): Any? {
        return invokeMethodWithTimeout(method, arguments, timeoutMs, allowDuringHandshake = false)
    }

    private suspend fun invokeMethodWithTimeout(
        method: String,
        arguments: Any?,
        timeoutMs: Long,
        allowDuringHandshake: Boolean,
    ): Any? {
        if (!allowDuringHandshake) {
            requireBridgeReady(method)
        }

        val response = withTimeoutOrNull(timeoutMs) {
            withContext(Dispatchers.Main) {
                suspendCancellableCoroutine { continuation ->
                    channel.invokeMethod(method, arguments, object : MethodChannel.Result {
                        override fun success(result: Any?) {
                            if (continuation.isActive) {
                                continuation.resume(result ?: NullResultSentinel)
                            }
                        }

                        override fun error(errorCode: String, errorMessage: String?, errorDetails: Any?) {
                            if (continuation.isActive) {
                                continuation.resumeWithException(
                                    LPBridgeMethodException(
                                        "Method '$method' failed: $errorCode ${errorMessage ?: ""}".trim(),
                                        errorDetails as? Throwable
                                    )
                                )
                            }
                        }

                        override fun notImplemented() {
                            if (continuation.isActive) {
                                continuation.resumeWithException(
                                    LPBridgeMethodException("Method '$method' not implemented on Flutter side")
                                )
                            }
                        }
                    })
                }
            }
        }

        if (response == null) {
            throw LPBridgeTimeoutException(
                "Timed out invoking bridge method '$method' within $timeoutMs ms"
            )
        }

        return if (response === NullResultSentinel) null else response
    }

    internal fun readinessSnapshot(): ReadinessState = currentReadinessState()

    private fun requireBridgeReady(method: String) {
        when (currentReadinessState()) {
            ReadinessState.READY -> return
            ReadinessState.PENDING_HANDSHAKE -> throw LPBridgeNotReadyException(
                "Flutter bridge is not ready yet for method '$method'"
            )
        }
    }

    private fun currentReadinessState(): ReadinessState = synchronized(readinessLock) {
        readinessState
    }

    private fun setReadinessState(state: ReadinessState) {
        synchronized(readinessLock) {
            readinessState = state
        }
    }

    internal fun shutdown() {
        channel.setMethodCallHandler(null)
    }

    private fun handleIncomingMethodCall(call: MethodCall, result: MethodChannel.Result) {
        when (call.method) {
            "bridge/ready" -> {
                Timber.i("FlutterEngineBridge: received bridge/ready method call from Dart")
                if (currentReadinessState() == ReadinessState.READY) {
                    Timber.d("FlutterEngineBridge: bridge/ready is idempotent, already ready")
                    result.success(mapOf<String, Any?>())
                    return
                }

                setReadinessState(ReadinessState.READY)
                if (!readinessDeferred.isCompleted) {
                    readinessDeferred.complete(Unit)
                }
                Timber.i("FlutterEngineBridge: bridge readiness state transitioned to READY")
                result.success(mapOf<String, Any?>())
            }

            else -> {
                Timber.w("FlutterEngineBridge: unhandled method call: %s", call.method)
                result.notImplemented()
            }
        }
    }
}
