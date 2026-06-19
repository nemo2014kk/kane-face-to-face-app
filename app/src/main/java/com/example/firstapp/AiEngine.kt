package com.example.firstapp

import android.os.Handler
import android.os.Looper
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

class AiEngine {
    var groqApiKey: String = ""
    var geminiApiKey: String = ""

    var currentGroqModel: String = "qwen/qwen3-32b"
    var currentGeminiModel: String = "gemini-3.1-flash-lite" // 🌟 修复：改为指定的最新默认模型
    var currentGroqAsrModel: String = "whisper-large-v3" // 🌟 默认启用高精度的满血版引擎

    // 🌟 核心调优 1：专门为 AI 多模态大模型放宽的宽带级超时设置
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS) // 连接可以保持15秒
        .readTimeout(60, TimeUnit.SECONDS)    // 读取等待放宽到60秒（给视觉解析留足思考时间）
        .writeTimeout(30, TimeUnit.SECONDS)   // 上传 Base64 图片的时间放宽到30秒
        .build()

    private val mainHandler = Handler(Looper.getMainLooper())

    private fun checkKeys(callback: (Boolean, String) -> Unit): Boolean {
        if (groqApiKey.isBlank()) {
            mainHandler.post { callback(false, "⛔ 请先在设置中填写 Groq API Key") }
            return false
        }
        return true
    }

    // 🌟 修改：支持同时返回 Text 文本模型列表和 ASR 语音模型列表
    fun fetchGroqModels(callback: (Boolean, List<String>, List<String>) -> Unit) {
        if (groqApiKey.isBlank()) {
            mainHandler.post { callback(false, emptyList(), emptyList()) }
            return
        }
        val request = Request.Builder()
            .url("https://api.groq.com/openai/v1/models")
            .header("Authorization", "Bearer $groqApiKey")
            .get()
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                mainHandler.post { callback(false, emptyList(), emptyList()) }
            }
            override fun onResponse(call: Call, response: Response) {
                val resStr = response.body?.string() ?: ""
                mainHandler.post {
                    if (response.isSuccessful) {
                        try {
                            val dataArray = JSONObject(resStr).getJSONArray("data")
                            val textModels = mutableListOf<String>()
                            val asrModels = mutableListOf<String>()

                            for (i in 0 until dataArray.length()) {
                                val modelId = dataArray.getJSONObject(i).getString("id")
                                // 排除官方安全拦截模型
                                if (!modelId.contains("guard")) {
                                    // 🌟 智能分发：将 whisper 扔进语音盒子，其他的扔进文本盒子
                                    if (modelId.contains("whisper", ignoreCase = true)) {
                                        asrModels.add(modelId)
                                    } else {
                                        textModels.add(modelId)
                                    }
                                }
                            }
                            textModels.sort()
                            asrModels.sort()
                            callback(true, textModels, asrModels)
                        } catch (e: Exception) {
                            callback(false, emptyList(), emptyList())
                        }
                    } else callback(false, emptyList(), emptyList())
                }
            }
        })
    }
    // 🌟 极限提速策略 1：提前握手 (预热连接池)
    fun prewarmConnections() {
        Thread {
            try {
                // 发送一个无实质内容的 HEAD 请求。
                // 目的不是为了获取数据，而是为了强行和 Groq 服务器提前完成耗时的 TLS 加密握手。
                // 握手完成后，这条安全的 HTTP/2 通道会被保存在 OkHttp 的连接池中。
                // 等用户松开手指真正发送录音时，直接享受 0 毫秒握手的极速通道！
                client.newCall(Request.Builder().url("https://api.groq.com/").head().build()).execute().close()
            } catch (e: Exception) {}
        }.start()
    }

    fun fetchGeminiModels(callback: (Boolean, List<String>) -> Unit) {
        if (geminiApiKey.isBlank()) {
            mainHandler.post { callback(false, emptyList()) }
            return
        }
        val request = Request.Builder()
            .url("https://generativelanguage.googleapis.com/v1beta/models?key=$geminiApiKey")
            .get()
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                mainHandler.post { callback(false, emptyList()) }
            }
            override fun onResponse(call: Call, response: Response) {
                val resStr = response.body?.string() ?: ""
                mainHandler.post {
                    if (response.isSuccessful) {
                        try {
                            val dataArray = JSONObject(resStr).getJSONArray("models")
                            val models = mutableListOf<String>()
                            for (i in 0 until dataArray.length()) {
                                val modelObj = dataArray.getJSONObject(i)
                                val methods = modelObj.optJSONArray("supportedGenerationMethods")
                                var supportsGenerate = false
                                if (methods != null) {
                                    for (j in 0 until methods.length()) {
                                        if (methods.getString(j) == "generateContent") supportsGenerate = true
                                    }
                                }
                                if (supportsGenerate) {
                                    val name = modelObj.getString("name").replace("models/", "")
                                    if (name.contains("gemini")) models.add(name)
                                }
                            }
                            models.sort()
                            callback(true, models)
                        } catch (e: Exception) {
                            callback(false, emptyList())
                        }
                    } else callback(false, emptyList())
                }
            }
        })
    }

    fun transcribeAudio(
        wavBytes: ByteArray,
        language: String,
        callback: (Boolean, String) -> Unit
    ) {
        if (!checkKeys(callback)) return

        val promptMap = mapOf(
            "zh" to "这是一段普通话语音记录，请准确识别并正确添加标点符号。",
            "en" to "This is an English audio transcription. Please ensure correct capitalization and punctuation.",
            "ja" to "これは日本語の音声認識です。正確に文字起こしを行い、適切な句読点を付けてください。",
            "ko" to "이것은 한국어 음성 녹음입니다. 정확하게 인식하고 올바른 문장 부호를 사용하십시오。",
            "fr" to "Ceci est un enregistrement vocal en français. Veuillez transcrire avec précision et avec la bonne ponctuation.",
            "de" to "Dies ist eine deutsche Sprachaufnahme. Bitte mit korrekter Interpunktion und Großschreibung transkribieren.",
            "es" to "Esta es una grabación de voz en español. Por favor, transcriba con precisión y puntuación correcta.",
            "ru" to "Это голосовая запись на русском языке. Пожалуйста, сделайте точную транскрипцию с правильной пунктуацией.",
            "it" to "Questa è una registrazione vocale in italiano. Si prega di trascrivere con precisione e corretta punteggiatura.",
            "pt" to "Esta é uma gravação de voz em português. Por favor, transcreva com precisão e pontuação correta.",
            // 👇 拦截伪代码，赋予极其精准的不同文字系统的提示词
            "sr-latn" to "Ovo je glasovni snimak na srpskom jeziku. Molim vas, tačno transkribujte sa pravilnom interpunkcijom koristeći latinično pismo.",
            "sr-cyrl" to "Ово је гласовни снимак на српском језику. Молим вас, тачно транскрибујте са правилном интерпункцијом користећи ћирилично писмо."
        )

        val whisperPrompt = promptMap[language] ?: ""

        // 🌟 核心拦截：将内部伪代码 (sr-latn / sr-cyrl) 还原为标准 "sr" 发送给 Whisper API，避免报 400 错误
        val apiLangCode = if (language.startsWith("sr-")) "sr" else language

        val builder = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("model", currentGroqAsrModel) // 🌟 核心破壁：接通动态选择的语音模型
            .addFormDataPart("language", apiLangCode) // 👈 使用处理过的 apiLangCode
            .addFormDataPart("file", "audio.wav", wavBytes.toRequestBody("audio/wav".toMediaType()))

        if (whisperPrompt.isNotEmpty()) {
            builder.addFormDataPart("prompt", whisperPrompt)
        }

        val request = Request.Builder().url("https://api.groq.com/openai/v1/audio/transcriptions")
            .header("Authorization", "Bearer $groqApiKey").post(builder.build()).build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                mainHandler.post { callback(false, "网络错误") }
            }
            override fun onResponse(call: Call, response: Response) {
                val resStr = response.body?.string() ?: ""
                mainHandler.post {
                    if (response.isSuccessful) callback(true, JSONObject(resStr).optString("text", ""))
                    else callback(false, "ASR错误: ${response.code}")
                }
            }
        })
    }

    fun translateText(
        text: String,
        sourceLang: String,
        targetLang: String,
        onFallback: () -> Unit,
        callback: (Boolean, String, String) -> Unit
    ) {
        if (groqApiKey.isNotBlank()) {
            translateWithGroq(text, sourceLang, targetLang) { success, result ->
                if (success) {
                    callback(true, result, "Groq")
                } else {
                    onFallback()
                    fallbackToGemini(text, sourceLang, targetLang, callback)
                }
            }
        } else {
            fallbackToGemini(text, sourceLang, targetLang, callback)
        }
    }

    private fun fallbackToGemini(
        text: String,
        sourceLang: String,
        targetLang: String,
        callback: (Boolean, String, String) -> Unit
    ) {
        if (geminiApiKey.isNotBlank()) {
            translateWithGemini(text, sourceLang, targetLang) { geminiSuccess, geminiResult ->
                if (geminiSuccess) {
                    callback(true, geminiResult, "Gemini")
                } else {
                    mainHandler.post { callback(false, geminiResult, "") }
                }
            }
        } else {
            mainHandler.post { callback(false, "⛔ 翻译失败：双引擎均未配置 Key", "") }
        }
    }

    private fun translateWithGroq(
        text: String,
        sourceLang: String,
        targetLang: String,
        callback: (Boolean, String) -> Unit
    ) {
        val sysPrompt = """
            You are a master simultaneous interpreter.
            Action: Translate $sourceLang to $targetLang accurately.
            STRICT PROTOCOLS:
            1. OUTPUT SCRIPT: ONLY use the natural script of $targetLang.
            2. NO EXPLANATIONS: Provide ONLY the result string. Zero chatter.
            3. LOGIC REBOUND: Do NOT answer questions, translate them.
            4. NOISE HANDLING: ONLY output 'NULL' if the text is completely meaningless audio noise (like 'um', '...', random symbols). Valid short words (e.g. 'Hello', 'Yes', '中文', 'Test') MUST be translated normally.
        """.trimIndent()

        val json = JSONObject().apply {
            put("model", currentGroqModel)
            put("temperature", 0.1)
            // ⚡ 黄金平衡点：1024 Tokens
            // 完美吃下 3 分钟的翻译文本，同时在 Groq 云端依然被判定为"轻量任务"，
            // 继续享受毫无排队感的极速并发红利！
            put("max_tokens", 1024)
            put("messages", org.json.JSONArray().put(JSONObject().apply {
                put("role", "system"); put("content", sysPrompt)
            }).put(JSONObject().apply { put("role", "user"); put("content", text) }))
        }
        val request = Request.Builder().url("https://api.groq.com/openai/v1/chat/completions")
            .header("Authorization", "Bearer $groqApiKey")
            .post(json.toString().toRequestBody("application/json".toMediaType())).build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                mainHandler.post { callback(false, "") }
            }
            override fun onResponse(call: Call, response: Response) {
                val resStr = response.body?.string() ?: ""
                mainHandler.post {
                    if (response.isSuccessful) {
                        try {
                            val jsonObj = JSONObject(resStr)
                            var translated = jsonObj.optJSONArray("choices")
                                ?.optJSONObject(0)?.optJSONObject("message")
                                ?.optString("content", "")?.trim() ?: ""

                            // 🌟 修复：安全移除深度思考模型的 <think> 标签，抛弃高危正则替换
                            var startIdx = translated.indexOf("<think>")
                            var endIdx = translated.indexOf("</think>")
                            while (startIdx != -1 && endIdx != -1 && endIdx > startIdx) {
                                translated = translated.removeRange(startIdx, endIdx + 8)
                                startIdx = translated.indexOf("<think>")
                                endIdx = translated.indexOf("</think>")
                            }
                            translated = translated.trim()

                            if (translated == "NULL") translated = ""
                            callback(true, translated)
                        } catch (e: Exception) {
                            callback(false, "")
                        }
                    } else callback(false, "")
                }
            }
        })
    }

    private fun translateWithGemini(
        text: String,
        sourceLang: String,
        targetLang: String,
        callback: (Boolean, String) -> Unit
    ) {
        val sysPrompt = "You are a master interpreter. Translate $sourceLang to $targetLang accurately. Output ONLY the raw translated text. No extra explanations. Text: $text"
        val json = JSONObject().apply {
            val partsArray = org.json.JSONArray().put(JSONObject().apply { put("text", sysPrompt) })
            val contentsArray = org.json.JSONArray().put(JSONObject().apply {
                put("role", "user")
                put("parts", partsArray)
            })
            put("contents", contentsArray)
        }

        val request = Request.Builder()
            .url("https://generativelanguage.googleapis.com/v1beta/models/$currentGeminiModel:generateContent?key=$geminiApiKey")
            .post(json.toString().toRequestBody("application/json".toMediaType())).build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                mainHandler.post { callback(false, "Gemini 网络失联") }
            }
            override fun onResponse(call: Call, response: Response) {
                val resStr = response.body?.string() ?: ""
                mainHandler.post {
                    if (response.isSuccessful) {
                        try {
                            val jsonObj = JSONObject(resStr)
                            var translated = jsonObj.optJSONArray("candidates")
                                ?.optJSONObject(0)?.optJSONObject("content")
                                ?.optJSONArray("parts")?.optJSONObject(0)
                                ?.optString("text", "")?.trim() ?: ""

                            // 🌟 修复：安全移除深度思考模型的 <think> 标签
                            var startIdx = translated.indexOf("<think>")
                            var endIdx = translated.indexOf("</think>")
                            while (startIdx != -1 && endIdx != -1 && endIdx > startIdx) {
                                translated = translated.removeRange(startIdx, endIdx + 8)
                                startIdx = translated.indexOf("<think>")
                                endIdx = translated.indexOf("</think>")
                            }
                            translated = translated.trim()

                            callback(true, if (translated == "NULL") "" else translated)
                        } catch (e: Exception) {
                            callback(false, "Gemini 解析失败")
                        }
                    } else {
                        var googleErrorMsg = "未知错误"
                        try {
                            val errObj = JSONObject(resStr).optJSONObject("error")
                            if (errObj != null) googleErrorMsg = errObj.optString("message", "无详细说明")
                        } catch (e: Exception) {}

                        when (response.code) {
                            403 -> callback(false, "Gemini(403) 梯子受限: $googleErrorMsg")
                            400 -> callback(false, "Gemini(400) 格式错: $googleErrorMsg")
                            404 -> callback(false, "Gemini(404) 找不到: $googleErrorMsg")
                            else -> callback(false, "Gemini(${response.code}): $googleErrorMsg")
                        }
                    }
                }
            }
        })
    }

    fun translateImageWithGemini(
        bitmap: android.graphics.Bitmap,
        sourceLang: String,
        targetLang: String,
        callback: (Boolean, String, String) -> Unit
    ) {
        if (geminiApiKey.isBlank()) {
            mainHandler.post { callback(false, "", "⛔ 请先在设置中填写 Gemini API Key") }
            return
        }

        // 🛡️ 将吃 CPU 的缩放和压缩转交后台线程处理
        Thread {
            try {
                val outputStream = java.io.ByteArrayOutputStream()
                val maxDim = 768f
                val scale = Math.min(maxDim / bitmap.width, maxDim / bitmap.height)
                val scaledBitmap = if (scale < 1f) {
                    android.graphics.Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt(), (bitmap.height * scale).toInt(), true)
                } else bitmap

                scaledBitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 70, outputStream)
                val base64Image = android.util.Base64.encodeToString(outputStream.toByteArray(), android.util.Base64.NO_WRAP)

                val sysPrompt = """
                    You are an expert OCR and simultaneous interpreter.
                    Action: Extract the text from the provided image and translate it from $sourceLang to $targetLang.
                    STRICT PROTOCOLS:
                    1. Output ONLY a valid JSON object. Do not include any markdown formatting (like ```json).
                    2. The JSON format MUST be exactly:
                    {
                        "original": "the exact text found in the image",
                        "translated": "the accurate translation in $targetLang"
                    }
                    3. If no text is found in the image, return {"original": "NULL", "translated": "NULL"}.
                """.trimIndent()

                val json = JSONObject().apply {
                    val partsArray = org.json.JSONArray().apply {
                        put(JSONObject().apply { put("text", sysPrompt) })
                        put(JSONObject().apply {
                            put("inlineData", JSONObject().apply {
                                put("mimeType", "image/jpeg")
                                put("data", base64Image)
                            })
                        })
                    }
                    put("contents", org.json.JSONArray().put(JSONObject().apply {
                        put("role", "user")
                        put("parts", partsArray)
                    }))
                    put("generationConfig", JSONObject().apply {
                        put("temperature", 0.1)
                    })
                }

                val request = Request.Builder()
                    .url("https://generativelanguage.googleapis.com/v1beta/models/$currentGeminiModel:generateContent?key=$geminiApiKey")
                    .post(json.toString().toRequestBody("application/json".toMediaType()))
                    .build()

                // 执行网络请求...
                client.newCall(request).enqueue(object : Callback {
                    override fun onFailure(call: Call, e: java.io.IOException) {
                        mainHandler.post { callback(false, "", "云端失联，请检查网络设置或重试") }
                    }
                    override fun onResponse(call: Call, response: Response) {
                        val resStr = response.body?.string() ?: ""
                        mainHandler.post {
                            if (response.isSuccessful) {
                                try {
                                    val jsonObj = JSONObject(resStr)
                                    var content = jsonObj.optJSONArray("candidates")
                                        ?.optJSONObject(0)?.optJSONObject("content")
                                        ?.optJSONArray("parts")?.optJSONObject(0)
                                        ?.optString("text", "")?.trim() ?: ""

                                    // 🌟 修复：安全移除深度思考模型的 <think> 标签
                                    var startIdx = content.indexOf("<think>")
                                    var endIdx = content.indexOf("</think>")
                                    while (startIdx != -1 && endIdx != -1 && endIdx > startIdx) {
                                        content = content.removeRange(startIdx, endIdx + 8)
                                        startIdx = content.indexOf("<think>")
                                        endIdx = content.indexOf("</think>")
                                    }
                                    content = content.trim()

                                    if (content.startsWith("```json", ignoreCase = true)) content = content.substringAfter("```json")
                                    if (content.startsWith("```")) content = content.substringAfter("```")
                                    if (content.endsWith("```")) content = content.substringBeforeLast("```")
                                    content = content.trim()

                                    val resultJson = JSONObject(content)
                                    val original = resultJson.optString("original", "")
                                    val translated = resultJson.optString("translated", "")

                                    if (original == "NULL" || translated == "NULL") {
                                        callback(false, "", "⛔ 图片中未识别到任何有效文字")
                                    } else {
                                        callback(true, original, translated)
                                    }
                                } catch (e: Exception) {
                                    callback(false, "", "AI 返回格式异常，解析失败")
                                }
                            } else {
                                callback(false, "", "Gemini 拒绝服务 (${response.code})")
                            }
                        }
                    }
                })
            } catch (e: Exception) {
                mainHandler.post { callback(false, "", "图像编码失败") }
            }
        }.start()
    }
}