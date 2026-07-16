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

    var currentGroqModel: String = "openai/gpt-oss-120b" // 🌟 紧急热更：应对 Qwen 下架，切换为官方推荐的 GPT OSS
    var currentGeminiModel: String = "gemini-3.1-flash-lite" // 🌟 视觉/翻译专属
    var currentGeminiLiveModel: String = "gemini-3.5-live-translate-preview" // 🌟 实时同传专属
    var currentGroqAsrModel: String = "whisper-large-v3"

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

    fun fetchGeminiModels(callback: (Boolean, List<String>, List<String>) -> Unit) {
        if (geminiApiKey.isBlank()) {
            mainHandler.post { callback(false, emptyList(), emptyList()) }
            return
        }
        val request = Request.Builder()
            .url("https://generativelanguage.googleapis.com/v1beta/models?key=$geminiApiKey")
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
                            val dataArray = JSONObject(resStr).getJSONArray("models")
                            val visionModels = mutableListOf<String>() // 视觉文本盒子
                            val liveModels = mutableListOf<String>()   // 同传专用盒子

                            for (i in 0 until dataArray.length()) {
                                val modelObj = dataArray.getJSONObject(i)
                                val name = modelObj.getString("name").replace("models/", "")
                                if (!name.contains("gemini")) continue

                                val methods = modelObj.optJSONArray("supportedGenerationMethods")
                                var supportsGenerate = false
                                var supportsBidi = false // WebSocket 实时同传协议

                                if (methods != null) {
                                    for (j in 0 until methods.length()) {
                                        val method = methods.getString(j)
                                        if (method == "generateContent") supportsGenerate = true
                                        if (method == "bidiGenerateContent") supportsBidi = true
                                    }
                                }

                                // 🌟 智能分流：支持 Bidi 协议或者名字里带 live 的扔进同传盒子
                                if (supportsBidi || name.contains("live", ignoreCase = true)) {
                                    liveModels.add(name)
                                }
                                // 其他的常规模型扔进视觉文本盒子
                                if (supportsGenerate && !name.contains("live", ignoreCase = true)) {
                                    visionModels.add(name)
                                }
                            }

                            // 👇 🌟 核心破壁：强制注入官方文档中隐藏的 Preview 预览版模型
                            if (!liveModels.contains("gemini-3.5-live-translate-preview")) {
                                liveModels.add("gemini-3.5-live-translate-preview")
                            }
                            // 👆 注入结束

                            visionModels.sort()
                            liveModels.sort()
                            callback(true, visionModels, liveModels)
                        } catch (e: Exception) {
                            callback(false, emptyList(), emptyList())
                        }
                    } else callback(false, emptyList(), emptyList())
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
                    if (response.isSuccessful) {
                        callback(true, JSONObject(resStr).optString("text", ""))
                    } else {
                        // 🌟 精准提取 Whisper 语音模型下架的真实报错
                        var errorMsg = "ASR错误: ${response.code}"
                        try {
                            val errObj = JSONObject(resStr).optJSONObject("error")
                            if (errObj != null) errorMsg = errObj.optString("message", errorMsg)
                        } catch (e: Exception) {}
                        callback(false, errorMsg)
                    }
                }
            }
        })
    }

    fun translateText(
        text: String,
        sourceLang: String,
        targetLang: String,
        onFallback: (Boolean) -> Unit, // 👈 增加标志，通知 UI 引擎是否死于模型下架
        callback: (Boolean, String, String) -> Unit
    ) {
        if (groqApiKey.isNotBlank()) {
            translateWithGroq(text, sourceLang, targetLang) { success, result ->
                if (success) {
                    callback(true, result, "Groq")
                } else {
                    // 🌟 雷达嗅探：判断是否因为模型下架导致
                    val isModelDead = result.contains("model", ignoreCase = true) &&
                        (result.contains("not exist", ignoreCase = true) ||
                         result.contains("not found", ignoreCase = true) ||
                         result.contains("404"))

                    onFallback(isModelDead) // 触动警报器
                    fallbackToGemini(text, sourceLang, targetLang, callback)
                }
            }
        } else {
            onFallback(false)
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
            // 🌟 升级：为 Qwen 等深度思考大模型放宽限制到 2048，防止思考到一半被憋死
            put("max_tokens", 2048)
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
                            // 1. 保留最原始的数据，作为兜底底牌
                            val rawOutput = jsonObj.optJSONArray("choices")
                                ?.optJSONObject(0)?.optJSONObject("message")
                                ?.optString("content", "")?.trim() ?: ""

                            // 🌟 核心排错 1：深度推理模型截断侦测
                            // 如果发现有 <think> 但没有闭合标签 </think>，说明模型由于超时或达到 max_tokens 被强行掐断了！
                            // 此时绝不能返回空字符串（否则主界面会误判为杂音），必须直接抛出 false，强制激活外层的 Gemini 备用引擎完美接管！
                            if (rawOutput.contains("<think>", ignoreCase = true) && !rawOutput.contains("</think>", ignoreCase = true)) {
                                callback(false, "大模型深度推理被截断")
                                return@post
                            }

                            var translated = rawOutput

                            // 2. 安全移除完整闭合的 <think>...</think>
                            translated = translated.replace(Regex("<think>.*?</think>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)), "").trim()

                            // 3. 脱壳：清理大模型由于 Prompt 约束产生的 Markdown 保护壳 (例如 ```json \n 你好 \n ```)
                            if (translated.startsWith("```")) {
                                val firstNewLine = translated.indexOf('\n')
                                if (firstNewLine != -1) {
                                    translated = translated.substring(firstNewLine).trim()
                                }
                                if (translated.endsWith("```")) {
                                    translated = translated.substring(0, translated.length - 3).trim()
                                }
                            }

                            // 4. 绞杀模型泄漏的各类常见废话前缀
                            val garbagePrefixes = listOf("translation:", "translated:", "output:", "here is the translation:", "the translation is:", "翻译：", "译文：", "输出：")
                            for (prefix in garbagePrefixes) {
                                if (translated.lowercase().startsWith(prefix)) {
                                    translated = translated.substring(prefix.length).trim()
                                    break
                                }
                            }

                            // 5. 脱壳：清除部分模型画蛇添足加上的前后英文双引号
                            if (translated.startsWith("\"") && translated.endsWith("\"") && translated.length >= 2) {
                                translated = translated.substring(1, translated.length - 1).trim()
                            }

                            // 6. 标准杂音识别 (严格遵从 Prompt)
                            if (translated == "NULL") translated = ""

                            callback(true, translated)

                        } catch (e: Exception) {
                            callback(false, "解析异常")
                        }
                    } else {
                        // 🌟 精准提取大模型下架或封控的真实死因
                        var errorMsg = "Groq拒绝服务 (${response.code})"
                        try {
                            val errObj = JSONObject(resStr).optJSONObject("error")
                            if (errObj != null) errorMsg = errObj.optString("message", errorMsg)
                        } catch (e: Exception) {}
                        callback(false, errorMsg)
                    }
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
                            val rawOutput = jsonObj.optJSONArray("candidates")
                                ?.optJSONObject(0)?.optJSONObject("content")
                                ?.optJSONArray("parts")?.optJSONObject(0)
                                ?.optString("text", "")?.trim() ?: ""

                            // 🌟 防护 1：未闭合的 <think> 截断侦测
                            // 如果模型因为被限流或其他原因卡死，连 </think> 都没吐出来，直接判定失败，丢弃废料
                            if (rawOutput.contains("<think>", ignoreCase = true) && !rawOutput.contains("</think>", ignoreCase = true)) {
                                callback(false, "Gemini 推理被截断")
                                return@post
                            }

                            var translated = rawOutput

                            // 🌟 防护 2：安全脱去思考外壳与 Markdown 代码壳
                            translated = translated.replace(Regex("<think>.*?</think>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)), "").trim()

                            if (translated.startsWith("```")) {
                                val firstNewLine = translated.indexOf('\n')
                                if (firstNewLine != -1) {
                                    translated = translated.substring(firstNewLine).trim()
                                }
                                if (translated.endsWith("```")) {
                                    translated = translated.substring(0, translated.length - 3).trim()
                                }
                            }

                            // 🌟 防护 3：绞杀各种模型喜欢画蛇添足的废话前缀
                            val garbagePrefixes = listOf("translation:", "translated:", "output:", "here is the translation:", "the translation is:", "翻译：", "译文：", "输出：")
                            for (prefix in garbagePrefixes) {
                                if (translated.lowercase().startsWith(prefix)) {
                                    translated = translated.substring(prefix.length).trim()
                                    break
                                }
                            }

                            // 🌟 防护 4：脱去无意义的双引号
                            if (translated.startsWith("\"") && translated.endsWith("\"") && translated.length >= 2) {
                                translated = translated.substring(1, translated.length - 1).trim()
                            }

                            if (translated == "NULL") translated = ""

                            callback(true, translated)
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

    // 🌟 新增：用于接收带有物理坐标的视觉翻译数据体
    data class ImageRegion(val ymin: Int, val xmin: Int, val ymax: Int, val xmax: Int, val original: String, val translated: String)

    fun translateImageWithGemini(
        bitmap: android.graphics.Bitmap,
        sourceLang: String,
        targetLang: String,
        callback: (Boolean, List<ImageRegion>, String) -> Unit
    ) {
        if (geminiApiKey.isBlank()) {
            mainHandler.post { callback(false, emptyList(), "⛔ 请先在设置中填写 Gemini API Key") }
            return
        }

        // 🛡️ 将吃 CPU 的缩放和压缩转交后台线程处理
        Thread {
            try {
                val outputStream = java.io.ByteArrayOutputStream()
                // 🌟 核心修复 2：将分辨率上限由 768 提至 1536，完美看清密集的发票和菜单
                val maxDim = 1536f
                val scale = Math.min(maxDim / bitmap.width, maxDim / bitmap.height)
                val scaledBitmap = if (scale < 1f) {
                    android.graphics.Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt(), (bitmap.height * scale).toInt(), true)
                } else bitmap

                scaledBitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 70, outputStream)
                val base64Image = android.util.Base64.encodeToString(outputStream.toByteArray(), android.util.Base64.NO_WRAP)

                // 🌟 核心修复 3：主动回收废弃的 Bitmap，断绝图片积压导致的内存泄漏
                if (scaledBitmap != bitmap) {
                    scaledBitmap.recycle()
                }

                // 🌟 核心升级：强迫大模型输出带有归一化物理坐标的 JSON 数组
                val sysPrompt = """
                    You are an expert OCR and simultaneous interpreter.
                    Action: Extract the text from the provided image and translate it from $sourceLang to $targetLang.
                    STRICT PROTOCOLS:
                    1. Output ONLY a valid JSON array of objects. Do not include any markdown formatting (like ```json).
                    2. The JSON format MUST be exactly:
                    [
                        {
                            "box_2d": [ymin, xmin, ymax, xmax],
                            "original": "exact text found in this region",
                            "translated": "accurate translation in $targetLang"
                        }
                    ]
                    3. Coordinates must be normalized integers between 0 and 1000. [ymin, xmin, ymax, xmax].
                    4. If no text is found in the image, return an empty array [].
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

                client.newCall(request).enqueue(object : Callback {
                    override fun onFailure(call: Call, e: java.io.IOException) {
                        mainHandler.post { callback(false, emptyList(), "云端失联，请检查网络设置或重试") }
                    }
                    override fun onResponse(call: Call, response: Response) {
                        val resStr = response.body?.string() ?: ""
                        mainHandler.post {
                            if (response.isSuccessful) {
                                try {
                                    val jsonObj = JSONObject(resStr)
                                    val rawOutput = jsonObj.optJSONArray("candidates")
                                        ?.optJSONObject(0)?.optJSONObject("content")
                                        ?.optJSONArray("parts")?.optJSONObject(0)
                                        ?.optString("text", "")?.trim() ?: ""

                                    var content = rawOutput

                                    content = content.replace(Regex("<think>.*?</think>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)), "").trim()

                                    if (content.contains("<think>", ignoreCase = true)) {
                                        content = content.replace(Regex("<think>.*", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)), "").trim()
                                    }

                                    // 终极救场：如果清除完发现空了，说明 JSON 被包在思考框里了，直接从原数据里扒 JSON
                                    val targetText = if (content.isBlank()) rawOutput else content

                                    // 核心修复：暴力正则提取 JSON
                                    val regex = Regex("\\[.*\\]", RegexOption.DOT_MATCHES_ALL)
                                    val matchResult = regex.find(targetText)
                                    if (matchResult != null) {
                                        content = matchResult.value
                                    } else {
                                        callback(false, emptyList(), "AI 未返回有效坐标数据")
                                        return@post
                                    }

                                    // 解析为坐标数据组
                                    val resultJson = org.json.JSONArray(content)
                                    val regions = mutableListOf<ImageRegion>()
                                    for (i in 0 until resultJson.length()) {
                                        val item = resultJson.optJSONObject(i) ?: continue
                                        val box = item.optJSONArray("box_2d")
                                        if (box != null && box.length() == 4) {
                                            regions.add(ImageRegion(
                                                ymin = box.optInt(0, 0).coerceIn(0, 1000),
                                                xmin = box.optInt(1, 0).coerceIn(0, 1000),
                                                ymax = box.optInt(2, 0).coerceIn(0, 1000),
                                                xmax = box.optInt(3, 0).coerceIn(0, 1000),
                                                original = item.optString("original", ""),
                                                translated = item.optString("translated", "")
                                            ))
                                        }
                                    }

                                    if (regions.isEmpty()) {
                                        callback(false, emptyList(), "⛔ 图片中未识别到任何有效文字")
                                    } else {
                                        callback(true, regions, "")
                                    }
                                } catch (e: Exception) {
                                    callback(false, emptyList(), "AI 返回格式异常，解析失败")
                                }
                            } else {
                                callback(false, emptyList(), "Gemini 拒绝服务 (${response.code})")
                            }
                        }
                    }
                })
            } catch (e: Exception) {
                mainHandler.post { callback(false, emptyList(), "图像编码失败") }
            }
        }.start()
    }
}