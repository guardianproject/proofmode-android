package org.witness.proofmode.plugins.lp.bridge

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import kotlin.system.measureTimeMillis

@RunWith(AndroidJUnit4::class)
class FlutterEngineBridgeTest {

    private class RecordingResult : MethodChannel.Result {
        var successValue: Any? = null
        var errorCode: String? = null
        var errorMessage: String? = null
        var errorDetails: Any? = null
        var notImplementedCalled: Boolean = false

        override fun success(result: Any?) {
            successValue = result
        }

        override fun error(errorCode: String, errorMessage: String?, errorDetails: Any?) {
            this.errorCode = errorCode
            this.errorMessage = errorMessage
            this.errorDetails = errorDetails
        }

        override fun notImplemented() {
            notImplementedCalled = true
        }
    }

    private fun invokeBridgeReady(
        bridge: FlutterEngineBridge,
    ): RecordingResult {
        val method = FlutterEngineBridge::class.java.getDeclaredMethod(
            "handleIncomingMethodCall",
            MethodCall::class.java,
            MethodChannel.Result::class.java,
        )
        method.isAccessible = true

        val result = RecordingResult()
        method.invoke(
            bridge,
            MethodCall(
                "bridge/ready",
                null,
            ),
            result,
        )
        return result
    }

    private fun handshakeResponseMap(result: RecordingResult): Map<*, *> {
        val response = result.successValue
        assertTrue(response is Map<*, *>)
        return response as Map<*, *>
    }

    @Test
    fun handshakeCompletesBeforeInvoke() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        FlutterEngineProvider.init(context)

        val bridge = FlutterEngineProvider.getBridge(context)
        val ready = async { bridge.awaitReady() }
        invokeBridgeReady(bridge)
        ready.await()

        val result = bridge.invokeMethod("ping", null)
        assertEquals("pong", result)
    }

    @Test
    fun bridgeReadyTransitionsToReadyState() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        FlutterEngineProvider.init(context)

        val bridge = FlutterEngineProvider.getBridge(context)
        assertEquals(FlutterEngineBridge.ReadinessState.PENDING_HANDSHAKE, bridge.readinessSnapshot())

        val result = invokeBridgeReady(bridge)
        val response = handshakeResponseMap(result)

        // Response should be an empty success map (minimal liveness signal)
        assertTrue(response.isEmpty() || response.keys.isEmpty())
        assertEquals(FlutterEngineBridge.ReadinessState.READY, bridge.readinessSnapshot())
    }

    @Test
    fun bridgeReadyIsIdempotentAfterReady() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        FlutterEngineProvider.init(context)

        val bridge = FlutterEngineProvider.getBridge(context)
        val first = invokeBridgeReady(bridge)
        assertEquals(FlutterEngineBridge.ReadinessState.READY, bridge.readinessSnapshot())

        val second = invokeBridgeReady(bridge)
        val secondResponse = handshakeResponseMap(second)
        // Still responds successfully (idempotent)
        assertTrue(secondResponse.isEmpty() || secondResponse.keys.isEmpty())
        assertEquals(FlutterEngineBridge.ReadinessState.READY, bridge.readinessSnapshot())
    }

    @Test
    fun preReadyPingCallIsRejectedDeterministically() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        FlutterEngineProvider.init(context)

        val bridge = FlutterEngineProvider.getBridge(context)

        try {
            bridge.invokeMethod("ping", null)
            throw AssertionError("Expected LPBridgeNotReadyException")
        } catch (e: LPBridgeNotReadyException) {
            assertTrue(e.message.orEmpty().contains("not ready yet"))
        }
    }

    @Test
    fun preReadyNonPingCallIsRejectedDeterministically() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        FlutterEngineProvider.init(context)

        val bridge = FlutterEngineProvider.getBridge(context)

        try {
            bridge.invokeMethod("send-transaction", mapOf("to" to "0xabc", "value" to "0x0"))
            throw AssertionError("Expected LPBridgeNotReadyException")
        } catch (e: LPBridgeNotReadyException) {
            assertTrue(e.message.orEmpty().contains("not ready yet"))
        }
    }

    @Test
    fun timeoutWhenDartNeverSignalsReady() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        FlutterEngineProvider.shutdown()
        FlutterEngineProvider.init(context)

        val bridge = FlutterEngineProvider.getBridge(
            context = context,
            readyTimeoutMs = 1_200L,
        )

        val elapsed = measureTimeMillis {
            try {
                bridge.awaitReady()
                throw AssertionError("Expected LPBridgeTimeoutException")
            } catch (e: LPBridgeTimeoutException) {
                assertTrue(e.message.orEmpty().contains("did not signal readiness"))
            }
        }

        assertTrue(elapsed >= 1_000L)
        assertTrue(elapsed <= 6_000L)
        FlutterEngineProvider.shutdown()
    }
}
