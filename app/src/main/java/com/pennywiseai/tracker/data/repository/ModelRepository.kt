package com.pennywiseai.tracker.data.repository

import android.app.ActivityManager
import android.content.Context
import android.os.Environment
import android.util.Log
import com.pennywiseai.tracker.core.ChatModelSpec
import com.pennywiseai.tracker.core.ChatModels
import com.pennywiseai.tracker.utils.TokenUtils
import com.pennywiseai.tracker.data.preferences.UserPreferencesRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ModelRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val userPreferencesRepository: UserPreferencesRepository,
) {
    // LOADING = "todavía no se resolvió": el modelo activo se lee de las
    // preferencias y el archivo se verifica antes de afirmar READY.
    private val _modelState = MutableStateFlow(ModelState.LOADING)
    val modelState: Flow<ModelState> = _modelState.asStateFlow()

    /**
     * Spec del modelo activo. Se resuelve con [refreshActiveModel] (prefs + RAM
     * gate) antes de usarse; hasta entonces vale [ChatModels.DEFAULT], que es el
     * comportamiento que ya tenían los usuarios existentes.
     */
    @Volatile
    private var active: ChatModelSpec = ChatModels.DEFAULT

    /**
     * RAM total del device. El RAM gate ([deviceSupports]) la compara contra
     * [ChatModelSpec.minRamBytes] de cada modelo del catálogo.
     */
    private val totalRamBytes: Long by lazy {
        val info = ActivityManager.MemoryInfo()
        (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(info)
        info.totalMem
    }

    /** ¿El device cumple los requisitos de RAM del modelo? */
    fun deviceSupports(spec: ChatModelSpec): Boolean = totalRamBytes >= spec.minRamBytes

    /**
     * F6-T1 RAM gate en runtime: memoria disponible actual (no total). Con un modelo
     * grande cargado (Gemma 4 E2B usa ~2.6 GB RSS) el lmkd puede matar el proceso si
     * la memoria libre cae demasiado. El caller (ViewModel del chat) usa esto para
     * ofrecer fallback a [ChatModels.DEFAULT] con diálogo en vez de crash silencioso.
     */
    fun availableRamBytes(): Long {
        val info = ActivityManager.MemoryInfo()
        (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(info)
        return info.availMem
    }

    /** Umbral de memoria libre bajo el cual un modelo grande (minRam >= 4 GB) se considera en riesgo de lmkd. */
    fun isLowMemoryForActiveModel(): Boolean {
        val spec = active
        if (spec.minRamBytes < 4L * 1024 * 1024 * 1024) return false // modelos chicos no gatean
        return availableRamBytes() < LOW_MEMORY_THRESHOLD_BYTES
    }

    /**
     * Modelos del catálogo que este device puede correr y son capaces de chat.
     * Filtra por RAM gate Y chat capability (excluye Whisper ASR-only).
     * Define qué opciones muestra el selector de Settings.
     */
    fun supportedModels(): List<ChatModelSpec> = ChatModels.chatCapableModels(
        userPreferencesRepository.getCustomModelConfigSync()
    ).filter { deviceSupports(it) }

    /**
     * Versión que recibe el JSON del custom model config (para uso en Flow).
     * Filtra por RAM gate Y chat capability.
     */
    fun supportedModelsWithCustom(customConfigJson: String?): List<ChatModelSpec> = ChatModels.chatCapableModels(customConfigJson)
        .filter { deviceSupports(it) }

    /** Relee la selección del usuario y aplica el RAM gate + chat capability:
     * un modelo que el device no aguanta o no es chat-capable cae a [ChatModels.DEFAULT]. */
    suspend fun refreshActiveModel() {
        val wanted = userPreferencesRepository.selectedChatModel.first()
        val spec = ChatModels.byId(wanted, userPreferencesRepository.getCustomModelConfigSync())
        active = if (deviceSupports(spec) && TokenUtils.isChatCapable(spec)) spec else {
            Log.w(TAG, "Selected model ${spec.id} needs ${spec.minRamBytes}B RAM or is not chat-capable — falling back to ${ChatModels.DEFAULT.id}")
            ChatModels.DEFAULT
        }
    }

    suspend fun selectModel(modelId: String) {
        userPreferencesRepository.setSelectedChatModel(modelId)
        // La selección cambia el archivo en disco que getModelFile() resuelve:
        // el estado del modelo activo se recalcula contra el nuevo spec.
        refreshActiveModel()
        refreshModelState()
    }

    fun activeSpec(): ChatModelSpec = active

    fun getModelFile(): File {
        val file = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), active.fileName)
        Log.d(TAG, "Model file path: ${file.absolutePath}")
        return file
    }

    fun isModelDownloaded(): Boolean {
        val modelFile = getModelFile()
        val exists = modelFile.exists()
        val size = if (exists) modelFile.length() else 0
        val expectedSize = active.sizeBytes
        // Allow 5% variance in file size as download sizes can vary
        // But also accept any file over 2GB as models can vary in size
        val minSize = minOf((expectedSize * 0.95).toLong(), 2L * 1024L * 1024L * 1024L) // 95% of expected or 2GB minimum
        val isDownloaded = exists && size >= minSize

        Log.d(TAG, "Checking model: exists=$exists, size=$size bytes (${size/1024/1024}MB), expectedSize=$expectedSize, minSize=$minSize, isDownloaded=$isDownloaded")
        return isDownloaded
    }

    /**
     * Verifies the downloaded model against the pinned [ChatModelSpec.sha256]
     * by streaming its full SHA-256 — every call actually re-hashes the bytes on disk.
     *
     * This is the load-time security boundary, so it must NOT be short-circuited by a
     * cheap proxy (size, mtime, a cached "already verified" flag): the model has a
     * fixed length, so a same-length replacement would otherwise pass without ever
     * being read. On a match it drops a verification marker that [isModelVerified] may
     * read for UI state only — never as a substitute for this hash.
     *
     * Returns true when the bytes match, or when no hash is pinned (verification
     * intentionally disabled, logged).
     */
    suspend fun verifyModelIntegrity(): Boolean = withContext(Dispatchers.IO) {
        val expected = active.sha256.trim().lowercase()
        if (expected.isEmpty()) {
            Log.w(TAG, "Active model pins no SHA-256 — model integrity verification is DISABLED")
            return@withContext true
        }

        val file = getModelFile()
        if (!file.exists()) {
            Log.w(TAG, "verifyModelIntegrity: model file missing")
            clearVerifiedMarker()
            return@withContext false
        }

        val actual = try {
            file.sha256Hex()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to hash model file", e)
            return@withContext false
        }

        val matches = actual == expected
        if (matches) {
            writeVerifiedMarker(expected)
            Log.d(TAG, "Model integrity verified (sha256=$actual)")
        } else {
            clearVerifiedMarker()
            Log.e(TAG, "Model integrity check FAILED: expected=$expected actual=$actual")
        }
        matches
    }

    private fun File.sha256Hex(): String {
        val digest = MessageDigest.getInstance("SHA-256")
        inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun verifiedMarkerFile(): File =
        File(getModelFile().parentFile, active.fileName + ".verified")

    private fun writeVerifiedMarker(hash: String) {
        try {
            verifiedMarkerFile().writeText(hash)
        } catch (e: Exception) {
            Log.w(TAG, "Could not write verification marker", e)
        }
    }

    private fun clearVerifiedMarker() {
        try {
            val marker = verifiedMarkerFile()
            if (marker.exists()) marker.delete()
        } catch (e: Exception) {
            Log.w(TAG, "Could not clear verification marker", e)
        }
    }

    /**
     * Cheap check (no hashing) of whether the model on disk has previously passed
     * [verifyModelIntegrity]. For driving UI state ONLY — the load path always
     * re-hashes via [verifyModelIntegrity] and never trusts this marker.
     */
    fun isModelVerified(): Boolean {
        if (!isModelDownloaded()) return false
        val expected = active.sha256.trim().lowercase()
        if (expected.isEmpty()) return true // verification disabled ��� any downloaded file counts
        val marker = verifiedMarkerFile()
        return try {
            marker.exists() && marker.readText().trim().lowercase() == expected
        } catch (e: Exception) {
            false
        }
    }

    fun updateModelState(state: ModelState) {
        Log.d(TAG, "Updating model state from ${_modelState.value} to $state")
        _modelState.value = state
    }

    /**
     * Resolves the model state on app open. A previously-verified file goes straight to
     * READY; a present-but-unverified file (a download whose post-download verification
     * was interrupted, or a model carried over from an older build) is re-hashed before
     * it is trusted, and deleted if it fails. Never reports READY for an unverified file.
     */
    suspend fun refreshModelState() {
        // La selección persistida define cuál es el modelo activo: resolverla
        // ANTES de evaluar el archivo evita reportar "no descargado" cuando el
        // modelo elegido ya está en el disco (el chat pedía descargarlo de nuevo
        // en cada arranque hasta que se pasaba por Settings).
        refreshActiveModel()
        when {
            isModelVerified() -> updateModelState(ModelState.READY)
            isModelDownloaded() -> {
                updateModelState(ModelState.LOADING)
                if (verifyModelIntegrity()) {
                    updateModelState(ModelState.READY)
                } else {
                    Log.e(TAG, "Present model failed verification on open — deleting")
                    deleteModel()
                }
            }
            else -> updateModelState(ModelState.NOT_DOWNLOADED)
        }
    }

    /**
     * Verifies a freshly-completed download before trusting it. Promotes to READY on a
     * hash match, otherwise deletes the file and reports failure. Returns true iff the
     * bytes matched the pinned hash.
     */
    suspend fun finalizeDownloadedModel(): Boolean {
        return if (verifyModelIntegrity()) {
            updateModelState(ModelState.READY)
            true
        } else {
            Log.e(TAG, "Downloaded model failed verification — deleting")
            deleteModel()
            false
        }
    }

    fun deleteModel(): Boolean {
        val modelFile = getModelFile()
        clearVerifiedMarker()
        return if (modelFile.exists()) {
            val deleted = modelFile.delete()
            if (deleted) {
                _modelState.value = ModelState.NOT_DOWNLOADED
            }
            deleted
        } else {
            _modelState.value = ModelState.NOT_DOWNLOADED
            false
        }
    }

    private companion object {
        const val TAG = "ModelRepository"
        /** 1.5 GB libre = umbral de alerta para modelos grandes cargados (F6-T1). */
        const val LOW_MEMORY_THRESHOLD_BYTES = 1_500L * 1024 * 1024
    }
}

enum class ModelState {
    NOT_DOWNLOADED,
    DOWNLOADING,
    READY,
    LOADING,
    ERROR
}
