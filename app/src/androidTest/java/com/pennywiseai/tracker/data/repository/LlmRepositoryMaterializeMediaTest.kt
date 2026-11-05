package com.pennywiseai.tracker.data.repository

import android.content.ContentResolver
import android.content.ContentValues
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pennywiseai.tracker.data.repository.LlmRepository.MediaFile
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class LlmRepositoryMaterializeMediaTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val testRepo = TestLlmRepository(context)

    @Test
    fun materializeMediaUsesFileUriAsIsWithoutCopying(): Unit = runBlocking {
        val tempFile = File(context.cacheDir, "test-image.jpg")
        tempFile.outputStream().use { it.write("fake image data".toByteArray()) }

        val fileUri = Uri.fromFile(tempFile)
        val result = testRepo.exposeMaterializeMedia(fileUri, "image", "jpg")

        assertFalse("file:// URI should not create temporary copy", result.isTemporary)
        assertEquals("Should return the same file", tempFile.absolutePath, result.file.absolutePath)
        assertTrue("Original file should still exist", tempFile.exists())

        tempFile.delete()
    }

    @Test
    fun materializeMediaCopiesContentUriImageFromMediaStoreToAppStorage(): Unit = runBlocking {
        val contentUri = insertImageIntoMediaStore("test-photo.jpg", "fake jpeg content".toByteArray())!!
        assertEquals("Scheme should be content", ContentResolver.SCHEME_CONTENT, contentUri.scheme)

        val result = testRepo.exposeMaterializeMedia(contentUri, "image", "jpg")

        assertTrue("content:// URI should create temporary copy", result.isTemporary)
        assertTrue("Copied file should exist in chat_media dir", result.file.exists())
        assertTrue("Copied file should be in app filesDir/chat_media", result.file.absolutePath.contains("chat_media"))
        assertTrue("Copied file should have .jpg extension", result.file.name.endsWith(".jpg"))
        assertTrue("Copied file should have content", result.file.length() > 0)

        // Verify content was copied correctly
        val copiedContent = result.file.readText()
        assertEquals("Content should match original", "fake jpeg content", copiedContent)

        // Cleanup: MediaStore entry
        context.contentResolver.delete(contentUri, null, null)
    }

    @Test
    fun materializeMediaResolvesExtensionFromMimeTypeForContentUris(): Unit = runBlocking {
        // Insert PNG but request defaultExtension jpg - should resolve to .png via MIME
        val contentUri = insertImageIntoMediaStore("test-photo.png", "fake png content".toByteArray())!!

        val result = testRepo.exposeMaterializeMedia(contentUri, "image", "jpg")

        assertTrue("Should resolve .png extension from MIME type", result.file.name.endsWith(".png"))

        context.contentResolver.delete(contentUri, null, null)
    }

    @Test
    fun materializeMediaCreatesChatMediaDirectoryIfNotExists(): Unit = runBlocking {
        val chatMediaDir = File(context.filesDir, "chat_media")
        if (chatMediaDir.exists()) {
            chatMediaDir.deleteRecursively()
        }
        assertFalse("Directory should not exist initially", chatMediaDir.exists())

        val contentUri = insertImageIntoMediaStore("dir-test.jpg", "data".toByteArray())!!

        val result = testRepo.exposeMaterializeMedia(contentUri, "image", "jpg")

        assertTrue("chat_media directory should be created", chatMediaDir.exists())
        assertTrue("File should be inside chat_media", result.file.parentFile == chatMediaDir)

        context.contentResolver.delete(contentUri, null, null)
        chatMediaDir.deleteRecursively()
    }

    @Test
    fun materializeMediaHandlesAudioContentUriWithM4aDefaultExtension(): Unit = runBlocking {
        // Test with audio prefix and m4a default - MIME will resolve actual extension from content
        // Using .jpg so helper sets image/jpeg MIME type for MediaStore.Images
        val contentUri = insertImageIntoMediaStore("audio-test.jpg", "fake audio".toByteArray())!!

        val result = testRepo.exposeMaterializeMedia(contentUri, "audio", "m4a")

        assertTrue("Should create temp copy for content:// audio", result.isTemporary)
        assertTrue("Should be in chat_media dir", result.file.absolutePath.contains("chat_media"))
        assertTrue("File should have extension", result.file.extension != null)

        context.contentResolver.delete(contentUri, null, null)
    }

    /** Helper to insert an image into MediaStore and return content:// URI */
    private fun insertImageIntoMediaStore(fileName: String, content: ByteArray): Uri? {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, when {
                fileName.endsWith(".png") -> "image/png"
                fileName.endsWith(".jpg") || fileName.endsWith(".jpeg") -> "image/jpeg"
                else -> "application/octet-stream"
            })
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES)
            }
        }

        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }

        val uri = resolver.insert(collection, values) ?: return null

        try {
            resolver.openOutputStream(uri)?.use { it.write(content) }
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            return null
        }
        return uri
    }

    /** Test subclass that exposes internal materializeMedia for testing */
    private class TestLlmRepository(private val testContext: android.content.Context) :
        LlmRepository(
            llmService = mockk(relaxed = true),
            chatDao = mockk(relaxed = true),
            modelRepository = mockk(relaxed = true),
            aiContextRepository = mockk(relaxed = true),
            userPreferencesRepository = mockk(relaxed = true),
            categoryRepository = mockk(relaxed = true),
            accountBalanceRepository = mockk(relaxed = true),
            transactionRepository = mockk(relaxed = true),
            budgetRepository = mockk(relaxed = true),
            ignoredAccountsStore = mockk(relaxed = true),
            context = testContext
        ) {
        // Expose internal materializeMedia for testing
        suspend fun exposeMaterializeMedia(uri: Uri, prefix: String, defaultExtension: String): MediaFile {
            return materializeMedia(uri, prefix, defaultExtension)
        }
    }
}