package com.example.firstapp

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.util.Log
import okhttp3.*
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.LinkedBlockingQueue

class GeminiLiveEngine(
    context: Context, // 👈 删掉前面的 private val，让它只作为传入参数
    private val apiKey: String,
    private val modelName: String,
    private val sourceLang: String,
    private val targetLang: String,
    private val onStateChange: (String) -> Unit,
    private val onSubtitleUpdate: (String, String) -> Unit,
    private val onTurnComplete: () -> Unit // 🌟 新增：回合结束回调，对接 MainActivity
) {
    // 👇 🌟 新增：提取全局生命周期的 ApplicationContext，彻底断绝内存泄漏！
    private val appContext = context.applicationContext

    // ==========================================
    // ✅ 核心修复：把 OkHttpClient 升维到静态伴生对象！
    // 不管你 new 多少次 GeminiLiveEngine，整个 APP 永远只共享这一个底层连接池和分发器。
    // 彻底消灭重复进出同传模式导致的僵尸线程堆积与 OOM 内存溢出崩溃！
    // ==========================================
    companion object {
        // 🌟 核心升级 1：注入心跳保活机制，防止进电梯或切后台导致 WebSocket 幽灵断开
        private val client = OkHttpClient.Builder()
            .pingInterval(15, java.util.concurrent.TimeUnit.SECONDS)
            .build()
    }

    private var webSocket: WebSocket? = null

    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null
    // ✅ 修改后：控制所有推流、音频播放的总闸，必须保证内存可见性！
    @Volatile
    private var isRunning = false

    @Volatile private var isCaptureRunning = false

    // 🚨 防抢跑锁：只有服务端发来 SetupComplete，才能开始送录音！
    @Volatile private var isSetupComplete = false
    // 👇 🌟 新增：同传引擎的麦克风销毁安全锁
    private var captureLatch: java.util.concurrent.CountDownLatch? = null

    private var playbackThread: Thread? = null
    private val audioQueue = LinkedBlockingQueue<ByteArray>()

    // 🌟 核心修复 1：引入“双重静音锁”，分离手动按钮与系统冲突的静音逻辑
    @Volatile
    var isMutedByButton = true // 受同传按钮松开/按住控制
    @Volatile
    var isMutedBySystem = false // 受传统麦克风/TTS发声冲突控制
    // 🌟 核心防线：向主界面暴露当前网络连接是否真正处于活跃状态
    val isActive: Boolean
        get() = isRunning
    // 只要任意一把锁被锁上，底层引擎就会执行软件级静音
    val isMuted: Boolean
        get() = isMutedByButton || isMutedBySystem

    private val mainHandler = Handler(Looper.getMainLooper())
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var audioFocusRequest: AudioFocusRequest? = null

    fun start() {
        if (isRunning) return
        isRunning = true
        isMutedByButton = false
        isMutedBySystem = false
        isSetupComplete = false
        audioQueue.clear()

        val focusAttr = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        audioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(focusAttr)
            .setOnAudioFocusChangeListener { focusChange ->
                when (focusChange) {
                    // 🌟 系统来电、闹钟等抢走了麦克风，我们立刻静默暂停
                    AudioManager.AUDIOFOCUS_LOSS,
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                        suspendHardwareMic() // 🌟 核心修复 4：系统打断时，彻底释放硬件资源
                        postState("⏸️ 被系统中断，同传暂停")
                    }
                    // 🌟 电话挂断，焦点归还，我们自动恢复同传
                    AudioManager.AUDIOFOCUS_GAIN -> {
                        restoreHardwareMic() // 🌟 核心修复 5：系统归还焦点时，重新启动麦克风硬件
                        postState("▶️ 音频恢复，同传继续")
                    }
                }
            }
            .build()
        audioManager.requestAudioFocus(audioFocusRequest!!)

        connectWebSocket()
        startAudioPlayback()
        startAudioCapture()
    }

    fun stop() {
        if (!isRunning) return
        isRunning = false
        isCaptureRunning = false
        isSetupComplete = false

        audioQueue.clear()
        playbackThread?.interrupt()

        webSocket?.close(1000, "Goodbye")
        webSocket = null

        // 🌟 航天级防御：拍摄硬件对象快照，并抓取当前的安全锁
        val recordToRelease = audioRecord
        val trackToRelease = audioTrack
        val latchToWait = captureLatch

        audioRecord = null
        audioTrack = null
        captureLatch = null

        // 🌟 核心修复：后台幽灵线程安全销毁
        Thread {
            // 严格等待 C++ 读取循环退出，超时 200ms 强制放行（防死锁）
            try {
                latchToWait?.await(200, java.util.concurrent.TimeUnit.MILLISECONDS)
            } catch (e: Exception) {}

            try {
                recordToRelease?.stop()
                recordToRelease?.release()
            } catch (e: Exception) {}

            try {
                trackToRelease?.stop()
                trackToRelease?.release()
            } catch (e: Exception) {}
        }.start()

        audioFocusRequest?.let {
            audioManager.abandonAudioFocusRequest(it)
            audioFocusRequest = null
        }
    }

    fun pauseCapture() {
        // 🌟 核心修复 2：同传松手时，只做“软件级软静音”，绝对不销毁扬声器和麦克风硬件！
        // 也不清空音频队列，这样 AI 没播完的话会从容播完。
        isMutedByButton = true
    }

    @SuppressLint("MissingPermission")
    fun resumeCapture() {
        if (!isRunning) return
        // 🌟 核心修复 3：按住时解除按钮锁。底层麦克风一直备着，瞬间恢复推流发送！
        isMutedByButton = false
    }
    // 🌟 新增：强制向服务器结算当前回合，掐断语境粘连
    fun commitTurn() {
        if (!isRunning || webSocket == null) return
        val frameJson = JSONObject().apply {
            put("clientContent", JSONObject().apply {
                put("turnComplete", true)
            })
        }
        try { webSocket?.send(frameJson.toString()) } catch (e: Exception) {}
    }
    // 🌟 新增：专门给系统电话、经典录音机让路时，使用的硬件级挂起方法
    // 🌟 新增：专门给系统电话、经典录音机让路时，使用的硬件级挂起方法
    fun suspendHardwareMic(onSuspended: (() -> Unit)? = null) { // 👈 核心升级：增加交接回调参数
        isCaptureRunning = false

        // 🌟 抓取安全锁与硬件快照
        val recordToRelease = audioRecord
        val latchToWait = captureLatch

        audioRecord = null
        captureLatch = null

        Thread {
            // 🌟 核心修复：严密等待 C++ 层彻底挂起
            try {
                latchToWait?.await(200, java.util.concurrent.TimeUnit.MILLISECONDS)
            } catch (e: Exception) {}

            try {
                recordToRelease?.stop()
                recordToRelease?.release()
            } catch (e: Exception) {}

            // 🌟 硬件彻底交接完毕，敲响回调通知主界面！
            onSuspended?.invoke()
        }.start()
    }

    // 🌟 新增：系统电话挂断、经典录音机用完后，归还并重启硬件控制权的方法
    @SuppressLint("MissingPermission")
    fun restoreHardwareMic() {
        if (!isRunning) return
        startAudioCapture()
    }


    private fun connectWebSocket() {
        // 🌟 核心修复：JSON结构正确后，使用官方推荐的 v1beta 稳定通道即可完美握手
        val url = "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent?key=$apiKey"
        val request = Request.Builder().url(url).build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.i("GeminiLiveRadar", "🟢 TCP 握手成功，准备发送 v1alpha Setup...")
                sendSetupFrame(webSocket)
                postState("⏳ 正在验证同传协议...")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                parseServerMessage(text)
            }

            // 🚨 捕获二进制幽灵帧：Google 偶尔会将 JSON 打包进 Binary 帧下发
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                parseServerMessage(bytes.utf8())
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (!isRunning) return // 🌟 如果是我们主动关闭的，不处理

                // 🌟 捕获 WebSocket 握手阶段的 404(未找到模型) 或 400 错误
                if (response?.code == 404 || response?.code == 400) {
                    postState("🔴 当前【同传模型】已被谷歌下架或失效！请去设置拉取最新列表。")
                    stop()
                    return
                }

                val rawMsg = t.message ?: ""

                // ==========================================
                // 🌟 终极逻辑：通过 isMuted (静音锁) 状态，精准区分【物理断网】与【因闲置被谷歌断开】
                // ==========================================
                if (isMuted) {
                    // 🎯 如果麦克风是关着的，说明用户松开了按钮，或者系统在播放TTS，此时连接断开属于正常的闲置断连
                    postState("💤 通道闲置，同传已休眠")
                } else {
                    // 🎯 只有当麦克风是开着的（用户正按着按钮说话），此时报错才判定为真正的信号不佳
                    if (rawMsg == "null" || rawMsg.isBlank() || rawMsg.contains("EOF", ignoreCase = true)) {
                        postState("🔴 同传连接意外断开，请重试")
                    } else if (rawMsg.contains("ping", ignoreCase = true) ||
                        rawMsg.contains("pong", ignoreCase = true) ||
                        rawMsg.contains("timeout", ignoreCase = true)) {
                        postState("🔴 信号不佳，同传连接断开")
                    } else {
                        val safeMsg = if (rawMsg.length > 15) "请检查网络环境" else rawMsg
                        postState("🔴 意外断开: $safeMsg")
                    }
                }

                stop() // 断开后及时清理引擎状态
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                if (!isRunning) return
                postState("💤 连接已关闭，同传已休眠")
                stop() // 🌟 核心修复：在这里立刻 stop()！一旦设为 false，后面的 onFailure 就会被直接拦截，绝不二次弹窗！
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (!isRunning) return
                postState("💤 连接已关闭，同传已休眠")
                stop()
            }
        })
    }

    private fun sendSetupFrame(ws: WebSocket) {
        val rawIsoCode = AppConstants.LANG_CODES[targetLang] ?: "en"
        val targetIsoCode = if (rawIsoCode.startsWith("sr-")) "sr" else rawIsoCode

        // 🌟 核心破局：为最新的 Gemini Live API 注入 System Instruction（系统指令）
        // 约束大模型输出的字幕必须带有正确的标点符号、大小写，并严格遵守目标语言的书写系统。
        val sysPrompt = when (targetLang) {
            "塞尔维亚语 (拉丁)" -> "You are a professional simultaneous interpreter. Translate to Serbian. You MUST output the translated text STRICTLY using the Latin script (latinica). Ensure correct capitalization and punctuation."
            "塞尔维亚语 (西里尔)" -> "You are a professional simultaneous interpreter. Translate to Serbian. You MUST output the translated text STRICTLY using the Cyrillic script (ћирилица). Ensure correct capitalization and punctuation."
            "粤语" -> "You are a professional simultaneous interpreter. Translate into natural Cantonese. You MUST output the text using Traditional Chinese characters (繁體中文). Ensure correct punctuation."
            "中文" -> "You are a professional simultaneous interpreter. Translate into natural Mandarin. You MUST output the text using Simplified Chinese characters (简体中文). Ensure correct punctuation."
            else -> "You are a professional simultaneous interpreter. Translate accurately into $targetLang. You MUST ensure correct native orthography, capitalization, and punctuation."
        }

        val setupJson = JSONObject().apply {
            put("setup", JSONObject().apply {
                put("model", "models/$modelName")

                // 👇 核心修复 2：按照官方 Live API 规范，将 systemInstruction 挂载在 setup 根节点！
                put("systemInstruction", JSONObject().apply {
                    put("parts", JSONArray().apply {
                        put(JSONObject().apply {
                            put("text", sysPrompt)
                        })
                    })
                })

                // 1. 字幕配置：保留在 setup 的最外层
                put("inputAudioTranscription", JSONObject())
                put("outputAudioTranscription", JSONObject())

                // 2. 翻译与生成配置：将 translationConfig 塞进 generationConfig 里面！
                put("generationConfig", JSONObject().apply {
                    put("responseModalities", JSONArray().apply { put("AUDIO") })

                    put("translationConfig", JSONObject().apply {
                        put("targetLanguageCode", targetIsoCode)
                        put("echoTargetLanguage", false)
                    })
                })
            })
        }

        Log.i("GeminiLiveRadar", "📤 发送修正后的 Setup: $setupJson")
        ws.send(setupJson.toString())
    }

    private fun parseServerMessage(jsonStr: String) {
        try {
            val root = JSONObject(jsonStr)

            // 🌟 雷达 1：捕捉服务器发来的握手成功信号
            if (root.has("setupComplete")) {
                Log.i("GeminiLiveRadar", "✅ 收到 SetupComplete! 解锁麦克风发送通道！")
                isSetupComplete = true // 解除封印，允许向云端抛麦克风音频！
                postState("同传模式，听对方说...") // 🌟 优化文案：听对方说话
                return
            }

            val serverContent = root.optJSONObject("serverContent") ?: return

            var inputTextChunk = ""
            var outputTextChunk = ""

            // 🌟 雷达 2：精准打捞我方的语音识别字幕 (User Input)
            val inputTrans = serverContent.optJSONObject("inputTranscription")
            if (inputTrans != null) {
                inputTextChunk = inputTrans.optString("text", "")
            }

            // 🌟 雷达 3：精准打捞大模型的同传翻译字幕 (Model Output)
            val outputTrans = serverContent.optJSONObject("outputTranscription")
            if (outputTrans != null) {
                outputTextChunk = outputTrans.optString("text", "")
            }

            // 🌟 雷达 4：提取音频流和备用文字 (Model Turn)
            val modelTurn = serverContent.optJSONObject("modelTurn")
            if (modelTurn != null) {
                val parts = modelTurn.optJSONArray("parts")
                if (parts != null) {
                    for (i in 0 until parts.length()) {
                        val part = parts.getJSONObject(i)

                        // 备用：部分模型可能会把文字塞在 parts 里，我们也抓取一下防漏
                        val textPart = part.optString("text", "")
                        if (textPart.isNotEmpty()) {
                            outputTextChunk += textPart
                        }

                        // 抓取音频流，送入播放队列
                        val inlineData = part.optJSONObject("inlineData")
                        if (inlineData != null) {
                            val base64Data = inlineData.optString("data", "")
                            if (base64Data.isNotEmpty()) {
                                try {
                                    val pcmBytes = android.util.Base64.decode(base64Data, android.util.Base64.DEFAULT)
                                    audioQueue.offer(pcmBytes)
                                } catch (e: Exception) {
                                    Log.e("GeminiLiveRadar", "🔴 音频解码失败: ${e.message}")
                                }
                            }
                        }
                    }
                }
            }

            // 🌟 雷达 5：捕获服务器发来的回合结束标志 (turnComplete)
            val turnComplete = serverContent.optBoolean("turnComplete", false)
            if (turnComplete) {
                mainHandler.post { onTurnComplete() } // 触发回合结束事件
            }

            // 🌟 终点站：只要抓到了任何文字碎片，立刻推给主线程的 UI 气泡
            if (inputTextChunk.isNotEmpty() || outputTextChunk.isNotEmpty()) {
                mainHandler.post {
                    onSubtitleUpdate(inputTextChunk, outputTextChunk)
                }
            }

        } catch (e: Exception) {
            // 抑制普通解析日志，避免刷屏
        }
    }

    private fun startAudioPlayback() {
        val sampleRate = 24000 // Gemini API 的输出标准为 24kHz
        val bufferSize = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)

        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(sampleRate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setBufferSizeInBytes(bufferSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        audioTrack?.play()

        playbackThread = Thread {
            try {
                while (isRunning) {
                    val chunk = audioQueue.take()
                    audioTrack?.write(chunk, 0, chunk.size)
                }
            } catch (e: InterruptedException) {
                // 线程被打断，平滑退出
            }
        }
        playbackThread?.start()
    }

    @SuppressLint("MissingPermission")
    private fun startAudioCapture() {
        if (isCaptureRunning) return

        try {
            audioManager.stopBluetoothSco()
            audioManager.isBluetoothScoOn = false
        } catch (e: Exception) {}

        isCaptureRunning = true

        // 🌟 航天级修补：使用局部变量锁定安全钟！
        val myLatch = java.util.concurrent.CountDownLatch(1)
        captureLatch = myLatch

        kotlin.concurrent.thread {
            try {
                val sampleRate = 16000
                val channelConfig = AudioFormat.CHANNEL_IN_MONO
                val audioFormat = AudioFormat.ENCODING_PCM_16BIT
                val bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)

                try {
                    audioRecord = AudioRecord(
                        MediaRecorder.AudioSource.VOICE_RECOGNITION,
                        sampleRate, channelConfig, audioFormat, bufferSize
                    )

                    if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                        throw IllegalStateException("初始化失败")
                    }

                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                        val devices = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
                        for (device in devices) {
                            if (device.type == android.media.AudioDeviceInfo.TYPE_BUILTIN_MIC) {
                                audioRecord?.preferredDevice = device
                                Log.i("GeminiLiveRadar", "🎤 [同传] 申请手机自带麦克风！")
                                break
                            }
                        }
                    }

                    audioRecord?.startRecording()

                    val testBuffer = ByteArray(2048)
                    val testRead = audioRecord?.read(testBuffer, 0, testBuffer.size) ?: 0
                    var isDeadStream = true
                    if (testRead > 0) {
                        for (i in 0 until testRead) {
                            if (testBuffer[i] != 0.toByte()) {
                                isDeadStream = false
                                break
                            }
                        }
                    }

                    if (testRead <= 0 || isDeadStream) {
                        throw IllegalStateException("检测到死流")
                    } else {
                        if (!isMuted && isSetupComplete) {
                            val base64Data = android.util.Base64.encodeToString(testBuffer, 0, testRead, android.util.Base64.NO_WRAP)
                            sendAudioFrame(base64Data)
                        }
                    }

                } catch (e: Exception) {
                    try {
                        audioRecord?.release()
                    } catch (ex: Exception) {}

                    try {
                        audioRecord = AudioRecord(
                            MediaRecorder.AudioSource.MIC,
                            sampleRate, channelConfig, audioFormat, bufferSize
                        )
                        audioRecord?.startRecording()
                        Log.i("GeminiLiveRadar", "🎤 [同传] 触发降级，已使用系统兜底麦克风！")
                    } catch (ex: Exception) {
                        postState("🔴 录音硬件初始化失败")
                        stop()
                        return@thread
                    }
                }

                val buffer = ByteArray(2048)
                while (isRunning && isCaptureRunning) {
                    val read = audioRecord?.read(buffer, 0, buffer.size) ?: 0

                    if (read < 0) {
                        Thread.sleep(10)
                        continue
                    }

                    if (read > 0 && !isMuted && isSetupComplete) {
                        val base64Data = android.util.Base64.encodeToString(buffer, 0, read, android.util.Base64.NO_WRAP)
                        sendAudioFrame(base64Data)
                    }
                }
            } finally {
                // 🌟 敲响专属的局部安全钟！
                myLatch.countDown()
            }
        }
    }

    private fun sendAudioFrame(base64Pcm: String) {
        val frameJson = JSONObject().apply {
            put("realtimeInput", JSONObject().apply {
                put("mediaChunks", JSONArray().apply {
                    put(JSONObject().apply {
                        put("mimeType", "audio/pcm;rate=16000")
                        put("data", base64Pcm)
                    })
                })
            })
        }
        try { webSocket?.send(frameJson.toString()) } catch (e: Exception) {}
    }

    private fun postState(state: String) {
        mainHandler.post { onStateChange(state) }
    }
}