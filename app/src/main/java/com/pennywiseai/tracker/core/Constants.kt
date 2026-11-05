package com.pennywiseai.tracker.core

import com.pennywiseai.tracker.utils.TokenUtils

/**
 * Application-wide constants to avoid hardcoded values
 */
object Constants {
    
    /**
     * SMS Processing Configuration
     */
    object SmsProcessing {
        const val DEFAULT_BATCH_SIZE = 100
        const val SMS_PREVIEW_LENGTH = 200
        const val QUERY_LIMIT = 100
        const val INITIAL_SCAN_MONTHS = 3
        const val SCANNING_DELAY_MS = 3000L
        /** Stored in [last_scan_period] when SMS scan period is set to all time. */
        const val SCAN_PERIOD_ALL_TIME = -1
        /** Stored in [last_scan_period] when SMS scan period is a custom start date. */
        const val SCAN_PERIOD_CUSTOM_DATE = -2
    }
    
    /**
     * UI Configuration - Moved to ui/theme/Dimensions.kt for better organization
     * Keeping only non-dimension constants here
     */
    object UI {
        const val BUTTON_WIDTH_RATIO = 0.8f
        const val PROGRESS_STROKE_WIDTH = 2f
    }
    
    /**
     * Database Configuration
     */
    object Database {
        const val DATABASE_NAME = "pennywise_database"
        const val CURRENT_VERSION = 2
        const val TRANSACTION_HASH_DEFAULT = ""
    }
    
    /**
     * WorkManager Configuration
     */
    object WorkManager {
        const val SMS_READER_WORK_NAME = "sms_reader_work"
        const val PERIODIC_SCAN_INTERVAL_HOURS = 24L
        const val INITIAL_DELAY_MINUTES = 15L
    }
    
    /**
     * Parsing Configuration
     */
    object Parsing {
        const val MIN_MERCHANT_NAME_LENGTH = 2
        const val MD5_ALGORITHM = "MD5"
        const val AMOUNT_SCALE = 2
        const val CONFIDENCE_PATTERN_BASED = 0.7f
        const val CONFIDENCE_AI_BASED = 0.9f
    }
    
    /**
     * Navigation Routes
     */
    object Routes {
        const val HOME = "home"
        const val TRANSACTIONS = "transactions"
        const val ANALYTICS = "analytics"
        const val CHAT = "chat"
        const val SETTINGS = "settings"
    }
    
    /**
     * LLM Model Configuration
     *
     * The chat model is distributed from a **versioned, immutable object key**
     * and verified against a pinned SHA-256 before it is ever loaded. To publish
     * a new model: upload it under a fresh [MODEL_VERSION] key (never overwrite an
     * existing version in place), then bump [MODEL_VERSION] and [MODEL_SHA256]
     * together. This makes a silently-swapped model impossible to ship.
     */
    object ModelDownload {
        const val MODEL_FILE_NAME = "Qwen2.5-1.5B-Instruct-q8-ekv4096.litertlm"

        /** Bump when a new model build is published under a new versioned key. */
        const val MODEL_VERSION = "v1"

        private const val R2_PUBLIC_BASE =
            "https://pub-fcfb3ffddb184540a758a7fe68249908.r2.dev"

        /**
         * Pinned to an immutable, versioned path — NOT the bare bucket root, so a
         * given URL always resolves to the same bytes. The object must be uploaded
         * to this exact key in R2 (`models/$MODEL_VERSION/$MODEL_FILE_NAME`).
         */
        const val MODEL_URL = "$R2_PUBLIC_BASE/models/$MODEL_VERSION/$MODEL_FILE_NAME"

        /**
         * Lowercase-hex SHA-256 of the exact bytes served at [MODEL_URL]. The
         * downloaded file is hashed and compared against this before it is loaded;
         * a mismatch is rejected and the file deleted. Recompute and bump on every
         * model change. Leave blank ONLY to intentionally disable verification (a
         * warning is logged and any file is accepted).
         */
        const val MODEL_SHA256 = "faa60663b333290c1496c499828b21d3e3254a788cacd8cce917ce0f761a2dc9"

        const val MODEL_SIZE_MB = 1524L
        const val MODEL_SIZE_BYTES = 1_597_931_520L
        const val REQUIRED_SPACE_BYTES = 3_195_863_040L // ~3.0GB (2x model size for safety)
    }

    /**
     * External Links
     */
    object Links {
        const val DISCORD_URL = "https://discord.gg/H3xWeMWjKQ"
        const val GITHUB_URL = "https://github.com/sarim2000/pennywiseai-tracker"
        const val WEB_PARSER_URL = "https://pennywise.zynth.dev"
        /** Where Pro is sold. The Play upgrade sheet shows it as plain text only — never a link. */
        const val PRO_PAGE_URL = "$WEB_PARSER_URL/pro"
        /** `?app=1` serves the guide without Get Pro or links to purchase pages (Play anti-steering). */
        const val USER_GUIDE_URL = "$WEB_PARSER_URL/guide?app=1"
    }
}

