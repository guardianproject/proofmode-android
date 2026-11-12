package org.witness.proofmode.filebase

import android.content.Context
import android.content.ContextWrapper
import android.content.res.Resources
import android.net.FakeUri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.witness.proofmode.storage.proofset.MediaInclusion

// Shared by the coordinator and Upload tap tests. Plain JUnit helpers: see
// FilebaseOnDemandCoordinatorTest for why these tests avoid Robolectric.

internal fun onDemandItem(hash: String) = FilebaseOnDemandItem(
    hash, FakeUri(), MediaInclusion.INCLUDE_MEDIA,
)

/** Eager dispatch so a listener callback is seen without a scheduler tick. */
internal fun TestScope.eagerScope() =
    CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler))

/** A `Context` that resolves any string id to a fixed text; nothing else works on it. */
internal fun fakeAppContext(): Context {
    val strings = @Suppress("DEPRECATION") object : Resources(null, null, null) {
        override fun getString(id: Int): String = "same-policy"
    }
    return object : ContextWrapper(null) {
        override fun getResources(): Resources = strings
    }
}

internal fun TestScope.collectUiEvents(into: MutableList<FilebaseOnDemandUiEvent>) {
    FilebaseOnDemandUiEvents.resetForTests()
    backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
        FilebaseOnDemandUiEvents.events.collect { into.add(it) }
    }
}
