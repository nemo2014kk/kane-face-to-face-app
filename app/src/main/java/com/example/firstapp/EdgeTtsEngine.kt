package com.example.firstapp

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.widget.Toast
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class EdgeTtsEngine(context: Context, private val fallbackTts: TextToSpeech) {
    var isSpeakerRoute = true
    var speakerVolume = 100
    var isPrivacyModeActive = false

    val actualSpeakerRoute: Boolean
        get() = isSpeakerRoute && !isPrivacyModeActive

    private val appContext = context.applicationContext
    private val sharedPrefs = appContext.getSharedPreferences("KaneAiPrefs", Context.MODE_PRIVATE)

    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var audioFocusRequest: AudioFocusRequest? = null

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    private val mainHandler = Handler(Looper.getMainLooper())
    private var mediaPlayer: MediaPlayer? = null
    private var currentCall: Call? = null

    // 🌟 航天级加固 1：彻底废弃 boolean 标志位，引入唯一的发音任务身份证！
    private val sessionCounter = AtomicInteger(0)

    private val blacklistedNodes = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private var lastPingTime: Long = 0L

    fun pingServer() {
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastPingTime < 120000) return
        lastPingTime = currentTime

        val mode = sharedPrefs.getString("tts_mode", "auto") ?: "auto"
        val urlsToPing = mutableListOf<String>()

        if (mode == "custom") {
            val url = sharedPrefs.getString("tts_server_url", "") ?: ""
            if (url.isNotBlank()) urlsToPing.add(url)
        } else {
            val nodes = sharedPrefs.getStringSet("tts_cached_nodes", emptySet()) ?: emptySet()
            urlsToPing.addAll(nodes)
        }

        val pingClient = client.newBuilder()
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(3, TimeUnit.SECONDS)
            .build()

        for (urlString in urlsToPing) {
            try {
                val httpUrl = urlString.toHttpUrlOrNull() ?: continue
                val baseUrl = httpUrl.newBuilder().encodedPath("/").query(null).build()
                val request = Request.Builder().url(baseUrl).get().build()

                pingClient.newCall(request).enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) {}
                    override fun onResponse(call: Call, response: Response) { response.close() }
                })
            } catch (e: Exception) {}
        }
    }

    val isSpeaking: Boolean get() = mediaPlayer?.isPlaying == true || fallbackTts.isSpeaking

    fun stop() {
        // 🌟 航天级加固 2：只要调用 stop，旧身份证瞬间作废，之前所有的网络请求就算回来了也会被直接丢弃！
        sessionCounter.incrementAndGet()

        currentCall?.cancel()
        currentCall = null
        try {
            mediaPlayer?.let {
                if (it.isPlaying) it.stop()
                it.release()
            }
        } catch (e: Exception) {}
        finally {
            mediaPlayer = null
        }
        fallbackTts.stop()

        restoreVolumeAndFocus()
    }

    private fun restoreVolumeAndFocus() {
        val willVolume = sharedPrefs.getInt("volume_will", -1)
        val streamType = sharedPrefs.getInt("stream_will", AudioManager.STREAM_MUSIC)

        if (willVolume != -1) {
            audioManager.setStreamVolume(streamType, willVolume, 0)
            sharedPrefs.edit().remove("volume_will").remove("stream_will").apply()
        }

        audioFocusRequest?.let {
            audioManager.abandonAudioFocusRequest(it)
            audioFocusRequest = null
        }
    }

    fun speak(
        text: String,
        voiceId: String,
        forceHeadset: Boolean = false,
        onNodeSelected: (String) -> Unit,
        onStart: (String) -> Unit,
        onDone: () -> Unit
    ) {
        stop() // 这个调用会产生一个新的身份证

        // 🌟 航天级加固 3：将本次发音的唯一身份证保存在局部变量中，供后续的所有异步回调核对！
        val currentSessionId = sessionCounter.get()

        var hasHeadset = false
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            for (device in devices) {
                if (device.type == android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                    device.type == android.media.AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                    device.type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                    device.type == android.media.AudioDeviceInfo.TYPE_BLE_HEADSET) {
                    hasHeadset = true
                    break
                }
            }
        }
        isPrivacyModeActive = forceHeadset && hasHeadset

        var processedText = text
        if (voiceId.startsWith("sr-RS-")) {
            val srMap = mapOf(
                "nj" to "њ", "Nj" to "Њ", "NJ" to "Њ", "lj" to "љ", "Lj" to "Љ", "LJ" to "Љ",
                "dž" to "џ", "Dž" to "Џ", "DŽ" to "Џ", "dj" to "ђ", "Dj" to "Ђ", "DJ" to "Ђ",
                "đ" to "ђ", "Đ" to "Ђ", "a" to "а", "b" to "б", "v" to "в", "g" to "г", "d" to "д",
                "e" to "е", "ž" to "ж", "z" to "з", "i" to "и", "j" to "ј", "k" to "к", "l" to "л",
                "m" to "м", "n" to "н", "o" to "о", "p" to "п", "r" to "р", "s" to "с", "t" to "т",
                "ć" to "ћ", "u" to "у", "f" to "ф", "h" to "х", "c" to "ц", "č" to "ч", "š" to "ш",
                "A" to "А", "B" to "Б", "V" to "В", "G" to "Г", "D" to "Д", "E" to "Е", "Ž" to "Ж",
                "Z" to "З", "I" to "И", "J" to "Ј", "K" to "К", "L" to "Л", "M" to "М", "N" to "Н",
                "O" to "О", "P" to "П", "R" to "Р", "S" to "С", "T" to "Т", "Ć" to "Ћ", "U" to "У",
                "F" to "Ф", "H" to "Х", "C" to "Ц", "Č" to "Ч", "Š" to "Ш"
            )
            for ((lat, cyr) in srMap) {
                processedText = processedText.replace(lat, cyr)
            }
        }

        val mode = sharedPrefs.getString("tts_mode", "auto") ?: "auto"
        var targetUrlStr = ""
        var nodeLabel = ""

        if (mode == "custom") {
            targetUrlStr = sharedPrefs.getString("tts_server_url", "") ?: ""
            nodeLabel = "自定义"
        } else {
            val nodes = sharedPrefs.getStringSet("tts_cached_nodes", emptySet())?.toList() ?: emptyList()
            if (nodes.isNotEmpty()) {
                val currentTime = System.currentTimeMillis()
                blacklistedNodes.entries.removeIf { currentTime - it.value > 10 * 60 * 1000 }
                val availableNodes = nodes.filter { !blacklistedNodes.containsKey(it) }

                targetUrlStr = if (availableNodes.isNotEmpty()) availableNodes.random() else {
                    blacklistedNodes.clear()
                    nodes.random()
                }

                val sortedNodes = nodes.sorted()
                val nodeIndex = sortedNodes.indexOf(targetUrlStr) + 1
                nodeLabel = "节点 $nodeIndex"
            }
        }

        if (nodeLabel.isNotEmpty()) {
            mainHandler.post {
                if (sessionCounter.get() == currentSessionId) onNodeSelected(nodeLabel)
            }
        }

        val currentToken = sharedPrefs.getString("tts_token", "") ?: ""

        if (targetUrlStr.isBlank()) {
            fallbackPlay(processedText, voiceId, currentSessionId, onStart, onDone, "未配置或未找到云端节点")
            return
        }

        val urlBuilder = targetUrlStr.toHttpUrlOrNull()?.newBuilder()
        if (urlBuilder == null) {
            fallbackPlay(processedText, voiceId, currentSessionId, onStart, onDone, "网址配置错误")
            return
        }

        val url = urlBuilder
            .addQueryParameter("text", processedText)
            .addQueryParameter("voice", voiceId)
            .addQueryParameter("token", currentToken)
            .build()

        val request = Request.Builder().url(url).build()
        val tempFile = File(appContext.cacheDir, "tts_${UUID.randomUUID().toString().take(6)}.mp3")

        var isFallbackTriggered = false
        val safeFallback: (String) -> Unit = { reason ->
            // 🌟 只有当前任务未被作废时，才允许启动降级播报
            if (sessionCounter.get() == currentSessionId && !isFallbackTriggered) {
                isFallbackTriggered = true
                fallbackPlay(processedText, voiceId, currentSessionId, onStart, onDone, reason)
            }
        }

        currentCall = client.newCall(request)
        currentCall?.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                mainHandler.post { safeFallback("云端联络失败") }
            }

            override fun onResponse(call: Call, response: Response) {
                // 🌟 航天级加固 4：网络返回后，第一件事就是核对身份证！过期直接丢弃数据。
                if (sessionCounter.get() != currentSessionId) {
                    response.close()
                    return
                }

                if (response.code == 403) {
                    mainHandler.post { safeFallback("暗号校验被拒(403)") }
                    return
                }

                if (!response.isSuccessful || response.body == null) {
                    val code = response.code
                    if (code == 429 || code == 404 || code == 500 || code == 503 || code == 504 || code == 403) {
                        blacklistedNodes[targetUrlStr] = System.currentTimeMillis()
                    }
                    val errorReason = when (code) {
                        500 -> "微软合成引擎崩溃(500)"
                        503, 504 -> "云端节点休眠或拥堵($code)"
                        400 -> "发送了无效文本(400)"
                        429 -> "请求过快被微软限流(429)"
                        404 -> "服务器找不到了(404)"
                        else -> "云端未知异常($code)"
                    }
                    mainHandler.post { safeFallback(errorReason) }
                    return
                }

                try {
                    val inputStream = response.body!!.byteStream()
                    val fos = FileOutputStream(tempFile)
                    inputStream.copyTo(fos)
                    fos.close()
                    inputStream.close()
                    mainHandler.post { playAudio(tempFile, currentSessionId, onStart, onDone) }
                } catch (e: Exception) {
                    tempFile.delete() // 👈 补上这一行：如果下载中途被掐断报错，立刻销毁残缺文件！
                    mainHandler.post { safeFallback("音频解码异常") }
                }
            }
        })
    }

    private fun playAudio(file: File, sessionId: Int, onStart: (String) -> Unit, onDone: () -> Unit) {
        // 🌟 航天级加固 5：准备实例化 MediaPlayer 前，再次核对身份证！
        if (sessionCounter.get() != sessionId) {
            file.delete()
            return
        }

        if (!file.exists() || file.length() < 100) {
            file.delete()
            if (sessionCounter.get() == sessionId) onDone()
            return
        }

        try {
            mediaPlayer = MediaPlayer().apply {
                val actualSpeakerRoute = isSpeakerRoute && !isPrivacyModeActive

                val usage = if (actualSpeakerRoute) AudioAttributes.USAGE_ALARM else AudioAttributes.USAGE_MEDIA
                val streamType = if (actualSpeakerRoute) AudioManager.STREAM_ALARM else AudioManager.STREAM_MUSIC

                setAudioAttributes(AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .setUsage(usage)
                    .build())
                val fis = FileInputStream(file)
                setDataSource(fis.fd)
                fis.close()

                if (actualSpeakerRoute) {
                    val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                    for (device in devices) {
                        if (device.type == android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) {
                            this.preferredDevice = device
                            break
                        }
                    }
                }

                setOnPreparedListener { mp ->
                    // 🌟 航天级加固 6：底层音频流就绪准备发声前，最后一次核对身份证！
                    if (sessionCounter.get() == sessionId) {
                        val focusAttr = AudioAttributes.Builder()
                            .setUsage(usage)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                        audioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                            .setAudioAttributes(focusAttr)
                            .setAcceptsDelayedFocusGain(false)
                            .setOnAudioFocusChangeListener { }
                            .build()
                        audioManager.requestAudioFocus(audioFocusRequest!!)

                        if (actualSpeakerRoute) {
                            val currentVol = audioManager.getStreamVolume(streamType)
                            sharedPrefs.edit().putInt("volume_will", currentVol)
                                .putInt("stream_will", streamType).apply()

                            val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM)
                            val targetVol = (speakerVolume / 100f * maxVol).toInt()
                            audioManager.setStreamVolume(AudioManager.STREAM_ALARM, targetVol, 0)
                        }

                        mp.start()
                        onStart("🔊 微软云端播报中...")
                    } else {
                        // 如果在准备期间用户已经切走了，直接销毁，不发出任何声音！
                        mp.release()
                        file.delete() // 👈 补上这一行：既然不让它发声了，顺手把这个没用的录音文件销毁掉！
                    }
                }

                setOnCompletionListener {
                    it.release()
                    mediaPlayer = null
                    file.delete()
                    restoreVolumeAndFocus()
                    if (sessionCounter.get() == sessionId) onDone()
                }

                setOnErrorListener { mp, _, _ ->
                    mp.release()
                    mediaPlayer = null
                    file.delete()
                    restoreVolumeAndFocus()
                    if (sessionCounter.get() == sessionId) onDone()
                    true
                }

                prepareAsync()
            }
        } catch (e: Exception) {
            file.delete()
            if (sessionCounter.get() == sessionId) onDone()
        }
    }

    private fun fallbackPlay(text: String, voiceId: String, sessionId: Int, onStart: (String) -> Unit, onDone: () -> Unit, reason: String) {
        if (sessionCounter.get() != sessionId) return

        mainHandler.post { Toast.makeText(appContext, "⚠️ 播报质量降级: $reason", Toast.LENGTH_LONG).show() }
        try {
            val langTag = voiceId.split("-").take(2).joinToString("-")
            fallbackTts.language = java.util.Locale.forLanguageTag(langTag)
        } catch (e: Exception) {}

        fallbackTts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "fallback_id")
        onStart("🔊 本地引擎播报中...")

        mainHandler.postDelayed({
            if (sessionCounter.get() == sessionId) onDone()
        }, 2000)
    }

    fun switchRouteOnTheFly() {
        val mp = mediaPlayer ?: return
        if (!mp.isPlaying) return

        stop()
        mainHandler.post {
            Toast.makeText(appContext, "🔄 路由已切换，将于下次发音生效", Toast.LENGTH_SHORT).show()
        }
    }
}