/**
 * Especificación de un modelo de chat on-device descargable: bytes inmutables
 * (URL + SHA-256 pineado), presupuesto de contexto y requisitos de hardware.
 */
data class ChatModelSpec(
    val id: String,
    val fileName: String,
    val url: String,
    val sha256: String,
    val sizeBytes: Long,
    /** KV cache que LiteRT-LM reserva; gobierna el presupuesto del chat vía TokenUtils. */
    val contextTokens: Int,
    /** RAM total del device necesaria para correr el modelo sin presión de memoria. */
    val minRamBytes: Long,
    /** Nombre corto para UI (header del chat, selector de Settings). */
    val displayName: String,
    /** true si es un modelo custom definido por el usuario (HuggingFace). */
    val isCustom: Boolean = false,
) {
    val sizeMb: Long get() = sizeBytes / (1024L * 1024L)
    /** Espacio libre requerido antes de descargar: 2x el tamaño del modelo. */
    val requiredSpaceBytes: Long get() = sizeBytes * 2
}

/**
 * Configuración del modo "Thinking" (LiteRT-LM 0.17.1+). Solo visible en
 * developer mode: expone el razonamiento interno del modelo como tokens
 * separados del output final.
 */
data class ThinkingConfig(
    val enabled: Boolean = false,
    /** Tokens reservados para el chain-of-thought (parte del chat budget). */
    val budgetTokens: Int = 1024,
    /** Si true, renderiza los tokens de thinking en el chat (solo debug). */
    val showRaw: Boolean = false,
) {
    /** Valida que el budget no exceda el 50% del context window del modelo. */
    fun validate(maxContextTokens: Int): Boolean =
        budgetTokens > 0 && budgetTokens <= (maxContextTokens / 2)
}

/**
 * Configuración de un modelo custom definido por el usuario vía HuggingFace (F7-T2).
 * Se serializa como JSON en preferencias y se valida antes de descargar.
 */
data class CustomModelConfig(
    val repoId: String,           // ej. "litert-community/gemma-3n-E2B-it-litert-lm"
    val fileName: String,         // ej. "gemma-3n-E2B-it-int4.litertlm"
    val sha256: String,           // SHA-256 hex del archivo en HF
    val sizeBytes: Long,          // tamaño en bytes (para verificación y espacio)
    val contextTokens: Int,       // tokens de contexto (ej. 8192)
    val minRamBytes: Long,        // RAM mínima requerida
    val displayName: String,      // nombre para UI (ej. "Mi Gemma 3n")
) {
    fun toChatModelSpec(): ChatModelSpec = ChatModels.customModel(
        repoId = repoId,
        fileName = fileName,
        sha256 = sha256,
        sizeBytes = sizeBytes,
        contextTokens = contextTokens,
        minRamBytes = minRamBytes,
        displayName = displayName
    )

    fun toJson(): String = com.google.gson.Gson().toJson(this)

    companion object {
        fun fromJson(json: String): CustomModelConfig? =
            try { com.google.gson.Gson().fromJson(json, CustomModelConfig::class.java) } catch (_: Exception) { null }
    }
}

/**
 * Catálogo de modelos de chat. El orden importa: el primero ([DEFAULT]) es el
 * fallback en devices que no cumplen los requisitos de los demás.
 */
object ChatModels {
    val QWEN_1_5B = ChatModelSpec(
        id = "qwen25-1.5b",
        fileName = Constants.ModelDownload.MODEL_FILE_NAME,
        url = Constants.ModelDownload.MODEL_URL,
        sha256 = Constants.ModelDownload.MODEL_SHA256,
        sizeBytes = Constants.ModelDownload.MODEL_SIZE_BYTES,
        contextTokens = 4096,
        minRamBytes = 0L,
        displayName = "Qwen 2.5 1.5B",
    )

    val GEMMA_4_E2B = ChatModelSpec(
        id = "gemma4-e2b",
        fileName = "gemma-4-E2B-it.litertlm",
        url = "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm",
        sha256 = "181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c",
        sizeBytes = 2_588_147_712L,
        contextTokens = 8192,
        minRamBytes = 6L * 1024 * 1024 * 1024, // ~2.34 GB RSS observados en SM-S908E
        displayName = "Gemma 4 E2B",
    )

