package com.pennywiseai.tracker.core

import com.pennywiseai.tracker.utils.TokenUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.runBlocking

class ChatModelsTest {

    @Test
    fun byId_resolvesCustomSpec_whenConfigJsonProvided() = runBlocking {
        val customJson = """
            {"repoId": "custom-model", "fileName": "model.litertlm", "sha256": "abc123", "sizeBytes": 1000, "contextTokens": 4096, "minRamBytes": 4294967296, "displayName": "My Custom Model"}
        """.trimIndent()
        val spec = ChatModels.byId("custom-custom-model", customJson)
        assertEquals("custom-custom-model", spec.id)
        assertEquals("My Custom Model", spec.displayName)
        assertEquals(4096, spec.contextTokens)
    }

    @Test
    fun byId_fallsBackToDefault_forUnknownIds() = runBlocking {
        val spec = ChatModels.byId("unknown-model", null)
        assertEquals(ChatModels.DEFAULT.id, spec.id)
        
        // Also with custom config but unknown ID
        val customJson = """
            {"repoId": "custom-model", "fileName": "model.litertlm", "sha256": "abc123", "sizeBytes": 1000, "contextTokens": 4096, "minRamBytes": 4294967296, "displayName": "My Custom Model"}
        """.trimIndent()
        val spec2 = ChatModels.byId("another-unknown", customJson)
        assertEquals(ChatModels.DEFAULT.id, spec2.id)
    }

    @Test
    fun whisper_isNotChatCapable() = runBlocking {
        assertFalse(TokenUtils.isChatCapable(ChatModels.WHISPER_BASE))
        assertEquals(-2304, TokenUtils.chatBudget(ChatModels.WHISPER_BASE))
    }

    @Test
    fun qwen_isChatCapable() = runBlocking {
        assertTrue(TokenUtils.isChatCapable(ChatModels.QWEN_1_5B))
        assertEquals(1792, TokenUtils.chatBudget(ChatModels.QWEN_1_5B))
    }

    @Test
    fun gemma4_isChatCapable() = runBlocking {
        assertTrue(TokenUtils.isChatCapable(ChatModels.GEMMA_4_E2B))
        assertEquals(5888, TokenUtils.chatBudget(ChatModels.GEMMA_4_E2B))
    }

    @Test
    fun gemma3n_isChatCapable() = runBlocking {
        assertTrue(TokenUtils.isChatCapable(ChatModels.GEMMA_3N_E2B))
        assertEquals(5888, TokenUtils.chatBudget(ChatModels.GEMMA_3N_E2B))
    }

    @Test
    fun chatBudgetOfGemmaModelsIs5888() = runBlocking {
        assertEquals(5888, TokenUtils.chatBudget(ChatModels.GEMMA_4_E2B))
        assertEquals(5888, TokenUtils.chatBudget(ChatModels.GEMMA_3N_E2B))
    }

    @Test
    fun allBuiltInModelsExceptWhisperAreChatCapable() = runBlocking {
        val all = listOf(
            ChatModels.QWEN_1_5B,
            ChatModels.GEMMA_4_E2B,
            ChatModels.GEMMA_3N_E2B
        )
        all.forEach { spec ->
            assertTrue("Model ${spec.id} should be chat capable", TokenUtils.isChatCapable(spec))
        }
    }

    @Test
    fun chatCapableModels_excludesWhisper() = runBlocking {
        val capable = ChatModels.chatCapableModels(null)
        val ids = capable.map { it.id }
        assertTrue("Should contain Qwen", ids.contains(ChatModels.QWEN_1_5B.id))
        assertTrue("Should contain Gemma 4", ids.contains(ChatModels.GEMMA_4_E2B.id))
        assertTrue("Should contain Gemma 3n", ids.contains(ChatModels.GEMMA_3N_E2B.id))
        assertFalse("Should NOT contain Whisper", ids.contains(ChatModels.WHISPER_BASE.id))
    }

    @Test
    fun chatCapableModels_includesCustomWhenProvided() = runBlocking {
        val customJson = """
            {"repoId": "custom-chat-model", "fileName": "model.litertlm", "sha256": "abc123", "sizeBytes": 1000, "contextTokens": 4096, "minRamBytes": 4294967296, "displayName": "My Custom Chat Model"}
        """.trimIndent()

        val capable = ChatModels.chatCapableModels(customJson)
        val ids = capable.map { it.id }
        assertTrue("Should contain custom model", ids.contains("custom-custom-chat-model"))
    }
}