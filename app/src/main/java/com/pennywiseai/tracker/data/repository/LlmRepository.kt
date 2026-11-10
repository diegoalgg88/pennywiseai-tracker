package com.pennywiseai.tracker.data.repository

import android.content.Context
import android.content.res.Configuration
import android.util.Log
import com.pennywiseai.tracker.data.database.dao.ChatDao
import com.pennywiseai.tracker.data.database.entity.ChatMessage
import com.pennywiseai.tracker.data.database.entity.SubscriptionState
import com.pennywiseai.tracker.data.database.entity.TransactionType
import com.pennywiseai.tracker.data.model.ChatContext
import com.pennywiseai.tracker.data.preferences.IgnoredAccountsStore
import com.pennywiseai.tracker.data.preferences.UserPreferencesRepository
import com.pennywiseai.tracker.core.ChatModelSpec
import com.pennywiseai.tracker.core.ChatModels
import com.pennywiseai.tracker.core.ThinkingConfig
import com.pennywiseai.tracker.domain.service.LlmService
import com.pennywiseai.shared.domain.mapping.SharedCategoryMapping
import com.pennywiseai.tracker.data.model.TransactionDraft
import com.pennywiseai.tracker.data.database.entity.TransactionEntity
import com.pennywiseai.tracker.data.model.TransactionFinder
import com.pennywiseai.tracker.data.model.PendingChatAction
import com.pennywiseai.tracker.data.service.PennyWiseTools
import com.pennywiseai.tracker.domain.service.ChatError
import com.pennywiseai.tracker.domain.service.LlmEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import com.pennywiseai.tracker.utils.CurrencyFormatter
import com.pennywiseai.tracker.utils.TokenUtils
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import java.math.BigDecimal
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import dagger.hilt.android.qualifiers.ApplicationContext
import com.pennywiseai.tracker.BuildConfig
import com.pennywiseai.tracker.R

