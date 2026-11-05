package com.pennywiseai.tracker.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Environment
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.ResponseFormat
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.Tool
import com.google.ai.edge.litertlm.ToolParam
import com.google.ai.edge.litertlm.ToolSet
import com.google.ai.edge.litertlm.tool
import com.pennywiseai.tracker.core.ChatModels
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/**
 * Debug-only prompt harness (never in release builds). Guarded by
 * android.permission.DUMP, which adb shell holds and third-party apps can't:
 *   adb shell am broadcast -n <pkg>/com.pennywiseai.tracker.debug.LlmProbeReceiver \
 *       -a LLM_PROBE --es system "<system prompt>" --es text "<user text>" \
 *       [--ef temp 0.1] [--es model /abs/path.litertlm] [--es mode text|tools|json] [--es schema '<json schema>']
 * mode=tools registers PennyTools (addTransaction / spendingByCategory) with
 * automaticToolCalling=false and records the model's tool calls; mode=json uses
 * constrained decoding (ResponseFormat.json) with the given schema.
 * Logs "LlmProbe: RESULT …" with timings only. The reply is written to the
 * app's private cache (llm_probe_reply.txt — read it with `run-as <pkg> cat
 * cache/llm_probe_reply.txt`), never to logcat, so prompt contents stay off
 * the shared log. One probe at a time; a second broadcast waits for the first.
 */
/** One eval case: prompt, expected amount, expected merchant substring (nullable). */
private data class EvalCase(val prompt: String, val amount: Double, val merchant: String?)

/** Tools the probe offers the model. Nothing here touches app data. */
@Suppress("unused")
class PennyTools : ToolSet {
    @Tool(description = "Record a new transaction the user is telling you about, e.g. money they spent or received.")
    fun addTransaction(
        @ToolParam(description = "Amount of money, as a number") amount: Double,
        @ToolParam(description = "Shop, service or person the money went to or came from") merchant: String,
        @ToolParam(description = "One of the known category names") category: String,
        @ToolParam(description = "EXPENSE or INCOME") type: String,
        @ToolParam(description = "Bank, card or cash the user mentioned, or empty") account: String,
        @ToolParam(description = "ISO currency code of the amount, e.g. INR; the user's default when not mentioned") currency: String
    ): String = "recorded"

    @Tool(description = "Total the user spent in a category this month")
    fun spendingByCategory(@ToolParam(description = "Category name") category: String): String = "1234 INR"
}

class LlmProbeReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "LlmProbe"
        private val lock = Mutex()
        private var engine: Engine? = null
        private var enginePath: String? = null
    }

    override fun onReceive(context: Context, intent: Intent) {
        val system = intent.getStringExtra("system") ?: ""
        val text = intent.getStringExtra("text") ?: return
        val temp = intent.getFloatExtra("temp", 0.1f).toDouble()
        val mode = intent.getStringExtra("mode") ?: "text"
        val schema = intent.getStringExtra("schema")
        // mode=eval: --ei n 10 limita a los primeros N casos (default: todos).
        val evalLimit = intent.getIntExtra("n", -1)
        // mode=image/audio: path del archivo multimodal enviado junto al texto.
        val mediaPath = intent.getStringExtra("media")
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                lock.withLock {
                    // La selección del usuario (ChatModels, en DataStore) manda cuando
                    // el intent no trae un path explícito: el probe debe probar lo que
                    // el chat productivo correría. Los receivers no participan en Hilt,
                    // así que el spec se resuelve directo del DataStore.
                    val explicitPath = intent.getStringExtra("model")
                    val modelPath = explicitPath ?: run {
                        val prefs = com.pennywiseai.tracker.data.preferences.UserPreferencesRepository(context)
                        val spec = ChatModels.byId(prefs.selectedChatModel.first())
                        File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), spec.fileName).absolutePath
                    }
                    // Clear the previous reply first so a failure can't be read as this probe's result.
                    File(context.cacheDir, "llm_probe_reply.txt").delete()
                    val t0 = System.currentTimeMillis()
                    // Modos multimodales: el vision/audio executor de LiteRT-LM carga
                    // lazy y SOLO se inicializa si el EngineConfig trae su backend —
                    // sin él, nativeSendMessage falla con "Vision executor should not
                    // be null, please TryLoadingVisionExecutor() first".
                    val eng = engine?.takeIf { enginePath == modelPath } ?: run {
                        engine?.close(); engine = null; enginePath = null
                        val multimodal = mode == "image" || mode == "audio"
                        Engine(
                            EngineConfig(
                                modelPath = modelPath,
                                backend = Backend.CPU(),
                                visionBackend = if (multimodal) Backend.CPU() else null,
                                audioBackend = if (multimodal) Backend.CPU() else null,
                                cacheDir = context.cacheDir.path
                            )
                        ).also { it.initialize(); engine = it; enginePath = modelPath }
                    }
                    val tLoad = System.currentTimeMillis()
                    val sampler = SamplerConfig(topK = 1, topP = 1.0, temperature = temp)
                    val reply = when (mode) {
                        "image", "audio" -> {
                            // Probe multimodal: Content.ImageFile/AudioFile + texto en
                            // un solo Contents; requiere un modelo multimodal (gemma-4).
                            val media = mediaPath?.let { path ->
                                if (!File(path).exists()) throw java.io.IOException("Media file not found: $path")
                                if (mode == "image") Content.ImageFile(path) else Content.AudioFile(path)
                            } ?: throw java.io.IOException("mode=$mode requires --es media /abs/path")
                            eng.createConversation(
                                ConversationConfig(systemInstruction = Contents.of(system), samplerConfig = sampler)
                            ).use { conv ->
                                val msg = conv.sendMessage(Contents.of(media, Content.Text(text)))
                                msg.contents.contents.filterIsInstance<Content.Text>().joinToString("") { it.text }
                            }
                        }
                        "tools" -> eng.createConversation(
                            ConversationConfig(
                                systemInstruction = Contents.of(system),
                                tools = listOf(tool(PennyTools())),
                                automaticToolCalling = false,
                                samplerConfig = sampler
                            )
                        ).use { conv ->
                            // Two turns in ONE conversation, to see whether the tool-schema
                            // prefill is paid once (the real chat keeps its conversation).
                            fun turn(t: String): String {
                                val s0 = System.currentTimeMillis()
                                val msg = conv.sendMessage(t)
                                val calls = msg.toolCalls.joinToString("; ") { "${it.name}(${it.arguments})" }
                                val txt = msg.contents.contents.filterIsInstance<Content.Text>().joinToString("") { it.text }
                                return "[${System.currentTimeMillis() - s0}ms] TOOL_CALLS=[$calls] TEXT=$txt"
                            }
                            val second = intent.getStringExtra("text2")
                            turn(text) + (second?.let { "\n" + turn(it) } ?: "")
                        }
                        "json" -> eng.createConversation(
                            ConversationConfig(
                                systemInstruction = Contents.of(system),
                                samplerConfig = sampler,
                                enableResponseFormat = true
                            )
                        ).use { conv ->
                            val msg = conv.sendMessage(text, responseFormat = ResponseFormat.json(schema ?: "{\"type\":\"object\"}"))
                            msg.contents.contents.filterIsInstance<Content.Text>().joinToString("") { it.text }
                        }
                        "eval" -> runEval(eng, system, sampler, temp, evalLimit)
                        else -> eng.createConversation(
                            ConversationConfig(systemInstruction = Contents.of(system), samplerConfig = sampler)
                        ).use { conv ->
                            conv.sendMessageAsync(text).toList()
                                .joinToString("") { m -> m.contents.contents.filterIsInstance<Content.Text>().joinToString("") { it.text } }
                        }
                    }
                    val t1 = System.currentTimeMillis()
                    File(context.cacheDir, "llm_probe_reply.txt").writeText(reply)
                    Log.i(TAG, "RESULT model=${File(modelPath).name} load=${tLoad - t0}ms gen=${t1 - tLoad}ms chars=${reply.length}")
                }
            } catch (e: Exception) {
                // Never let a failed probe be read as the previous reply.
                File(context.cacheDir, "llm_probe_reply.txt").delete()
                Log.e(TAG, "FAILED: ${e.javaClass.simpleName}: ${e.message}")
            } finally {
                pending.finish()
            }
        }
    }

    /**
     * F5-T1 eval rig: N prompts es-MX de transacción; mide tasa de tool-call correcto
     * (addTransaction con amount>0 y merchant no vacío). Cada caso corre en su propia
     * conversación para no contaminar el contexto entre casos.
     */
    private fun runEval(engine: Engine, system: String, sampler: SamplerConfig, temp: Double, limit: Int = -1): String {
        val allCases = listOf(
            EvalCase("gasté 250 pesos en Oxxo", 250.0, "Oxxo"),
            EvalCase("pagué 89 de luz en CFE", 89.0, "CFE"),
            EvalCase("compré café en Starbucks por 120 pesos", 120.0, "Starbucks"),
            EvalCase("uber me cobró 175 pesos", 175.0, "uber"),
            EvalCase("me depositaron 15000 de nómina", 15000.0, null),
            EvalCase("pagué la renta 8500 pesos", 8500.0, null),
            EvalCase("gasté 340 en la farmacia", 340.0, null),
            EvalCase("cinépolis 240 pesos", 240.0, "cinépolis"),
            EvalCase("recargué 100 pesos al celular", 100.0, null),
            EvalCase("pagué 1200 de internet", 1200.0, null),
            EvalCase("comida con amigos 560 pesos", 560.0, null),
            EvalCase("taxi al aeropuerto 430", 430.0, "taxi"),
            EvalCase("supermercado 890 pesos", 890.0, null),
            EvalCase("farmacia 210 pesos", 210.0, null),
            EvalCase("me regresaron 99 pesos del súper", 99.0, null),
            EvalCase("gasolina 600 pesos", 600.0, null),
            EvalCase("netflix 199 pesos", 199.0, "netflix"),
            EvalCase("gasté 75 en el estacionamiento", 75.0, null),
            EvalCase("pagué 2500 de la tarjeta de crédito", 2500.0, null),
            EvalCase("cine 180 pesos", 180.0, null),
            EvalCase("doné 200 pesos a la iglesia", 200.0, null),
            EvalCase("me cobraron 45 de comisión", 45.0, null),
            EvalCase("compré pan 35 pesos", 35.0, null),
            EvalCase("lavandería 150 pesos", 150.0, null),
            EvalCase("gasté 620 en el restaurante", 620.0, null),
            EvalCase("pagué 980 de luz", 980.0, null),
            EvalCase("transporte público 30 pesos", 30.0, null),
            EvalCase("gasté 450 en la peluquería", 450.0, null),
            EvalCase("amazon 670 pesos", 670.0, "amazon")
        )
        val cases = if (limit > 0) allCases.take(limit) else allCases
        val toolSet = listOf(tool(PennyTools()))
        var pass = 0
        val failures = mutableListOf<String>()
        for (case in cases) {
            val prompt = case.prompt
            val amount = case.amount
            val merchant = case.merchant
            val conv = engine.createConversation(
                ConversationConfig(
                    systemInstruction = Contents.of(system),
                    tools = toolSet,
                    automaticToolCalling = false,
                    samplerConfig = sampler
                )
            )
            try {
                val msg = conv.sendMessage(prompt)
                val call = msg.toolCalls.firstOrNull { it.name == "add_transaction" || it.name == "addTransaction" }
                if (call == null) {
                    failures.add("NO_CALL: $prompt")
                    continue
                }
                val args = call.arguments
                val a = (args["amount"] as? Number)?.toDouble() ?: (args["amount"] as? String)?.toDoubleOrNull()
                val m = (args["merchant"] as? String)?.trim().orEmpty()
                val ok = a != null && kotlin.math.abs(a - amount) < 0.01 && (merchant == null || m.contains(merchant, ignoreCase = true))
                if (ok) pass++ else failures.add("WRONG_ARGS: $prompt -> amount=$a merchant=$m (expected $amount/$merchant)")
            } catch (e: Exception) {
                failures.add("ERROR: $prompt -> ${e.javaClass.simpleName}: ${e.message}")
            } finally {
                conv.close()
            }
        }
        val total = cases.size
        val pct = if (total > 0) pass * 100.0 / total else 0.0
        return buildString {
            appendLine("EVAL tool-calling: $pass/$total correct (${"%.1f".format(pct)}%)")
            if (failures.isNotEmpty()) {
                appendLine("FAILURES:")
                failures.forEach { appendLine("  $it") }
            }
        }
    }
}
