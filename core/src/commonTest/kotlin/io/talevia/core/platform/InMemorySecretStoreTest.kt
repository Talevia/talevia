package io.talevia.core.platform

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class InMemorySecretStoreTest {
    @Test
    fun readsSeededValues() = runTest {
        val store = InMemorySecretStore(mapOf("anthropic" to "sk-a", "openai" to "sk-o"))
        assertEquals("sk-a", store.get("anthropic"))
        assertEquals("sk-o", store.get("openai"))
        assertEquals(setOf("anthropic", "openai"), store.keys())
    }

    @Test
    fun putOverwritesAndRemoveDeletes() = runTest {
        val store = InMemorySecretStore(mapOf("anthropic" to "sk-a"))
        store.put("anthropic", "sk-b")
        assertEquals("sk-b", store.get("anthropic"))
        store.remove("anthropic")
        assertNull(store.get("anthropic"))
        assertTrue(store.keys().isEmpty())
    }

    @Test
    fun unknownKeyReturnsNull() = runTest {
        val store = InMemorySecretStore()
        assertNull(store.get("missing"))
    }
}
