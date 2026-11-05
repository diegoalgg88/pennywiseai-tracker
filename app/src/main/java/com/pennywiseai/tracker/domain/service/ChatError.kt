package com.pennywiseai.tracker.domain.service

/** Typed chat errors for robust, localizable error handling (W9). */
sealed class ChatError(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class MemoryFull : ChatError("Chat memory is full")
    class ModelDownloading : ChatError("Model is downloading")
    class ModelNotDownloaded : ChatError("Model not downloaded")
    class ModelIntegrityFailed : ChatError("Model failed its integrity check")
    class VisionRequiresGemma3n : ChatError("Vision requires Gemma 3n E2B")
    class AudioRequiresGemma3n : ChatError("Audio requires Gemma 3n E2B")
    class MediaUnreadable(cause: Throwable? = null) : ChatError("Could not read the selected media", cause)
    class Generic(cause: Throwable) : ChatError("Unexpected error", cause)
}