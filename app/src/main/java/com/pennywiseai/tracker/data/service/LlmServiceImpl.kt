package com.pennywiseai.tracker.data.service

import android.content.Context
import android.net.Uri
import android.util.Log
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.ThinkingConfig as LiteRtThinkingConfig
import com.google.ai.edge.litertlm.tool
import com.pennywiseai.tracker.core.ThinkingConfig
import com.pennywiseai.tracker.domain.service.ChatError
import com.pennywiseai.tracker.domain.service.LlmEvent
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.transform
import com.pennywiseai.tracker.domain.service.LlmService
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LlmServiceImpl @Inject constructor(
    @ApplicationContext private val context: Context
) : LlmService {

    private var engine: Engine? = null
    private var conversation: Conversation? = null

    // What the engine and the live conversation were built from, so a GPU that fails
    // its first reply can be swapped for the CPU in the middle of that send.
    private var modelPath: String? = null
    private var conversationConfig: ConversationConfig? = null

    // Create / close / reset run on Dispatchers.IO from different callers; without
    // this two closes could race on one Conversation, and LiteRT throws
    // IllegalStateException for a close it can't perform (a 2.21.0 crash).
    private val lifecycle = Mutex()

    // True from a GPU start until its first reply completes; KEY_GPU_FAILED stays armed meanwhile.
    @Volatile private var gpuUnconfirmed = false

    private val backendPrefs by lazy { context.getSharedPreferences("llm_backend", Context.MODE_PRIVATE) }

    // Whether the engine was started with the vision and audio executors; the CPU
    // retry after a GPU failure has to rebuild it the same way.
    @Volatile private var multimodal: Boolean = false

    override suspend fun initialize(modelPath: String, multimodal: Boolean): Result<Unit> = withContext(Dispatchers.IO) {
        lifecycle.withLock { initializeLocked(modelPath, multimodal) }
    }

    private fun initializeLocked(modelPath: String, multimodal: Boolean): Result<Unit> {
        // A caller that queued behind another start (or the CPU retry) must not replace,
        // and leak, the engine the live conversation runs on.
        if (engine != null) return Result.success(Unit)
        this.modelPath = modelPath
        this.multimodal = multimodal
        return try {
            // Force CPU backend to avoid GPU precision corruption issues (Mali/Adreno).
            // GPU backend has known issues with numeric precision on Mali/Adreno GPUs
            // causing tool call argument corruption (Issue #2202).
            val cpu = {
                gpuUnconfirmed = false
                startEngine(modelPath, Backend.CPU(), multimodal).also { Log.i(TAG, "Engine initialized on CPU backend") }
            }
            engine = cpu()
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize engine", e)
            Result.failure(e)
        }
    }

    override suspend fun createConversation(
        systemPrompt: String,
        history: List<Pair<String, Boolean>>,
        withTools: Boolean,
        thinkingConfig: ThinkingConfig?
    ): Result<Unit> = withContext(Dispatchers.IO) { lifecycle.withLock {
        try {
            val currentEngine = engine ?: return@withLock Result.failure(
                IllegalStateException("Engine not initialized")
            )

            // Close any existing conversation. Taken first, so a failed close can't
            // leave a dead conversation behind for the next caller to close again.
            takeConversation()?.closeQuietly()

            val config = chatConversationConfig(systemPrompt, history, withTools, thinkingConfig)
            conversation = currentEngine.createConversation(config)
            conversationConfig = config
            Log.d(TAG, "Conversation created with ${history.size} history messages")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create conversation", e)
            Result.failure(e)
        }
    } }

    override fun sendMessage(message: String): Flow<String> =
        sendMessageEvents(message).mapNotNull { (it as? LlmEvent.Text)?.delta }

    override fun sendMessageEvents(message: String): Flow<LlmEvent> {
        val activeConversation = conversation
            ?: throw IllegalStateException("No active conversation")

        Log.d(TAG, "Sending message (${message.length} chars)")

        // A GPU engine can start fine and only fail once it generates (the emulator's
        // "Can not find OpenCL library"). Rather than fail the user's first message,
        // retry it once on the CPU, if nothing was streamed yet.
        return flow {
            val onUnconfirmedGpu = gpuUnconfirmed
            var streamed = false
            emitAll(
                stream(activeConversation, message)
                    .onEach { streamed = true }
                    .catch { e ->
                        if (e is CancellationException || !onUnconfirmedGpu || streamed) throw e
                        Log.w(TAG, "GPU failed its first reply (${e.javaClass.simpleName}), retrying on CPU")
                        val cpuConversation = restartOnCpu(activeConversation) ?: throw e
                        emitAll(stream(cpuConversation, message))
                    }
            )
        }
    }

    override fun sendMessageWithImage(message: String, imageUri: Uri): Flow<LlmEvent> {
        val activeConversation = conversation
            ?: throw IllegalStateException("No active conversation")
        // LiteRT-LM opens media by file path, never by content:// URI.
        val imagePath = imageUri.path ?: throw ChatError.MediaUnreadable()
        Log.d(TAG, "Sending message with image: ${message.take(50)}...")
        val contents = Contents.of(Content.ImageFile(imagePath), Content.Text(message))
        return sendContentsWithGpuRetry(activeConversation, contents)
    }

    override fun sendMessageWithAudio(message: String, audioUri: Uri): Flow<LlmEvent> {
        val activeConversation = conversation
            ?: throw IllegalStateException("No active conversation")
        val audioPath = audioUri.path ?: throw ChatError.MediaUnreadable()
        Log.d(TAG, "Sending message with audio: ${message.take(50)}...")
        val contents = Contents.of(Content.AudioFile(audioPath), Content.Text(message))
        return sendContentsWithGpuRetry(activeConversation, contents)
    }

    /** A media reply follows the same GPU-then-CPU rule as a text one. */
    private fun sendContentsWithGpuRetry(
        activeConversation: Conversation,
        contents: Contents
    ): Flow<LlmEvent> = flow {
        val onUnconfirmedGpu = gpuUnconfirmed
        var streamed = false
        emitAll(
            streamContents(activeConversation, contents)
                .onEach { streamed = true }
                .catch { e ->
                    if (e is CancellationException || !onUnconfirmedGpu || streamed) throw e
                    Log.w(TAG, "GPU failed its first reply (${e.javaClass.simpleName}), retrying on CPU")
                    val cpuConversation = restartOnCpu(activeConversation) ?: throw e
                    emitAll(streamContents(cpuConversation, contents))
                }
        )
    }

    private fun stream(activeConversation: Conversation, message: String): Flow<LlmEvent> =
        activeConversation.sendMessageAsync(message)
            .withGpuGuard()
            .toLlmEvents()
            .flowOn(Dispatchers.IO)

    /** Media goes through the same guard: the GPU verdict is about the engine, not the payload. */
    private fun streamContents(activeConversation: Conversation, contents: Contents): Flow<LlmEvent> =
        activeConversation.sendMessageAsync(contents)
            .withGpuGuard()
            .toLlmEvents()
            .flowOn(Dispatchers.IO)

    /**
     * Re-arm after a cancelled first reply; commit() so a native crash still finds it.
     * Finished: the GPU works. Cancelled (chat closed): no verdict, so the next start may
     * try GPU again. A thrown error keeps the flag, so it starts on CPU.
     */
    private fun Flow<Message>.withGpuGuard(): Flow<Message> =
        onStart { if (gpuUnconfirmed) backendPrefs.edit().putBoolean(KEY_GPU_FAILED, true).commit() }
            .onCompletion { cause ->
                if (gpuUnconfirmed && (cause == null || cause is CancellationException)) {
                    if (cause == null) gpuUnconfirmed = false
                    backendPrefs.edit().putBoolean(KEY_GPU_FAILED, false).apply()
                }
            }
            .catch { e ->
                // Class only: LiteRT's messages can quote the model's reply or the prompt.
                Log.e(TAG, "Error during streaming response (${e.javaClass.simpleName})")
                throw e
            }

    /**
     * Maps LiteRT chunks to app events. Reasoning is NOT a Content subtype: the runtime
     * delivers it in the message's "thought" channel, so the thinking panel only receives
     * anything when that channel is read.
     */
    private fun Flow<Message>.toLlmEvents(): Flow<LlmEvent> =
        transform { chunk ->
            val text = chunk.contents.contents
                .filterIsInstance<Content.Text>()
                .joinToString("") { it.text }
            if (text.isNotEmpty()) emit(LlmEvent.Text(text))
            chunk.toolCalls.forEach { emit(LlmEvent.ToolCall(it.name, it.arguments)) }
            chunk.channels[THOUGHT_CHANNEL]
                ?.takeIf { it.isNotEmpty() }
                ?.let { emit(LlmEvent.Thinking(it)) }
        }

    /**
     * Replaces the GPU engine whose first reply failed with a CPU one and rebuilds the
     * conversation that failed on it. Null if that conversation was closed or replaced
     * meanwhile, or the CPU start failed (the next start then begins on CPU).
     */
    private suspend fun restartOnCpu(failed: Conversation): Conversation? = withContext(Dispatchers.IO) {
        lifecycle.withLock {
            val path = modelPath
            val config = conversationConfig
            if (conversation !== failed || path == null || config == null) return@withLock null
            takeConversation()?.closeQuietly()
            closeEngine()
            try {
                gpuUnconfirmed = false
                val cpu = startEngine(path, Backend.CPU(), multimodal).also { engine = it }
                Log.i(TAG, "Engine restarted on CPU backend")
                cpu.createConversation(config).also { conversation = it }
            } catch (e: Exception) {
                Log.e(TAG, "CPU restart failed (${e.javaClass.simpleName})")
                null
            }
        }
    }

    override fun hasActiveConversation(): Boolean = conversation != null

    override suspend fun closeConversation() {
        withContext(Dispatchers.IO) {
            lifecycle.withLock { takeConversation()?.closeQuietly() }
            Log.d(TAG, "Conversation closed")
        }
    }

    override suspend fun reset() {
        withContext(Dispatchers.IO) {
            lifecycle.withLock {
                takeConversation()?.closeQuietly()
                closeEngine()
            }
            Log.d(TAG, "Engine and conversation reset")
        }
    }

    /** Builds and initializes an engine on [backend], releasing it if initialization fails. */
    private fun startEngine(modelPath: String, backend: Backend, multimodal: Boolean): Engine {
        val e = Engine(
            EngineConfig(
                modelPath = modelPath,
                backend = backend,
                // Without these executors the engine rejects Content.ImageFile / AudioFile
                // ("vision executor should not be null"). Only models that ship them may ask
                // for them; a text-only bundle fails to initialize if it is given one.
                // El ejecutor de visión de Gemma 3n E2B exige GPU ([gpu]) sin importar el
                // backend del motor; atarlo al backend principal rompía el arranque en CPU
                // ("Vision backend constraint mismatch"). Si no hay GPU, el arranque falla
                // con un error claro en lugar de aceptar imágenes que no podría procesar.
                visionBackend = if (multimodal) Backend.GPU() else null,
                audioBackend = if (multimodal) Backend.CPU() else null,
                cacheDir = context.cacheDir.path
            )
        )
        try {
            e.initialize()
        } catch (t: Throwable) {
            runCatching { e.close() }
            throw t
        }
        return e
    }

    private fun takeConversation(): Conversation? = conversation.also { conversation = null }

    private fun closeEngine() {
        engine?.let { e -> runCatching { e.close() }.onFailure { Log.w(TAG, "Ignoring failed engine close", it) } }
        engine = null
    }

    /** Closing is cleanup: a close LiteRT refuses (already closed, mid-generation) must not crash the app. */
    private fun Conversation.closeQuietly() {
        runCatching { close() }.onFailure { Log.w(TAG, "Ignoring failed conversation close", it) }
    }

    override fun isInitialized(): Boolean = engine != null

    /** Path with the engine was initialized from; the caller compares it against the active model. */
    override fun initializedModelPath(): String? = modelPath

    companion object {
        private const val TAG = "LlmServiceImpl"
        private const val KEY_GPU_FAILED = "gpu_failed"

        /** Channel name LiteRT-LM uses for chain-of-thought tokens. */
        private const val THOUGHT_CHANNEL = "thought"

        /** The chat's conversation setup; the debug LLM probe builds the same one. */
        internal fun chatConversationConfig(
            systemPrompt: String,
            history: List<Pair<String, Boolean>>,
            withTools: Boolean,
            thinkingConfig: ThinkingConfig? = null
        ) = ConversationConfig(
            systemInstruction = Contents.of(systemPrompt),
            initialMessages = history.map { (message, isUser) ->
                if (isUser) Message.user(message) else Message.model(message)
            },
            // Tool calls are surfaced, never auto-executed (#170): every
            // mutation goes through the user first.
            tools = if (withTools) listOf(tool(PennyWiseTools())) else emptyList(),
            automaticToolCalling = false,
            samplerConfig = SamplerConfig(
                topK = 10,
                topP = 0.95,
                temperature = 0.8
            ),
            // Chain-of-thought only when the app asks for it (developer mode).
            thinkingConfig = thinkingConfig?.takeIf { it.enabled }?.let {
                LiteRtThinkingConfig(true, it.budgetTokens)
            },
            // The model's built-in template can't read LiteRT-LM >= 0.18's message format.
            chatTemplate = QwenChatTemplate.TEMPLATE
        )
    }
}
