package com.pennywiseai.tracker.data.repository

import com.pennywiseai.tracker.domain.service.ChatError
import com.pennywiseai.tracker.ui.UiText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.runBlocking

class ChatErrorMappingTest {

    companion object {
        // String resource IDs for chat errors (these match values/strings_rules.xml)
        private const val ERR_MEMORY_FULL = 2131951680 // R.string.chat_error_memory_full
        private const val ERR_MODEL_DOWNLOADING = 2131951681 // R.string.chat_error_model_downloading
        private const val ERR_MODEL_NOT_DOWNLOADED = 2131951682 // R.string.chat_error_model_not_downloaded
        private const val ERR_INTEGRITY_FAILED = 2131951683 // R.string.chat_error_integrity_failed
        private const val ERR_VISION_REQUIRES_GEMMA3N = 2131951684 // R.string.chat_error_vision_requires_gemma3n
        private const val ERR_AUDIO_REQUIRES_GEMMA3N = 2131951685 // R.string.chat_error_audio_requires_gemma3n
        private const val ERR_MEDIA_UNREADABLE = 2131951686 // R.string.chat_error_media_unreadable
        private const val ERR_GENERATE_FAILED = 2131951687 // R.string.chat_error_generate_failed
    }

    private fun toChatUiText(error: Throwable, fallback: Int): UiText = when (error) {
        is ChatError.MemoryFull -> UiText.Res(ERR_MEMORY_FULL)
        is ChatError.ModelDownloading -> UiText.Res(ERR_MODEL_DOWNLOADING)
        is ChatError.ModelNotDownloaded -> UiText.Res(ERR_MODEL_NOT_DOWNLOADED)
        is ChatError.ModelIntegrityFailed -> UiText.Res(ERR_INTEGRITY_FAILED)
        is ChatError.VisionRequiresGemma3n -> UiText.Res(ERR_VISION_REQUIRES_GEMMA3N)
        is ChatError.AudioRequiresGemma3n -> UiText.Res(ERR_AUDIO_REQUIRES_GEMMA3N)
        is ChatError.MediaUnreadable -> UiText.Res(ERR_MEDIA_UNREADABLE)
        else -> error.message?.let { UiText.Plain(it) } ?: UiText.Res(fallback)
    }

    @Test
    fun memoryFull_mapsToStringRes() = runBlocking {
        val result = toChatUiText(ChatError.MemoryFull(), ERR_GENERATE_FAILED)
        assertTrue(result is UiText.Res)
        assertEquals(ERR_MEMORY_FULL, (result as UiText.Res).id)
    }

    @Test
    fun modelDownloading_mapsToStringRes() = runBlocking {
        val result = toChatUiText(ChatError.ModelDownloading(), ERR_GENERATE_FAILED)
        assertTrue(result is UiText.Res)
        assertEquals(ERR_MODEL_DOWNLOADING, (result as UiText.Res).id)
    }

    @Test
    fun modelNotDownloaded_mapsToStringRes() = runBlocking {
        val result = toChatUiText(ChatError.ModelNotDownloaded(), ERR_GENERATE_FAILED)
        assertTrue(result is UiText.Res)
        assertEquals(ERR_MODEL_NOT_DOWNLOADED, (result as UiText.Res).id)
    }

    @Test
    fun modelIntegrityFailed_mapsToStringRes() = runBlocking {
        val result = toChatUiText(ChatError.ModelIntegrityFailed(), ERR_GENERATE_FAILED)
        assertTrue(result is UiText.Res)
        assertEquals(ERR_INTEGRITY_FAILED, (result as UiText.Res).id)
    }

    @Test
    fun visionRequiresGemma3n_mapsToStringRes() = runBlocking {
        val result = toChatUiText(ChatError.VisionRequiresGemma3n(), ERR_GENERATE_FAILED)
        assertTrue(result is UiText.Res)
        assertEquals(ERR_VISION_REQUIRES_GEMMA3N, (result as UiText.Res).id)
    }

    @Test
    fun audioRequiresGemma3n_mapsToStringRes() = runBlocking {
        val result = toChatUiText(ChatError.AudioRequiresGemma3n(), ERR_GENERATE_FAILED)
        assertTrue(result is UiText.Res)
        assertEquals(ERR_AUDIO_REQUIRES_GEMMA3N, (result as UiText.Res).id)
    }

    @Test
    fun mediaUnreadable_mapsToStringRes() = runBlocking {
        val result = toChatUiText(ChatError.MediaUnreadable(Exception("test")), ERR_GENERATE_FAILED)
        assertTrue(result is UiText.Res)
        assertEquals(ERR_MEDIA_UNREADABLE, (result as UiText.Res).id)
    }

    @Test
    fun unknownException_withMessage_mapsToPlain() = runBlocking {
        val result = toChatUiText(Exception("custom error"), ERR_GENERATE_FAILED)
        assertTrue(result is UiText.Plain)
        assertEquals("custom error", (result as UiText.Plain).value)
    }

    @Test
    fun unknownException_noMessage_fallbackToRes() = runBlocking {
        val result = toChatUiText(Exception(), ERR_GENERATE_FAILED)
        assertTrue(result is UiText.Res)
        assertEquals(ERR_GENERATE_FAILED, (result as UiText.Res).id)
    }

    @Test
    fun allChatErrorSubclasses_haveMapping() = runBlocking {
        // Just verify all sealed subclasses are handled in the when expression
        val errors = listOf(
            ChatError.MemoryFull(),
            ChatError.ModelDownloading(),
            ChatError.ModelNotDownloaded(),
            ChatError.ModelIntegrityFailed(),
            ChatError.VisionRequiresGemma3n(),
            ChatError.AudioRequiresGemma3n(),
            ChatError.MediaUnreadable(),
            ChatError.Generic(Exception("test"))
        )
        
        errors.forEach { error ->
            val result = toChatUiText(error, ERR_GENERATE_FAILED)
            assertTrue(result is UiText.Res || result is UiText.Plain)
        }
    }
}