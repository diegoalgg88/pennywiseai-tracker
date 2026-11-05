package com.pennywiseai.tracker.domain.service

import android.net.Uri
import com.pennywiseai.tracker.core.ThinkingConfig
import kotlinx.coroutines.flow.Flow

/** One piece of a streamed model reply: text, or a tool the model wants called. */
sealed class LlmEvent {
    data class Text(val delta: String) : LlmEvent()
    data class ToolCall(val name: String, val arguments: Map<String, Any?>) : LlmEvent()
    /** Tokens del chain-of-thought (solo cuando ThinkingConfig.showRaw=true). */
    data class Thinking(val delta: String) : LlmEvent()
}

interface LlmService {
    /**
     * @param multimodal register the vision and audio executors (Gemma 3n). Without
     *  them every Content.ImageFile / AudioFile fails with "vision executor should
     *  not be null". Text-only models must keep this false: they have no such executors.
     */
    suspend fun initialize(modelPath: String, multimodal: Boolean = false): Result<Unit>

    /**
     * @param withTools register the app's tool set (#170). Tool calls are never
     *  executed by the runtime — they surface as [LlmEvent.ToolCall] and the app
     *  decides (mutations always go through user confirmation).
     * @param thinkingConfig configuración opcional de thinking (developer mode).
     *  Requiere LiteRT-LM 0.17.1+; si null o disabled, no se pasa al builder.
     */
    suspend fun createConversation(
        systemPrompt: String,
        history: List<Pair<String, Boolean>>, // message to isUser
        withTools: Boolean = false,
        thinkingConfig: ThinkingConfig? = null
    ): Result<Unit>

    fun sendMessage(message: String): Flow<String>
    fun sendMessageEvents(message: String): Flow<LlmEvent>
    
    /**
     * Send a message with an image for vision tasks (receipt OCR, etc.).
     * Only works when the model supports vision (Gemma 3n E2B) and engine has visionBackend.
     */
    fun sendMessageWithImage(message: String, imageUri: Uri): Flow<LlmEvent>

    /**
     * Envía un mensaje con audio para transcripción (nota de voz → addTransaction).
     * El audioBackend se carga lazy al primer Content.AudioFile (igual que visionBackend).
     */
    fun sendMessageWithAudio(message: String, audioUri: Uri): Flow<LlmEvent>

    fun hasActiveConversation(): Boolean
    suspend fun closeConversation()
    suspend fun reset()
    fun isInitialized(): Boolean

    /**
     * Path con el que el engine se inicializó (null si nunca). Con selección de
     * modelo, el caller compara contra el archivo actual y llama [reset] cuando
     * difieren: un engine vivo con el modelo viejo jamás debe seguir sirviendo.
     */
    fun initializedModelPath(): String?
}
