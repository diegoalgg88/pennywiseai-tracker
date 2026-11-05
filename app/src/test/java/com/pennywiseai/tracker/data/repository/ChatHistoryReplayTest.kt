package com.pennywiseai.tracker.data.repository

import com.pennywiseai.tracker.core.ChatModels
import com.pennywiseai.tracker.data.database.dao.ChatDao
import com.pennywiseai.tracker.data.database.entity.ChatMessage
import com.pennywiseai.tracker.domain.service.LlmEvent
import com.pennywiseai.tracker.domain.service.LlmService
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import java.io.File
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/** A rebuilt conversation replays the saved chat, but not the message that is being sent. */
class ChatHistoryReplayTest {

    @Test
    fun `message being sent is not replayed as history`() = runTest {
        val saved = mutableListOf(
            ChatMessage(message = "system prompt", isUser = false, timestamp = 1, isSystemPrompt = true),
            ChatMessage(message = "coffee 120", isUser = true, timestamp = 2),
            ChatMessage(message = "Here's what I'll add", isUser = false, timestamp = 3)
        )
        val chatDao = mockk<ChatDao>(relaxed = true) {
            coEvery { getAllMessagesForContext() } answers { saved.sortedBy { it.timestamp } }
            coEvery { insertMessage(any()) } answers { saved += firstArg<ChatMessage>() }
        }
        val history = slot<List<Pair<String, Boolean>>>()
        // El engine vivo ya corre este modelo: la conversación se reconstruye sin reiniciarlo.
        val modelFile = File(".")
        val llmService = mockk<LlmService>(relaxed = true) {
            every { isInitialized() } returns true
            every { initializedModelPath() } returns modelFile.absolutePath
            every { hasActiveConversation() } returns false
            coEvery { createConversation(any(), capture(history), any(), any()) } returns Result.success(Unit)
            every { sendMessageEvents(any()) } returns flowOf(LlmEvent.Text("ok"))
        }
        val modelRepository = mockk<ModelRepository>(relaxed = true) {
            every { modelState } returns flowOf(ModelState.READY)
            // El guard de presupuesto del chat pide el spec del modelo activo.
            every { activeSpec() } returns ChatModels.QWEN_1_5B
            every { getModelFile() } returns modelFile
            coEvery { verifyModelIntegrity() } returns true
            // La conversación se reconstruye contra el modelo seleccionado.
            coEvery { refreshActiveModel() } returns Unit
        }
        val repository = LlmRepository(
            llmService = llmService,
            chatDao = chatDao,
            modelRepository = modelRepository,
            aiContextRepository = mockk(relaxed = true),
            userPreferencesRepository = mockk(relaxed = true),
            categoryRepository = mockk(relaxed = true),
            accountBalanceRepository = mockk(relaxed = true),
            transactionRepository = mockk(relaxed = true),
            budgetRepository = mockk(relaxed = true),
            ignoredAccountsStore = mockk(relaxed = true),
            context = mockk(relaxed = true)
        )

        repository.sendMessageStream("how much on food?").collect()

        assertEquals(listOf("coffee 120" to true, "Here's what I'll add" to false), history.captured)
    }
}