    /**
     * Gemma 3n E2B — modelo multimodal nativo (texto + visión + audio) diseñado
     * para low-resource. Bundle int4 de LiteRT-LM servido por Google en HF.
     * ~3.4 GB, contexto 8192, requiere ~4GB RAM para visión/audio.
     */
    val GEMMA_3N_E2B = ChatModelSpec(
        id = "gemma3n-e2b",
        fileName = "gemma-3n-E2B-it-int4.litertlm",
        url = "https://huggingface.co/bhrum108/gemma-3n-E2B-it-litertlm/resolve/main/gemma-3n-E2B-it-int4.litertlm",
        sha256 = "2ed7bc3a0026c93d5b8a4544b352d9d00cd66ff0bac3ef6a20ac3d2cba4010d6",
        sizeBytes = 3_655_827_456L,
        contextTokens = 8192,
        minRamBytes = 4L * 1024 * 1024 * 1024,
        displayName = "Gemma 3n E2B (Visión)",
    )

    /**
     * Whisper Tiny i8 — modelo ASR dedicado en formato .litertlm (litert-community).
     * Solo existe .litertlm para whisper-tiny; whisper-base solo tiene .tflite.
     * ~40 MB, carga vía Content.AudioFile con audioBackend lazy.
     */
    val WHISPER_BASE = ChatModelSpec(
        id = "whisper-base",
        fileName = "whisper_tiny_30s_i8.litertlm",
        url = "https://huggingface.co/litert-community/whisper-tiny/resolve/main/whisper_tiny_30s_i8.litertlm",
        sha256 = "92f194600d98e1d94b1ff6f636d109c0f4ac66f3f6a1803f7a665e93daf78012",
        sizeBytes = 41_804_416L,
        contextTokens = 0, // No contexto de chat, solo ASR
        minRamBytes = 2L * 1024 * 1024 * 1024,
        displayName = "Whisper Tiny i8 (Audio)",
    )

    /**
     * Modelo custom definido por el usuario vía HuggingFace (F7-T2).
     * Se crea dinámicamente cuando el usuario añade un repo HF válido.
     * El spec se guarda en preferencias y se valida antes de descargar.
     */
    fun customModel(
        repoId: String,
        fileName: String,
        sha256: String,
        sizeBytes: Long,
        contextTokens: Int,
        minRamBytes: Long,
        displayName: String
    ): ChatModelSpec = ChatModelSpec(
        id = "custom-$repoId".replace('/', '-'),
        fileName = fileName,
        url = "https://huggingface.co/$repoId/resolve/main/$fileName",
        sha256 = sha256,
        sizeBytes = sizeBytes,
        contextTokens = contextTokens,
        minRamBytes = minRamBytes,
        displayName = displayName,
        isCustom = true
    )

    val ALL = listOf(QWEN_1_5B, GEMMA_4_E2B, GEMMA_3N_E2B, WHISPER_BASE)

    /** Modelo que corre en cualquier device soportado — fallback del RAM gate. */
    val DEFAULT = QWEN_1_5B

    /**
     * Only Gemma 3n E2B ships the vision and audio executors. The engine needs
     * visionBackend/audioBackend at init to accept Content.ImageFile/AudioFile,
     * and the chat only offers photo/voice input for these models.
     */
    fun isMultimodal(spec: ChatModelSpec): Boolean = spec.id == GEMMA_3N_E2B.id

    /**
     * Busca un modelo por ID, incluyendo el custom model si se provee su config JSON.
     * Si no hay match, cae a [DEFAULT].
     */
    fun byId(id: String?, customConfigJson: String? = null): ChatModelSpec =
        allWithCustom(customConfigJson).firstOrNull { it.id == id } ?: DEFAULT

    /**
     * Retorna la lista completa de modelos incluyendo el custom model si existe en prefs.
     * Se llama desde ModelRepository/Config con acceso a UserPreferencesRepository.
     */
    fun allWithCustom(customConfigJson: String?): List<ChatModelSpec> {
        val custom = customConfigJson?.let { CustomModelConfig.fromJson(it) }?.toChatModelSpec()
        return ALL + (custom?.let { listOf(it) } ?: emptyList())
    }

    /**
     * Filtra modelos capaces de chat (chatBudget > 0).
     * Excluye Whisper (ASR-only, contextTokens = 0).
     */
    fun chatCapableModels(customConfigJson: String?): List<ChatModelSpec> =
        allWithCustom(customConfigJson).filter { TokenUtils.isChatCapable(it) }

    /**
     * External Links
     */
    object Links {
        const val DISCORD_URL = "https://discord.gg/H3xWeMWjKQ"
        const val GITHUB_URL = "https://github.com/sarim2000/pennywiseai-tracker"
        const val WEB_PARSER_URL = "https://pennywise.zynth.dev"
        /** Where Pro is sold. The Play upgrade sheet shows it as plain text only — never a link. */
        const val PRO_PAGE_URL = "$WEB_PARSER_URL/pro"
        /** `?app=1` serves the guide without Get Pro or links to purchase pages (Play anti-steering). */
        const val USER_GUIDE_URL = "$WEB_PARSER_URL/guide?app=1"
    }
}