@Singleton
open class LlmRepository @Inject constructor(
    private val llmService: LlmService,
    private val chatDao: ChatDao,
    private val modelRepository: ModelRepository,
    private val aiContextRepository: AiContextRepository,
    private val userPreferencesRepository: UserPreferencesRepository,
    private val categoryRepository: CategoryRepository,
    private val accountBalanceRepository: AccountBalanceRepository,
    private val transactionRepository: TransactionRepository,
    private val budgetRepository: BudgetRepository,
    private val ignoredAccountsStore: IgnoredAccountsStore,
    private val exchangeRateProvider: com.pennywiseai.tracker.data.currency.CurrencyConversionService,
    private val subscriptionRepository: com.pennywiseai.tracker.data.repository.SubscriptionRepository,
    @ApplicationContext private val context: Context
) {

    // True while a reply streams: updateSystemPrompt() defers closing the conversation until it ends.
    @Volatile private var generating = false

    // A transaction the model proposed via the add_transaction tool (#170),
    // waiting for the user's confirmation on the chat screen. Never auto-saved.
    private val _pendingAction = MutableStateFlow<PendingChatAction?>(null)
    val pendingAction: StateFlow<PendingChatAction?> = _pendingAction.asStateFlow()

    fun clearPendingAction() { _pendingAction.value = null }

    /** Logging tag. */
    private val TAG = "LlmRepository"

    /** Adds an assistant line to the history (e.g. "Added ₹120 at Starbucks"). */
    suspend fun appendAssistantMessage(text: String) {
        chatDao.insertMessage(ChatMessage(message = text, isUser = false))
    }

    fun getAllMessages(): Flow<List<ChatMessage>> = chatDao.getAllMessages()

    fun getAllMessagesIncludingSystem(): Flow<List<ChatMessage>> = chatDao.getAllMessagesIncludingSystem()

    suspend fun sendMessage(userMessage: String): Result<String> {
        val userChatMessage = ChatMessage(
            message = userMessage,
            isUser = true
        )
        chatDao.insertMessage(userChatMessage)

        ensureConversation()

        // Use synchronous collection for non-streaming path
        val responseBuilder = StringBuilder()
        try {
            llmService.sendMessage(userMessage).collect { partial ->
                responseBuilder.append(partial)
            }
        } catch (e: Exception) {
            return Result.failure(e)
        }

        val response = responseBuilder.toString()
        val aiChatMessage = ChatMessage(
            message = response,
            isUser = false
        )
        chatDao.insertMessage(aiChatMessage)

        return Result.success(response)
    }

    suspend fun sendMessageStream(userMessage: String): Flow<LlmEvent> {
        val existingMessages = chatDao.getAllMessagesForContext()
        val isNewChat = existingMessages.isEmpty()

        // If new chat, add system prompt first. Always rebuilt: a cached one
        // carries a stale date, figures and lists — and, when oversized, used to
        // block every new chat until something refreshed the cache (#959).
        if (isNewChat) {
            val systemPrompt = buildSystemPrompt(aiContextRepository.getChatContext(), modelRepository.activeSpec())
            userPreferencesRepository.updateSystemPrompt(systemPrompt)

            val systemMessage = ChatMessage(
                message = systemPrompt,
                isUser = false,
                isSystemPrompt = true
            )
            chatDao.insertMessage(systemMessage)
            Log.d(TAG, "System prompt added to new chat")
        }

        // Estimate tokens from all messages
        val currentMessages = chatDao.getAllMessagesForContext()
        requireFitsChatBudget(currentMessages, userMessage)

        // Check if model is downloading
        requireModelNotDownloading()

        // Save user message first (UX: appears instantly)
        val userChatMessage = ChatMessage(
            message = userMessage,
            isUser = true
        )
        if (BuildConfig.DEBUG) Log.d(TAG, "Saving user message: ${userMessage.take(50)}...")
        chatDao.insertMessage(userChatMessage)

        // Ensure engine is initialized and conversation is active.
        // Pass excludeMessageId so the message being sent isn't replayed as history.
        // A failure here must not leave the user row without an answer (history alternation).
        val events = try {
            ensureConversation(userChatMessage.id)
            llmService.sendMessageEvents(userMessage)
        } catch (t: Throwable) {
            chatDao.deleteById(userChatMessage.id)
            throw t
        }

        Log.d(TAG, "=== SENDING TO LLM ===")
        Log.d(TAG, "Total messages in context: ${currentMessages.size}")

        return respond(userMessage, events, "text", userChatMessage.id)
    }

    /**
     * Send a message with an image for vision tasks (receipt OCR → addTransaction).
     * Only works when the active model is Gemma 3n E2B (vision capable).
     * The user's message is sent along with the image so the model has context.
     */
    suspend fun sendMessageWithImage(userMessage: String, imageUri: android.net.Uri): Flow<LlmEvent> = sendMedia(
        uri = imageUri,
        prefix = "image",
        defaultExtension = "jpg",
        markerRes = R.string.chat_user_photo_message,
        unsupported = ChatError.VisionRequiresGemma3n(),
        label = "image",
        userMessage = userMessage
    ) { file -> llmService.sendMessageWithImage(userMessage, file) }

    /**
     * Envía un mensaje con audio para transcripción (nota de voz → addTransaction).
     * Solo funciona con Gemma 3n E2B (multimodal nativo audio + visión).
     * Whisper Base es ASR-only y no es modelo de chat (W3).
     * El mensaje del usuario se envía junto con el audio para dar contexto.
     */
    suspend fun sendMessageWithAudio(userMessage: String, audioUri: android.net.Uri): Flow<LlmEvent> = sendMedia(
        uri = audioUri,
        prefix = "audio",
        defaultExtension = "wav",
        markerRes = R.string.chat_user_voice_message,
        unsupported = ChatError.AudioRequiresGemma3n(),
        label = "audio",
        userMessage = userMessage
    ) { file -> llmService.sendMessageWithAudio(userMessage, file) }

    /**
     * Shared path for photo and voice input. The user gets a localized marker row (no
     * media is stored, so the schema stays untouched), the media is copied to app
     * storage, and the temporary copy is deleted only once the stream has been
     * consumed: deleting it earlier removes the file before the engine opens it.
     */
    private suspend fun sendMedia(
        uri: android.net.Uri,
        prefix: String,
        defaultExtension: String,
        @androidx.annotation.StringRes markerRes: Int,
        unsupported: ChatError,
        label: String,
        userMessage: String,
        send: (android.net.Uri) -> Flow<LlmEvent>
    ): Flow<LlmEvent> {
        modelRepository.refreshActiveModel()
        if (!ChatModels.isMultimodal(modelRepository.activeSpec())) throw unsupported
        requireModelNotDownloading()
        requireFitsChatBudget(chatDao.getAllMessagesForContext(), userMessage)

        val userChatMessage = ChatMessage(message = userMessage, isUser = true)
        chatDao.insertMessage(userChatMessage)

        // LiteRT-LM opens media by file path, so content:// URIs (gallery picker,
        // camera FileProvider) must be copied into app storage first.
        val media = try {
            materializeMedia(uri, prefix, defaultExtension)
        } catch (t: Throwable) {
            chatDao.deleteById(userChatMessage.id)
            throw t
        }

        return flow {
            try {
                // The marker row is excluded from the replay: it is the message being sent.
                ensureConversation(userChatMessage.id)
                Log.d(TAG, "=== SENDING TO LLM ($label) ===")
                emitAll(respond(userMessage, send(android.net.Uri.fromFile(media.file)), label, userChatMessage.id))
            } catch (t: Throwable) {
                // respond() cleans up after a failed stream; this covers a failure before it.
                if (chatDao.getAllMessagesForContext().lastOrNull()?.id == userChatMessage.id) {
                    chatDao.deleteById(userChatMessage.id)
                }
                throw t
            } finally {
                media.deleteIfTemporary()
            }
        }
    }

    /**
     * Common handler for all streaming responses: emits text/thinking events,
     * collects tool calls, processes them, saves the final assistant message.
     * Tool calls are resolved internally and NOT emitted downstream.
     *
     * On failure or cancellation the partial reply is kept (or, with none, the
     * unanswered user row is removed) and the engine conversation is closed so the
     * next send rebuilds it from Room: history keeps alternating user/assistant.
     */
    private fun respond(
        heuristicsText: String,
        upstream: Flow<LlmEvent>,
        logLabel: String,
        userRowId: String? = null
    ): Flow<LlmEvent> = flow {
        generating = true
        val responseBuilder = StringBuilder()
        try {
            val toolCalls = mutableListOf<LlmEvent.ToolCall>()
            val thinkingBuffer = StringBuilder()

            upstream.collect { event ->
                when (event) {
                    is LlmEvent.Text -> { responseBuilder.append(event.delta); emit(event) }
                    is LlmEvent.Thinking -> { thinkingBuffer.append(event.delta); emit(event) }
                    is LlmEvent.ToolCall -> {
                        // Solo el nombre del tool y las claves: los valores traen merchant y monto (PII).
                        Log.d(TAG, "Tool call received: ${event.name} args=${event.arguments.keys}")
                        toolCalls.add(event)
                    }
                }
            }
            // Process tool calls and build final response
            // For media (image/audio), heuristicsText is empty — we don't want the prompt as sourceText
            val finalResponse = handleResponseAndEmit(responseBuilder, toolCalls, thinkingBuffer, heuristicsText)
            // Save the complete AI response
            chatDao.insertMessage(ChatMessage(message = finalResponse, isUser = false))
            Log.d(TAG, "AI response saved ($logLabel)")
        } catch (t: Throwable) {
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                if (responseBuilder.isNotBlank()) {
                    chatDao.insertMessage(ChatMessage(message = responseBuilder.toString(), isUser = false))
                } else {
                    userRowId?.let { chatDao.deleteById(it) }
                }
                llmService.closeConversation()
            }
            throw t
        } finally {
            generating = false
        }
    }

    /**
     * Processes tool calls and returns the final response string.
     * This is a regular function (not a flow builder) so it can call suspend functions.
     * The caller is responsible for emitting the text chunks.
     */
    private suspend fun handleResponseAndEmit(
        responseBuilder: StringBuilder,
        toolCalls: MutableList<LlmEvent.ToolCall>,
        thinkingBuffer: StringBuilder,
        userMessage: String
    ): String {
        // Log thinking tokens if present (developer mode)
        if (thinkingBuffer.isNotEmpty()) {
            Log.d(TAG, "Thinking tokens captured: ${thinkingBuffer.length} chars")
        }
        // Modelos edge a veces narran el nombre del tool como texto plano en vez de
        // emitir el tool call estructurado (patrón LiteRT-LM #2202, observado con
        // gemma-4-E2B: la respuesta quedó como "monthSummary" crudo). Detectar esa
        // narración y sintetizar el call que el modelo quiso hacer.
        if (toolCalls.isEmpty()) {
            NARRATED_TOOL.matchEntire(responseBuilder.toString().trim())?.let { m ->
                val name = m.groupValues[1]
                val known = listOf(
                    PennyWiseTools.ADD_TRANSACTION, PennyWiseTools.DELETE_TRANSACTION,
                    PennyWiseTools.UPDATE_TRANSACTION, PennyWiseTools.MONTH_SUMMARY,
                    PennyWiseTools.SPENDING_BY_CATEGORY, PennyWiseTools.BUDGET_STATUS,
                    PennyWiseTools.CATEGORY_BREAKDOWN, PennyWiseTools.RECENT_TRANSACTIONS,
                    PennyWiseTools.ACCOUNT_BALANCE, PennyWiseTools.SPENDING_FORECAST
                )
                val snake = known.firstOrNull { it.equals(name, ignoreCase = true) || it.equals(name.replace(Regex("([A-Z])")) { "_" + it.value.lowercase() }, ignoreCase = true) }
                if (snake != null) {
                    Log.w(TAG, "Model narrated tool '$name' as text — synthesizing $snake call")
                    responseBuilder.clear()
                    toolCalls.add(LlmEvent.ToolCall(snake, emptyMap()))
                }
            }
        }
        // Fallback 2: some models emit the tool call as a JSON object in the text
        // stream (e.g., {"name": "spending_by_category", "arguments": {...}}).
        // Try to extract it if we still have no structured tool calls.
        if (toolCalls.isEmpty()) {
            val text = responseBuilder.toString().trim()
            Log.d(TAG, "JSON fallback: attempting extraction from text (len=${text.length})")
            val jsonToolCall = Regex(
                """\{\s*"name"\s*:\s*"([A-Za-z_][A-Za-z0-9_]*)"\s*,\s*"arguments"\s*:\s*(\{.*?\})\s*\}""",
                RegexOption.DOT_MATCHES_ALL
            ).find(text)
            jsonToolCall?.let { m ->
                val name = m.groupValues[1]
                val argsJson = m.groupValues[2]
                Log.d(TAG, "JSON fallback: matched name='$name' argsJson='$argsJson'")
                val known = listOf(
                    PennyWiseTools.ADD_TRANSACTION, PennyWiseTools.DELETE_TRANSACTION,
                    PennyWiseTools.UPDATE_TRANSACTION, PennyWiseTools.MONTH_SUMMARY,
                    PennyWiseTools.SPENDING_BY_CATEGORY, PennyWiseTools.BUDGET_STATUS,
                    PennyWiseTools.CATEGORY_BREAKDOWN, PennyWiseTools.RECENT_TRANSACTIONS,
                    PennyWiseTools.ACCOUNT_BALANCE, PennyWiseTools.SPENDING_FORECAST,
                    PennyWiseTools.SEARCH_TRANSACTIONS, PennyWiseTools.SPENDING_TREND,
                    PennyWiseTools.SUBSCRIPTION_SUMMARY, PennyWiseTools.EXCHANGE_RATE,
                    PennyWiseTools.CATEGORY_STATS
                )
                val snake = known.firstOrNull { it.equals(name, ignoreCase = true) }
                // CamelCase → snake_case: "monthSummary" → "month_summary"
                ?: known.firstOrNull { it.equals(name.replace(Regex("([A-Z])")) { "_" + it.value.lowercase() }, ignoreCase = true) }
                if (snake != null) {
                    val args = try {
                        val jsonElement = kotlinx.serialization.json.Json.decodeFromString<kotlinx.serialization.json.JsonElement>(argsJson)
                        // Convert JsonElement to Map<String, Any?>
                        if (jsonElement is kotlinx.serialization.json.JsonObject) {
                            jsonElement.entries.associate { it.key to jsonElementToAny(it.value) }
                        } else {
                            emptyMap<String, Any?>()
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "JSON fallback: failed to decode argsJson='$argsJson'", e)
                        emptyMap<String, Any?>()
                    }
                    Log.w(TAG, "Model emitted tool '$name' as JSON in text — synthesizing $snake call with args: ${args.keys}")
                    responseBuilder.clear()
                    toolCalls.add(LlmEvent.ToolCall(snake, args))
                }
            }
            if (jsonToolCall == null) {
                Log.d(TAG, "JSON fallback: no match in text")
            }
        }
        for (call in toolCalls) {
            Log.d(TAG, "Processing tool call: ${call.name} args=${call.arguments.keys}")
            val line = handleToolCall(call, userMessage) ?: continue
            if (BuildConfig.DEBUG) Log.d(TAG, "Tool call ${call.name} result: ${line.take(100)}")
            if (responseBuilder.isNotEmpty()) { responseBuilder.append("\n") }
            responseBuilder.append(line)
        }
        // A small model sometimes narrates a change instead of calling the tool.
        // Nothing happened — say so, or the user walks away believing it did.
        if (toolCalls.isEmpty() && CLAIMS_A_CHANGE.containsMatchIn(responseBuilder)) {
            val note = "\n(Nothing was changed — I can only add, update or delete through a confirmation card. Try: \"change the tea to Groceries\" or \"delete the tea from today\".)"
            responseBuilder.append(note)
        }

        // Save the complete AI response
        val finalResponse = responseBuilder.toString()
        if (BuildConfig.DEBUG) Log.d(TAG, "Saving AI response: ${finalResponse.take(50)}...")
        return finalResponse
    }

    /** Fails fast while the model file is still being downloaded. */
    private suspend fun requireModelNotDownloading() {
        if (modelRepository.modelState.first() == ModelState.DOWNLOADING) {
            throw ChatError.ModelDownloading()
        }
    }

    /** Fails when [userMessage] on top of the stored history exceeds the active model's chat budget. */
    private fun requireFitsChatBudget(history: List<ChatMessage>, userMessage: String) {
        if (!TokenUtils.fitsChatBudget(history.map { it.message }, userMessage, modelRepository.activeSpec())) {
            throw ChatError.MemoryFull()
        }
    }

    /** A media file LiteRT-LM can open by path; temporary copies are ours to delete. */
    internal class MediaFile(val file: java.io.File, internal val isTemporary: Boolean) {
        fun deleteIfTemporary() {
            if (isTemporary) file.delete()
        }
    }

    /**
     * LiteRT-LM reads media with open(path), which fails for content:// URIs
     * (e.g. the photo picker's "/picker_get_content/..." path is not a real file).
     * File URIs are used as-is; anything else is copied into app-private storage.
     * If the URI comes from our FileProvider (camera_temp/ or voice_notes/), the
     * original temp file is deleted after successful copy.
     * 
     * For images, converts HEIC/HEIF to JPEG since LiteRT-LM vision doesn't support HEIC format.
     */
    internal suspend fun materializeMedia(uri: android.net.Uri, prefix: String, defaultExtension: String): MediaFile {
        if (uri.scheme == null || uri.scheme == android.content.ContentResolver.SCHEME_FILE) {
            val path = uri.path ?: throw ChatError.MediaUnreadable(Exception("Invalid media URI."))
            return MediaFile(java.io.File(path), isTemporary = false)
        }
        val mimeType = context.contentResolver.getType(uri) ?: "application/octet-stream"
        val extension = mimeType.let { android.webkit.MimeTypeMap.getSingleton().getExtensionFromMimeType(it) }
            ?: defaultExtension
        val isImage = mimeType.startsWith("image/")
        val targetExtension = if (isImage && (extension.equals("heic", ignoreCase = true) || extension.equals("heif", ignoreCase = true))) {
            "jpg" // Convert HEIC/HEIF to JPEG
        } else {
            extension
        }
        val directory = java.io.File(context.filesDir, "chat_media").apply { mkdirs() }
        val target = java.io.File(directory, "$prefix-${UUID.randomUUID()}.$targetExtension")
        try {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                val input = context.contentResolver.openInputStream(uri)
                    ?: throw java.io.IOException("Could not open the selected file.")
                if (isImage && (extension.equals("heic", ignoreCase = true) || extension.equals("heif", ignoreCase = true))) {
                    // Convert HEIC/HEIF to JPEG using BitmapFactory
                    val bitmap = android.graphics.BitmapFactory.decodeStream(input)
                    if (bitmap != null) {
                        java.io.FileOutputStream(target).use { output ->
                            bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, output)
                        }
                    } else {
                        // Fallback: copy as-is if decoding fails
                        input.use { source -> target.outputStream().use { sink -> source.copyTo(sink) } }
                    }
                } else {
                    // Non-image or non-HEIC: copy as-is
                    input.use { source -> target.outputStream().use { sink -> source.copyTo(sink) } }
                }
            }
        } catch (e: Exception) {
            target.delete()
            Log.e(TAG, "Failed to copy media from $uri", e)
            throw ChatError.MediaUnreadable(e)
        }
        // If URI is from our FileProvider (camera_temp or voice_notes), delete the original temp file
        if (uri.authority == "${context.packageName}.fileprovider") {
            val pathSegments = uri.pathSegments
            if (pathSegments.size >= 2 && (pathSegments[0] == "camera_temp" || pathSegments[0] == "voice_notes")) {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    context.contentResolver.delete(uri, null, null)
                }
            }
        }
        return MediaFile(target, isTemporary = true)
    }

    private suspend fun ensureConversation(excludeMessageId: String? = null) {
        // La selección puede haber cambiado desde la última carga: resolverla
        // aquí garantiza que el engine sirva el modelo activo.
        modelRepository.refreshActiveModel()
        val modelFile = modelRepository.getModelFile()

        // Initialize engine if needed
        if (!llmService.isInitialized() || llmService.initializedModelPath() != modelFile.absolutePath) {
            if (llmService.isInitialized()) {
                // El engine vivo corre el modelo viejo: se descarta antes de cargar
                // el nuevo (los 2.4 GB de gemma no caben junto a los 1.5 GB de qwen).
                llmService.reset()
            }
            if (!modelFile.exists()) {
                throw ChatError.ModelNotDownloaded()
            }

            // Integrity gate: never load a model whose bytes don't match the
            // pinned hash (guards against an in-place-swapped or corrupted file).
            if (!modelRepository.verifyModelIntegrity()) {
                modelRepository.deleteModel()
                throw ChatError.ModelIntegrityFailed()
            }

            // Gemma 3n is the only bundle with vision/audio executors; they must be
            // declared at engine init or every image/voice message is rejected.
            val initResult = llmService.initialize(
                modelFile.absolutePath,
                multimodal = ChatModels.isMultimodal(modelRepository.activeSpec())
            )
            if (initResult.isFailure) {
                throw initResult.exceptionOrNull() ?: Exception("Failed to initialize LLM")
            }
        }

        // Create conversation if needed, replaying history from Room DB
        if (!llmService.hasActiveConversation()) {
            // W10: close any deferred conversation before creating new one
            llmService.closeConversation()

            val allMessages = chatDao.getAllMessagesForContext()

            // Extract system prompt
            val systemPrompt = allMessages
                .firstOrNull { it.isSystemPrompt }
                ?.message ?: run {
                    val chatContext = aiContextRepository.getChatContext()
                    buildSystemPrompt(chatContext, modelRepository.activeSpec())
                }

            // Build history (excluding system prompt and the message being sent)
            val history = allMessages
                .filter { !it.isSystemPrompt && (excludeMessageId == null || it.id != excludeMessageId) }
                .map { it.message to it.isUser }

            // Thinking config: solo en developer mode con modelo que lo soporte (Gemma 4 E2B)
            val activeSpec = modelRepository.activeSpec()
            val thinkingConfig = if (activeSpec.id == ChatModels.GEMMA_4_E2B.id) {
                val prefs = userPreferencesRepository
                // Solo habilitar thinking si developer mode está on Y thinking está habilitado en prefs
                if (prefs.isDeveloperModeEnabled.first() && prefs.isThinkingEnabled.first()) {
                    ThinkingConfig(
                        enabled = true,
                        budgetTokens = prefs.thinkingBudgetTokens.first(),
                        showRaw = prefs.thinkingShowRaw.first()
                    ).takeIf { it.validate(activeSpec.contextTokens) }
                        ?: run {
                            Log.w(TAG, "Thinking budget exceeds half of the ${activeSpec.contextTokens}-token context — thinking disabled")
                            null
                        }
                } else null
            } else null

            val result = llmService.createConversation(systemPrompt, history, withTools = true, thinkingConfig = thinkingConfig)
            if (result.isFailure) {
                throw result.exceptionOrNull() ?: Exception("Failed to create conversation")
            }
            Log.d(TAG, "Conversation created with ${history.size} history messages" +
                (thinkingConfig?.let { " + thinking (budget=${it.budgetTokens})" } ?: ""))
        }
    }

    suspend fun deleteAllMessages() {
        llmService.closeConversation()
        chatDao.deleteAllMessages()
    }

    suspend fun deleteOldMessages(beforeTimestamp: Long) {
        // Conversation needs to be recreated after history changes
        llmService.closeConversation()
        chatDao.deleteMessagesBefore(beforeTimestamp)
    }

    suspend fun getMessageCount(): Int = chatDao.getMessageCount()

    /**
     * Resolves one tool call into a line for the chat. Returns null when there's
     * nothing to say (the transaction card carries its own text).
     */
    private suspend fun handleToolCall(call: LlmEvent.ToolCall, userMessage: String): String? {
        Log.d(TAG, "handleToolCall: ${call.name} args=${call.arguments.keys}")
        return handleToolCallInner(call, userMessage)
    }

    /** LiteRT-LM snake_cases parameter names (daysAgo → days_ago); accept either. */
    private fun Map<String, Any?>.arg(camel: String): Any? =
        this[camel] ?: this[camel.replace(Regex("([A-Z])")) { "_" + it.value.lowercase() }]

    /** Category the user named, tolerant of "Food", "food and dining", "FOOD & DINING". */
    private fun matchCategory(wanted: String, categories: List<com.pennywiseai.tracker.data.database.entity.CategoryEntity>): String? {
        val norm = { x: String -> x.lowercase().replace("&", "and").replace(Regex("[^a-z0-9]+"), " ").trim() }
        val w = norm(wanted); if (w.isEmpty()) return null
        return categories.firstOrNull { norm(it.name) == w }?.name
            ?: categories.firstOrNull { norm(it.name).startsWith(w) || w.startsWith(norm(it.name)) }?.name
            ?: categories.firstOrNull { norm(it.name).split(" ").first() == w.split(" ").first() }?.name
    }

    private suspend fun handleToolCallInner(call: LlmEvent.ToolCall, userMessage: String): String? {
        Log.d(TAG, "handleToolCallInner: ${call.name} args=${call.arguments.keys}")
        return when (call.name) {
        PennyWiseTools.ADD_TRANSACTION -> {
            val draft = TransactionDraft.fromToolArgs(
                args = call.arguments,
                sourceText = userMessage,
                categories = categoryRepository.getVisibleCategories().first(),
                accounts = accountBalanceRepository.getAllLatestBalancesOnce(),
                fallbackCategory = { merchant ->
                    com.pennywiseai.tracker.data.mapper.BuiltinCategoryNames.current(SharedCategoryMapping.getCategory(merchant))
                }
            )
            if (draft == null) {
                "I couldn't work out the amount. Try something like \"coffee 120 at Starbucks\"."
            } else {
                _pendingAction.value = PendingChatAction.Add(draft)
                val currency = userPreferencesRepository.baseCurrency.first()
                "Here's what I'll add — confirm below:\n${CurrencyFormatter.formatCurrency(draft.amount, currency)} " +
                    "${if (draft.type == TransactionType.INCOME) "from" else "at"} ${draft.merchant} · ${draft.category} · ${draft.accountLabel}"
            }
        }
        PennyWiseTools.DELETE_TRANSACTION, PennyWiseTools.UPDATE_TRANSACTION -> {
            val match = findTransaction(call.arguments)
            val target = match?.transaction
            // Say so when the day or amount had to be dropped to find it.
            val closest = if (match?.loosened == true) "I couldn't find an exact match — this is the closest one. " else ""
            if (target == null) {
                "I couldn't find a matching transaction in the last 30 days. Try the merchant name and amount."
            } else if (call.name == PennyWiseTools.DELETE_TRANSACTION) {
                _pendingAction.value = PendingChatAction.Delete(target)
                "${closest}Delete this one? Confirm below:\n${describe(target)}"
            } else {
                val newCategory = (call.arguments.arg("newCategory") as? String)?.trim()?.takeIf { it.isNotEmpty() }?.let { wanted ->
                    matchCategory(wanted, categoryRepository.getVisibleCategories().first())
                }?.takeIf { it != target.category }
                val newMerchant = (call.arguments.arg("newMerchant") as? String)?.trim()
                    ?.takeIf { it.isNotEmpty() && !it.equals(target.merchantName, ignoreCase = true) }
                if (newCategory == null && newMerchant == null) {
                    "What should I change it to? Say a category from your list or a new merchant name."
                } else {
                    _pendingAction.value = PendingChatAction.Update(target, newCategory, newMerchant)
                    "${closest}Update this one? Confirm below:\n${describe(target)} → " +
                        listOfNotNull(newMerchant?.let { "merchant $it" }, newCategory?.let { "category $it" }).joinToString(", ")
                }
            }
        }
        PennyWiseTools.MONTH_SUMMARY -> {
            val m = aiContextRepository.getChatContext().monthSummary
            val currency = userPreferencesRepository.baseCurrency.first()
            context.getString(
                R.string.chat_tool_month_summary,
                CurrencyFormatter.formatCurrency(m.totalExpense, currency),
                CurrencyFormatter.formatCurrency(m.totalIncome, currency),
                m.transactionCount
            )
        }
        PennyWiseTools.SPENDING_BY_CATEGORY -> {
            val category = (call.arguments["category"] as? String)?.trim().orEmpty()
            val currency = userPreferencesRepository.baseCurrency.first()
            val spend = aiContextRepository.getCategorySpending(category)
            if (spend == null) "Nothing spent on $category this month."
            else "${CurrencyFormatter.formatCurrency(spend.amount, currency)} on ${spend.category} this month across ${spend.transactionCount} transactions."
        }
        PennyWiseTools.BUDGET_STATUS -> {
            val currency = userPreferencesRepository.baseCurrency.first()
            val budgets = budgetRepository.getActiveBudgets().first()
            if (budgets.isEmpty()) return@handleToolCallInner "No active budgets right now."
            // Recolectar spendings ANTES del joinToString: first() es suspend y el lambda no lo es
            val lines = budgets.map { b ->
                val spend = budgetRepository.getBudgetSpending(b).first()
                "${b.name}: ${CurrencyFormatter.formatCurrency(spend.totalSpent, currency)} of " +
                    "${CurrencyFormatter.formatCurrency(b.limitAmount, currency)} used " +
                    "(${spend.percentageUsed.toInt()}%), ${CurrencyFormatter.formatCurrency(spend.remaining, currency)} left " +
                    "(${budgetRepository.getDaysRemaining(b)} days left)."
            }
            lines.joinToString("\n")
        }
        PennyWiseTools.CATEGORY_BREAKDOWN -> {
            val currency = userPreferencesRepository.baseCurrency.first()
            val limit = (call.arguments["limit"] as? Number)?.toInt()?.coerceIn(1, 10) ?: 5
            val ctx = aiContextRepository.getChatContext()
            val top = ctx.topCategories.take(limit)
            if (top.isEmpty()) return@handleToolCallInner "No spending recorded this month yet."
            top.joinToString("\n") { c ->
                "${c.category}: ${CurrencyFormatter.formatCurrency(c.amount, currency)} (${c.percentage.toInt()}%, ${c.transactionCount} txns)"
            }
        }
        PennyWiseTools.RECENT_TRANSACTIONS -> {
            val currency = userPreferencesRepository.baseCurrency.first()
            val limit = (call.arguments["limit"] as? Number)?.toInt()?.coerceIn(1, 20) ?: 5
            val daysBack = (call.arguments.arg("daysBack") as? Number)?.toInt()?.coerceIn(1, 30) ?: 7
            val ctx = aiContextRepository.getChatContext()
            val cutoff = java.time.LocalDate.now().minusDays(daysBack.toLong())
            val recent = ctx.recentTransactions.filter { it.dateTime.toLocalDate() >= cutoff }.take(limit)
            if (recent.isEmpty()) return@handleToolCallInner "No transactions in the last $daysBack days."
            recent.joinToString("\n") { t ->
                val when_ = if (t.daysAgo == 0) "today" else if (t.daysAgo == 1) "yesterday" else "${t.daysAgo} days ago"
                "${CurrencyFormatter.formatCurrency(t.amount, currency)} at ${t.merchantName} · ${t.category} · $when_"
            }
        }
        PennyWiseTools.ACCOUNT_BALANCE -> {
            val ignored = ignoredAccountsStore.keys()
            val accounts = accountBalanceRepository.getAllLatestBalancesOnce()
                .filter { IgnoredAccountsStore.keyFor(it.bankName, it.accountLast4) !in ignored }
            if (accounts.isEmpty()) return@handleToolCallInner "No accounts on record yet."
            accounts.joinToString("\n") { a ->
                val label = a.alias ?: "${a.bankName} ${a.accountLast4}"
                "${CurrencyFormatter.formatCurrency(a.balance, a.currency)} — $label"
            }
        }
        PennyWiseTools.SPENDING_FORECAST -> {
            val currency = userPreferencesRepository.baseCurrency.first()
            val m = aiContextRepository.getChatContext().monthSummary
            val forecast = if (m.currentDay > 0) {
                m.totalExpense.multiply(java.math.BigDecimal(m.daysInMonth))
                    .divide(java.math.BigDecimal(m.currentDay), 2, java.math.RoundingMode.HALF_UP)
            } else m.totalExpense
            "Spent so far this month: ${CurrencyFormatter.formatCurrency(m.totalExpense, currency)} over ${m.currentDay} of ${m.daysInMonth} days. " +
                "At this pace, the month will end around ${CurrencyFormatter.formatCurrency(forecast, currency)}."
        }
        PennyWiseTools.SEARCH_TRANSACTIONS -> {
            val currency = userPreferencesRepository.baseCurrency.first()
            val query = call.arguments.arg("query") as? String
            val dateFrom = (call.arguments.arg("dateFrom") as? String)?.let { java.time.LocalDate.parse(it).atStartOfDay() }
            val dateTo = (call.arguments.arg("dateTo") as? String)?.let { java.time.LocalDate.parse(it).atTime(23, 59, 59) }
            val category = call.arguments.arg("category") as? String
            val merchant = call.arguments.arg("merchant") as? String
            val amountMin = (call.arguments.arg("amountMin") as? Number)?.let { java.math.BigDecimal.valueOf(it.toDouble()) }
            val amountMax = (call.arguments.arg("amountMax") as? Number)?.let { java.math.BigDecimal.valueOf(it.toDouble()) }
            val account = call.arguments.arg("account") as? String
            val currencyFilter = call.arguments.arg("currency") as? String
            val type = call.arguments.arg("type") as? String
            val limit = (call.arguments.arg("limit") as? Number)?.toInt()?.coerceIn(1, 50) ?: 20
            val results = transactionRepository.searchTransactions(
                query = query,
                dateFrom = dateFrom,
                dateTo = dateTo,
                category = category,
                merchant = merchant,
                amountMin = amountMin,
                amountMax = amountMax,
                account = account,
                currency = currencyFilter,
                type = type?.let { TransactionType.valueOf(it) },
                limit = limit
            )
            if (results.isEmpty()) return@handleToolCallInner "No transactions match your filters."
            results.joinToString("\n") { t ->
                "${CurrencyFormatter.formatCurrency(t.amount, t.currency)} at ${t.merchantName} · ${t.category} · ${t.dateTime.toLocalDate()}"
            }
        }
        PennyWiseTools.SPENDING_TREND -> {
            val currency = userPreferencesRepository.baseCurrency.first()
            val monthsBack = (call.arguments.arg("monthsBack") as? Number)?.toInt()?.coerceIn(3, 12) ?: 6
            val granularity = call.arguments.arg("granularity") as? String
            val isWeekly = granularity?.uppercase() == "WEEK"
            val trend = aiContextRepository.getSpendingTrend(monthsBack, isWeekly)
            if (trend.isEmpty()) return@handleToolCallInner "Not enough data for a trend."
            trend.joinToString("\n") { (period, amount) ->
                "$period: ${CurrencyFormatter.formatCurrency(amount, currency)}"
            }
        }
        PennyWiseTools.SUBSCRIPTION_SUMMARY -> {
            val currency = userPreferencesRepository.baseCurrency.first()
            val status = call.arguments.arg("status") as? String
            val state = status?.let { SubscriptionState.valueOf(it) } ?: SubscriptionState.ACTIVE
            val subs = subscriptionRepository.getSubscriptionsByStateList(state)
            if (subs.isEmpty()) return@handleToolCallInner "No subscriptions found."
            subs.joinToString("\n") { s ->
                "${s.merchantName}: ${CurrencyFormatter.formatCurrency(s.amount, s.currency)} · ${s.direction} · next ${s.nextPaymentDate} · ${s.state}"
            }
        }
        PennyWiseTools.EXCHANGE_RATE -> {
            val amount = (call.arguments.arg("amount") as? Number)?.toDouble() ?: 0.0
            val fromCurrency = call.arguments.arg("fromCurrency") as? String
            val toCurrency = call.arguments.arg("toCurrency") as? String
            val useCustomRate = (call.arguments.arg("useCustomRate") as? Boolean) ?: true
            if (fromCurrency == null || toCurrency == null || amount == 0.0) {
                "I need amount, fromCurrency, and toCurrency."
            } else {
                val rate = exchangeRateProvider.getExchangeRate(fromCurrency, toCurrency, !useCustomRate)
                val converted = java.math.BigDecimal(amount).multiply(rate)
                "Exchange rate: 1 $fromCurrency = $rate $toCurrency\n${CurrencyFormatter.formatCurrency(amount, fromCurrency)} = ${CurrencyFormatter.formatCurrency(converted, toCurrency)}"
            }
        }
        PennyWiseTools.CATEGORY_STATS -> {
            val currency = userPreferencesRepository.baseCurrency.first()
            val category = call.arguments.arg("category") as? String ?: return@handleToolCallInner "I need a category name."
            val monthsBack = (call.arguments.arg("monthsBack") as? Number)?.toInt() ?: 3
            val stats = aiContextRepository.getCategoryStats(category, monthsBack)
            if (stats == null) return@handleToolCallInner "No stats for $category."
            "Category: $category (last $monthsBack months)\n" +
                "Avg: ${CurrencyFormatter.formatCurrency(stats.average, currency)}\n" +
                "Median: ${CurrencyFormatter.formatCurrency(stats.median, currency)}\n" +
                "Top merchants: ${stats.topMerchants.joinToString(", ")}\n" +
                "vs Budget: ${stats.budgetPercentage.toInt()}%"
        }
        else -> {
            // Unknown tool - log and return null to avoid saving blank message
            if (com.pennywiseai.tracker.BuildConfig.DEBUG) {
                Log.w(TAG, "Unknown tool called: ${call.name}")
            }
            null
        }
        }
    }

    private suspend fun findTransaction(args: Map<String, Any?>): TransactionFinder.Match? {
        val words = (args["merchant"] as? String).orEmpty()
        val amount = (args["amount"] as? Number)?.toDouble()?.takeIf { it > 0 }?.let { BigDecimal.valueOf(it) }
        val daysAgo = (args.arg("daysAgo") as? Number)?.toInt()?.takeIf { it >= 0 }
        val now = java.time.LocalDateTime.now()
        val recent = transactionRepository.getTransactionsBetweenDates(now.minusDays(30), now).first()
        return TransactionFinder.findWithFallback(recent, words, amount, daysAgo)
    }

    /** An existing row is shown in ITS currency, never relabelled with the base one. */
    private fun describe(tx: TransactionEntity): String =
        "${CurrencyFormatter.formatCurrency(tx.amount, tx.currency)} ${if (tx.transactionType == TransactionType.INCOME) "from" else "at"} ${tx.merchantName} · ${tx.category} · " +
            tx.dateTime.format(java.time.format.DateTimeFormatter.ofPattern("d MMM"))

    /**
     * Thin system prompt (#170): who the assistant is, the month at a glance,
     * the user's categories and accounts, and how to use the tools. The old
     * prompt embedded recent transactions, which a 1.5B model tends to parrot
     * back into tool arguments.
     */
    private suspend fun buildSystemPrompt(context: ChatContext, activeSpec: ChatModelSpec): String {
        val monthSummary = context.monthSummary
        val currency = userPreferencesRepository.baseCurrency.first()
        val categories = categoryRepository.getVisibleCategories().first()
        val ignored = ignoredAccountsStore.keys()
        val accounts = accountBalanceRepository.getAllLatestBalancesOnce()
            .filter { IgnoredAccountsStore.keyFor(it.bankName, it.accountLast4) !in ignored }
            .map { it.alias ?: "${it.bankName} ${it.accountLast4}" }
        val supportsMedia = ChatModels.isMultimodal(activeSpec)
        val customInstructions = userPreferencesRepository.customInstructions.first()
        val systemPromptAdditions = userPreferencesRepository.systemPromptAdditions.first()
        // Get device locale for response language
        val locale = this.context.resources.configuration.locales.get(0)
        val languageTag = locale.toLanguageTag() // e.g. "es-MX", "en-US"
        
        // Format date and month line in the user's locale
        val dateFormatter = java.time.format.DateTimeFormatter.ofLocalizedDate(java.time.format.FormatStyle.LONG).withLocale(locale)
        val formattedDate = context.currentDate.format(dateFormatter)
        val monthLine = when (locale.language) {
            "es" -> "${CurrencyFormatter.formatCurrency(monthSummary.totalExpense, currency)} gastados, " +
                "${CurrencyFormatter.formatCurrency(monthSummary.totalIncome, currency)} recibidos, ${monthSummary.transactionCount} transacciones"
            else -> "${CurrencyFormatter.formatCurrency(monthSummary.totalExpense, currency)} spent, " +
                "${CurrencyFormatter.formatCurrency(monthSummary.totalIncome, currency)} income, ${monthSummary.transactionCount} transactions"
        }

        return composeSystemPrompt(
            currentDate = formattedDate,
            monthLine = monthLine,
            expenseCategories = categories.filter { !it.isIncome }.map { it.name },
            incomeCategories = categories.filter { it.isIncome }.map { it.name },
            accounts = accounts,
            supportsVision = supportsMedia,
            supportsAudio = supportsMedia,
            customInstructions = customInstructions,
            systemPromptAdditions = systemPromptAdditions,
            responseLanguage = languageTag
        )
    }

    suspend fun updateSystemPrompt() {
        val chatContext = aiContextRepository.getChatContext()
        val newPrompt = buildSystemPrompt(chatContext, modelRepository.activeSpec())
        // Actualizar la fila isSystemPrompt=1 en Room ANTES de cerrar la conversación
        // para que al recrearse use el prompt nuevo (W2)
        chatDao.updateSystemPromptMessage(newPrompt)
        userPreferencesRepository.updateSystemPrompt(newPrompt)
        // W10: defer closeConversation if a generation is in progress
        if (generating) {
            Log.d(TAG, "Deferring closeConversation until generation completes")
        } else {
            // Close conversation so it gets recreated with updated prompt
            llmService.closeConversation()
        }
        Log.d(TAG, "System prompt updated with latest financial data")
    }

    // DEAD CODE (2026-11-04): Replaced by ChatEmptyState in ChatScreen which shows
    // localized example prompts. Kept for reference if a welcome message is needed again.
    /*
    suspend fun getFormattedContextForDisplay(): String {
        val chatContext = aiContextRepository.getChatContext()
        val monthSummary = chatContext.monthSummary
        val currency = userPreferencesRepository.baseCurrency.first()

        return """
        Hi! I'm PennyWise AI.

        Tell me what you spent and I'll add it after you confirm — e.g. "coffee 120 at Starbucks" or "got 50000 salary". Ask "how much on groceries this month?" for a total.

        This month so far: ${monthSummary.transactionCount} transactions, ${CurrencyFormatter.formatCurrency(monthSummary.totalExpense, currency)} spent.
        Nothing is saved without your tap.
        """.trimIndent()
    }
    */

    companion object {
        /**
         * The system prompt from already-gathered data. Each list is capped so the
         * prompt stays a bounded slice of [TokenUtils.CHAT_TOKEN_BUDGET] however
         * many categories or accounts the user has — a fresh chat must always fit
         * (#959). The tools still match any category or account the user names.
         */
        internal fun composeSystemPrompt(
            currentDate: String,
            monthLine: String,
            expenseCategories: List<String>,
            incomeCategories: List<String>,
            accounts: List<String>,
            supportsVision: Boolean = false,
            supportsAudio: Boolean = false,
            customInstructions: String = "",
            systemPromptAdditions: String = "",
            responseLanguage: String = "en-US"
        ): String = """
            You are PennyWise AI, a friendly assistant inside an expense tracker. Today is $currentDate.
            This month so far: $monthLine.

            When the user tells you about money they spent, paid, bought or received, call addTransaction. Use EXPENSE unless they clearly received money.
            When the user asks how much they spent on something, call spendingByCategory; for this month's overall totals call monthSummary (the figures below are only a snapshot from when this chat started).
            When the user asks about budgets, call budgetStatus; for a per-category breakdown call categoryBreakdown; for recent activity call recentTransactions; for account balances call accountBalance; to predict the month's total call spendingForecast.
            When the user wants to find specific transactions, call searchTransactions with filters like date range, category, merchant, amount range, account, currency, or type.
            When the user asks about spending trends over multiple months, call spendingTrend with monthsBack and granularity (MONTH or WEEK).
            When the user asks about subscriptions, call subscriptionSummary with optional status filter.
            When the user asks to convert between currencies, call exchangeRate with amount, fromCurrency, toCurrency, and optional useCustomRate.
            When the user asks for advanced category statistics, call categoryStats with category and monthsBack.
            If the user refers to something already recorded — "the tea from today", "that uber", "change", "should be", "was actually", "delete", "remove" — call updateTransaction or deleteTransaction with the words they used to identify it. Never call addTransaction for those.
            ${if (supportsVision) """
            If the user attaches an image of a receipt, extract: merchant, amount, date, category. Omit currency if not visible. If the image/document does NOT declare category, account, type (gasto/ingreso) or date, you MUST ask the user for the missing fields BEFORE calling addTransaction. Propose reasonable defaults from the available accounts and categories above, and ask for explicit confirmation. Never assume currency — always use CurrencyFormatter.formatCurrency(amount, code). Emit ONLY the addTransaction tool call with those fields (after user confirms).
            """ else ""}
            ${if (supportsAudio) """
            If the user sends a voice note, transcribe it, extract transaction fields (merchant, amount, date, category). Omit currency if not mentioned. If the audio does NOT declare category, account, type (gasto/ingreso) or date, you MUST ask the user for the missing fields BEFORE calling addTransaction. Propose reasonable defaults. Never assume currency.
            """ else ""}
            Otherwise answer briefly and helpfully. Never invent transactions or figures, and never say you added, changed or deleted anything yourself — only the tools do that, and the user confirms each one.

            If you suggest a follow-up question, make it specific, actionable, and professional (e.g., "Would you like me to break down spending by category?" not "¿Cuánto en comida?"). Keep suggestions relevant to the user's financial context.

            Expense categories: ${promptList(expenseCategories, MAX_EXPENSE_CATEGORY_CHARS)}
            Income categories: ${promptList(incomeCategories, MAX_INCOME_CATEGORY_CHARS)}
            Accounts: ${promptList(accounts, MAX_ACCOUNT_CHARS).ifEmpty { "none yet" }}

            ${if (customInstructions.isNotBlank()) """
            --- Additional user instructions ---
            $customInstructions
            """ else ""}
            ${if (systemPromptAdditions.isNotBlank()) """
            --- System prompt additions (semantic) ---
            $systemPromptAdditions
            """ else ""}

            Respond in the user's language: $responseLanguage (e.g., es-MX → Spanish, en-US → English). Match the language the user writes in. If unsure, default to $responseLanguage.

            Plain text only: no markdown, asterisks or backticks.
            """.trimIndent()

        /** Names joined in order until [maxChars]; the rest are counted, not listed. */
        internal fun promptList(names: List<String>, maxChars: Int): String {
            var used = 0
            val shown = names.takeWhile { used += it.length + 2; used <= maxChars }
            val more = names.size - shown.size
            return (shown + listOfNotNull(if (more > 0) "and $more more" else null)).joinToString(", ")
        }

        // ~2,000 chars (~500 tokens) of lists at most; default categories fit with room to spare.
        private const val MAX_EXPENSE_CATEGORY_CHARS = 1200
        private const val MAX_INCOME_CATEGORY_CHARS = 400
        private const val MAX_ACCOUNT_CHARS = 500
        private val CLAIMS_A_CHANGE = Regex(
        "\\b(I(?:'ve| have)?|has been|have been|is now|was|it'?s been|it was) (added|updated|changed|deleted|removed|recorded|he (agregado|añadido|registrado|eliminado|actualizado|borrado)|se (ha )?(agreg|añadi|registr|elimin|actualiz)[óo])\\b",
        RegexOption.IGNORE_CASE
    )
        /**
         * Respuesta que es SOLO el nombre de un tool (camel o snake) — el modelo
         * narró el call en vez de emitirlo (patrón #2202 con gemma-4-E2B:
         * "monthSummary" crudo como respuesta completa).
         */
        private val NARRATED_TOOL = Regex("^([A-Za-z_][A-Za-z0-9_]*)$")

        /** Prompt for vision (receipt OCR → addTransaction). In English (system prompt language). */
        const val IMAGE_PROMPT = "If the user attached an image of a receipt, extract: merchant, amount, date, category. Omit currency if not visible. If the image/document does NOT declare category, account, type (gasto/ingreso) or date, you MUST ask the user for the missing fields BEFORE calling addTransaction. Propose reasonable defaults from the available accounts and categories above, and ask for explicit confirmation. Never assume currency — always specify the currency code (ISO 4217) with the amount. Emit ONLY the addTransaction tool call with those fields (after user confirms)."
        /** Prompt for audio (voice note → addTransaction). In English (system prompt language). */
        const val AUDIO_PROMPT = "If the user attached an audio note, transcribe it and extract transaction details. Same rules as IMAGE_PROMPT: ask for missing fields (category, account, type, date) before calling addTransaction. Propose reasonable defaults. Never assume currency — always specify the currency code (ISO 4217) with the amount."

        /**
         * Converts a JsonElement to a Kotlin Any value for Map serialization.
         * Supports strings, numbers, booleans, and null.
         */
        private fun jsonElementToAny(element: kotlinx.serialization.json.JsonElement): Any? = when {
            element is kotlinx.serialization.json.JsonPrimitive -> {
                val content = element.content
                when {
                    content == "null" -> null
                    content.startsWith("\"") && content.endsWith("\"") -> content.substring(1, content.length - 1)
                    content == "true" || content == "false" -> content.toBoolean()
                    else -> {
                        try {
                            content.toBigDecimal()
                        } catch (e: NumberFormatException) {
                            content
                        }
                    }
                }
            }
            element is kotlinx.serialization.json.JsonNull -> null
            else -> element.toString()
        }
    }
}
