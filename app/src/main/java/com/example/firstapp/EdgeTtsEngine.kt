package com.example.firstapp

import android.content.Context
import android.media.AudioAttributes
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

class EdgeTtsEngine(context: Context, private val fallbackTts: TextToSpeech) {

    // 🛡️ 提取全局唯一的 ApplicationContext，它与 App 同生共死，彻底断绝内存泄漏
    private val appContext = context.applicationContext

    // 🌟 核心修改 1：建立与 MainActivity 共享的记忆体读取通道
    private val sharedPrefs = appContext.getSharedPreferences("KaneAiPrefs", Context.MODE_PRIVATE)

    // ⚠️ 已删除写死的 PYTHON_SERVER_URL 和 KANE_SECRET_TOKEN

    // 🌟 核心调优：专为面对面对话定制的极限防卡死机制
    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)  // 握手时间压缩到 5 秒（连不上直接放弃）
        .readTimeout(8, TimeUnit.SECONDS)     // 读取时间压缩到 8 秒（8秒不出声，无情斩断，光速切本地 TTS 救场）
        .build()

    private val mainHandler = Handler(Looper.getMainLooper())
    private var mediaPlayer: MediaPlayer? = null
    private var currentCall: Call? = null

    private var isCancelledManually = false

    // 🌟 新增：小黑屋机制记录 (URL -> 关入时间戳)
    // 🛡️ 航天级修复：换用线程安全的并发哈希表，杜绝因异步网络回调与主线程同时读写导致的崩溃
    private val blacklistedNodes = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private var lastPingTime: Long = 0L

    // 🌟 升级：0成本的集群静默唤醒器 (群发 Ping)
    fun pingServer() {
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastPingTime < 120000) return // 2分钟防抖
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

        // ⚡ 极速客制化 Client：复用连接池，仅把超时时间压榨到 3 秒
        val pingClient = client.newBuilder()
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(3, TimeUnit.SECONDS)
            .build()

        // 🚀 群发阅后即焚：同时唤醒列表里的所有服务器
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
        isCancelledManually = true
        currentCall?.cancel()
        currentCall = null
        try {
            mediaPlayer?.let {
                // 🌟 这里可能会遇到 MediaPlayer 正在异步准备期间被强制调 stop 导致的抛错
                // 没关系，直接吞掉这个 Exception 并 release，保证 App 绝不崩溃
                if (it.isPlaying) it.stop()
                it.release()
            }
        } catch (e: Exception) {}
        finally {
            mediaPlayer = null
        }
        fallbackTts.stop()
    }

    // 🌟 核心修改：新增了 onNodeSelected 回调，用于将选中的节点名字传给 UI
    fun speak(
        text: String,
        voiceId: String,
        onNodeSelected: (String) -> Unit,
        onStart: (String) -> Unit,
        onDone: () -> Unit
    ) {
        stop()
        isCancelledManually = false

        // 🌟 动态判断模式并随机抽取可用节点
        val mode = sharedPrefs.getString("tts_mode", "auto") ?: "auto"
        var targetUrlStr = ""
        var nodeLabel = "" // 用于记录当前选中了哪个节点

        if (mode == "custom") {
            targetUrlStr = sharedPrefs.getString("tts_server_url", "") ?: ""
            nodeLabel = "自定义"
        } else {
            val nodes = sharedPrefs.getStringSet("tts_cached_nodes", emptySet())?.toList() ?: emptyList()
            if (nodes.isNotEmpty()) {
                val currentTime = System.currentTimeMillis()
                // 🧹 智能洗牌：清理刑满释放的节点 (关押超10分钟即释放)
                blacklistedNodes.entries.removeIf { currentTime - it.value > 10 * 60 * 1000 }
                val availableNodes = nodes.filter { !blacklistedNodes.containsKey(it) }

                targetUrlStr = if (availableNodes.isNotEmpty()) {
                    availableNodes.random() // 🎲 随机负载均衡
                } else {
                    // 全军覆没，紧急洗牌释放所有节点
                    blacklistedNodes.clear()
                    nodes.random()
                }

                // 🌟 核心算法：对 URL 列表进行首字母排序，赋予固定序号，用于 UI 展示
                val sortedNodes = nodes.sorted()
                val nodeIndex = sortedNodes.indexOf(targetUrlStr) + 1
                nodeLabel = "节点 $nodeIndex"
            }
        }

        // 🌟 立即将选中的节点名通过回调推给主界面
        if (nodeLabel.isNotEmpty()) {
            mainHandler.post { onNodeSelected(nodeLabel) }
        }

        val currentToken = sharedPrefs.getString("tts_token", "") ?: ""

        if (targetUrlStr.isBlank()) {
            fallbackPlay(text, voiceId, onStart, onDone, "未配置或未找到云端节点")
            return
        }

        val urlBuilder = targetUrlStr.toHttpUrlOrNull()?.newBuilder()
        if (urlBuilder == null) {
            fallbackPlay(text, voiceId, onStart, onDone, "网址配置错误")
            return
        }

        val url = urlBuilder
            .addQueryParameter("text", text)
            .addQueryParameter("voice", voiceId)
            .addQueryParameter("token", currentToken)
            .build()

        val request = Request.Builder().url(url).build()
        val tempFile = File(appContext.cacheDir, "tts_${UUID.randomUUID().toString().take(6)}.mp3")

        var isFallbackTriggered = false
        val safeFallback: (String) -> Unit = { reason ->
            if (!isCancelledManually && !isFallbackTriggered) {
                isFallbackTriggered = true
                fallbackPlay(text, voiceId, onStart, onDone, reason)
            }
        }

        currentCall = client.newCall(request)
        currentCall?.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                mainHandler.post { safeFallback("云端联络失败") }
            }

            override fun onResponse(call: Call, response: Response) {
                if (isCancelledManually) return

                if (response.code == 403) {
                    mainHandler.post { safeFallback("暗号校验被拒(403)") }
                    return
                }

                if (!response.isSuccessful || response.body == null) {
                    val code = response.code
                    // 🌟 核心拦截：明确拒接，判死刑，关入小黑屋 10 分钟！
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
                    mainHandler.post { playAudio(tempFile, onStart, onDone) }
                } catch (e: Exception) {
                    mainHandler.post { safeFallback("音频解码异常") }
                }
            }
        })
    }

    private fun playAudio(file: File, onStart: (String) -> Unit, onDone: () -> Unit) {
        if (isCancelledManually) {
            file.delete()
            return
        }

        if (!file.exists() || file.length() < 100) {
            file.delete()
            if (!isCancelledManually) onDone()
            return
        }

        try {
            mediaPlayer = MediaPlayer().apply {
                setAudioAttributes(AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .build())
                val fis = FileInputStream(file)
                setDataSource(fis.fd)
                fis.close()

                // 🌟 核心修复：改为主线程异步准备，不再阻塞 UI
                setOnPreparedListener { mp ->
                    if (!isCancelledManually) {
                        mp.start()
                        onStart("🔊 微软云端播报中...")
                    } else {
                        mp.release()
                    }
                }

                setOnCompletionListener {
                    it.release()
                    mediaPlayer = null
                    file.delete()
                    if (!isCancelledManually) onDone()
                }

                // 🛡️ 航天级修复：拦截解码器崩溃或音频焦点丢失，防止回调断链卡死灵动岛
                setOnErrorListener { mp, _, _ ->
                    mp.release()
                    mediaPlayer = null
                    file.delete()
                    if (!isCancelledManually) onDone()
                    true // 返回 true 表示我们已经接管了错误处理
                }

                prepareAsync() // 🚀 启动异步准备状态机
            }
        } catch (e: Exception) {
            file.delete()
            if (!isCancelledManually) onDone()
        }
    }

    private fun fallbackPlay(text: String, voiceId: String, onStart: (String) -> Unit, onDone: () -> Unit, reason: String) {
        // 🌟 核心优化：将提示时长改为 LENGTH_LONG，延长一倍时间，确保看清！
        mainHandler.post { Toast.makeText(appContext, "⚠️ 播报质量降级: $reason", Toast.LENGTH_LONG).show() }
        try {
            val langTag = voiceId.split("-").take(2).joinToString("-")
            fallbackTts.language = java.util.Locale.forLanguageTag(langTag)
        } catch (e: Exception) {}

        fallbackTts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "fallback_id")
        onStart("🔊 本地引擎播报中...")

        mainHandler.postDelayed({
            if (!isCancelledManually) onDone()
        }, 2000)
    }
}