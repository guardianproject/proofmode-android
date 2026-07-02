package org.witness.proofmode.plugins.lp.bridge

import android.content.Context
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import io.flutter.FlutterInjector
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.embedding.engine.FlutterEngineGroup
import io.flutter.embedding.engine.dart.DartExecutor
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object FlutterEngineProvider {
    // Feature flag guard: FlutterEngineProvider.init() is reached only through LocationProtocolPlugin.register,
    // which is app-gated by FeatureFlags.lpEnabled.
    private val lock = Any()

    @Volatile
    private var appContext: Context? = null
    private var observerRegistered = false
    private var engineGroup: FlutterEngineGroup? = null
    private var engine: FlutterEngine? = null
    private var bridgeInstance: FlutterEngineBridge? = null
    private var bridgeInitDeferred: CompletableDeferred<FlutterEngineBridge>? = null

    private val processObserver = object : DefaultLifecycleObserver {
        override fun onDestroy(owner: LifecycleOwner) {
            shutdown()
        }
    }

    fun init(context: Context) {
        val applicationContext = context.applicationContext
        synchronized(lock) {
            if (appContext == null) {
                appContext = applicationContext
            }

            if (!observerRegistered) {
                ProcessLifecycleOwner.get().lifecycle.addObserver(processObserver)
                observerRegistered = true
            }

            if (engineGroup == null) {
                engineGroup = FlutterEngineGroup(applicationContext)
            }
        }
    }

    suspend fun getBridge(
        context: Context,
        readyTimeoutMs: Long = FlutterEngineBridge.DEFAULT_READY_TIMEOUT_MS,
    ): FlutterEngineBridge {
        init(context)

        val readyBridge = synchronized(lock) { bridgeInstance }
        if (readyBridge != null) {
            return readyBridge
        }

        var shouldCreate = false
        val deferred = synchronized(lock) {
            bridgeInstance?.let { return@synchronized CompletableDeferred(it).apply { complete(it) } }
            bridgeInitDeferred?.let { return@synchronized it }
            shouldCreate = true
            CompletableDeferred<FlutterEngineBridge>().also { bridgeInitDeferred = it }
        }

        if (shouldCreate) {
            try {
                val contextRef = synchronized(lock) { appContext } ?: context.applicationContext
                val createdEngine = createAndRunWithEntrypoint(contextRef)
                val createdBridge = withContext(Dispatchers.Main) {
                    FlutterEngineBridge(
                        engine = createdEngine,
                        readyTimeoutMs = readyTimeoutMs,
                    )
                }

                synchronized(lock) {
                    engine = createdEngine
                    bridgeInstance = createdBridge
                    bridgeInitDeferred = null
                }
                deferred.complete(createdBridge)
            } catch (t: Throwable) {
                synchronized(lock) {
                    bridgeInitDeferred = null
                }
                deferred.completeExceptionally(t)
            }
        }

        return deferred.await()
    }

    fun shutdown() {
        val bridgeToShutdown: FlutterEngineBridge?
        val engineToDestroy: FlutterEngine?
        synchronized(lock) {
            bridgeToShutdown = bridgeInstance
            engineToDestroy = engine
            bridgeInstance = null
            engine = null
            bridgeInitDeferred = null
            engineGroup = null
            appContext = null
            if (observerRegistered) {
                ProcessLifecycleOwner.get().lifecycle.removeObserver(processObserver)
                observerRegistered = false
            }
        }

        bridgeToShutdown?.shutdown()
        engineToDestroy?.destroy()
    }

    private suspend fun createAndRunWithEntrypoint(context: Context): FlutterEngine {
        // Create engine on Main dispatcher (required by Flutter JNI @UiThread constraint).
        // NOTE: Both FlutterLoader.ensureInitializationComplete() and engine creation
        // must run on the main thread — ensureInitializationComplete enforces this internally.
        return withContext(Dispatchers.Main) {
            val flutterLoader = FlutterInjector.instance().flutterLoader()
            if (!flutterLoader.initialized()) {
                flutterLoader.startInitialization(context)
                flutterLoader.ensureInitializationComplete(context, emptyArray())
            }

            val entrypoint = DartExecutor.DartEntrypoint.createDefault()

            val group = synchronized(lock) {
                engineGroup ?: FlutterEngineGroup(context).also { engineGroup = it }
            }
            val engine = group.createAndRunEngine(context, entrypoint)
            
            engine
        }
    }
}
