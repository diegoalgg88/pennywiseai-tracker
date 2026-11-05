package com.pennywiseai.tracker.ui.screens.chat

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pennywiseai.tracker.R
import com.pennywiseai.tracker.core.Constants
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import com.pennywiseai.tracker.domain.service.ChatError
import com.pennywiseai.tracker.domain.service.LlmEvent
import com.pennywiseai.tracker.data.manager.ModelDownloader
import com.pennywiseai.tracker.data.database.entity.ChatMessage
import com.pennywiseai.tracker.data.repository.LlmRepository
import com.pennywiseai.tracker.data.repository.ModelRepository
import com.pennywiseai.tracker.data.repository.ModelState
import com.pennywiseai.tracker.data.preferences.UserPreferencesRepository
import com.pennywiseai.tracker.ui.UiText
import com.pennywiseai.tracker.utils.TokenUtils
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

@HiltViewModel
class ChatViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val llmRepository: LlmRepository,
    private val modelRepository: ModelRepository,
    private val userPreferencesRepository: UserPreferencesRepository,
    private val addTransactionUseCase: com.pennywiseai.tracker.domain.usecase.AddTransactionUseCase,
    private val deleteTransactionUseCase: com.pennywiseai.tracker.domain.usecase.DeleteTransactionUseCase,
    private val transactionRepository: com.pennywiseai.tracker.data.repository.TransactionRepository,
    private val modelDownloader: ModelDownloader
) : ViewModel() {


    val downloadProgress: StateFlow<Int> = modelDownloader.progress
        .map { (it.downloadedBytes * 100 / it.totalBytes.coerceAtLeast(1)).toInt() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val downloadedMB: StateFlow<Long> = modelDownloader.progress
        .map { it.downloadedBytes / BYTES_PER_MB }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0L)

    val totalMB: StateFlow<Long> = modelDownloader.progress
        .map { it.totalBytes / BYTES_PER_MB }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), com.pennywiseai.tracker.core.ChatModels.DEFAULT.sizeMb)


    private val _contextMessage = MutableStateFlow<ChatMessage?>(null)

    /**
     * Spec del modelo activo (selección DataStore + RAM gate), refrescado en cada
     * nueva colección — el header del chat y el budget de tokens lo siguen.
     */
    val activeModelSpec = flow {
        modelRepository.refreshActiveModel()
        emit(modelRepository.activeSpec())
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), com.pennywiseai.tracker.core.ChatModels.DEFAULT)

    val messages: StateFlow<List<ChatMessage>> = combine(
        llmRepository.getAllMessages(),
        _contextMessage
    ) { dbMessages, contextMsg ->
        if (dbMessages.isEmpty() && contextMsg != null) {
            // Show context message only when chat is empty
            listOf(contextMsg)
        } else {
            // Show actual chat messages
            dbMessages
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    val modelState: StateFlow<ModelState> = modelRepository.modelState
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            // El estado real se resuelve contra el modelo seleccionado al
            // arrancar; LOADING es el transitorio honesto mientras tanto.
            initialValue = ModelState.LOADING
        )

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    private val _currentResponse = MutableStateFlow("")
    val currentResponse: StateFlow<String> = _currentResponse.asStateFlow()

    val isDeveloperModeEnabled = userPreferencesRepository.isDeveloperModeEnabled
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = false
        )

    // Get all messages including system for accurate token count
    private val allMessagesIncludingSystem = llmRepository.getAllMessagesIncludingSystem()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    // Chat statistics for developer mode
    val chatStats = combine(allMessagesIncludingSystem, currentResponse, activeModelSpec) { allMsgs, current, spec ->
        // Calculate system prompt tokens separately
        val systemPromptText = allMsgs.filter { it.isSystemPrompt }.joinToString(" ") { it.message }
        val systemPromptTokens = if (systemPromptText.isNotEmpty()) {
            TokenUtils.estimateTokens(systemPromptText)
        } else {
            0
        }

        // Calculate total tokens
        val allText = allMsgs.joinToString(" ") { it.message } + " " + current
        val totalChars = allText.length
        val estimatedTokens = TokenUtils.estimateTokens(allText)
        // Presupuesto del modelo ACTIVO (Qwen 2560, Gemma 6640) — no el default.
        val maxTokens = TokenUtils.chatBudget(spec)

        // Count only visible messages for UI
        val visibleCount = allMsgs.count { !it.isSystemPrompt }

        ChatStats(
            messageCount = visibleCount,
            totalCharacters = totalChars,
            estimatedTokens = estimatedTokens,
            systemPromptTokens = systemPromptTokens,
            maxTokens = maxTokens,
            contextUsagePercent = TokenUtils.calculateContextUsagePercent(estimatedTokens, maxTokens),
            modelDisplayName = spec.displayName
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = ChatStats()
    )

    init {
        // Resume an in-progress download, or (when idle) resolve + re-verify the model.
        checkAndResumeDownload()

        viewModelScope.launch {
            modelDownloader.progress.map { it.failure }.distinctUntilChanged().collect { failure ->
                val message = when (failure) {
                    ModelDownloader.Failure.NO_SPACE -> R.string.chat_error_no_storage
                    ModelDownloader.Failure.NETWORK -> R.string.chat_error_download_failed
                    ModelDownloader.Failure.INTEGRITY -> R.string.chat_error_integrity_failed
                    ModelDownloader.Failure.ROAMING -> R.string.chat_error_download_roaming
                    null -> return@collect
                }
                _uiState.value = _uiState.value.copy(error = UiText.Res(message))
            }
        }
    }

    // Attached image for preview before sending (W13)
    private val _attachedImage = MutableStateFlow<Uri?>(null)
    val attachedImage: StateFlow<Uri?> = _attachedImage.asStateFlow()

    fun attachImage(uri: Uri) {
        _attachedImage.value = uri
    }

    fun clearAttachment() {
        _attachedImage.value = null
    }

    /** Sends the attached image and clears the attachment. */
    fun sendAttachedImage() {
        _attachedImage.value?.let { uri ->
            clearAttachment()
            sendImage(uri)
        }
    }

    // Transaction the model proposed; shown as a confirm card (#170). Nothing
    // is written until the user taps Add.
    val pendingAction: StateFlow<com.pennywiseai.tracker.data.model.PendingChatAction?> = llmRepository.pendingAction

    val baseCurrency: StateFlow<String> = userPreferencesRepository.baseCurrency
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "INR")

    private val _isConfirming = MutableStateFlow(false)
    val isConfirming: StateFlow<Boolean> = _isConfirming.asStateFlow()

    fun dismissPendingAction() = llmRepository.clearPendingAction()

    /** A card belongs to the conversation that produced it: leaving the screen drops it. */
    override fun onCleared() {
        llmRepository.clearPendingAction()
        super.onCleared()
    }

    fun confirmPendingAction() {
        val action = pendingAction.value ?: return
        // One write per card: the flag disables the buttons, and a racing second
        // tap returns here before it can launch a second write.
        if (!_isConfirming.compareAndSet(expect = false, update = true)) return
        viewModelScope.launch {
            try {
                val currency = userPreferencesRepository.baseCurrency.first()
                val fmt = { a: java.math.BigDecimal -> com.pennywiseai.tracker.utils.CurrencyFormatter.formatCurrency(a, currency, maskIfHidden = false) }
                when (action) {
                    is com.pennywiseai.tracker.data.model.PendingChatAction.Delete -> {
                        deleteTransactionUseCase(action.transaction)
                        llmRepository.clearPendingAction()
                        val t = action.transaction
                        llmRepository.appendAssistantMessage("Deleted ${com.pennywiseai.tracker.utils.CurrencyFormatter.formatCurrency(t.amount, t.currency, maskIfHidden = false)} at ${t.merchantName}.")
                        return@launch
                    }
                    is com.pennywiseai.tracker.data.model.PendingChatAction.Update -> {
                        action.newCategory?.let { transactionRepository.updateCategory(action.transaction.id, it) }
                        action.newMerchant?.let { m ->
                            val current = transactionRepository.getTransactionById(action.transaction.id) ?: action.transaction
                            transactionRepository.updateTransaction(current.copy(merchantName = m, updatedAt = java.time.LocalDateTime.now()))
                        }
                        llmRepository.clearPendingAction()
                        val t = action.transaction
                        llmRepository.appendAssistantMessage(
                            "Updated ${com.pennywiseai.tracker.utils.CurrencyFormatter.formatCurrency(t.amount, t.currency, maskIfHidden = false)} at ${action.newMerchant ?: t.merchantName}" +
                                (action.newCategory?.let { " → $it" } ?: "") + "."
                        )
                        return@launch
                    }
                    is com.pennywiseai.tracker.data.model.PendingChatAction.Add -> Unit
                }
                val draft = action.draft
                addTransactionUseCase.execute(
                    amount = draft.amount,
                    merchant = draft.merchant,
                    category = draft.category,
                    type = draft.type,
                    date = java.time.LocalDateTime.now(),
                    // Para media (foto/voz), sourceText viene vacío — no incluir el prompt hardcodeado
                    notes = draft.sourceText.takeIf { it.isNotBlank() }?.let { "Added from chat: \"$it\"" } ?: "Added from chat",
                    bankName = draft.bankName,
                    accountLast4 = draft.accountLast4,
                    currency = currency
                )
                llmRepository.clearPendingAction()
                llmRepository.appendAssistantMessage(
                    "Added ${com.pennywiseai.tracker.utils.CurrencyFormatter.formatCurrency(draft.amount, currency, maskIfHidden = false)} " +
                        "${if (draft.type == com.pennywiseai.tracker.data.database.entity.TransactionType.INCOME) "from" else "at"} ${draft.merchant} (${draft.category})."
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(error = UiText.Res(R.string.chat_error_apply_failed, listOf(e.message.orEmpty())))
            } finally {
                _isConfirming.value = false
            }
        }
    }

    /**
     * Common generation runner: validates loading state, clears pending action,
     * sets up UI state, runs the generation flow, handles errors/cancellation,
     * and restores UI state. Used by sendMessage, sendImage, sendAudio.
     */
    private fun runGeneration(source: suspend () -> Flow<LlmEvent>) {
        if (_uiState.value.isLoading) return
        llmRepository.clearPendingAction()

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                isLoading = true,
                error = null,
                thinkingTokens = ""
            )
            _currentResponse.value = ""

            try {
                source().collect { event ->
                    when (event) {
                        is LlmEvent.Text -> _currentResponse.value += event.delta
                        is LlmEvent.Thinking -> _uiState.value = _uiState.value.copy(
                            thinkingTokens = _uiState.value.thinkingTokens + event.delta
                        )
                        is LlmEvent.ToolCall -> Unit // resuelto en LlmRepository
                    }
                }
            } catch (c: CancellationException) {
                throw c // re-lanzar para no tragar cancelación
            } catch (e: Exception) {
                val errorMessage = sendErrorText(e, R.string.chat_error_send_failed)
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = errorMessage
                )
            } finally {
                _uiState.value = _uiState.value.copy(isLoading = false)
                _currentResponse.value = ""
            }
        }
    }

    fun sendMessage(message: String) {
        if (message.isBlank() || _uiState.value.isLoading) return
        // A new message supersedes any card still waiting from the last one.
        runGeneration { llmRepository.sendMessageStream(message) }
    }

    fun clearChat() {
        llmRepository.clearPendingAction()
        viewModelScope.launch {
            llmRepository.deleteAllMessages()
            _uiState.value = _uiState.value.copy(
                error = null
            )
            // No recargar el mensaje de contexto: al estar vacío el chat,
            // el ChatEmptyState con prompts de ejemplo se muestra automáticamente
        }
    }

    // The checks match LlmRepository's own (untranslated) exception messages.
    private fun sendErrorText(error: Throwable, @androidx.annotation.StringRes fallback: Int): UiText = when {
        error is ChatError.VisionRequiresGemma3n -> UiText.Res(R.string.chat_error_vision_requires_gemma3n)
        error is ChatError.AudioRequiresGemma3n -> UiText.Res(R.string.chat_error_audio_requires_gemma3n)
        error is ChatError.MediaUnreadable -> UiText.Res(R.string.chat_error_media_unreadable)
        error.message?.contains("memory is full") == true -> UiText.Res(R.string.chat_error_memory_full)
        error.message?.contains("downloading") == true -> UiText.Res(R.string.chat_error_model_downloading)
        error.message?.contains("not downloaded") == true -> UiText.Res(R.string.chat_error_model_not_downloaded)
        else -> error.message?.let { UiText.Plain(it) } ?: UiText.Res(fallback)
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }

    fun startModelDownload() = modelDownloader.start()

    fun checkAndResumeDownload() {
        viewModelScope.launch {
            // El modelo activo se resuelve antes de tocar el disco: los archivos
            // de descarga (.part) y el archivo final pertenecen al modelo elegido.
            modelRepository.refreshActiveModel()
            if (modelDownloader.isRunning ||
                modelDownloader.adoptLegacyDownload() ||
                modelDownloader.resumeIfInterrupted()
            ) return@launch
            // No download to resume → resolve the model state, re-verifying a
            // present-but-unverified file before it is shown as READY.
            modelRepository.refreshModelState()
        }
    }

    fun cancelDownload() = modelDownloader.cancel()

    /**
     * Sends an image for vision tasks (receipt OCR → addTransaction).
     * Only works when the active model is Gemma 3n E2B (vision capable).
     */
    fun sendImage(imageUri: android.net.Uri) {
        if (_uiState.value.isLoading) return
        runGeneration { llmRepository.sendMessageWithImage(imageUri) }
    }

    /**
     * Envía una nota de voz para transcripción (audio → texto → addTransaction).
     * Solo funciona con Gemma 3n E2B (multimodal nativo audio + visión).
     * Whisper Base es ASR-only y no es modelo de chat (W3).
     */
    fun sendAudio(audioUri: android.net.Uri) {
        if (_uiState.value.isLoading) return
        runGeneration { llmRepository.sendMessageWithAudio(audioUri) }
    }

    private companion object {
        const val BYTES_PER_MB = 1024L * 1024L
    }
}

data class ChatUiState(
    val isLoading: Boolean = false,
    val error: UiText? = null,
    /** Tokens de thinking (chain-of-thought) acumulados en esta respuesta — solo developer mode. */
    val thinkingTokens: String = ""
)

data class ChatStats(
    val messageCount: Int = 0,
    val totalCharacters: Int = 0,
    val estimatedTokens: Int = 0,
    val systemPromptTokens: Int = 0,
    val maxTokens: Int = TokenUtils.CHAT_TOKEN_BUDGET,
    val contextUsagePercent: Int = 0,
    /** Nombre del modelo activo para el header del chat (doc 25 F0-T1). */
    val modelDisplayName: String = com.pennywiseai.tracker.core.ChatModels.DEFAULT.displayName
)