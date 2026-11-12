package org.witness.proofmode.ui

import android.net.FakeUri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ProofsetActionBarLpEnqueueTest {
    @Test
    fun lpGeneratedItemsForManualEnqueue_dropsNonGeneratedStatuses() {
        val generatedUri = FakeUri()
        val items = listOf(
            ProofableItem("pending", FakeUri(), ProofStatus.PENDING),
            ProofableItem("generating", FakeUri(), ProofStatus.GENERATING),
            ProofableItem("generated", generatedUri, ProofStatus.GENERATED),
        )
        val result = lpGeneratedItemsForManualEnqueue(items)
        assertEquals(1, result.size)
        assertSame(generatedUri, result.single().uri)
    }

    @Test
    fun lpGeneratedItemsForManualEnqueue_returnsEmptyWhenNoGeneratedItems() {
        val items = listOf(
            ProofableItem("pending", FakeUri(), ProofStatus.PENDING),
            ProofableItem("generating", FakeUri(), ProofStatus.GENERATING),
        )
        assertTrue(lpGeneratedItemsForManualEnqueue(items).isEmpty())
    }

    @Test
    fun lpGeneratedItemsForManualEnqueue_returnsAllGeneratedItemsWithoutHashFiltering() {
        val uriA = FakeUri()
        val uriB = FakeUri()
        val items = listOf(
            ProofableItem("a", uriA, ProofStatus.GENERATED),
            ProofableItem("b", uriB, ProofStatus.GENERATED),
        )
        val result = lpGeneratedItemsForManualEnqueue(items)
        assertEquals(2, result.size)
        assertSame(uriA, result[0].uri)
        assertSame(uriB, result[1].uri)
    }
}
