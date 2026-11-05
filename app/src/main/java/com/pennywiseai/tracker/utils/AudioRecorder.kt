package com.pennywiseai.tracker.utils

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile

/**
 * Grabación de notas de voz para el chat (F3): hold-to-record.
 *
 * Pipeline: PCM 16-bit mono a 16 kHz en un .wav temporal del cacheDir. LiteRT-LM
 * decodifica Content.AudioFile con un decoder que solo garantiza WAV (también
 * MP3/FLAC); el contenedor .m4a/OPUS que producía MediaRecorder no es legible
 * por el engine, así que se graba PCM crudo y se escribe la cabecera WAV a mano.
 * El archivo se sirve vía FileProvider (`voice_notes`).
 */
object AudioRecorder {

    private const val TAG = "AudioRecorder"
    private const val SAMPLE_RATE = 16_000 // Hz — lo que espera el audio frontend de Gemma 3n
    private const val BYTES_PER_SAMPLE = 2
    private const val WAV_HEADER_BYTES = 44
    private const val MAX_DURATION_MS = 60_000L
    private const val MIN_DURATION_MS = 500L
    private const val MAX_PCM_BYTES = SAMPLE_RATE * BYTES_PER_SAMPLE * (MAX_DURATION_MS / 1000).toInt()

    private var record: AudioRecord? = null
    private var worker: Thread? = null
    private var outputFile: File? = null
    private var startedAt = 0L

    /** Se vuelve false para pedirle al hilo de captura que termine. */
    @Volatile private var capturing = false

    /** Inicia la grabación en un archivo temporal del cacheDir. Requiere RECORD_AUDIO concedido. */
    @Synchronized
    fun start(context: Context): Boolean {
        if (record != null) return false
        val minBuffer = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuffer <= 0) return false

        val file = File(context.cacheDir, "voice_${System.currentTimeMillis()}.wav")
        val audioRecord = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                minBuffer * 2
            )
        } catch (e: SecurityException) {
            Log.e(TAG, "start() sin permiso RECORD_AUDIO", e)
            return false
        }
        if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord no se pudo inicializar")
            audioRecord.release()
            return false
        }

        return try {
            audioRecord.startRecording()
            record = audioRecord
            outputFile = file
            startedAt = System.currentTimeMillis()
            capturing = true
            worker = Thread({ capture(audioRecord, file, minBuffer) }, "voice-recorder").also { it.start() }
            true
        } catch (e: Exception) {
            Log.e(TAG, "start() falló", e)
            capturing = false
            record = null
            outputFile = null
            runCatching { audioRecord.release() }
            file.delete()
            false
        }
    }

    /** Hilo de captura: escribe PCM tras una cabecera provisional y la corrige al terminar. */
    private fun capture(audioRecord: AudioRecord, file: File, bufferSize: Int) {
        val buffer = ByteArray(bufferSize)
        var pcmBytes = 0
        try {
            RandomAccessFile(file, "rw").use { out ->
                out.setLength(0)
                out.write(ByteArray(WAV_HEADER_BYTES))
                while (capturing && pcmBytes < MAX_PCM_BYTES) {
                    val read = audioRecord.read(buffer, 0, buffer.size)
                    if (read <= 0) break
                    out.write(buffer, 0, read)
                    pcmBytes += read
                }
                out.seek(0)
                out.write(wavHeader(pcmBytes))
            }
        } catch (e: Exception) {
            Log.e(TAG, "captura falló", e)
        }
    }

    /**
     * Detiene la grabación. Devuelve el Uri del archivo listo para enviar, o null
     * si la grabación fue demasiado corta (< [MIN_DURATION_MS]) o falló.
     */
    suspend fun stop(context: Context): Uri? = withContext(Dispatchers.IO) {
        val (file, elapsed) = finish() ?: return@withContext null
        if (!file.exists() || elapsed < MIN_DURATION_MS) {
            file.delete()
            return@withContext null
        }
        try {
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        } catch (e: Exception) {
            Log.e(TAG, "stop() falló", e)
            file.delete()
            null
        }
    }

    /** Cancela la grabación en curso y borra el archivo. */
    fun cancel() {
        finish()?.first?.delete()
    }

    fun isRecording(): Boolean = record != null

    /** Duración máxima de una nota de voz en ms. */
    const val maxDurationMs: Long = MAX_DURATION_MS

    /** Detiene captura, espera al hilo y libera el recorder. Devuelve archivo y duración, o null si no había grabación. */
    @Synchronized
    private fun finish(): Pair<File, Long>? {
        val audioRecord = record ?: return null
        val file = outputFile
        val thread = worker
        record = null
        outputFile = null
        worker = null
        capturing = false
        val elapsed = System.currentTimeMillis() - startedAt
        runCatching { audioRecord.stop() }
        runCatching { thread?.join(1_000) }
        runCatching { audioRecord.release() }
        return file?.let { it to elapsed }
    }

    /** Cabecera RIFF/WAVE de 44 bytes para PCM 16-bit mono a [SAMPLE_RATE]. */
    private fun wavHeader(pcmBytes: Int): ByteArray {
        val byteRate = SAMPLE_RATE * BYTES_PER_SAMPLE
        val header = ByteArray(WAV_HEADER_BYTES)
        fun putInt(offset: Int, value: Int) {
            for (i in 0 until 4) header[offset + i] = (value shr (8 * i) and 0xFF).toByte()
        }
        fun putShort(offset: Int, value: Int) {
            header[offset] = (value and 0xFF).toByte()
            header[offset + 1] = (value shr 8 and 0xFF).toByte()
        }
        "RIFF".toByteArray().copyInto(header, 0)
        putInt(4, 36 + pcmBytes)
        "WAVEfmt ".toByteArray().copyInto(header, 8)
        putInt(16, 16)              // tamaño del bloque fmt
        putShort(20, 1)             // PCM
        putShort(22, 1)             // mono
        putInt(24, SAMPLE_RATE)
        putInt(28, byteRate)
        putShort(32, BYTES_PER_SAMPLE)
        putShort(34, 16)            // bits por muestra
        "data".toByteArray().copyInto(header, 36)
        putInt(40, pcmBytes)
        return header
    }
}
