package com.example.firstapp

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.OvershootInterpolator
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.util.*

class MainActivity : AppCompatActivity() {

    private lateinit var btnLiveTranslate: TextView
    private var geminiLiveEngine: GeminiLiveEngine? = null
    private var isCurrentlyRecordingClassic = false // 记录我方当前是否正在进行传统的录音或处理
    private var isLiveTranslateEnabled = false // 记录同传模式是否开启
    private lateinit var btnTopMic: TextView
    private lateinit var btnBottomMic: TextView
    private lateinit var tvDynamicIsland: TextView
    private lateinit var btnSettings: TextView
    private lateinit var btnSwap: TextView
    private lateinit var btnClear: TextView
    private lateinit var btnKeyboard: TextView
    private lateinit var btnTtsRoute: TextView
    private lateinit var seekbarTtsVolume: SeekBar

    private lateinit var btnMainCamera: TextView
    private lateinit var btnSubCamera: TextView
    private lateinit var btnSubGallery: TextView
    private var isCameraMenuOpen = false

    private var cameraImageUri: android.net.Uri? = null

    // 1. 相机权限申请器
    private val cameraPermissionLauncher = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { isGranted ->
        if (isGranted) openCamera() else Toast.makeText(this, "⚠️ 需要相机权限才能拍照翻译", Toast.LENGTH_SHORT).show()
    }

    // 2. 拍照启动器
    private val takePhotoLauncher = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.TakePicture()) { success ->
        if (success) cameraImageUri?.let { launchCrop(it) }
    }

    // 3. 相册选图启动器
    private val pickGalleryLauncher = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) launchCrop(uri)
    }

    // 4. uCrop 裁剪结果接收器
    private val cropLauncher = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            val resultUri = com.yalantis.ucrop.UCrop.getOutput(result.data!!)
            if (resultUri != null) processCroppedImage(resultUri)
        } else if (result.resultCode == com.yalantis.ucrop.UCrop.RESULT_ERROR) {
            val cropError = com.yalantis.ucrop.UCrop.getError(result.data!!)
            Toast.makeText(this, "⚠️ 裁剪异常: ${cropError?.message}", Toast.LENGTH_SHORT).show()
        }
    }
    // 👇 新增 5. 笔记本回调记忆与 JSON 文件导入启动器
    private var activeNotebookCallback: ((String) -> Unit)? = null
    private val importNotebookLauncher = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) processImportedFile(uri)
    }

    private lateinit var layoutFullscreenOverlay: FrameLayout
    private lateinit var tvFullscreenText: TextView

    private lateinit var rvTopChat: RecyclerView
    private lateinit var rvBottomChat: RecyclerView
    private lateinit var topAdapter: ChatAdapter
    private lateinit var bottomAdapter: ChatAdapter

    private lateinit var vibrator: Vibrator
    private lateinit var edgeTts: EdgeTtsEngine
    private lateinit var fallbackTts: android.speech.tts.TextToSpeech
    private val audioProcessor = AudioProcessor()
    private val aiEngine = AiEngine()

    private lateinit var sharedPrefs: SharedPreferences
    private var killerSet = mutableSetOf<String>()

    private var isProcessingAudio = false
    // 🌟 新增：单线程任务队列，专门处理吃内存的脏活累活，防止并发把内存撑爆
    private val heavyTaskExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()

    // 🌟 新增：同传专用的软 PTT 记忆体
    private var liveEngineIdleRunnable: Runnable? = null
    private var currentLiveMsgId: String = ""
    private var currentLiveInputText: String = ""
    private var currentLiveOutputText: String = ""

    private var myLangName = "中文"
    private var ptLangName = "英语"
    private var myVoiceName = "晓晓 (中&英·温柔女声)"
    private var ptVoiceName = "Ava (英文·自然女声)"
    private var isTtsEnabled = true

    private val resetIslandRunnable = Runnable { resetIsland() }

    // 🌟 航天级防断片：将相机图片 URI 存入系统记忆体，防止 Activity 被杀导致失忆
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        cameraImageUri?.let { outState.putString("kane_camera_image_uri", it.toString()) }
    }

    // 🌟 航天级防断片：如果系统重启了我们，立刻恢复 URI
    override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        super.onRestoreInstanceState(savedInstanceState)
        savedInstanceState.getString("kane_camera_image_uri")?.let {
            cameraImageUri = android.net.Uri.parse(it)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val tempPrefs = getSharedPreferences("KaneAiPrefs", Context.MODE_PRIVATE)
        val will = tempPrefs.getInt("volume_will", -1)
        if (will != -1) {
            // 读取遗书，判断上次崩溃前我们篡改的是哪个音频流，精准复原！
            val streamType = tempPrefs.getInt("stream_will", android.media.AudioManager.STREAM_MUSIC)
            val am = getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
            am.setStreamVolume(streamType, will, 0)
            tempPrefs.edit().remove("volume_will").remove("stream_will").apply()
        }
        // 1. 告诉系统我们要接管全屏布局
        WindowCompat.setDecorFitsSystemWindows(window, false)

        // 2. 先把我们写好的 XML 界面挂载到屏幕上
        setContentView(R.layout.activity_main)

        // 3. 界面挂载完毕后，再执行隐藏系统状态栏和导航栏
        hideSystemUI() // ✅ 这次系统绝对听话了

        clearZombieCacheFiles()
        // ... 下面的代码保持不变

        sharedPrefs = getSharedPreferences("KaneAiPrefs", Context.MODE_PRIVATE)
        val defaultKillers = setOf("字幕", "谢谢观看", "点个赞", "Mingjing", "Subscribe", "watching","请不吝点赞 订阅 转发 打赏支持明镜与点点栏目")
        killerSet = sharedPrefs.getStringSet("killer_list", defaultKillers)?.toMutableSet() ?: mutableSetOf()

        vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        fallbackTts = android.speech.tts.TextToSpeech(this) {}
        edgeTts = EdgeTtsEngine(this, fallbackTts)
        // 👇 注入这行：把系统的麦克风控制权交给我们的录音器
        audioProcessor.audioManager = getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
        // 🌟 新增：App开机时，兵分两路：一路去拉取更新缓存列表，另一路直接Ping上次存好的列表
        fetchTtsNodesSilently()

        // 🌟 新增：App冷启动时，立刻在后台静默唤醒HuggingFace容器
        edgeTts.pingServer()

        // 🌟 新增：App冷启动时，立刻在后台静默唤醒HuggingFace容器
        edgeTts.pingServer()

        btnTopMic = findViewById(R.id.btn_top_mic)

        btnLiveTranslate = findViewById(R.id.btn_live_translate)

        // 🌟 1. 启动时先根据耳机状态刷一次颜色 (智能变色替代了写死的金光)
        updateLiveTranslateButtonUI()

        // 🌟 2. 注册系统级硬件监听器 (耳机/蓝牙插拔时，瞬间自动变色！)
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
        audioManager.registerAudioDeviceCallback(object : android.media.AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out android.media.AudioDeviceInfo>?) {
                runOnUiThread { updateLiveTranslateButtonUI() }
            }
            override fun onAudioDevicesRemoved(removedDevices: Array<out android.media.AudioDeviceInfo>?) {
                runOnUiThread {
                    updateLiveTranslateButtonUI()

                    // 🌟 核心破局：如果用户拔掉了耳机，且当前处于“耳机模式”，强行踢回外放模式！
                    if (!edgeTts.isSpeakerRoute && !isHeadsetPluggedIn()) {
                        // 模拟手指去按一下路由切换按钮，触发完美切换（自动还原音量及UI）
                        btnTtsRoute.performClick()
                        showTransientIslandMessage("⚠️ 耳机已断开，发音自动切回喇叭", "#FFA500", isTop = false)
                    }
                }
            }
        }, null)

        // 🌟 3. 新增一个幽灵事件拦截器变量
        var isValidLivePress = false

        // 🌟 初始化 TTS 路由开关与音量条
        btnTtsRoute = findViewById(R.id.btn_tts_route)
        seekbarTtsVolume = findViewById(R.id.seekbar_tts_volume)

        btnTtsRoute.background = GradientDrawable().apply {
            setColor(Color.parseColor("#1A1A1B"))
            setStroke(2, Color.parseColor("#00BCFF"))
            cornerRadius = 50f // 🌟 变成完美的竖向椭圆胶囊
        }

        // 路由开关逻辑
        btnTtsRoute.setOnClickListener {
            // 🌟 核心拦截：如果当前是外放，且想切到耳机，但根本没插耳机，严禁通行！
            if (edgeTts.isSpeakerRoute && !isHeadsetPluggedIn()) {
                triggerVibration(20)
                Toast.makeText(this@MainActivity, "⚠️ 请连接耳机或蓝牙设备", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            triggerVibration(30)
            edgeTts.isSpeakerRoute = !edgeTts.isSpeakerRoute // 状态反转
            edgeTts.switchRouteOnTheFly() // 瞬间干预底层

            val am = getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
            val maxVol = am.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)

            if (edgeTts.isSpeakerRoute) {
                // 👇 替换从这里开始：动态硬件齿轮初始化
                btnTtsRoute.text = "🔈\n外\n放"
                btnTtsRoute.setTextColor(Color.parseColor("#00BCFF"))
                (btnTtsRoute.background as GradientDrawable).setStroke(2, Color.parseColor("#00BCFF"))

                seekbarTtsVolume.visibility = View.VISIBLE
                seekbarTtsVolume.isEnabled = true

                // 🌟 动态重构这台手机专属的磁吸点
                val am = getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
                val maxAlarmVol = am.getStreamMaxVolume(android.media.AudioManager.STREAM_ALARM)
                val stepSize = 100f / maxAlarmVol
                val hardwareGear = Math.round(edgeTts.speakerVolume / stepSize)
                val snappedProgress = (hardwareGear * stepSize).toInt().coerceIn(0, 100)

                seekbarTtsVolume.progress = snappedProgress
                edgeTts.speakerVolume = snappedProgress // 同步矫正内存误差

                seekbarTtsVolume.progressTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#00BCFF"))
                seekbarTtsVolume.thumbTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#00BCFF"))

                showTransientIslandMessage("🔈 TTS外放模式，音量：$snappedProgress%", "#00BCFF", isTop = false)
                // 👆 替换到这里结束
            } else {
                // ==========================================
                // 【🎧 耳机模式：真实物理同步】
                // ==========================================
                btnTtsRoute.text = "🎧\n耳\n机"
                btnTtsRoute.setTextColor(Color.parseColor("#00E676"))
                (btnTtsRoute.background as GradientDrawable).setStroke(2, Color.parseColor("#00E676"))

                seekbarTtsVolume.visibility = View.VISIBLE // 🌟 不再隐藏！保留在屏幕上作为真实指示器
                seekbarTtsVolume.isEnabled = true

                // 🌟 瞬间跳变回：系统真实的耳机媒体音量
                val currentVol = am.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)
                seekbarTtsVolume.progress = ((currentVol.toFloat() / maxVol) * 100).toInt()

                // 变成暗灰色（代表已被系统接管）
                seekbarTtsVolume.progressTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#00E676"))
                seekbarTtsVolume.thumbTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#00E676"))

                showTransientIslandMessage("🎧 TTS耳机模式，音量：${seekbarTtsVolume.progress}%", "#00E676", isTop = false)
            }
        }

        // 🌟 变色龙音量条：滑动拖拽逻辑
        seekbarTtsVolume.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                // 👇 替换从这里开始：消除生物微颤，硬件自适应齿轮化
                if (fromUser && seekBar != null) {
                    val am = getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager

                    if (edgeTts.actualSpeakerRoute) { // 🌟 改为 actualSpeakerRoute
                        // 【🔈 外放模式】根据这台手机真实的闹钟档位，切分 100%
                        val maxAlarmVol = am.getStreamMaxVolume(android.media.AudioManager.STREAM_ALARM)
                        val stepSize = 100f / maxAlarmVol
                        val hardwareGear = Math.round(progress / stepSize) // 四舍五入到最近的物理档位
                        val snappedProgress = (hardwareGear * stepSize).toInt().coerceIn(0, 100)

                        seekBar.progress = snappedProgress // 强制吸附，此时 fromUser 为 false 不会死循环
                        edgeTts.speakerVolume = snappedProgress // 更新内存安全预设值

                        if (edgeTts.isSpeaking) {
                            // 🌟 AI 在说话，直接击穿底层，改变真实闹钟音量！
                            am.setStreamVolume(android.media.AudioManager.STREAM_ALARM, hardwareGear, 0)
                            setIslandState("🔊 TTS音量: $snappedProgress%   ⏹️ ", "#00FF00", animatePop = false, isTop = false)
                        } else {
                            // 闲置时，绝不碰底层，只显示进度
                            setIslandState("🔈 TTS外放音量：$snappedProgress%", "#00BCFF", animatePop = false, isTop = false)
                        }
                    } else {
                        // 【🎧 耳机模式】映射真实的媒体档位
                        val maxMusicVol = am.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)
                        val stepSize = 100f / maxMusicVol
                        val hardwareGear = Math.round(progress / stepSize)
                        val snappedProgress = (hardwareGear * stepSize).toInt().coerceIn(0, 100)

                        seekBar.progress = snappedProgress

                        // 强行改变系统媒体音量
                        am.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, hardwareGear, 0)
                        setIslandState("🎧 TTS耳机音量：$snappedProgress%", "#00E676", animatePop = false, isTop = false)
                    }
                }
                // 👆 替换到这里结束
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                triggerVibration(20)
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                if (seekBar != null) {
                    triggerVibration(30)
                    if (edgeTts.actualSpeakerRoute) { // 🌟 改为 actualSpeakerRoute
                        // 🌟 外放模式：松手时才将安全预设值写进硬盘，杜绝卡顿！
                        sharedPrefs.edit().putInt("tts_speaker_volume", seekBar.progress).apply()
                        if (edgeTts.isSpeaking) {
                            setIslandState("🔊   正在播报   ⏹️ ", "#00FF00", animatePop = false, isTop = false)
                        } else {
                            resetIslandDelayed(1500L)
                        }
                    } else {
                        // 耳机模式无需存盘，安卓系统自带蓝牙记忆，1.5秒后灵动岛复原即可
                        resetIslandDelayed(1500L)
                    }
                }
            }
        })

        // 🌟 全新重构：同传软 PTT 控制中心 (按住解禁，松开静音，3分钟断网)
        btnLiveTranslate.setOnTouchListener { _, event ->
            val bg = btnLiveTranslate.background as GradientDrawable

            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    if (!checkAudioPermission()) {
                        requestAudioPermission()
                        return@setOnTouchListener true
                    }
                    if (!isHeadsetPluggedIn()) {
                        triggerVibration(20) // 🌟 新增：拔掉耳机时按它，给个清脆的短震动提示
                        Toast.makeText(this@MainActivity, "⚠️ 请佩戴耳机", Toast.LENGTH_SHORT).show()
                        isValidLivePress = false // 🚫 标记为无效按压！
                        return@setOnTouchListener true
                    }

                    isValidLivePress = true // ✅ 标记为有效按压！
                    triggerVibration(50)

                    // 🌟 视觉升级：按下时爆闪为炽热的高亮金
                    bg.setStroke(8, Color.parseColor("#FFFF00")) // 边框加粗变亮金/高光黄
                    bg.setColor(Color.parseColor("#332B00"))     // 内部核心微微透出暗金光泽
                    btnLiveTranslate.setShadowLayer(20f, 0f, 0f, Color.parseColor("#FFFF00")) // 光晕猛烈爆闪

                    btnLiveTranslate.animate().scaleX(0.85f).scaleY(0.85f).setDuration(150).start()

                    // 取消休眠倒计时
                    liveEngineIdleRunnable?.let { tvDynamicIsland.removeCallbacks(it) }

                    // 🌟 终极防线：双重校验，如果 UI 引用还在但底层网络已死，强行超度，准备冷启动！
                    if (geminiLiveEngine != null && !geminiLiveEngine!!.isActive) {
                        geminiLiveEngine?.stop()
                        geminiLiveEngine = null
                    }

                    // 如果引擎是空的，冷启动连网
                    if (geminiLiveEngine == null) {
                        val geminiKey = sharedPrefs.getString("gemini_key", "") ?: ""
                        val geminiLiveModel = sharedPrefs.getString("gemini_live_model", "gemini-3.5-live-translate-preview") ?: "gemini-3.5-live-translate-preview"
                        if (geminiKey.isBlank()) {
                            Toast.makeText(this@MainActivity, "⛔ 请先在设置里填写 Gemini API Key", Toast.LENGTH_SHORT).show()
                            return@setOnTouchListener true
                        }

                        setIslandState("⏳ 正在接通同传通道...", "#00BCFF", isTop = false)

                        geminiLiveEngine = GeminiLiveEngine(
                            context = this@MainActivity, apiKey = geminiKey, modelName = geminiLiveModel,
                            sourceLang = ptLangName, targetLang = myLangName,
                            onStateChange = { stateText ->
                                setIslandState(stateText, "#00BCFF", isTop = false)
                                // 🌟 修复点：吸收那个 AI 的思路，补齐 "休眠", "关闭" 拦截！只要断线，必定设为 null
                                if (stateText.contains("异常") || stateText.contains("断开") || stateText.contains("休眠") || stateText.contains("关闭")) {
                                    geminiLiveEngine?.stop()
                                    geminiLiveEngine = null
                                } else {
                                    isLiveTranslateEnabled = true
                                }
                            },
                            onSubtitleUpdate = { input, output ->
                                // 🌟 智能气泡引擎：如果当前 ID 是空的，自动新建一个气泡！
                                if (currentLiveMsgId.isEmpty()) {
                                    currentLiveMsgId = java.util.UUID.randomUUID().toString()
                                    currentLiveInputText = ""
                                    currentLiveOutputText = ""
                                    val voiceId = getSmartVoiceId(myVoiceName, myLangName)
                                    topAdapter.addMessage(ChatMessage("...", "...", isMe = true, voiceId = voiceId, isTopSpeaker = true, id = currentLiveMsgId))
                                    bottomAdapter.addMessage(ChatMessage("...", "...", isMe = false, voiceId = voiceId, isTopSpeaker = true, id = currentLiveMsgId))
                                }

                                if (input.isNotEmpty()) currentLiveInputText += input
                                if (output.isNotEmpty()) currentLiveOutputText += output

                                val displayText = currentLiveOutputText.ifEmpty { "..." }
                                val origText = currentLiveInputText.ifEmpty { "..." }

                                topAdapter.updateMessageById(currentLiveMsgId, displayText, origText)
                                bottomAdapter.updateMessageById(currentLiveMsgId, displayText, origText)

                                // 🌟 核心物理修复：使用带偏移量的吸附滚动，绝对保证上半屏气泡牢牢咬住灵动岛边缘，绝不被推出版图！
                                (rvTopChat.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(topAdapter.itemCount - 1, 0)
                                rvBottomChat.scrollToPosition(bottomAdapter.itemCount - 1)
                            },
                            onTurnComplete = {
                                // 🌟 核心修复 3：不再在这里清空 ID！
                                // 允许延时到达的最后一点字幕，继续安全落入刚才松手时的气泡中。
                                // 直到下一次按下手指，再由 ACTION_DOWN 去建立全新气泡。
                            }
                        )
                        geminiLiveEngine?.start()
                    } else {
                        // 引擎还活着且网络畅通，热启动！瞬间解开麦克风
                        setIslandState("👂正在听对方...", "#00BCFF", isTop = false)
                        geminiLiveEngine?.resumeCapture()
                    }

                    // 🌟 核心修复 1：手指按下时，无条件强制生成新气泡，彻底掐断上一个回合！
                    currentLiveMsgId = java.util.UUID.randomUUID().toString()
                    currentLiveInputText = ""
                    currentLiveOutputText = ""
                    val voiceId = getSmartVoiceId(myVoiceName, myLangName)

                    topAdapter.addMessage(ChatMessage("...", "...", isMe = true, voiceId = voiceId, isTopSpeaker = true, id = currentLiveMsgId))
                    bottomAdapter.addMessage(ChatMessage("...", "...", isMe = false, voiceId = voiceId, isTopSpeaker = true, id = currentLiveMsgId))

                    (rvTopChat.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(topAdapter.itemCount - 1, 0)
                    rvBottomChat.scrollToPosition(bottomAdapter.itemCount - 1)

                    true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    // 👇 🌟 核心拦截机制：如果刚才判定为无效按压（没插耳机），直接截断，不做任何多余的动作！
                    if (!isValidLivePress) return@setOnTouchListener true

                    // 🌟 视觉升级：松手恢复智能状态（插着耳机就是金光，拔掉就是灰暗）
                    updateLiveTranslateButtonUI()

                    btnLiveTranslate.animate().scaleX(1.0f).scaleY(1.0f).setDuration(250).start()

                    // 仅仅是捂住麦克风，不断网
                    geminiLiveEngine?.pauseCapture()
                    // 🌟 核心修复 2：告诉大模型“我说完了，赶紧结算”，掐断上下文粘连
                    geminiLiveEngine?.commitTurn()

                    // 👇 🌟 新增代码：阅后即焚（无情清理无效的短暂空录音气泡）
                    if (currentLiveInputText.isEmpty() && currentLiveOutputText.isEmpty()) {
                        topAdapter.removeMessageById(currentLiveMsgId)
                        bottomAdapter.removeMessageById(currentLiveMsgId)

                        // 🌟 致命 Bug 修复：气泡烧毁了，必须把它的 ID 从记忆体里抹除！
                        // 否则下次按键会把字幕发给幽灵气泡，导致界面完全无响应！
                        currentLiveMsgId = ""
                    }
                    // 👆 新增结束
                    // 🌟 核心修复 4：暂停提示面向我方正向显示
                    setIslandState("⏸️ 暂停同传", "#888888", isTop = false)
                    resetIslandDelayed(2000L)

                    // 🌟 开启 3 分钟断网休眠机制
                    liveEngineIdleRunnable = Runnable {
                        geminiLiveEngine?.stop()
                        geminiLiveEngine = null
                        isLiveTranslateEnabled = false
                        // 🌟 核心修复 5：休眠提示面向我方正向显示
                        showTransientIslandMessage("💤 同传已自动休眠", "#888888", isTop = false)
                    }
                    tvDynamicIsland.postDelayed(liveEngineIdleRunnable, 3 * 60 * 1000)

                    true
                }
                else -> false
            }
        }
        btnBottomMic = findViewById(R.id.btn_bottom_mic)
        tvDynamicIsland = findViewById(R.id.tv_dynamic_island)
        btnSettings = findViewById(R.id.btn_settings)
        btnSwap = findViewById(R.id.btn_swap)
        btnClear = findViewById(R.id.btn_clear)
        btnKeyboard = findViewById(R.id.btn_keyboard)
        // 1. 保留原本的单击事件（弹出输入面板）
        btnKeyboard.setOnClickListener { showTextInputDialog() }

        // 2. 🌟 新增：长按事件（直接弹出笔记本并一键翻译发送）
        btnKeyboard.setOnLongClickListener {
            triggerVibration(50)

            // 🛡️ 防御 Bug 1：强制打断当前可能正在播放的系统 TTS，防止音频抢占
            if (edgeTts.isSpeaking) {
                edgeTts.stop()
                resetIsland()
            }

            // 👇 核心修复：只要是从主界面长按唤出的，强制将焦点记忆重置为我方(下方)！
            lastActiveInputIsTop = false

            // 呼出改造后的笔记本模块
            showNotebookSubDialog { content ->
                // 🛡️ 核心管道调用：isTop = false 代表强制作为“我方”的母语发送
                processTextPipeline(content, isTop = false)
            }

            // 🛡️ 防御 Bug 4：返回 true，彻底消费掉长按事件，杜绝松手时误触单击
            true
        }

        rvTopChat = findViewById(R.id.rv_top_chat)
        rvBottomChat = findViewById(R.id.rv_bottom_chat)

        layoutFullscreenOverlay = findViewById(R.id.layout_fullscreen_overlay)
        tvFullscreenText = findViewById(R.id.tv_fullscreen_text)
        val scrollFullscreen = findViewById<ScrollView>(R.id.scroll_fullscreen)
        val llFullscreenContent = findViewById<LinearLayout>(R.id.ll_fullscreen_content)
        val btnFullscreenPlay = findViewById<TextView>(R.id.btn_fullscreen_play)

        btnFullscreenPlay.background = GradientDrawable().apply {
            setColor(Color.parseColor("#222223"))
            cornerRadius = 35f
            setStroke(3, Color.parseColor("#444444"))
        }

        var isScaling = false
        var currentScaleFactor = 1.0f

        val closeFullscreenAction = View.OnClickListener {
            if (isScaling) return@OnClickListener

            if (edgeTts.isSpeaking) {
                edgeTts.stop()
                resetIsland()
            }
            layoutFullscreenOverlay.animate().alpha(0f).setDuration(150).withEndAction {
                layoutFullscreenOverlay.visibility = View.GONE
                currentScaleFactor = 1.0f
                tvFullscreenText.textSize = 38f

                // 👇 新增核心：全屏大字报退出时，如果刚才藏了草稿框，现在把它无损弹回来！
                pendingDraftDialog?.let {
                    it.show()
                    pendingDraftDialog = null
                }
            }.start()
        }

        val scaleGestureDetector = android.view.ScaleGestureDetector(this, object : android.view.ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: android.view.ScaleGestureDetector): Boolean {
                currentScaleFactor *= detector.scaleFactor
                currentScaleFactor = currentScaleFactor.coerceIn(0.5f, 3.0f)
                tvFullscreenText.textSize = 38f * currentScaleFactor
                return true
            }
            override fun onScaleBegin(detector: android.view.ScaleGestureDetector): Boolean {
                isScaling = true
                scrollFullscreen.requestDisallowInterceptTouchEvent(true)
                return true
            }
            override fun onScaleEnd(detector: android.view.ScaleGestureDetector) {
                scrollFullscreen.requestDisallowInterceptTouchEvent(false)
            }
        })

        val fullscreenTouchListener = View.OnTouchListener { v, event ->
            scaleGestureDetector.onTouchEvent(event)
            if (event.pointerCount > 1) {
                isScaling = true
                scrollFullscreen.requestDisallowInterceptTouchEvent(true)
            } else if (event.action == MotionEvent.ACTION_UP || event.action == MotionEvent.ACTION_CANCEL) {
                scrollFullscreen.requestDisallowInterceptTouchEvent(false)
                v.postDelayed({ isScaling = false }, 100)
            }
            false
        }

        layoutFullscreenOverlay.setOnTouchListener(fullscreenTouchListener)
        scrollFullscreen.setOnTouchListener(fullscreenTouchListener)
        llFullscreenContent.setOnTouchListener(fullscreenTouchListener)
        tvFullscreenText.setOnTouchListener(fullscreenTouchListener)

        layoutFullscreenOverlay.setOnClickListener(closeFullscreenAction)
        scrollFullscreen.setOnClickListener(closeFullscreenAction)
        llFullscreenContent.setOnClickListener(closeFullscreenAction)
        tvFullscreenText.setOnClickListener(closeFullscreenAction)

        val onLongClickAction = { msg: ChatMessage -> showBubbleOptionsDialog(msg) }

        val onPlayClickAction = { text: String, voiceId: String, isTopSpeaker: Boolean -> // 🌟 新增身份参数
            if (voiceId.startsWith("hy-AM")) {
                setIslandState("⚠️ 亚美尼亚语暂不支持语音播报", "#FFA500")
                resetIslandDelayed()
            } else {
                val forceHeadset = isTopSpeaker && isHeadsetPluggedIn() // 🌟 对方的话强制走耳机
                edgeTts.speak(text, voiceId, forceHeadset,
                    onNodeSelected = { nodeName ->
                        // 灵动岛提示节点加载
                        setIslandState("🎵 准备发音 [$nodeName]", "#00BCFF")
                    },
                    onStart = {
                        setIslandState("🔊   正在播报   ⏹️ ", "#00BCFF")
                        tvDynamicIsland.isClickable = true
                        tvDynamicIsland.setOnClickListener {
                            triggerVibration(50)
                            edgeTts.stop()
                            resetIsland()
                        }
                    },
                    onDone = { resetIsland() }
                )
            }
        }

        val showFullscreenDisplay = { translatedText: String, originalText: String, voiceId: String, color: String, isTop: Boolean ->
            triggerVibration(30)
            currentScaleFactor = 1.0f
            tvFullscreenText.textSize = 38f

            if (isTop) {
                tvFullscreenText.text = translatedText
                tvFullscreenText.setTextColor(android.graphics.Color.parseColor(color))
            } else {
                val spannable = android.text.SpannableStringBuilder()
                val transSpan = android.text.SpannableString(translatedText)
                transSpan.setSpan(android.text.style.ForegroundColorSpan(android.graphics.Color.parseColor(color)), 0, translatedText.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                spannable.append(transSpan)

                val origStr = "\n\n(原: $originalText)"
                val origSpan = android.text.SpannableString(origStr)
                origSpan.setSpan(android.text.style.ForegroundColorSpan(android.graphics.Color.parseColor("#888888")), 0, origStr.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                origSpan.setSpan(android.text.style.RelativeSizeSpan(0.6f), 0, origStr.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                origSpan.setSpan(android.text.style.StyleSpan(android.graphics.Typeface.NORMAL), 0, origStr.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                spannable.append(origSpan)

                tvFullscreenText.text = spannable
                tvFullscreenText.setTextColor(android.graphics.Color.WHITE)
            }

            // 👇 核心统一：只转字，不转底板！
            val scrollFullscreen = findViewById<ScrollView>(R.id.scroll_fullscreen)
            scrollFullscreen.rotation = if (isTop) 180f else 0f

            layoutFullscreenOverlay.rotation = 0f
            layoutFullscreenOverlay.alpha = 0f
            layoutFullscreenOverlay.visibility = View.VISIBLE
            layoutFullscreenOverlay.animate().alpha(1f).setDuration(150).start()

            // 👇 完美对称左边：播放按钮
            if (voiceId.startsWith("hy-AM")) {
                btnFullscreenPlay.visibility = View.GONE
            } else {
                btnFullscreenPlay.visibility = View.VISIBLE
                val strPlay = "🔊 播放语音"
                val strStop = "⏹️ 停止播报"

                btnFullscreenPlay.text = strPlay
                btnFullscreenPlay.setTextColor(android.graphics.Color.WHITE)
                btnFullscreenPlay.textSize = 16f
                btnFullscreenPlay.setPadding(60, 30, 60, 30)
                btnFullscreenPlay.background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(android.graphics.Color.parseColor("#1A1A1B"))
                    cornerRadius = 60f
                    setStroke(4, android.graphics.Color.parseColor("#00BCFF"))
                }

                val playParams = btnFullscreenPlay.layoutParams as FrameLayout.LayoutParams
                playParams.gravity = android.view.Gravity.BOTTOM or android.view.Gravity.START
                playParams.setMargins(60, 0, 0, 100)
                btnFullscreenPlay.layoutParams = playParams

                btnFullscreenPlay.setOnClickListener {
                    triggerVibration(50)
                    if (edgeTts.isSpeaking) {
                        edgeTts.stop()
                        btnFullscreenPlay.text = strPlay
                        btnFullscreenPlay.setTextColor(android.graphics.Color.WHITE)
                        (btnFullscreenPlay.background as android.graphics.drawable.GradientDrawable).setStroke(4, android.graphics.Color.parseColor("#00BCFF"))
                    } else {
                        val forceHeadset = isTop && isHeadsetPluggedIn() // 🌟 强制私密播报
                        edgeTts.speak(translatedText, voiceId, forceHeadset,
                            onNodeSelected = { nodeName ->
                                runOnUiThread {
                                    btnFullscreenPlay.text = "⏳ 连接 $nodeName"
                                    btnFullscreenPlay.setTextColor(android.graphics.Color.parseColor("#00BCFF"))
                                }
                            },
                            onStart = {
                                runOnUiThread {
                                    btnFullscreenPlay.text = strStop
                                    btnFullscreenPlay.setTextColor(android.graphics.Color.parseColor("#00FF00"))
                                    updateLiveTranslateMuteState() // 🌟 喇叭发声，持续静音同传
                                    (btnFullscreenPlay.background as android.graphics.drawable.GradientDrawable).setStroke(4, android.graphics.Color.parseColor("#00FF00"))
                                }
                            },
                            onDone = {
                                runOnUiThread {
                                    btnFullscreenPlay.text = strPlay
                                    finishClassicRecordingCycle() // 🌟 播报结束，善后清理并瞬间恢复同传
                                    btnFullscreenPlay.setTextColor(android.graphics.Color.WHITE)
                                    (btnFullscreenPlay.background as android.graphics.drawable.GradientDrawable).setStroke(4, android.graphics.Color.parseColor("#00BCFF"))
                                }
                            }
                        )
                    }
                }
            }

            // 👇 完美对称右边：赋予双击气泡大字报白嫖“翻转”功能！
            var btnFlip = layoutFullscreenOverlay.findViewWithTag<TextView>("btn_flip_screen")
            if (btnFlip == null) {
                btnFlip = TextView(this@MainActivity).apply {
                    tag = "btn_flip_screen"
                    this.text = "🔄 翻转视角"
                    setTextColor(android.graphics.Color.WHITE)
                    textSize = 16f
                    setTypeface(null, android.graphics.Typeface.BOLD)
                    setPadding(60, 30, 60, 30)
                    gravity = android.view.Gravity.CENTER
                    background = android.graphics.drawable.GradientDrawable().apply {
                        setColor(android.graphics.Color.parseColor("#1A1A1B"))
                        cornerRadius = 60f
                        setStroke(4, android.graphics.Color.parseColor("#00E676"))
                    }
                    val params = FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.WRAP_CONTENT,
                        FrameLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        gravity = android.view.Gravity.BOTTOM or android.view.Gravity.END
                        setMargins(0, 0, 60, 100)
                    }
                    layoutFullscreenOverlay.addView(this, params)

                    setOnClickListener {
                        triggerVibration(40)
                        val currentRotation = scrollFullscreen.rotation
                        val targetRotation = if (currentRotation == 0f) 180f else 0f
                        scrollFullscreen.animate().rotation(targetRotation).setDuration(250).start()
                    }
                }
            }
            btnFlip.visibility = View.VISIBLE
        }

        val onDoubleTapTop = { msg: ChatMessage -> showFullscreenDisplay(msg.translatedText, msg.originalText, msg.voiceId, "#00BCFF", true) }
        val onDoubleTapBottom = { msg: ChatMessage -> showFullscreenDisplay(msg.translatedText, msg.originalText, msg.voiceId, "#00E676", false) }
        val onEditClickAction = { msg: ChatMessage -> showEditDialog(msg) } // 👈 声明编辑直达动作
        topAdapter = ChatAdapter(
            onMessageLongClick = onLongClickAction,
            onMessageDoubleTap = onDoubleTapTop,
            onPlayClick = onPlayClickAction,
            onEditClick = onEditClickAction // 👈 传入动作
        )
        bottomAdapter = ChatAdapter(
            onMessageLongClick = onLongClickAction,
            onMessageDoubleTap = onDoubleTapBottom,
            onPlayClick = onPlayClickAction,
            onEditClick = onEditClickAction // 👈 传入动作
        )
        // 🌟 读取历史保存的光效偏好，并下发给适配器
        val savedEffect = sharedPrefs.getString("chat_effect_mode", "A") ?: "A"
        topAdapter.effectMode = savedEffect
        bottomAdapter.effectMode = savedEffect

        // 🌟 灵动岛长按彩蛋：呼出全新的灵动控制台
        tvDynamicIsland.setOnLongClickListener {
            triggerVibration(50)
            showQuickControlPanel() // 👈 这里改成了呼出新面板
            true // 消费事件，防止触发其他冲突
        }

        // 🌟 核心破局点：给底部增加一条半个屏幕长的“透明虚拟跑道”
        // 这样安卓系统就会认为“内容很长”，从而允许我们将新气泡强制拉到灵动岛，顺理成章地把旧气泡挤出画外！
        rvTopChat.layoutManager = LinearLayoutManager(this)
        rvTopChat.setPadding(rvTopChat.paddingLeft, rvTopChat.paddingTop, rvTopChat.paddingRight, resources.displayMetrics.heightPixels / 2)
        rvTopChat.clipToPadding = false // 确保跑道透明

        rvBottomChat.layoutManager = LinearLayoutManager(this).apply { stackFromEnd = true }
        rvTopChat.adapter = topAdapter
        rvBottomChat.adapter = bottomAdapter

        setupUIBackgrounds()

        val llFontControl = findViewById<LinearLayout>(R.id.ll_font_control)
        val btnFontPlus = findViewById<TextView>(R.id.btn_font_plus)
        val btnFontReset = findViewById<TextView>(R.id.btn_font_reset)
        val btnFontMinus = findViewById<TextView>(R.id.btn_font_minus)

        llFontControl.background = GradientDrawable().apply {
            setColor(Color.parseColor("#121212"))
            cornerRadius = 60f
            setStroke(2, Color.parseColor("#00E676"))
        }

        var chatTransSize = sharedPrefs.getFloat("chat_trans_size", 20f)
        var chatOrigSize = sharedPrefs.getFloat("chat_orig_size", 14f)
        topAdapter.updateFontSize(chatTransSize, chatOrigSize)
        bottomAdapter.updateFontSize(chatTransSize, chatOrigSize)

        fun applyChatFontSize(trans: Float, orig: Float) {
            chatTransSize = trans
            chatOrigSize = orig
            sharedPrefs.edit().putFloat("chat_trans_size", trans).putFloat("chat_orig_size", orig).apply()
            topAdapter.updateFontSize(trans, orig)
            bottomAdapter.updateFontSize(trans, orig)
        }

        btnFontPlus.setOnClickListener {
            if (chatTransSize < 36f) {
                triggerVibration(20)
                applyChatFontSize(chatTransSize + 2f, chatOrigSize + 2f)
            } else {
                showTransientIslandMessage("⚠️ 已经是最大字号了", "#FFA500", isTop = false)
            }
        }

        btnFontMinus.setOnClickListener {
            if (chatTransSize > 14f) {
                triggerVibration(20)
                applyChatFontSize(chatTransSize - 2f, chatOrigSize - 2f)
            } else {
                showTransientIslandMessage("⚠️ 已经是最小字号了", "#FFA500", isTop = false)
            }
        }

        btnFontReset.setOnClickListener {
            triggerVibration(40)
            applyChatFontSize(20f, 14f)
            showTransientIslandMessage("✅ 恢复默认排版", "#00FF00", isTop = false)
        }

        btnMainCamera = findViewById(R.id.btn_main_camera)
        btnSubCamera = findViewById(R.id.btn_sub_camera)
        btnSubGallery = findViewById(R.id.btn_sub_gallery)

        val cyberBg = { size: Float ->
            GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#121212"))
                setStroke(2, Color.parseColor("#00E676"))
            }
        }
        btnMainCamera.background = cyberBg(46f)
        btnSubCamera.background = cyberBg(40f)
        btnSubGallery.background = cyberBg(40f)

        btnMainCamera.setOnClickListener {
            triggerVibration(30)
            val density = resources.displayMetrics.density

            if (!isCameraMenuOpen) {
                btnSubCamera.visibility = View.VISIBLE
                btnSubGallery.visibility = View.VISIBLE

                btnMainCamera.animate().rotation(45f).setDuration(250).start()
                btnSubCamera.animate().translationX(-55f * density).alpha(1f)
                    .setDuration(300).setInterpolator(OvershootInterpolator(1.5f)).start()
                btnSubGallery.animate().translationX(-105f * density).alpha(1f)
                    .setDuration(300).setInterpolator(OvershootInterpolator(1.5f)).setStartDelay(50).start()

                isCameraMenuOpen = true
            } else {
                btnMainCamera.animate().rotation(0f).setDuration(250).start()
                btnSubGallery.animate().translationX(0f).alpha(0f)
                    .setDuration(200).setInterpolator(null).setStartDelay(0).start()
                btnSubCamera.animate().translationX(0f).alpha(0f)
                    .setDuration(200).setInterpolator(null).withEndAction {
                        btnSubCamera.visibility = View.INVISIBLE
                        btnSubGallery.visibility = View.INVISIBLE
                    }.start()

                isCameraMenuOpen = false
            }
        }

        btnSubCamera.setOnClickListener {
            btnMainCamera.performClick()
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                openCamera()
            } else {
                cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
            }
        }

        btnSubGallery.setOnClickListener {
            btnMainCamera.performClick()
            pickGalleryLauncher.launch("image/*")
        }

        setupPushToTalkListener(btnTopMic, isTop = true)
        setupPushToTalkListener(btnBottomMic, isTop = false)

        btnSettings.setOnClickListener { showSettingsPanel() }

        btnSwap.setOnClickListener {
            val tempLang = myLangName
            myLangName = ptLangName
            ptLangName = tempLang

            val tempVoice = myVoiceName
            myVoiceName = ptVoiceName
            ptVoiceName = tempVoice

            btnBottomMic.text = "🎙️\n${myLangName.take(1)}"
            btnTopMic.text = "🎙️\n${ptLangName.take(1)}"

            sharedPrefs.edit().apply {
                putString("lang_me", myLangName)
                putString("lang_pt", ptLangName)
                putString("voice_me", myVoiceName)
                putString("voice_pt", ptVoiceName)
                apply()
            }

            triggerVibration(50)
            showTransientIslandMessage("🔄 双方语种已互换", "#00BCFF")

            // 🌟 核心修复 3：语种变了，必须杀掉旧引擎！迫使下次按键时拉取新的语种配置
            geminiLiveEngine?.stop()
            geminiLiveEngine = null
        }

        btnClear.setOnClickListener {
            if (topAdapter.itemCount > 0 || bottomAdapter.itemCount > 0) {
                val clearDialog = AlertDialog.Builder(this)
                    .setCustomTitle(createCyberTitle("🧹 一键清屏"))
                    .setMessage("确定清空屏幕上的所有气泡吗？")
                    .setPositiveButton("清空") { _, _ ->
                        topAdapter.clearMessages()
                        bottomAdapter.clearMessages()

                        // 🌟 核心修复 4：清屏时彻底抹除同传记忆体，防止幽灵气泡和消失 Bug！
                        currentLiveMsgId = ""
                        currentLiveInputText = ""
                        currentLiveOutputText = ""

                        triggerVibration(50)
                        showTransientIslandMessage("✨ 已清屏", "#00FF00")
                    }
                    .setNegativeButton("取消", null)
                    .create()
                if (!isFinishing && !isDestroyed) clearDialog.show()
            } else {
                showTransientIslandMessage("✨ 屏幕已经是干净的啦", "#00FF00")
            }
        }

        loadSettings()
        requestAudioPermission()
    }
    // 🌟 核心防线：物理耳机侦测器
    private fun isHeadsetPluggedIn(): Boolean {
        // 👇 新加这一行，直接强行返回 true，骗过系统，测试完 UI 记得删掉！
        //return true
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
        val devices = audioManager.getDevices(android.media.AudioManager.GET_DEVICES_OUTPUTS)
        for (device in devices) {
            // 只要检测到有线耳机、蓝牙A2DP耳机、或低功耗蓝牙耳机，就放行
            if (device.type == android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                device.type == android.media.AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                device.type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                device.type == android.media.AudioDeviceInfo.TYPE_BLE_HEADSET) {
                return true
            }
        }
        return false
    }
    // 🌟 新增：中央音量互斥锁。只要我方在录音或系统在外放喇叭，强行捂住同传引擎的嘴！
    private fun updateLiveTranslateMuteState() {
        val shouldMute = isCurrentlyRecordingClassic || edgeTts.isSpeaking
        geminiLiveEngine?.isMutedBySystem = shouldMute // 🌟 锁住系统锁，绝不干扰用户的物理按键锁
    }

    // 👇 确保加入了这个全新的公共方法
    private fun finishClassicRecordingCycle() {
        isCurrentlyRecordingClassic = false
        updateLiveTranslateMuteState()
        // 麦克风物归原主，让同传重新接管麦克风硬件 (但保持软静音等待用户按下)
        if (isLiveTranslateEnabled) {
            geminiLiveEngine?.restoreHardwareMic()
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemUI()
    }
    // ==========================================
    // 🌟 核心修复：生命周期级状态重同步
    // ==========================================
    override fun onResume() {
        super.onResume()

        // 确保各种引擎和控件已经初始化，防止极小概率的冷启动空指针
        if (::edgeTts.isInitialized && ::seekbarTtsVolume.isInitialized) {

            // 🎯 核心逻辑：只有在【耳机模式】下，才去抓取底层系统音量。
            if (!edgeTts.isSpeakerRoute) {
                val am = getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager

                // 1. 获取系统最真实的媒体流 (Music) 音量档位
                val maxVol = am.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)
                val currentVol = am.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)

                // 2. 将物理档位按比例换算回 0-100 的百分比
                val percent = ((currentVol.toFloat() / maxVol) * 100).toInt().coerceIn(0, 100)

                // 3. 静默同步 UI 进度条
                seekbarTtsVolume.progress = percent
            }
        }
    }
    private fun hideSystemUI() {
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private fun clearZombieCacheFiles() {
        Thread {
            try {
                val cacheFiles = cacheDir.listFiles() ?: return@Thread
                for (file in cacheFiles) {
                    if (file.name.startsWith("tts_") && file.name.endsWith(".mp3")) file.delete()
                }
                clearVisionCache(forceWipe = false)
            } catch (e: Exception) { e.printStackTrace() }
        }.start()
    }

    private fun showSettingsPanel() {
        val context = this
        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 20, 40, 40)
            setBackgroundColor(Color.parseColor("#121212"))
        }

        fun createCard(): LinearLayout {
            return LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(40, 10, 40, 40)
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = 25
                    bottomMargin = 10
                }
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#222223"))
                    cornerRadius = 35f
                    setStroke(2, Color.parseColor("#333333"))
                }
            }
        }

        fun addTitle(text: String, url: String? = null, container: LinearLayout = layout, colorStr: String = "#00BCFF") {
            val tv = TextView(context).apply {
                this.text = text
                setTextColor(Color.parseColor(colorStr))
                textSize = 13f
                setPadding(0, 30, 0, 10)

                if (url != null) {
                    val outValue = android.util.TypedValue()
                    context.theme.resolveAttribute(android.R.attr.selectableItemBackground, outValue, true)
                    setBackgroundResource(outValue.resourceId)

                    setOnClickListener {
                        try {
                            val intent = android.content.Intent(android.content.Intent.ACTION_VIEW)
                            intent.data = android.net.Uri.parse(url)
                            context.startActivity(intent)
                        } catch (e: Exception) {
                            Toast.makeText(context, "无法打开浏览器", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
            container.addView(tv)
        }

        fun addInput(hint: String, defaultVal: String, container: LinearLayout = layout): EditText {
            val et = EditText(context).apply {
                this.hint = hint; setTextColor(Color.WHITE); setHintTextColor(Color.GRAY); setText(defaultVal); setPadding(30, 20, 30, 20)
                background = GradientDrawable().apply { setColor(Color.parseColor("#0F0F0F")); setStroke(2, Color.DKGRAY); cornerRadius = 15f }
            }
            container.addView(et); return et
        }

        fun addSpinner(items: List<String>, defaultName: String, container: LinearLayout = layout): Spinner {
            val spinner = Spinner(context).apply {
                adapter = CategorizedAdapter(context, items)
                val idx = items.indexOf(defaultName)
                if (idx >= 0) setSelection(idx)
                background = GradientDrawable().apply { setColor(Color.parseColor("#0F0F0F")); setStroke(2, Color.DKGRAY); cornerRadius = 15f }
            }
            container.addView(spinner); return spinner
        }

        fun createModelAdapter(items: List<String>): ArrayAdapter<String> {
            return object : ArrayAdapter<String>(context, android.R.layout.simple_spinner_dropdown_item, items) {
                override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                    val view = super.getView(position, convertView, parent) as TextView
                    view.setTextColor(Color.parseColor("#00E676"))
                    view.setTypeface(null, android.graphics.Typeface.BOLD)
                    return view
                }
                override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View {
                    val view = super.getDropDownView(position, convertView, parent) as TextView
                    view.setTextColor(Color.WHITE)
                    view.setBackgroundColor(Color.parseColor("#252526"))
                    view.setPadding(40, 25, 20, 25)
                    return view
                }
            }
        }

        // 🌟 将语种和音色数据源声明置于前方，以便顶端卡片使用
        val langItems = AppConstants.getFlatLangList()
        val voiceItems = AppConstants.getFlatVoiceList()



        // ==========================================
        // 🏅 【已置顶】对方语种 (上半屏) 卡片
        // ==========================================
        val ptCard = createCard()
        layout.addView(ptCard)
        addTitle("🗣️ 对方语种 (上半屏)", null, ptCard, "#00BCFF")
        val spPt = addSpinner(langItems, ptLangName, ptCard)
        addTitle("🔊 对方发音音色", null, ptCard, "#00BCFF")
        val spPtVoice = addSpinner(voiceItems, ptVoiceName, ptCard)

        // ==========================================
        // 🏅 【已置顶】我方语种 (下半屏) 卡片
        // ==========================================
        val myCard = createCard()
        layout.addView(myCard)
        addTitle("🗣️ 我方语种 (下半屏)", null, myCard, "#00E676")
        val spMe = addSpinner(langItems, myLangName, myCard)
        addTitle("🔊 我方发音音色", null, myCard, "#00E676")
        val spMeVoice = addSpinner(voiceItems, myVoiceName, myCard)

        fun bindBiDirectionalSyncLogic(spLang: Spinner, spVoice: Spinner) {
            var isSyncing = false

            // 🌟 核心修复：增加两个拦截器标志，专门防范 Android 系统的首次幽灵回调
            var isLangFirstInit = true
            var isVoiceFirstInit = true

            spLang.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    // 🌟 拦截首次自动触发，保护用户此前保存的非默认音色不被重置
                    if (isLangFirstInit) {
                        isLangFirstInit = false
                        return
                    }

                    if (isSyncing) return
                    val selectedLang = langItems[position]
                    if (selectedLang.startsWith("━━")) return

                    val defaultVoice = AppConstants.DEFAULT_VOICE_MAP[selectedLang]
                    if (defaultVoice != null) {
                        val idx = voiceItems.indexOf(defaultVoice)
                        if (idx >= 0 && spVoice.selectedItemPosition != idx) {
                            isSyncing = true
                            spVoice.setSelection(idx)
                            isSyncing = false
                        }
                    }
                }
                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }

            spVoice.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    // 🌟 拦截首次自动触发
                    if (isVoiceFirstInit) {
                        isVoiceFirstInit = false
                        return
                    }

                    if (isSyncing) return
                    val selectedVoice = voiceItems[position]
                    if (selectedVoice.startsWith("━━")) return

                    val voiceId = AppConstants.TTS_VOICES[selectedVoice] ?: ""
                    var targetLang = ""

                    if (voiceId.startsWith("zh-HK")) {
                        targetLang = "粤语"
                    } else if (voiceId.startsWith("zh-CN")) {
                        val currentLang = langItems[spLang.selectedItemPosition]
                        if (currentLang == "中文" || currentLang == "英语") {
                            targetLang = currentLang
                        } else {
                            targetLang = "中文"
                        }
                    } else {
                        for ((prefix, lang) in AppConstants.VOICE_PREFIX_TO_LANG) {
                            if (voiceId.startsWith(prefix)) {
                                targetLang = lang
                                break
                            }
                        }
                    }

                    if (targetLang.isNotEmpty()) {
                        val idx = langItems.indexOf(targetLang)
                        if (idx >= 0 && spLang.selectedItemPosition != idx) {
                            isSyncing = true
                            spLang.setSelection(idx)
                            isSyncing = false
                        }
                    }
                }
                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }
        }

        bindBiDirectionalSyncLogic(spMe, spMeVoice)
        bindBiDirectionalSyncLogic(spPt, spPtVoice)


        // ==========================================
        // 🥈 【已下沉】AI 大模型 Key 与引擎卡片
        // ==========================================
        val engineCard = createCard()
        layout.addView(engineCard)

        addTitle("🔑 Groq API Key【🌐 前往官网获取】", "https://console.groq.com/keys", engineCard)
        val etGroq = addInput("gsk_...", aiEngine.groqApiKey, engineCard)

        // --- 开始：增强版 Groq 引擎面板 ---
        addTitle("🧠 Groq 主力翻译模型", null, engineCard)
        val spGroqModel = Spinner(context).apply {
            val initList = mutableListOf(aiEngine.currentGroqModel)
            adapter = createModelAdapter(initList)
            background = GradientDrawable().apply { setColor(Color.parseColor("#0F0F0F")); setStroke(2, Color.DKGRAY); cornerRadius = 15f }
        }
        engineCard.addView(spGroqModel)

        // 🌟 新增：Groq 专属语音识别下拉框
        addTitle("🎙️ Groq 语音识别模型", null, engineCard)
        val spGroqAsrModel = Spinner(context).apply {
            val initList = mutableListOf(aiEngine.currentGroqAsrModel)
            adapter = createModelAdapter(initList)
            background = GradientDrawable().apply { setColor(Color.parseColor("#0F0F0F")); setStroke(2, Color.DKGRAY); cornerRadius = 15f }
        }
        engineCard.addView(spGroqAsrModel)

        val btnFetchModels = Button(context).apply {
            text = "🔄 联网拉取Groq最新模型"
            setBackgroundColor(Color.parseColor("#00BCFF"))
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = 20 }
            setOnClickListener {
                this.text = "拉取中..."
                this.isEnabled = false
                aiEngine.groqApiKey = etGroq.text.toString().trim()

                // 🌟 核心：一键拉取，双向分发！
                aiEngine.fetchGroqModels { success, textModels, asrModels ->
                    if (isDestroyed || isFinishing) return@fetchGroqModels
                    this.text = "🔄 联网拉取Groq最新模型"
                    this.isEnabled = true

                    if (success && textModels.isNotEmpty()) {
                        // 1. 刷新文本大模型下拉框
                        spGroqModel.adapter = createModelAdapter(textModels)
                        // 🌟 智能推举热更：Qwen 已下架，优先寻找官方推荐的 gpt-oss，找不到找 120b，再兜底 70b
                        val targetIndex = textModels.indexOfFirst { it.contains("gpt-oss", ignoreCase = true) }.takeIf { it >= 0 }
                            ?: textModels.indexOfFirst { it.contains("120b", ignoreCase = true) }.takeIf { it >= 0 }
                            ?: textModels.indexOfFirst { it.contains("70b", ignoreCase = true) }.takeIf { it >= 0 }
                            ?: 0
                        spGroqModel.setSelection(targetIndex)

                        // 2. 刷新语音大模型下拉框
                        if (asrModels.isNotEmpty()) {
                            spGroqAsrModel.adapter = createModelAdapter(asrModels)
                            // 默认先找满血版 v3，找不到就选 turbo，再找不到选第一个
                            val asrTargetIndex = asrModels.indexOfFirst { it == "whisper-large-v3" }
                                .takeIf { it >= 0 }
                                ?: asrModels.indexOfFirst { it == "whisper-large-v3-turbo" }.takeIf { it >= 0 }
                                ?: 0
                            spGroqAsrModel.setSelection(asrTargetIndex)
                        }

                        Toast.makeText(context, "Groq 模型库与语音库已更新！", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(context, "拉取失败，请检查 Key 或网络", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
        engineCard.addView(btnFetchModels)
        // --- 结束：增强版 Groq 引擎面板 ---

        addTitle("🔑 Gemini API Key【🌐 前往官网获取】", "https://aistudio.google.com/app/apikey", engineCard)
        val etGemini = addInput("AIzaSy...", aiEngine.geminiApiKey, engineCard)

        addTitle("👁️ Gemini 视觉翻译模型", null, engineCard)
        val spGeminiModel = Spinner(context).apply {
            val initList = mutableListOf(aiEngine.currentGeminiModel)
            adapter = createModelAdapter(initList)
            background = GradientDrawable().apply { setColor(Color.parseColor("#0F0F0F")); setStroke(2, Color.DKGRAY); cornerRadius = 15f }
        }
        engineCard.addView(spGeminiModel)

        // 🌟 新增：专属的实时同传模型下拉框
        addTitle("🎧 Gemini 实时同传模型", null, engineCard, "#00E676")
        val spGeminiLiveModel = Spinner(context).apply {
            val initList = mutableListOf(aiEngine.currentGeminiLiveModel)
            adapter = createModelAdapter(initList)
            background = GradientDrawable().apply { setColor(Color.parseColor("#0F0F0F")); setStroke(2, Color.DKGRAY); cornerRadius = 15f }
        }
        engineCard.addView(spGeminiLiveModel)

        val btnFetchGeminiModels = Button(context).apply {
            text = "🔄 联网拉取 Gemini 最新模型库"
            setBackgroundColor(Color.parseColor("#00BCFF"))
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = 20 }
            setOnClickListener {
                this.text = "拉取中..."
                this.isEnabled = false
                aiEngine.geminiApiKey = etGemini.text.toString().trim()

                // 🌟 双向分发回调
                aiEngine.fetchGeminiModels { success, visionModels, liveModels ->
                    if (isDestroyed || isFinishing) return@fetchGeminiModels
                    this.text = "🔄 联网拉取 Gemini 最新模型库"
                    this.isEnabled = true

                    if (success) {
                        // 1. 刷新视觉文本盒子
                        if (visionModels.isNotEmpty()) {
                            spGeminiModel.adapter = createModelAdapter(visionModels)
                            val targetIndex = visionModels.indexOfFirst { it == "gemini-3.1-flash-lite" }.takeIf { it >= 0 }
                                ?: visionModels.indexOfFirst { it.contains("gemini-3.1-flash", ignoreCase = true) }.takeIf { it >= 0 }
                                ?: 0
                            spGeminiModel.setSelection(targetIndex)
                        }

                        // 2. 刷新同传专属盒子
                        if (liveModels.isNotEmpty()) {
                            spGeminiLiveModel.adapter = createModelAdapter(liveModels)
                            val liveTargetIndex = liveModels.indexOfFirst { it.contains("gemini-3.5-live-translate", ignoreCase = true) }.takeIf { it >= 0 }
                                ?: 0
                            spGeminiLiveModel.setSelection(liveTargetIndex)
                        }
                        Toast.makeText(context, "Gemini 双轨模型库已更新！", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(context, "拉取失败，请检查 Key 或网络环境", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
        engineCard.addView(btnFetchGeminiModels)



        // =========================================================================
        // 🥈 【已下沉】云端 TTS 语音服务器集群设置 (双轨制)
        // =========================================================================
        val ttsNodeCard = createCard()
        layout.addView(ttsNodeCard)

        addTitle("🌐 TTS 语音服务器列表", null, ttsNodeCard, "#FFA500")

        val modeItems = listOf("🤖 自动模式(推荐)", "⚙️ 自定义单节点模式")
        val savedMode = sharedPrefs.getString("tts_mode", "auto") ?: "auto"
        val initModeName = if (savedMode == "custom") modeItems[1] else modeItems[0]

        val spTtsMode = addSpinner(modeItems, initModeName, ttsNodeCard)

        // --- 模块 A：全自动集群面板 ---
        val autoContainer = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        ttsNodeCard.addView(autoContainer)

        // 🌟 修复：精简默认提示文案
        val tvAutoStatus = TextView(context).apply {
            val count = sharedPrefs.getStringSet("tts_cached_nodes", emptySet())?.size ?: 0
            text = if (count > 0) "✅ 当前已缓存 $count 个云端节点，智能负载均衡。" else "⏳ 正在后台拉取可用节点..."
            setTextColor(Color.parseColor("#00E676"))
            setPadding(10, 20, 10, 20)
        }
        autoContainer.addView(tvAutoStatus)

        val btnForceRefresh = Button(context).apply {
            text = "🔄 手动刷新服务器列表"
            setBackgroundColor(Color.parseColor("#FFA500"))
            setTextColor(Color.parseColor("#121212"))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = 10; bottomMargin = 10 }
            setOnClickListener {
                this.text = "拉取中..."
                this.isEnabled = false

                // 🌟 修复：同时接收 count(节点数) 和 source(拉取渠道)，并展现在 UI 上！
                fetchTtsNodesSilently { count, source ->
                    runOnUiThread {
                        this.text = "🔄 手动刷新服务器列表"
                        this.isEnabled = true
                        if (count > 0) {
                            tvAutoStatus.text = "✅ 已更新 $count 个云端节点，智能负载均衡。\n✅ 列表来自：$source"
                            Toast.makeText(context, "服务器节点已更新！", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(context, "拉取失败，请检查网络", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        }
        autoContainer.addView(btnForceRefresh)

        // --- 模块 B：自定义单节点面板 ---
        val customContainer = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        ttsNodeCard.addView(customContainer)
        addTitle("🔗 私有节点 URL (仅自定义模式有效)", null, customContainer, "#BBBBBB")
        val currentTtsUrl = sharedPrefs.getString("tts_server_url", "") ?: ""
        val etTtsUrl = addInput("https://...", currentTtsUrl, customContainer)

        addTitle(
            "🔑 暗号 (Hugging Face 密钥，点我发邮件联系 KANE 获取)",
            "mailto:huizhen2018qz@gmail.com?subject=KANE%20Face-to-Face%20暗号申请【请表明身份】",
            ttsNodeCard,
            "#BBBBBB"
        )
        val currentTtsToken = sharedPrefs.getString("tts_token", "") ?: ""
        val etTtsToken = addInput("输入暗号...", currentTtsToken, ttsNodeCard)

        // 🌟 动态双轨联动逻辑
        spTtsMode.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (position == 0) { // 选中全自动模式
                    autoContainer.visibility = View.VISIBLE
                    customContainer.visibility = View.GONE
                } else { // 选中自定义模式
                    autoContainer.visibility = View.GONE
                    customContainer.visibility = View.VISIBLE
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        // =========================================================================

        val advCard = createCard()
        layout.addView(advCard)

        val cbTts = CheckBox(context).apply {
            text = "🔊 开启语音自动播报 (TTS)"
            setTextColor(Color.WHITE)
            isChecked = isTtsEnabled
            setPadding(20, 20, 0, 20)
        }
        advCard.addView(cbTts)

        addTitle("🛡️ 幻听防火墙 ( Killer )", null, advCard, "#FF4444")
        val btnManageBlacklist = Button(context).apply {
            text = "📝 管理幻听屏蔽词黑名单"
            setBackgroundColor(Color.parseColor("#331111"))
            setTextColor(Color.parseColor("#FF4444"))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = 10 }
            setOnClickListener { showBlacklistManagerDialog() }
        }
        advCard.addView(btnManageBlacklist)

        // =========================================================================
        // 📚 【新增】关于与帮助说明书卡片
        // =========================================================================
        val helpCard = createCard()
        layout.addView(helpCard)

        addTitle("📦 关于与帮助 (About & Help)", null, helpCard, "#BBBBBB")

        val btnManual = Button(context).apply {
            text = "📖 完整使用指南 & 免责声明"
            setBackgroundColor(Color.parseColor("#0B2233")) // 赛博朋克深空蓝底色
            setTextColor(Color.parseColor("#00BCFF"))       // 亮蓝色字体
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = 10 }
            setOnClickListener { showInstructionManualDialog() }
        }
        helpCard.addView(btnManual)

        val scroll = ScrollView(context)
        scroll.addView(layout)

        val settingsDialog = AlertDialog.Builder(this)
            .setCustomTitle(createCyberTitle("KANE Face-to-Face v5 设置面板"))
            .setView(scroll)
            .setPositiveButton("保存") { _, _ ->
                val editor = sharedPrefs.edit()
                editor.putString("groq_key", etGroq.text.toString().trim())
                editor.putString("groq_model", spGroqModel.selectedItem?.toString() ?: "openai/gpt-oss-120b")
                editor.putString("groq_asr_model", spGroqAsrModel.selectedItem?.toString() ?: "whisper-large-v3")
                editor.putString("gemini_key", etGemini.text.toString().trim())
                editor.putString("gemini_model", spGeminiModel.selectedItem?.toString() ?: "gemini-3.1-flash-lite")
                editor.putString("gemini_live_model", spGeminiLiveModel.selectedItem?.toString() ?: "gemini-3.5-live-translate-preview")

                // 🌟 修改：保存新的双轨制模式和配置
                editor.putString("tts_mode", if (spTtsMode.selectedItemPosition == 0) "auto" else "custom")
                editor.putString("tts_server_url", etTtsUrl.text.toString().trim())
                editor.putString("tts_token", etTtsToken.text.toString().trim())

                editor.putString("lang_me", spMe.selectedItem.toString())
                editor.putString("lang_pt", spPt.selectedItem.toString())
                editor.putString("voice_me", spMeVoice.selectedItem.toString())
                editor.putString("voice_pt", spPtVoice.selectedItem.toString())
                editor.putBoolean("tts_enabled", cbTts.isChecked)
                editor.apply()

                loadSettings()

                // 🌟 核心修复 4：设置保存了，必须杀掉旧引擎重置记忆体！
                geminiLiveEngine?.stop()
                geminiLiveEngine = null

                showTransientIslandMessage("✅ 设置已保存", "#00FF00")
            }
            .setNegativeButton("取消", null)
            .create()

        if (!isFinishing && !isDestroyed) settingsDialog.show()
    }

    private fun showInstructionManualDialog() {
        val context = this
        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(50, 40, 50, 50) // 底部边距微调
            setBackgroundColor(Color.parseColor("#1A1A1B"))
        }

        fun addSection(titleStr: String, htmlContent: String, titleColor: String) {
            val tvTitle = TextView(context).apply {
                text = titleStr
                setTextColor(Color.parseColor(titleColor))
                textSize = 17f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setPadding(0, 55, 0, 15)
            }
            val tvContent = TextView(context).apply {
                text = androidx.core.text.HtmlCompat.fromHtml(htmlContent, androidx.core.text.HtmlCompat.FROM_HTML_MODE_COMPACT)
                setTextColor(Color.parseColor("#CCCCCC"))
                textSize = 14f
                setLineSpacing(15f, 1.3f)
                setPadding(0, 0, 0, 20)
            }
            layout.addView(tvTitle)
            layout.addView(tvContent)
        }

        // ================= 📝 正文区 =================
        addSection("📱 界面 UI 控件", """
            <b>【上半屏 • 对方控制区（倒置呈现，方便面对面交流）】</b><br>
            <b>• 对方文字区：</b>新消息气泡会自动贴近屏幕中央。<br>
            <b>• 同传按钮：</b><br>
            &nbsp;&nbsp;<u>💡 提示：</u>这是<b>谷歌（Google）全新推出的独立实时同传功能</b>。原理与普通翻译完全不同：它能一边听对方说话，一边在后台实时翻译并同步播放声音。不需要像以前那样“说完一整句话，然后等它翻译、播放”，而是能做到真正的“一边说一边翻译”。<br>
            &nbsp;&nbsp;<u>⚠️ 注意：</u><b>使用【同传】功能时，请务必佩戴耳机！</b>目前同传功能主要是针对于把“对方语言”实时翻译成“我方语言”，实时翻译的结果音频流总是从耳机播放。<br>
            <b>• 音量调节滑条：</b>用于智能调节 TTS 播报音量，具备外放/耳机双通道独立记忆功能（详细机制见下文）。<br>
            <b>• 声音路由 【 🔈外放/🎧耳机 】：</b>决定发音和扩音的物理通道。配合耳机使用，可让手机化身为极其强大的“单向翻译官模式”。详细运转逻辑请仔细阅读下方的<b>《🎧 智能音频路由引擎》</b>专属说明。<br>
            <b>• 对方录音键 【 🎙️对方 】：</b>传统的“你一句、我一句”对讲模式（不需要戴耳机）。按住说话，松手翻译。<br><br>
            
            <b>【中轴控制线 • 灵动状态面板】</b><br>
            <b>• 中央灵动岛：</b>实时显示翻译进度与连接提示。长按可打开控制台，开关TTS发音或切换气泡动画。<br>
            <b>• 语种互换 【 ↕️ 】：</b>互换双方的输入语言及对应的发音人。<br>
            <b>• 一键清屏 【 🗑️ 】：</b>清空屏幕聊天记录。<br>
            <b>• 键盘输入 【 ✍️ 】：</b>点击打字输入文本；<b>长按</b>直接拉出笔记本常用话语。<br>
            <b>• 设置面板 【 ⚙️ 】：</b>打开系统配置卡片，输入你的 API Key 密钥并配置模型。<br><br>
            
            <b>【下半屏 • 我方控制区（正常方向，我方观看）】</b><br>
            <b>• 我方文字区：</b>我方视角的双语翻译记录。<br>
            <b>• 我方录音键 【 🎙️我方 】：</b>长按录制我方语言，松手翻译。往屏幕中央滑动手指可取消发送。<br>
            <b>• 字号控制器 【 A+ / ◆ / A- 】：</b>A+ 放大聊天文字，A- 缩小文字，◆ 恢复默认字号。即时调节双聊天框。<br>
            <b>• 视觉翻译 【 📷 】：</b>点击展开子菜单。点击 📸 调起相机拍照，点击 🖼️ 从相册导入图片。
        """.trimIndent(), "#00E676")

        // 🌟 新增独立专区：音频路由引擎
        addSection("🎧 KANE 智能音频路由引擎 (Smart Audio Routing)", """
            
            <b>【场景一：没戴耳机（裸机交流）】</b><br>
            当你没有连接任何耳机或蓝牙设备时：<br>
            无论你点什么按钮，<b>所有的声音</b>（对方的话、你的话、AR 视觉翻译），统统从手机的<b>大喇叭</b>里播放出来。此时手机就像一个纯粹的双向扩音器。<br><br>
            
            <b>【场景二：戴上耳机 ＋ 选择了 🎧耳机 模式】</b><br>
            这是<b>“全沉浸模式”</b>。当你戴着耳机，且面板上的开关拨到了【🎧耳机】时：<br>
            所有的声音<b>无一例外</b>，全部都在你的耳机里私密播放。不管是对方对你说的悄悄话，还是你输入的草稿发音，亦或是 AR 拍照翻译的菜单，手机本体绝对保持安静，绝不社死。<br><br>
            
            <b>【场景三：戴上耳机 ＋ 选择了 🔈外放 模式】🌟 (核心魔法)</b><br>
            这是本软件最强大的<b>“单向翻译官模式”</b>！当你戴着耳机，却按下了【🔈外放】按钮时，系统会瞬间化身智能交警，进行声音分流：<br>
            <b>🗣️ 老外对你说的话</b>（对方气泡自动播报 / 点击老外气泡小喇叭）：<br>
            &nbsp;&nbsp;<u>魔法拦截：</u>系统知道这是说给你听的中文，如果外放出来会很奇怪。因此，系统会无视你的“外放”按钮，<b>强制将老外的话塞进你的耳机里私密播报！</b><br>
            <b>🙋‍♂️ 你对老外说的话</b>（我方气泡自动播报 / 草稿全屏 / AR拍照朗读）：<br>
            &nbsp;&nbsp;<u>听从指挥：</u>系统知道这是你想展示给外界的，于是<b>立刻打通手机本体的大喇叭</b>，把你的外语翻译大声向外广播，手机瞬间变成你的单向扩音器！<br><br>
            
            <b>📌 两个特殊补充说明：</b><br>
            <b>1. 独立音量记忆：</b><br>
            &nbsp;&nbsp;在【🔈外放】模式下，滑动音量条调节的是手机的扩音喇叭，<b>不影响</b>你耳机里听歌的音量。<br>
            &nbsp;&nbsp;在【🎧耳机】模式下，滑动音量条则与你手机系统的媒体音量完美同步。<br>
            <b>2. 🎙️ 实时同传功能：</b><br>
            &nbsp;&nbsp;同传功能是最高级别的同声传译。为了不干扰你和对方说话，同传的声音<b>永远只在耳机内播放</b>，不受外放按钮控制。<br><br>
            
            <b>💡 总结</b><br>
            你不需要去记复杂的逻辑。你只要记住：<b>戴上耳机后，老外的话永远在你耳边低语。而你可以随时通过【🔈外放/🎧耳机】按钮，决定你自己的翻译结果与各种文字发音是要“喊给世界听”，还是“留给自己听”。</b>
        """.trimIndent(), "#FFD700")

        addSection("✨ 气泡手势与视觉 AR 交互", """
            <b>• 双击全屏大字报展示：</b>快速双击任意聊天气泡，可打开全屏大字报。并可旋转、TTS发音，在嘈杂的机场或商铺非常好用。<br>
            <b>• 长按聊天气泡：</b>长按气泡，可快速复制、<b>存入快捷笔记本</b>。如果识别结果有误，你不用重新录音，直接选择<b>“编辑并重译”</b>就能手动修改原文并重新翻译。或者“拉黑”彻底过滤某些词。<br>
            <b>• 文本草稿全屏：</b>打字输入时，点击“全屏展示”按钮，可将尚未发送的草稿放大在全屏举牌展示（支持朗读与翻转）。<br>
            <b>• 视觉 AR 字幕滤镜：</b>使用相机拍照翻译后，<b>点击结果页的图片空白处</b>，即可在默认背景、高透背景、无背景黑边等 5 种字幕样式间循环切换。<br>
            &nbsp;&nbsp;<u>⚠️ 建议：</u>拍照时尽量把手机端平，并在裁剪框里精准框选需要翻译的文字，这能帮助 AI 把翻译后的字幕最精准地贴在原图对应位置。""".trimIndent(), "#00BCFF")

        addSection("⚙️ 设置面板各项功能与使用方法", """
            <b>【Groq 极速对讲配置】</b><br>
            <b>• Groq API Key：</b>点击"前往官网获取"，填入你在官网申请的Groq API KEY（gsk_开头）。<br>
            <b>• Groq 主力翻译模型：</b>你日常文字翻译所用的大模型，如果偶尔遇到网络拥堵，系统会自动使用备用引擎接管，整个过程无缝完成。（默认推荐 GPT OSS 120B，速度与翻译质量俱佳，如果之后官网下架相关模型，可重新【拉取最新模型】之后选择一个，选70B也是可以的）。<br>
            <b>• Groq 语音识别模型：</b>将你声音识别为文字的模型（默认推荐 whisper-large-v3）。<br>
            <b>• 联网拉取 Groq 模型按钮：</b>输入 Key 后点击此键，系统会连接服务器，自动刷新并列出当前云端最新可用的翻译和听写模型。<br><br>
            
            <b>【Gemini 视觉与同传配置】</b><br>
            <b>• Gemini API Key：</b>点击"前往官网获取"，填入你在谷歌 AI Studio 申请的密钥（AIzaSy开头）。<br>
            <b>• Gemini 视觉翻译模型：</b>备用翻译、拍照翻译、提取文字并智能对齐位置呈现（默认推荐 gemini-3.1-flash-lite）。<br>
            <b>• Gemini 实时同传模型：</b>专用于流式同传的专属大模型（默认推荐 gemini-3.5-live-translate-preview）。<br>
            <b>• 联网拉取 Gemini 模型按钮：</b>输入 Key 后点击，自动连网更新并列出谷歌官方最新发布的视觉与实时同传大模型列表。<br><br>
            
            <b>【TTS 云端发音服务器集群配置】</b><br>
            <b>• 服务器工作模式切换：</b>
            &nbsp;&nbsp;- <u>自动模式</u>：全自动智能负载均衡，推荐绝大多数情况下使用。
            &nbsp;&nbsp;- <u>自定义单节点模式</u>：允许你单独连通自建或私有的语音节点。<br>
            <b>• 手动刷新服务器列表按钮：</b>在自动模式下，可一键拉取、更新最新的免费高保真发音服务器列表。<br>
            <b>• 私有节点 URL：</b>在自定义模式下，手动填入你自己搭建的微软高拟真语音合成（TTS）服务地址。<br>
            <b>• 授权暗号 (Token)：</b>填入校验暗号。用于身份安全验证，防止TTS服务器节点被盗刷。<br><br>
            
            <b>【高级选项】</b><br>
            <b>• 开启语音自动播报 (TTS)：</b>勾选则翻译完成后自动开口说话；取消勾选则系统保持安静，只出文字不播发音。<br>
            <b>• 管理幻听屏蔽词黑名单按钮：</b>点击进入黑名单面板，可以手动添加常出现的底噪噪音词，或一键重置系统推荐的 7 个初始核心过滤词。
        """.trimIndent(), "#FFA500")



        addSection("⚖️ 隐私合规与免责声明 (GDPR & TOU)", """
            本软件架构与数据处理流程严格遵从《欧盟通用数据保护条例》(GDPR) 规范，请您在使用前知悉并同意以下条款：<br><br>
            <b>1. 数据处理与零留存 (Zero Retention)：</b><br>
            本应用作为纯本地端请求工具运行。麦克风音频、相机图像及文本数据仅在设备内存中进行短暂的加密封装，并直接通过 HTTPS 协议传输至第三方 API 服务商 (Groq/Google/Microsoft)。应用不在本地持久化存储、记录 or 向任何其他未经授权的服务器上传用户的个人隐私数据。视觉缓存文件严格执行即时销毁 (阅后即焚) 机制。<br><br>
            <b>2. 责任隔离与自备密钥 (BYOK Liability)：</b><br>
            本应用强制采用自备密钥 (Bring Your Own Key) 模式运行。用户自主填写的 API Key 所产生的数据传输、存储规范及合规性，受该 API 供应商 (如 Google LLC, Groq Inc.) 的最终用户服务条款约束。因输出违规内容或滥用接口导致的账户封禁、法律纠纷及一切财务损失，完全由使用者个人独立承担。<br><br>
            <b>3. 按原样提供与免责 (AS-IS Disclaimer)：</b><br>
            本应用代码遵循开源软件惯例，按“原样 (AS-IS)”提供，不附带任何明示或暗示的法律担保。开发者不对因本地网络阻断、API 供应商策略变动、第三方 TTS 节点失效或任何不可抗力导致的可用性中断承担连带维护义务 or 侵权赔偿责任。
        """.trimIndent(), "#888888")

        // 署名留白区
        val tvFooter = TextView(context).apply {
            text = "Designed & Developed by KANE\nVer 5.4.0 Pro"
            setTextColor(Color.parseColor("#666666"))
            textSize = 12f
            gravity = android.view.Gravity.CENTER
            setPadding(0, 30, 0, 40)
            setTypeface(null, android.graphics.Typeface.BOLD)
        }
        layout.addView(tvFooter)

        // ================= 🌟 新增：独立且实体的赛博同意按钮 =================
        var dialog: AlertDialog? = null // 提前声明对话框变量
        val btnClose = TextView(context).apply {
            text = "已阅并同意"
            setTextColor(Color.parseColor("#1A1A1B")) // 黑字
            textSize = 16f
            setTypeface(null, android.graphics.Typeface.BOLD)
            gravity = android.view.Gravity.CENTER
            setPadding(0, 35, 0, 35)
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.parseColor("#00E676")) // 亮绿底色，醒目且赛博
                cornerRadius = 20f
            }
            setOnClickListener {
                dialog?.dismiss() // 点击关闭弹窗
            }
        }
        layout.addView(btnClose)
        // ======================================================================

        val scroll = ScrollView(context).apply {
            addView(layout)
            isVerticalScrollBarEnabled = false
        }

        dialog = AlertDialog.Builder(this)
            .setCustomTitle(createCyberTitle("📖 KANE Face-to-Face 使用手册"))
            .setView(scroll)
            // ⚠️ 彻底删除了系统自带的 .setPositiveButton
            .create()

        dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))

        if (!isFinishing && !isDestroyed) dialog.show()
    }
    // 🌟 新增一个全局变量，用来记忆输入框的状态，防止弹窗“叠罗汉”
    private var currentTextInputDialog: androidx.appcompat.app.AlertDialog? = null
    // 👇 新增：用于在全屏大字报期间，把没写完的草稿框暂存起来
    private var pendingDraftDialog: androidx.appcompat.app.AlertDialog? = null
    // 👇 新增：记忆最后一次激活的是上方还是下方输入框
    private var lastActiveInputIsTop = false

    // 🌟 新增：专门用于展示输入框草稿的全屏大字报引擎 (含发音与翻转)
    private fun showDraftFullscreenDisplay(text: String, isTop: Boolean, color: String, voiceId: String) {
        triggerVibration(30)

        tvFullscreenText.textSize = 38f
        tvFullscreenText.text = text
        tvFullscreenText.setTextColor(android.graphics.Color.parseColor(color))

        // 👇 核心修正 1：只翻转装载文字的滑动层，底板和按钮永远不转！
        val scrollFullscreen = findViewById<ScrollView>(R.id.scroll_fullscreen)
        scrollFullscreen.rotation = if (isTop) 180f else 0f

        layoutFullscreenOverlay.rotation = 0f
        layoutFullscreenOverlay.alpha = 0f
        layoutFullscreenOverlay.visibility = View.VISIBLE
        layoutFullscreenOverlay.animate().alpha(1f).setDuration(150).start()

        // 👇 核心修正 2：赛博风“播放”按钮 (永远锚定在左下角)
        val btnFullscreenPlay = findViewById<TextView>(R.id.btn_fullscreen_play)
        if (voiceId.startsWith("hy-AM")) {
            btnFullscreenPlay.visibility = View.GONE
        } else {
            btnFullscreenPlay.visibility = View.VISIBLE
            val strPlay = "🔊 播放语音"
            val strStop = "⏹️ 停止播报"
            btnFullscreenPlay.text = strPlay
            btnFullscreenPlay.setTextColor(android.graphics.Color.WHITE)
            btnFullscreenPlay.textSize = 16f
            btnFullscreenPlay.setPadding(60, 30, 60, 30) // 胶囊内边距
            btnFullscreenPlay.background = android.graphics.drawable.GradientDrawable().apply {
                setColor(android.graphics.Color.parseColor("#1A1A1B"))
                cornerRadius = 60f
                setStroke(4, android.graphics.Color.parseColor("#00BCFF")) // 蓝边框
            }

            val playParams = btnFullscreenPlay.layoutParams as FrameLayout.LayoutParams
            playParams.gravity = android.view.Gravity.BOTTOM or android.view.Gravity.START
            playParams.setMargins(60, 0, 0, 100) // 靠左 60，离底 100
            btnFullscreenPlay.layoutParams = playParams

            btnFullscreenPlay.setOnClickListener {
                triggerVibration(50)
                if (edgeTts.isSpeaking) {
                    edgeTts.stop()
                    btnFullscreenPlay.text = strPlay
                    btnFullscreenPlay.setTextColor(android.graphics.Color.WHITE)
                    (btnFullscreenPlay.background as android.graphics.drawable.GradientDrawable).setStroke(4, android.graphics.Color.parseColor("#00BCFF"))
                } else {
                    val forceHeadset = isTop && isHeadsetPluggedIn() // 🌟 强制私密播报
                    edgeTts.speak(text, voiceId, forceHeadset,
                        onNodeSelected = { nodeName ->
                            runOnUiThread {
                                btnFullscreenPlay.text = "⏳ 连接 $nodeName"
                                btnFullscreenPlay.setTextColor(android.graphics.Color.parseColor("#00BCFF"))
                            }
                        },
                        onStart = {
                            runOnUiThread {
                                btnFullscreenPlay.text = strStop
                                btnFullscreenPlay.setTextColor(android.graphics.Color.parseColor("#00FF00"))
                                (btnFullscreenPlay.background as android.graphics.drawable.GradientDrawable).setStroke(4, android.graphics.Color.parseColor("#00FF00"))
                            }
                        },
                        onDone = {
                            runOnUiThread {
                                btnFullscreenPlay.text = strPlay
                                btnFullscreenPlay.setTextColor(android.graphics.Color.WHITE)
                                (btnFullscreenPlay.background as android.graphics.drawable.GradientDrawable).setStroke(4, android.graphics.Color.parseColor("#00BCFF"))
                            }
                        }
                    )
                }
            }
        }

        // 👇 核心修正 3：赛博风“翻转”按钮 (永远锚定在右下角)
        var btnFlip = layoutFullscreenOverlay.findViewWithTag<TextView>("btn_flip_screen")
        if (btnFlip == null) {
            btnFlip = TextView(this).apply {
                tag = "btn_flip_screen"
                this.text = "🔄 翻转视角"
                setTextColor(android.graphics.Color.WHITE)
                textSize = 16f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setPadding(60, 30, 60, 30) // 胶囊内边距
                gravity = android.view.Gravity.CENTER
                background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(android.graphics.Color.parseColor("#1A1A1B"))
                    cornerRadius = 60f
                    setStroke(4, android.graphics.Color.parseColor("#00E676")) // 绿边框
                }

                val params = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    gravity = android.view.Gravity.BOTTOM or android.view.Gravity.END
                    setMargins(0, 0, 60, 100) // 靠右 60，离底 100 (完美对称)
                }
                layoutFullscreenOverlay.addView(this, params)
            }
        } else {
            btnFlip.background = android.graphics.drawable.GradientDrawable().apply {
                setColor(android.graphics.Color.parseColor("#1A1A1B"))
                cornerRadius = 60f
                setStroke(4, android.graphics.Color.parseColor("#00E676"))
            }
            val flipParams = btnFlip.layoutParams as FrameLayout.LayoutParams
            flipParams.gravity = android.view.Gravity.BOTTOM or android.view.Gravity.END
            flipParams.setMargins(0, 0, 60, 100)
            btnFlip.layoutParams = flipParams
        }
        btnFlip.visibility = View.VISIBLE

        // 点击翻转，只转 ScrollView
        btnFlip.setOnClickListener {
            triggerVibration(40)
            val currentRotation = scrollFullscreen.rotation
            val targetRotation = if (currentRotation == 0f) 180f else 0f
            scrollFullscreen.animate().rotation(targetRotation).setDuration(250).start()
        }

        pendingDraftDialog = currentTextInputDialog
    }
    private fun showTextInputDialog(initialText: String = "") {
        currentTextInputDialog?.dismiss()

        val context = this
        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(60, 50, 60, 50)
            layoutTransition = android.animation.LayoutTransition()
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#1A1A1B"))
                cornerRadius = 40f
                setStroke(4, Color.parseColor("#333333"))
            }
        }

        class ModuleUI(
            val root: LinearLayout,
            val title: TextView,
            val inputRow: LinearLayout,
            val et: EditText,
            val btnContainer: LinearLayout,
            val speakerBtn: TextView
        )

        fun createInputModule(titleText: String, isTop: Boolean, activeColor: String): ModuleUI {
            val moduleLayout = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                layoutTransition = android.animation.LayoutTransition()
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    bottomMargin = 50
                }
            }

            val title = TextView(context).apply {
                text = titleText
                setTextColor(android.graphics.Color.parseColor(activeColor))
                textSize = 14f
                setPadding(10, 0, 0, 15)
                setTypeface(null, android.graphics.Typeface.BOLD)
            }
            moduleLayout.addView(title)

            val inputRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                isBaselineAligned = false
                layoutTransition = android.animation.LayoutTransition()
                clipChildren = false
                clipToPadding = false
            }

            val et = EditText(context).apply {
                hint = "输入文字 / Type here..."
                setHintTextColor(android.graphics.Color.parseColor("#555555"))
                setTextColor(android.graphics.Color.WHITE)
                textSize = 16f
                setPadding(35, 30, 35, 30)
                gravity = android.view.Gravity.TOP or android.view.Gravity.START
                inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
                maxLines = 18
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginEnd = 25
                }
                background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(android.graphics.Color.parseColor("#0F0F0F"))
                    setStroke(2, android.graphics.Color.DKGRAY)
                    cornerRadius = 20f
                }
            }

            val density = context.resources.displayMetrics.density
            val btnSize = (45 * density).toInt()
            val smallBtnSize = (38 * density).toInt()

            val btnContainer = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                layoutTransition = android.animation.LayoutTransition()
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.MATCH_PARENT).apply {
                    marginStart = (12 * density).toInt()
                }
            }

            val topBtnGroup = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = android.view.Gravity.CENTER_HORIZONTAL
            }

            val btnSave = TextView(context).apply {
                text = "➕"
                textSize = 18f
                gravity = android.view.Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(smallBtnSize, smallBtnSize).apply { bottomMargin = (10 * density).toInt() }
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.parseColor("#1A1A1B"))
                    setStroke((1.5f * density).toInt(), Color.parseColor("#555555"))
                }
            }

            val btnNotebook = TextView(context).apply {
                text = "📑"
                textSize = 18f
                gravity = android.view.Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(smallBtnSize, smallBtnSize)
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.parseColor("#1A1A1B"))
                    setStroke((1.5f * density).toInt(), Color.parseColor("#555555"))
                }
            }
            topBtnGroup.addView(btnSave)
            topBtnGroup.addView(btnNotebook)

            val spacer = android.widget.Space(context).apply {
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
            }

            val bottomBtnGroup = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = android.view.Gravity.CENTER_HORIZONTAL
                layoutTransition = android.animation.LayoutTransition()
            }

            val speakerBtn = TextView(context).apply {
                text = "🔊"
                textSize = 20f
                gravity = android.view.Gravity.CENTER
                visibility = View.GONE
                layoutParams = LinearLayout.LayoutParams(btnSize, btnSize).apply { bottomMargin = (12 * density).toInt() }
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.parseColor("#252526"))
                    setStroke((2 * density).toInt(), Color.parseColor(activeColor))
                }
            }

            val sendBtn = TextView(context).apply {
                text = "🚀"
                textSize = 20f
                gravity = android.view.Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(btnSize, btnSize)
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.parseColor("#252526"))
                    setStroke((2 * density).toInt(), Color.parseColor(activeColor))
                }
            }
            bottomBtnGroup.addView(speakerBtn)
            bottomBtnGroup.addView(sendBtn)

            btnSave.setOnClickListener {
                val content = et.text.toString().trim()
                if (content.isEmpty()) {
                    triggerVibration(20)
                    Toast.makeText(context, "⚠️ 请先输入文字", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                it.animate().scaleX(0.8f).scaleY(0.8f).setDuration(100).withEndAction {
                    it.animate().scaleX(1f).scaleY(1f).setDuration(100).start()
                }.start()

                val rawTitle = content.replace("\n", " ").take(8)
                val title = if (content.length > 8) "$rawTitle..." else rawTitle
                val timeFormatter = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
                val timeStr = timeFormatter.format(java.util.Date())

                val array = getNotebookData()
                val newObj = org.json.JSONObject().apply {
                    put("id", java.util.UUID.randomUUID().toString())
                    put("title", title)
                    put("content", content)
                    put("timestamp", timeStr)
                }
                val newArray = org.json.JSONArray()
                newArray.put(newObj)
                for (i in 0 until array.length()) newArray.put(array.getJSONObject(i))
                heavyTaskExecutor.execute { try { java.io.File(filesDir, "kane_notebook.json").writeText(newArray.toString(), Charsets.UTF_8) } catch (e: Exception) {} }
                triggerVibration(40)
                Toast.makeText(context, "✅ 已存入笔记本", Toast.LENGTH_SHORT).show()
            }

            btnNotebook.setOnClickListener {
                triggerVibration(30)
                showNotebookSubDialog { content ->
                    et.setText(content)
                    et.setSelection(et.text.length)
                }
            }

            speakerBtn.setOnClickListener {
                val input = et.text.toString().trim()
                if (input.isEmpty()) return@setOnClickListener

                triggerVibration(40)
                if (edgeTts.isSpeaking) {
                    edgeTts.stop()
                    speakerBtn.text = "🔊"
                    (speakerBtn.background as GradientDrawable).setColor(Color.parseColor("#252526"))
                    (speakerBtn.background as GradientDrawable).setStroke((2 * density).toInt(), Color.parseColor(activeColor))
                } else {
                    val voiceName = if (isTop) ptVoiceName else myVoiceName
                    val targetLangName = if (isTop) ptLangName else myLangName
                    val voiceId = getSmartVoiceId(voiceName, targetLangName)

                    val forceHeadset = isTop && isHeadsetPluggedIn() // 🌟 强制私密播报
                    edgeTts.speak(input, voiceId, forceHeadset,
                        onNodeSelected = { _ -> runOnUiThread { speakerBtn.text = "⏳"; (speakerBtn.background as GradientDrawable).setColor(Color.parseColor("#121212")) } },
                        onStart = { runOnUiThread { speakerBtn.text = "⏹️"; (speakerBtn.background as GradientDrawable).setColor(Color.parseColor("#331111")); (speakerBtn.background as GradientDrawable).setStroke((2 * density).toInt(), Color.parseColor("#FF4444")) } },
                        onDone = { runOnUiThread { speakerBtn.text = "🔊"; (speakerBtn.background as GradientDrawable).setColor(Color.parseColor("#252526")); (speakerBtn.background as GradientDrawable).setStroke((2 * density).toInt(), Color.parseColor(activeColor)) } }
                    )
                }
            }

            sendBtn.setOnClickListener {
                val input = et.text.toString().trim()
                if (input.isNotEmpty()) {
                    triggerVibration(50)
                    edgeTts.stop()
                    try {
                        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
                        imm.hideSoftInputFromWindow(et.windowToken, 0)
                    } catch (e: Exception) {}
                    currentTextInputDialog?.dismiss()
                    processTextPipeline(input, isTop)
                }
            }

            btnContainer.addView(topBtnGroup)
            btnContainer.addView(spacer)
            btnContainer.addView(bottomBtnGroup)
            inputRow.addView(et)
            inputRow.addView(btnContainer)
            moduleLayout.addView(inputRow)
            return ModuleUI(moduleLayout, title, inputRow, et, btnContainer, speakerBtn)
        }

        val topModule = createInputModule("✍️ 对方文字输入【 ${ptLangName} 】", isTop = true, activeColor = "#00BCFF")
        val bottomModule = createInputModule("✍️ 我方文字输入【 ${myLangName} 】", isTop = false, activeColor = "#00E676")

        layout.addView(topModule.root)
        layout.addView(bottomModule.root)

        val universalControlBar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = 20
            }
            visibility = View.GONE
        }

        var universalFontSize = 16f

        val btnUniversalMinus = TextView(context).apply {
            text = "➖ 缩小"
            setTextColor(Color.parseColor("#888888"))
            textSize = 14f
            setTypeface(null, android.graphics.Typeface.BOLD)
            gravity = android.view.Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setPadding(0, 20, 0, 10)
            setOnClickListener {
                triggerVibration(20)
                if (universalFontSize > 12f) {
                    universalFontSize -= 2f
                    topModule.et.textSize = universalFontSize
                    bottomModule.et.textSize = universalFontSize
                }
            }
        }

        val btnUniversalFullscreen = TextView(context).apply {
            text = "⛶ 全屏展示"
            setTextColor(android.graphics.Color.parseColor("#00E676"))
            textSize = 15f
            setTypeface(null, android.graphics.Typeface.BOLD)
            gravity = android.view.Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setPadding(0, 20, 0, 10)
            setOnClickListener {
                val isTopActive = topModule.inputRow.visibility == View.VISIBLE
                val activeModule = if (isTopActive) topModule else bottomModule
                val activeColor = if (isTopActive) "#00BCFF" else "#00E676"
                val draftText = activeModule.et.text.toString().trim()

                if (draftText.isNotEmpty()) {
                    triggerVibration(40)
                    val voiceName = if (isTopActive) ptVoiceName else myVoiceName
                    val langName = if (isTopActive) ptLangName else myLangName
                    val voiceId = getSmartVoiceId(voiceName, langName)

                    try {
                        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
                        imm.hideSoftInputFromWindow(activeModule.et.windowToken, 0)
                    } catch (e: Exception) {}
                    currentTextInputDialog?.hide()
                    showDraftFullscreenDisplay(draftText, isTopActive, activeColor, voiceId)
                } else {
                    Toast.makeText(context, "⚠️ 框内还没有文字哦", Toast.LENGTH_SHORT).show()
                }
            }
        }

        val btnUniversalPlus = TextView(context).apply {
            text = "➕ 放大"
            setTextColor(Color.parseColor("#888888"))
            textSize = 14f
            setTypeface(null, android.graphics.Typeface.BOLD)
            gravity = android.view.Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setPadding(0, 20, 0, 10)
            setOnClickListener {
                triggerVibration(20)
                if (universalFontSize < 36f) {
                    universalFontSize += 2f
                    topModule.et.textSize = universalFontSize
                    bottomModule.et.textSize = universalFontSize
                }
            }
        }

        universalControlBar.addView(btnUniversalMinus)
        universalControlBar.addView(btnUniversalFullscreen)
        universalControlBar.addView(btnUniversalPlus)
        layout.addView(universalControlBar)

        fun updateSpeakerState(module: ModuleUI, hasFocus: Boolean, currentText: String) {
            if (hasFocus || currentText.isNotEmpty()) module.speakerBtn.visibility = View.VISIBLE
            else module.speakerBtn.visibility = View.GONE
        }

        fun switchToTopFocus() {
            lastActiveInputIsTop = true
            topModule.title.text = "✍️ 对方文字输入【 ${ptLangName} 】"
            topModule.inputRow.visibility = View.VISIBLE
            topModule.btnContainer.visibility = View.VISIBLE
            topModule.et.minLines = 12
            bottomModule.inputRow.visibility = View.GONE
            bottomModule.title.text = "👉 点击切换：我方输入【 ${myLangName} 】"
            universalControlBar.visibility = View.VISIBLE
        }

        fun switchToBottomFocus() {
            lastActiveInputIsTop = false
            bottomModule.title.text = "✍️ 我方文字输入【 ${myLangName} 】"
            bottomModule.inputRow.visibility = View.VISIBLE
            bottomModule.btnContainer.visibility = View.VISIBLE
            bottomModule.et.minLines = 12
            topModule.inputRow.visibility = View.GONE
            topModule.title.text = "👉 点击切换：对方输入【 ${ptLangName} 】"
            universalControlBar.visibility = View.VISIBLE
        }

        fun resetInitialState() {
            topModule.title.text = "✍️ 对方文字输入【 ${ptLangName} 】"
            topModule.inputRow.visibility = View.VISIBLE
            topModule.btnContainer.visibility = View.GONE
            topModule.et.minLines = 2

            bottomModule.title.text = "✍️ 我方文字输入【 ${myLangName} 】"
            bottomModule.inputRow.visibility = View.VISIBLE
            bottomModule.btnContainer.visibility = View.GONE
            bottomModule.et.minLines = 2

            universalControlBar.visibility = View.GONE
        }

        fun bindModuleEvents(module: ModuleUI, isTop: Boolean, activeColor: String) {
            module.title.setOnClickListener {
                if (isTop) switchToTopFocus() else switchToBottomFocus()
                module.et.post {
                    module.et.requestFocus()
                    val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
                    imm.showSoftInput(module.et, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
                }
            }
            module.et.setOnFocusChangeListener { _, hasFocus ->
                val color = if (hasFocus) activeColor else "#444444"
                val width = if (hasFocus) 5 else 2
                module.et.background = GradientDrawable().apply {
                    setColor(Color.parseColor("#0F0F0F"))
                    setStroke(width, Color.parseColor(color))
                    cornerRadius = 20f
                }
                if (hasFocus) {
                    if (isTop) switchToTopFocus() else switchToBottomFocus()
                }
                updateSpeakerState(module, hasFocus, module.et.text.toString().trim())
            }
            module.et.addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    updateSpeakerState(module, module.et.hasFocus(), s.toString().trim())
                }
                override fun afterTextChanged(s: android.text.Editable?) {}
            })
        }

        bindModuleEvents(topModule, isTop = true, activeColor = "#00BCFF")
        bindModuleEvents(bottomModule, isTop = false, activeColor = "#00E676")

        if (initialText.isNotEmpty()) {
            if (lastActiveInputIsTop) {
                topModule.et.setText(initialText)
                topModule.et.setSelection(initialText.length)
                switchToTopFocus()
                updateSpeakerState(topModule, topModule.et.hasFocus(), initialText)
            } else {
                bottomModule.et.setText(initialText)
                bottomModule.et.setSelection(initialText.length)
                switchToBottomFocus()
                updateSpeakerState(bottomModule, bottomModule.et.hasFocus(), initialText)
            }
        } else {
            resetInitialState()
        }

        // 🌟 核心修复：为弹窗套上滑动外衣，彻底解决安卓键盘挤压遮挡的问题
        val scrollWrapper = ScrollView(context).apply {
            addView(layout)
            isVerticalScrollBarEnabled = false
        }

        currentTextInputDialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setView(scrollWrapper)
            .create()

        currentTextInputDialog?.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
        currentTextInputDialog?.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        currentTextInputDialog?.setOnDismissListener { edgeTts.stop() }

        if (!isFinishing && !isDestroyed) currentTextInputDialog?.show()
    }

    private fun loadSettings() {
        aiEngine.groqApiKey = sharedPrefs.getString("groq_key", "") ?: ""
        aiEngine.currentGroqModel = sharedPrefs.getString("groq_model", "openai/gpt-oss-120b") ?: "openai/gpt-oss-120b" // 👈 修改默认值
        aiEngine.currentGroqAsrModel = sharedPrefs.getString("groq_asr_model", "whisper-large-v3") ?: "whisper-large-v3"
        aiEngine.geminiApiKey = sharedPrefs.getString("gemini_key", "") ?: ""
        aiEngine.currentGeminiModel = sharedPrefs.getString("gemini_model", "gemini-3.1-flash-lite") ?: "gemini-3.1-flash-lite"
        // 🌟 新增：加载专属同传模型
        aiEngine.currentGeminiLiveModel = sharedPrefs.getString("gemini_live_model", "gemini-3.5-live-translate-preview") ?: "gemini-3.5-live-translate-preview"
        myLangName = sharedPrefs.getString("lang_me", "中文") ?: "中文"
        ptLangName = sharedPrefs.getString("lang_pt", "英语") ?: "英语"
        myVoiceName = sharedPrefs.getString("voice_me", "晓晓 (中&英·温柔女声)") ?: "晓晓 (中&英·温柔女声)"
        ptVoiceName = sharedPrefs.getString("voice_pt", "Ava (英文·自然女声)") ?: "Ava (英文·自然女声)"
        isTtsEnabled = sharedPrefs.getBoolean("tts_enabled", true)

        // 👇 🌟 新增这三行：加载上次保存的外放音量，默认值为 100
        val savedVolume = sharedPrefs.getInt("tts_speaker_volume", 100)
        edgeTts.speakerVolume = savedVolume
        if (edgeTts.isSpeakerRoute) seekbarTtsVolume.progress = savedVolume

        btnBottomMic.text = "🎙️\n${myLangName.take(1)}"
        btnTopMic.text = "🎙️\n${ptLangName.take(1)}"
    }

    private fun createCyberTitle(titleText: String): TextView {
        return TextView(this).apply {
            text = titleText
            textSize = 15f
            setTextColor(Color.WHITE)
            setTypeface(null, android.graphics.Typeface.BOLD)
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(60, 45, 60, 45)
            setBackgroundColor(Color.parseColor("#1A1A1B"))
        }
    }

    private fun showKillerDialog(text: String) {
        val context = this
        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(60, 40, 60, 40)
            setBackgroundColor(Color.parseColor("#1A1A1B"))
        }

        val hintText = TextView(context).apply {
            this.text = "🎯 请精简核心幻听词汇：\n(建议越短越好，只要此后识别的语音包含该词，将被直接拦截)"
            setTextColor(Color.parseColor("#BBBBBB"))
            textSize = 14f
            setPadding(0, 0, 0, 20)
        }

        val etKeyword = EditText(context).apply {
            setText(text.trim())
            setTextColor(Color.WHITE)
            textSize = 16f
            setPadding(30, 30, 30, 30)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#0F0F0F"))
                setStroke(3, Color.parseColor("#FF4444"))
                cornerRadius = 15f
            }
        }

        layout.addView(hintText)
        layout.addView(etKeyword)

        val killerDialog = AlertDialog.Builder(this)
            .setTitle("🛡️ 封印幻听词汇")
            .setView(layout)
            .setPositiveButton("永久拉黑") { _, _ ->
                val keyword = etKeyword.text.toString().trim().lowercase()
                if (keyword.isNotEmpty()) {
                    killerSet.add(keyword)
                    sharedPrefs.edit().putStringSet("killer_list", killerSet).apply()
                    showTransientIslandMessage("🎯 规则已生效", "#FF4444")
                    resetIslandDelayed()
                }
            }
            .setNegativeButton("取消", null)
            .create()

        if (!isFinishing && !isDestroyed) killerDialog.show()
    }

    private fun showBlacklistManagerDialog() {
        val context = this
        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(50, 50, 50, 50)
            setBackgroundColor(Color.parseColor("#1A1A1B"))
            layoutTransition = android.animation.LayoutTransition()
        }

        val addRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }

        val etNewRule = EditText(context).apply {
            hint = "手动输入幻听拦截词..."
            setHintTextColor(Color.parseColor("#555555"))
            setTextColor(Color.WHITE)
            setPadding(25, 25, 25, 25)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#0F0F0F"))
                setStroke(2, Color.DKGRAY)
                cornerRadius = 15f
            }
        }

        val btnAdd = TextView(context).apply {
            text = "➕"
            textSize = 24f
            setPadding(30, 0, 10, 0)
        }

        addRow.addView(etNewRule)
        addRow.addView(btnAdd)
        layout.addView(addRow)

        val tvListTitle = TextView(context).apply {
            text = "已激活的拦截规则"
            setTextColor(Color.parseColor("#00BCFF"))
            setTypeface(null, android.graphics.Typeface.BOLD)
            textSize = 14f
            setPadding(0, 40, 0, 20)
        }
        layout.addView(tvListTitle)

        val listContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutTransition = android.animation.LayoutTransition()
        }

        val density = context.resources.displayMetrics.density
        val scrollView = ScrollView(context).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (300 * density).toInt())
            addView(listContainer)
        }
        layout.addView(scrollView)

        val btnReset = Button(context).apply {
            text = "🔄 恢复初始推荐规则"
            setBackgroundColor(Color.parseColor("#331111"))
            setTextColor(Color.parseColor("#FF4444"))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = 40 }
        }
        layout.addView(btnReset)

        fun renderList() {
            listContainer.removeAllViews()
            tvListTitle.text = "已激活的拦截规则 (${killerSet.size})"

            val sortedList = killerSet.toList().sorted()
            for (rule in sortedList) {
                val row = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    setPadding(30, 25, 10, 25)
                    background = GradientDrawable().apply {
                        setColor(Color.parseColor("#252526"))
                        cornerRadius = 15f
                    }
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                        bottomMargin = 15
                    }
                }

                val tvRule = TextView(context).apply {
                    text = rule
                    setTextColor(Color.WHITE)
                    textSize = 16f
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                }

                val btnDelete = TextView(context).apply {
                    text = "❌"
                    textSize = 18f
                    setPadding(20, 10, 20, 10)
                    setOnClickListener {
                        killerSet.remove(rule)
                        sharedPrefs.edit().putStringSet("killer_list", killerSet).apply()
                        renderList()
                        triggerVibration(30)
                    }
                }

                row.addView(tvRule)
                row.addView(btnDelete)
                listContainer.addView(row)
            }
        }

        renderList()

        btnAdd.setOnClickListener {
            val newRule = etNewRule.text.toString().trim().lowercase()
            if (newRule.isNotEmpty() && !killerSet.contains(newRule)) {
                killerSet.add(newRule)
                sharedPrefs.edit().putStringSet("killer_list", killerSet).apply()
                etNewRule.text.clear()
                renderList()
                triggerVibration(30)
            }
        }

        btnReset.setOnClickListener {
            val resetConfirmDialog = AlertDialog.Builder(context)
                .setTitle("⚠️ 紧急洗牌")
                .setMessage("确定要清空所有自定义规则，并恢复系统默认的 7 个底层规则吗？\n(这将会清除你此前手动添加的所有黑名单)")
                .setPositiveButton("确定恢复") { _, _ ->
                    killerSet.clear()
                    killerSet.addAll(setOf("字幕", "谢谢观看", "点个赞", "mingjing", "subscribe", "watching","请不吝点赞 订阅 转发 打赏支持明镜与点点栏目"))
                    sharedPrefs.edit().putStringSet("killer_list", killerSet).apply()
                    renderList()
                    triggerVibration(50)
                }
                .setNegativeButton("取消", null)
                .create()

            if (!isFinishing && !isDestroyed) resetConfirmDialog.show()
        }

        val blacklistDialog = AlertDialog.Builder(context)
            .setCustomTitle(createCyberTitle("🛡️ 幻听防火墙管理台"))
            .setView(layout)
            .setPositiveButton("完成", null)
            .create()

        if (!isFinishing && !isDestroyed) blacklistDialog.show()
    }

    private fun showBubbleOptionsDialog(msg: ChatMessage) {
        val options = arrayOf("📋 复制译文", "📄 复制原文", "📑 加入快捷笔记本", "✏️ 编辑并重译", "🎯 标记为幻听拉黑")

        val optionsDialog = AlertDialog.Builder(this)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> copyToClipboard("译文", msg.translatedText)
                    1 -> copyToClipboard("原文", msg.originalText)
                    2 -> {
                        val content = msg.originalText
                        val rawTitle = content.replace("\n", " ").take(12)
                        val title = if (content.length > 12) "$rawTitle..." else rawTitle

                        // 🌟 新增：获取当前时间作为时间戳
                        val timeFormatter = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
                        val timeStr = timeFormatter.format(java.util.Date())

                        val array = getNotebookData()
                        val newObj = org.json.JSONObject().apply {
                            put("id", java.util.UUID.randomUUID().toString())
                            put("title", title)
                            put("content", content)
                            put("timestamp", timeStr) // 👈 存入时间戳
                        }

                        val newArray = org.json.JSONArray()
                        newArray.put(newObj)
                        for (i in 0 until array.length()) newArray.put(array.getJSONObject(i))

                        heavyTaskExecutor.execute { try { java.io.File(filesDir, "kane_notebook.json").writeText(newArray.toString(), Charsets.UTF_8) } catch (e: Exception) {} }

                        triggerVibration(40)
                        Toast.makeText(this, "✅ 已存入快捷笔记本", Toast.LENGTH_SHORT).show()
                    }
                    3 -> showEditDialog(msg)
                    4 -> showKillerDialog(msg.originalText)
                }
            }
            .create()

        if (!isFinishing && !isDestroyed) optionsDialog.show()
    }

    private fun copyToClipboard(label: String, text: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val clip = android.content.ClipData.newPlainText(label, text)
        clipboard.setPrimaryClip(clip)

        triggerVibration(50)
        showTransientIslandMessage("✅ $label 已复制", "#00FF00")
    }

    private fun isHallucination(text: String): Boolean {
        val clean = text.trim().lowercase()
        if (clean.isEmpty()) return true
        for (badWord in killerSet) if (clean.contains(badWord.lowercase())) return true
        return false
    }

    private fun processAiPipeline(wavBytes: ByteArray, isTop: Boolean) {
        val sourceLangName = if (isTop) ptLangName else myLangName
        val asrLangCode = AppConstants.LANG_CODES[sourceLangName] ?: "en"

        aiEngine.transcribeAudio(wavBytes, asrLangCode) { asrSuccess, rawText ->
            if (isDestroyed || isFinishing) return@transcribeAudio

            if (asrSuccess) {
                val text = rawText.replace(Regex("<\\|.*?\\|>"), "").trim()

                if (text.isNotBlank()) {
                    if (isHallucination(text)) {
                        val noiseMsg = if (isTop) "🎯 Noise Filtered" else "🎯 已过滤杂音"
                        setIslandState(noiseMsg, "#888888", isTop = isTop)
                        resetIslandDelayed()
                        return@transcribeAudio
                    }
                    processTextPipeline(text, isTop)
                } else {
                    val noVoiceMsg = if (isTop) "⚠️ Voice not detected" else "⚠️ 未听清，请重试"
                    setIslandState(noVoiceMsg, "#FFA500", isTop = isTop)
                    finishClassicRecordingCycle() // 🌟 没听清退出，善后并恢复同传
                    resetIslandDelayed()
                }
            } else {
                // 🌟 雷达嗅探：语音模型死掉了，触发主界面级强弹窗警告
                if (rawText.contains("model", true) && (rawText.contains("not exist", true) || rawText.contains("404"))) {
                    Toast.makeText(this@MainActivity, "🚨 语音识别模型已失效/下架！请去设置点击【联网拉取最新模型】。", Toast.LENGTH_LONG).show()
                }
                setIslandState("语音服务失联: " + rawText.take(15), "#FF4444", isTop = isTop)
                finishClassicRecordingCycle() // 🌟 报错退出，善后并恢复同传
                resetIslandDelayed(3500L)
            }
        }
    }

    private fun processTextPipeline(text: String, isTop: Boolean) {
        val sourceLangName = if (isTop) ptLangName else myLangName
        val targetLangName = if (isTop) myLangName else ptLangName

        val llmSourceEn = AppConstants.LANG_MAP_EN[sourceLangName] ?: "English"
        val llmTargetEn = AppConstants.LANG_MAP_EN[targetLangName] ?: "English"

        val voiceName = if (isTop) myVoiceName else ptVoiceName
        val voiceId = getSmartVoiceId(voiceName, targetLangName) // 🌟 使用智能路由获取真实发音ID

        val transMsg = if (isTop) "⏳ Translating..." else "⏳ 正在翻译..."
        setIslandState(transMsg, "#FFFF00", isTop = isTop)

        aiEngine.translateText(text, llmSourceEn, llmTargetEn,
            onFallback = { isModelDead ->
                runOnUiThread {
                    val fallbackMsg = if (isTop) "⚠️ Using Backup AI..." else "⚠️ 切换备用引擎..."
                    setIslandState(fallbackMsg, "#FFA500", isTop = isTop)

                    // 🌟 终极报警：捕捉到模型死亡信号！弹出 Toast 强警告！
                    if (isModelDead) {
                        Toast.makeText(this@MainActivity, "🚨 Groq当前模型已下架！请前往设置【拉取最新模型】，推荐尝试 GPT OSS 120B 或 llama-3.3-70B！", Toast.LENGTH_LONG).show()
                    }
                }
            }
        ) { llmSuccess, translated, engineName ->
            if (isDestroyed || isFinishing) return@translateText

            if (llmSuccess) {
                if (translated.isBlank()) {
                    val noiseMsg = if (isTop) "🎯 Noise Filtered" else "🎯 已过滤杂音"
                    setIslandState(noiseMsg, "#888888", isTop = isTop)
                    finishClassicRecordingCycle() // 🌟 过滤杂音退出，善后并恢复同传
                    resetIslandDelayed()
                    return@translateText
                }

                // 🌟 核心联动：给上下同源的气泡打上相同 ID 和发言人标签
                val msgId = java.util.UUID.randomUUID().toString()
                topAdapter.addMessage(ChatMessage(translated, text, isMe = isTop, voiceId = voiceId, isTopSpeaker = isTop, id = msgId))
                bottomAdapter.addMessage(ChatMessage(translated, text, isMe = !isTop, voiceId = voiceId, isTopSpeaker = isTop, id = msgId))

                rvTopChat.post {
                    // 🌟 终极物理法则：新气泡从听筒冒出后，强制吸附到 START（逻辑顶部 = 物理的灵动岛）
                    // 配合刚才铺设的虚拟跑道，旧气泡会被毫无阻碍地挤过中轴线，消失在画外！
                    val topScroller = object : androidx.recyclerview.widget.LinearSmoothScroller(this@MainActivity) {
                        override fun getVerticalSnapPreference(): Int {
                            return SNAP_TO_START
                        }
                    }
                    topScroller.targetPosition = topAdapter.itemCount - 1
                    rvTopChat.layoutManager?.startSmoothScroll(topScroller)
                }

                rvBottomChat.post {
                    val bottomScroller = object : androidx.recyclerview.widget.LinearSmoothScroller(this@MainActivity) {
                        override fun getVerticalSnapPreference(): Int {
                            return SNAP_TO_END
                        }
                    }
                    bottomScroller.targetPosition = bottomAdapter.itemCount - 1
                    rvBottomChat.layoutManager?.startSmoothScroll(bottomScroller)
                }

                if (isTtsEnabled) {
                    if (voiceId.startsWith("hy-AM")) {
                        val noAudioMsg = if (isTop) "⚠️ No Audio Support" else "⚠️ 该语种暂不支持语音"
                        setIslandState(noAudioMsg, "#FFA500", isTop = isTop)
                        resetIslandDelayed(3000L)
                    } else {
                        // 🌟 新增：注入 onNodeSelected 回调，在灵动岛展示节点分配情况
                        val forceHeadset = isTop && isHeadsetPluggedIn() // 🌟 强制私密播报
                        edgeTts.speak(translated, voiceId, forceHeadset,
                            onNodeSelected = { nodeName ->
                                runOnUiThread {
                                    val waitingMsg = if (isTop) "🎵 Preparing [$nodeName]" else "🎵 准备发音 [$nodeName]"
                                    setIslandState(waitingMsg, "#00BCFF", isTop = isTop, animatePop = false)
                                }
                            },
                            onStart = {
                                setIslandState("🔊   正在播报   ⏹️ ", "#00FF00", isTop = false)
                                updateLiveTranslateMuteState() // 🌟 外放喇叭发声，持续静音同传
                                tvDynamicIsland.isClickable = true
                                tvDynamicIsland.setOnClickListener {
                                    triggerVibration(50)
                                    edgeTts.stop()
                                    resetIsland()
                                }
                            },
                            onDone = {
                                finishClassicRecordingCycle() // 🌟 播报完美结束，善后并恢复同传
                                resetIsland()
                            }
                        )
                    }
                } else {
                    val doneMsg = if (isTop) "⚡ Translated via $engineName" else "⚡ 翻译成功 (由 $engineName 提供)"
                    setIslandState(doneMsg, "#00FF00", isTop = isTop)
                    finishClassicRecordingCycle() // 🌟 文字翻译完成直接结束，善后并恢复同传
                    resetIslandDelayed()
                }
            } else {
                setIslandState(translated, "#FF4444", isTop = isTop)
                resetIslandDelayed(3500L)
            }
        }
    }

    private fun setupUIBackgrounds() {
        tvDynamicIsland.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE; cornerRadius = 50f; setColor(Color.parseColor("#151515")); setStroke(3, Color.parseColor("#00FF00"))
        }

        btnTopMic.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.parseColor("#121212"))
            setStroke(6, Color.parseColor("#00BCFF"))
        }
        btnTopMic.setTextColor(Color.parseColor("#00BCFF"))

        btnBottomMic.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.parseColor("#121212"))
            setStroke(6, Color.parseColor("#00E676"))
        }
        btnBottomMic.setTextColor(Color.parseColor("#00E676"))
    }

    private var lastIslandText = "" // 🌟 新增：记忆灵动岛的上一条文本

    private fun setIslandState(text: String, colorStr: String, animatePop: Boolean = true, isTop: Boolean? = null) {
        if (isDestroyed || isFinishing) return

        // 🌟 核心升级 5：排他锁！强制杀死之前遗留的所有恢复任务，杜绝文字闪烁乱跳
        tvDynamicIsland.removeCallbacks(resetIslandRunnable)

        if (isTop != null) {
            tvDynamicIsland.rotation = if (isTop) 180f else 0f
        }

        val parsedColor = Color.parseColor(colorStr)

        // 🌟 核心升级 6：视觉防抖！如果连续收到相同的状态提示，不再触发疯狂弹跳动画
        val shouldAnimate = animatePop && (text != lastIslandText)

        tvDynamicIsland.text = text
        tvDynamicIsland.setTextColor(parsedColor)
        (tvDynamicIsland.background as GradientDrawable).setStroke(3, parsedColor)

        lastIslandText = text

        if (shouldAnimate) {
            tvDynamicIsland.animate().cancel() // 强行打断未完成的旧动画
            tvDynamicIsland.scaleX = 1.0f
            tvDynamicIsland.scaleY = 1.0f
            tvDynamicIsland.animate().scaleX(1.05f).scaleY(1.05f).setDuration(80).withEndAction {
                tvDynamicIsland.animate().scaleX(1.0f).scaleY(1.0f).setDuration(120).start()
            }.start()
        }
    }

    private fun resetIslandDelayed(delayMillis: Long = 1500L) {
        tvDynamicIsland.removeCallbacks(resetIslandRunnable)
        tvDynamicIsland.postDelayed(resetIslandRunnable, delayMillis)
    }

    private fun resetIsland() {
        setIslandState("🌤️ 随处心安，沟通无界", "#00FF00", animatePop = false, isTop = false)
        tvDynamicIsland.setOnClickListener(null)
        tvDynamicIsland.isClickable = false
    }
    // 👇 🌟 新增：智能降级展示方法。专门保护 TTS 停止按钮不被低优消息刷掉！
    private fun showTransientIslandMessage(text: String, colorStr: String, isTop: Boolean? = null) {
        // 如果 TTS 引擎正在发声，或者灵动岛已经被挂载了“停止按钮”（isClickable 为 true）
        if (edgeTts.isSpeaking || tvDynamicIsland.isClickable) {
            // 降级为原生 Toast 提示，绝对不触碰灵动岛
            Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
        } else {
            // 闲置状态，允许华丽地展示在灵动岛上
            setIslandState(text, colorStr, animatePop = true, isTop = isTop)
            resetIslandDelayed()
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupPushToTalkListener(button: TextView, isTop: Boolean) {
        var startY = 0f; var isCancelled = false
        val originalColor = if (isTop) "#00BCFF" else "#00E676"

        // 🌟 修复：引入安卓屏幕密度(dpi)计算，设定标准滑动物理距离（约 80dp）
        val cancelThreshold = 80 * resources.displayMetrics.density

        button.setOnTouchListener { _, event ->
            val bg = button.background as GradientDrawable

            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    if (!checkAudioPermission()) {
                        triggerVibration(100)
                        requestAudioPermission()
                        Toast.makeText(this@MainActivity, "⚠️ 必须允许麦克风权限才能录音！", Toast.LENGTH_SHORT).show()
                        return@setOnTouchListener true
                    }
                    edgeTts.pingServer()
                    aiEngine.prewarmConnections() // 👈 新增：手指刚按下，就让 AI 引擎在后台去“抢跑”铺路！

                    // 🌟 夺取底层麦克风控制权！彻底废弃 Thread.sleep，改为精准回调握手！
                    if (edgeTts.isSpeaking) edgeTts.stop()
                    startY = event.y; isCancelled = false; triggerVibration(50)

                    val msg = if (isTop) "🔴 Listening..." else "🔴 翻译员正在聆听..."
                    setIslandState(msg, "#FF4444", isTop = isTop)
                    bg.setStroke(6, Color.parseColor("#FF4444"))
                    button.setTextColor(Color.parseColor("#FF4444"))

                    button.animate().scaleX(0.85f).scaleY(0.85f).setDuration(150).start()
                    isCurrentlyRecordingClassic = true
                    updateLiveTranslateMuteState() // 🌟 录音开始，瞬间静音同传

                    // 🌟 脱去冗余的套娃线程，直接调用！
                    val startRecordingTask = {
                        audioProcessor.startRecording()
                    }

                    if (geminiLiveEngine != null) {
                        geminiLiveEngine?.suspendHardwareMic {
                            startRecordingTask()
                        }
                    } else {
                        startRecordingTask()
                    }

                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val deltaY = if (isTop) event.y - startY else startY - event.y
                    // 🌟 修复：使用动态计算的像素阈值，杜绝高分屏滑动迟钝问题
                    if (deltaY > cancelThreshold) {
                        if (!isCancelled) {
                            isCancelled = true; triggerVibration(80)
                            val cancelMsg = if (isTop) "🚫 Release to cancel" else "🚫 松开手指取消"
                            setIslandState(cancelMsg, "#FF4444", isTop = isTop)
                            button.animate().alpha(0.5f).setDuration(150).start()
                        }
                    } else {
                        if (isCancelled) {
                            isCancelled = false
                            val msg = if (isTop) "🔴 Listening..." else "🔴 翻译员正在聆听..."
                            setIslandState(msg, "#FF4444", isTop = isTop)
                            button.animate().alpha(1.0f).setDuration(150).start()
                        }
                    }
                    true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    bg.setStroke(6, Color.parseColor(originalColor))
                    button.setTextColor(Color.parseColor(originalColor))

                    button.animate().scaleX(1.0f).scaleY(1.0f).alpha(1.0f)
                        .setDuration(300).setInterpolator(OvershootInterpolator(2.0f)).start()

                    if (isProcessingAudio) return@setOnTouchListener true
                    isProcessingAudio = true

                    Thread {
                        try {
                            val wavData = audioProcessor.stopAndProcess()
                            runOnUiThread {
                                if (isDestroyed || isFinishing) return@runOnUiThread
                                isProcessingAudio = false // 正常解锁

                                if (!isCancelled && wavData != null) {
                                    val recogMsg = if (isTop) "⏳ Recognizing..." else "⏳ 正在识别语音..."
                                    setIslandState(recogMsg, "#FFFF00", isTop = isTop)
                                    processAiPipeline(wavData, isTop)
                                } else if (isCancelled) {
                                    val cancelMsg = if (isTop) "🚫 Cancelled" else "🚫 已取消"
                                    setIslandState(cancelMsg, "#FF4444", isTop = isTop)
                                    finishClassicRecordingCycle()
                                    resetIslandDelayed()
                                } else {
                                    val shortMsg = if (isTop) "⚠️ Too short" else "⚠️ 录音时间太短"
                                    setIslandState(shortMsg, "#FFA500", isTop = isTop)
                                    finishClassicRecordingCycle()
                                    resetIslandDelayed()
                                }
                            }
                        } catch (e: Exception) {
                            // 👇 航天级兜底：万一底层报错，强制解锁按钮，防止永久变砖
                            runOnUiThread {
                                isProcessingAudio = false
                                finishClassicRecordingCycle()
                                resetIslandDelayed()
                            }
                        }
                    }.start()
                    true
                }
                else -> false
            }
        }
    }

    private fun triggerVibration(duration: Long) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) vibrator.vibrate(VibrationEffect.createOneShot(duration, VibrationEffect.DEFAULT_AMPLITUDE))
        else @Suppress("DEPRECATION") vibrator.vibrate(duration)
    }

    private fun checkAudioPermission(): Boolean = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    private fun requestAudioPermission() { ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 100) }

    override fun onStop() {
        super.onStop()
        // 🌟 锁屏或退到后台时，自动切断同传，保护隐私、电量与流量
        if (isLiveTranslateEnabled) {
            isLiveTranslateEnabled = false
            btnLiveTranslate.text = "同\n传" // 🌟 恢复极简的竖排

            // 👇 修改这里：直接调用智能变色方法，如果有耳机就保持金光，没耳机就变灰
            updateLiveTranslateButtonUI()
            // 👆 修改结束

            geminiLiveEngine?.stop()
            geminiLiveEngine = null

            resetIsland()
        }
    }
    override fun onDestroy() {
        edgeTts.stop()
        geminiLiveEngine?.stop() // 👈 绝不遗留后台窃听器
        // 🌟 新增：航天级善后。彻底注销掉 3 分钟的休眠定时器，防止其在 Activity 死亡后被幽灵唤醒！
        liveEngineIdleRunnable?.let { tvDynamicIsland.removeCallbacks(it) }

        // 🌟 新增：关闭文件读写的单线程池，拒绝僵尸线程占用系统内存
        heavyTaskExecutor.shutdown()

        super.onDestroy()
    }

    private fun openCamera() {
        try {
            val cacheFolder = java.io.File(cacheDir, "kane_vision_cache")
            if (!cacheFolder.exists()) cacheFolder.mkdirs()

            val photoFile = java.io.File(cacheFolder, "photo_${System.currentTimeMillis()}.jpg")
            if (!photoFile.exists()) photoFile.createNewFile()

            cameraImageUri = androidx.core.content.FileProvider.getUriForFile(this, "${packageName}.fileprovider", photoFile)

            val captureIntent = android.content.Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE)
            val resolveInfoList = packageManager.queryIntentActivities(captureIntent, PackageManager.MATCH_DEFAULT_ONLY)
            for (resolveInfo in resolveInfoList) {
                val pName = resolveInfo.activityInfo.packageName
                grantUriPermission(
                    pName,
                    cameraImageUri,
                    android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION or android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }

            takePhotoLauncher.launch(cameraImageUri)
        } catch (e: Exception) {
            Toast.makeText(this, "⚠️ 无法创建相片缓存，请检查手机存储空间", Toast.LENGTH_SHORT).show()
        }
    }

    private fun launchCrop(sourceUri: android.net.Uri) {
        val cacheFolder = java.io.File(cacheDir, "kane_vision_cache")
        if (!cacheFolder.exists()) cacheFolder.mkdirs()
        val destUri = android.net.Uri.fromFile(java.io.File(cacheFolder, "crop_${System.currentTimeMillis()}.jpg"))

        val options = com.yalantis.ucrop.UCrop.Options().apply {
            setCompressionQuality(90)
            setToolbarColor(Color.parseColor("#121212"))
            setStatusBarColor(Color.parseColor("#000000"))
            setToolbarWidgetColor(Color.parseColor("#00E676"))
            setActiveControlsWidgetColor(Color.parseColor("#00E676"))

            // 🌟 提示文案微调
            setToolbarTitle("✨ 框选并扶正需要破译的文字")

            // 🌟 解锁自由裁切模式！允许手指直接拖拽线框的四个角和边缘
            setFreeStyleCropEnabled(true)

            // 🌟 核心修复 1：将底部控制栏设为显示 (false)！
            // 这样底部就会出现 uCrop 自带的“旋转”面板，里面包含一个【一键转 90° 的按钮】以及【细微角度调节滑块】
            setHideBottomControls(false)

            // 🌟 核心修复 2：全面解锁多点触控手势！
            // 无论用户在底部切换到哪个面板，都可以直接用【两根手指在屏幕上直接旋转和缩放图片】
            setAllowedGestures(
                com.yalantis.ucrop.UCropActivity.ALL, // 缩放卡片下的手势：允许全部
                com.yalantis.ucrop.UCropActivity.ALL, // 旋转卡片下的手势：允许全部
                com.yalantis.ucrop.UCropActivity.ALL  // 比例卡片下的手势：允许全部
            )
        }

        val uCropIntent = com.yalantis.ucrop.UCrop.of(sourceUri, destUri)
            .withOptions(options)
            .withMaxResultSize(2048, 2048) // 之前加固的防 OOM 内存防线保留
            .getIntent(this)

        cropLauncher.launch(uCropIntent)
    }

    private fun processCroppedImage(uri: android.net.Uri) {
        setIslandState("👁️ 视觉AI正在破译...", "#00BCFF")
        triggerVibration(50)

        // 👇 【已修改】换成了单线程排队执行
        heavyTaskExecutor.execute {
            try {
                val path = uri.path ?: return@execute // 👈 【已修改】同步更改为 return@execute
                val bitmap = android.graphics.BitmapFactory.decodeFile(path) ?: return@execute // 👈 【已修改】同步更改为 return@execute

                val sourceLangEn = AppConstants.LANG_MAP_EN[ptLangName] ?: "English"
                val targetLangEn = AppConstants.LANG_MAP_EN[myLangName] ?: "Chinese"

                // 🌟 更新了数据接收格式
                aiEngine.translateImageWithGemini(bitmap, sourceLangEn, targetLangEn) { success, regions, errorMsg ->
                    if (isDestroyed || isFinishing) return@translateImageWithGemini
                    if (success) {
                        setIslandState("✨ 破译完成", "#00FF00")
                        resetIslandDelayed()
                        showImageResultDialog(bitmap, regions) // 传递坐标数据组
                    } else {
                        setIslandState(errorMsg, "#FF4444")
                        resetIslandDelayed(3000)
                        clearVisionCache(forceWipe = true)
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    if (isDestroyed || isFinishing) return@runOnUiThread
                    setIslandState("⚠️ 图片读取异常", "#FF4444")
                    resetIslandDelayed()
                    clearVisionCache(forceWipe = true)
                }
            }
        } // 👈 【已修改】去掉了结尾的 .start()
    }

    private fun showImageResultDialog(croppedBitmap: android.graphics.Bitmap?, regions: List<AiEngine.ImageRegion>) {
        val context = this
        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#1A1A1B"))
            clipChildren = false
        }

        val toggleRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(20, 40, 20, 20)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }

        val btnTranslated = TextView(context).apply {
            text = "🌐 译文"
            textSize = 13f
            maxLines = 1
            gravity = android.view.Gravity.CENTER
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, 25, 0, 25)
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = 10 }
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#00E676"))
                cornerRadius = 50f
            }
        }

        val btnOriginal = TextView(context).apply {
            text = "📄 原文"
            textSize = 13f
            maxLines = 1
            gravity = android.view.Gravity.CENTER
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, 25, 0, 25)
            setTextColor(Color.parseColor("#888888"))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = 10 }
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#252526"))
                cornerRadius = 50f
            }
        }

        val btnCopyAll = TextView(context).apply {
            text = "复制全文"
            textSize = 13f
            maxLines = 1
            gravity = android.view.Gravity.CENTER
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, 25, 0, 25)
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = 10 }
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#333333"))
                setStroke(2, Color.parseColor("#555555"))
                cornerRadius = 50f
            }
        }

        val btnSaveNote = TextView(context).apply {
            text = "存入笔记"
            textSize = 13f
            maxLines = 1
            gravity = android.view.Gravity.CENTER
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, 25, 0, 25)
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#333333"))
                setStroke(2, Color.parseColor("#555555"))
                cornerRadius = 50f
            }
        }

        toggleRow.addView(btnTranslated)
        toggleRow.addView(btnOriginal)
        toggleRow.addView(btnCopyAll)
        toggleRow.addView(btnSaveNote)
        layout.addView(toggleRow)

        var overlayScale = 1.0f
        val overlayViews = mutableListOf<StrokeTextView>()

        val fontControlRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER
            setPadding(20, 0, 20, 40)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }

        val pillGroup = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#121212"))
                setStroke(2, Color.parseColor("#333333"))
                cornerRadius = 50f
            }
        }

        val tvScale = TextView(context).apply {
            text = "100%"
            textSize = 14f
            setTextColor(Color.parseColor("#00E676"))
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(20, 0, 20, 0)
        }

        fun applyScaleToOverlays() {
            tvScale.text = "${(overlayScale * 100).toInt()}%"
            for (tv in overlayViews) {
                tv.animate().scaleX(overlayScale).scaleY(overlayScale).setDuration(150).start()
            }
        }

        val btnMinus = TextView(context).apply {
            text = "A-"
            textSize = 16f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(60, 20, 40, 20)
            setTextColor(Color.WHITE)
            setOnClickListener {
                triggerVibration(30)
                if (overlayScale > 0.6f) {
                    overlayScale -= 0.2f
                    applyScaleToOverlays()
                } else {
                    Toast.makeText(context, "已经是最小了", Toast.LENGTH_SHORT).show()
                }
            }
        }

        val btnPlus = TextView(context).apply {
            text = "A+"
            textSize = 16f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(40, 20, 60, 20)
            setTextColor(Color.WHITE)
            setOnClickListener {
                triggerVibration(30)
                if (overlayScale < 3.0f) {
                    overlayScale += 0.2f
                    applyScaleToOverlays()
                } else {
                    Toast.makeText(context, "已经是最大了", Toast.LENGTH_SHORT).show()
                }
            }
        }

        pillGroup.addView(btnMinus)
        pillGroup.addView(tvScale)
        pillGroup.addView(btnPlus)
        fontControlRow.addView(pillGroup)
        layout.addView(fontControlRow)

        val frameLayout = FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            clipChildren = false
            clipToPadding = false
        }

        val imageView = ImageView(context).apply {
            setImageBitmap(croppedBitmap)
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT)
        }
        frameLayout.addView(imageView)

        // 🌟 新增 1：字幕样式状态机 (0: 默认半透底板, 1: 极简高透底板, 2: 无底色纯文字描边)
        var overlayStyleMode = 0
        var isShowingTranslated = true

        fun updateOverlayState() {
            for ((index, region) in regions.withIndex()) {
                val tv = overlayViews.getOrNull(index) ?: continue

                tv.text = if (isShowingTranslated) region.translated else region.original

                // 每次重绘前，先清除可能残留的阴影和描边
                tv.setShadowLayer(0f, 0f, 0f, Color.TRANSPARENT)
                tv.setStroke(Color.TRANSPARENT, 0f) // 🌟 清除实体描边

                when (overlayStyleMode) {
                    0 -> {
                        // 【模式 0：默认半透底板】
                        if (isShowingTranslated) {
                            tv.setTextColor(Color.WHITE)
                            tv.background = GradientDrawable().apply {
                                setColor(Color.parseColor("#E61A1A1B"))
                                setStroke(3, Color.parseColor("#00E676"))
                                cornerRadius = 8f
                            }
                        } else {
                            tv.setTextColor(Color.parseColor("#00BCFF"))
                            tv.background = GradientDrawable().apply {
                                setColor(Color.parseColor("#99000000"))
                                setStroke(3, Color.parseColor("#00BCFF"))
                                cornerRadius = 8f
                            }
                        }
                    }
                    1 -> {
                        // 【模式 1：极简高透底板】
                        if (isShowingTranslated) {
                            tv.setTextColor(Color.WHITE)
                            tv.background = GradientDrawable().apply {
                                setColor(Color.parseColor("#661A1A1B"))
                                setStroke(2, Color.parseColor("#8800E676"))
                                cornerRadius = 8f
                            }
                        } else {
                            tv.setTextColor(Color.parseColor("#00BCFF"))
                            tv.background = GradientDrawable().apply {
                                setColor(Color.parseColor("#44000000"))
                                setStroke(2, Color.parseColor("#8800BCFF"))
                                cornerRadius = 8f
                            }
                        }
                        tv.setShadowLayer(2f, 0f, 0f, Color.BLACK)
                    }
                    2 -> {
                        // 【模式 2：黑边 (羽化实体描边)】
                        if (isShowingTranslated) {
                            tv.setTextColor(Color.parseColor("#00E676"))
                        } else {
                            tv.setTextColor(Color.parseColor("#00BCFF"))
                        }
                        tv.background = null
                        tv.setStroke(Color.BLACK, 3f) // 5f 的底层实体硬边
                        // 🌟 核心：加上 3f 的纯黑光晕，让实体硬边的最外圈产生细腻的羽化过渡
                        tv.setShadowLayer(3f, 0f, 0f, Color.BLACK)
                    }
                    3 -> {
                        // 【模式 3：红字白边】
                        tv.setTextColor(Color.parseColor("#FF3333"))
                        tv.background = null
                        tv.setStroke(Color.WHITE, 5f)
                        // 🌟 羽化：加上纯白色的光晕
                        tv.setShadowLayer(3f, 0f, 0f, Color.WHITE)
                    }
                    4 -> {
                        // 【模式 4：白字红边】
                        tv.setTextColor(Color.WHITE)
                        tv.background = null
                        tv.setStroke(Color.parseColor("#FF3333"), 3f)
                        // 🌟 羽化：加上亮红色的光晕
                        tv.setShadowLayer(4f, 0f, 0f, Color.parseColor("#FF3333"))
                    }
                }
            }

            // 保持按钮切换逻辑不变
            if (isShowingTranslated) {
                btnTranslated.setTextColor(Color.WHITE)
                (btnTranslated.background as GradientDrawable).setColor(Color.parseColor("#00E676"))
                btnOriginal.setTextColor(Color.parseColor("#888888"))
                (btnOriginal.background as GradientDrawable).setColor(Color.parseColor("#252526"))
            } else {
                btnTranslated.setTextColor(Color.parseColor("#888888"))
                (btnTranslated.background as GradientDrawable).setColor(Color.parseColor("#252526"))
                btnOriginal.setTextColor(Color.WHITE)
                (btnOriginal.background as GradientDrawable).setColor(Color.parseColor("#00BCFF"))
            }
        }

        btnTranslated.setOnClickListener {
            triggerVibration(30)
            isShowingTranslated = true
            updateOverlayState()
        }

        btnOriginal.setOnClickListener {
            triggerVibration(30)
            isShowingTranslated = false
            updateOverlayState()
        }
        // 👇 粘贴到这里的 imageView 点击事件
        imageView.setOnClickListener {
            triggerVibration(20)
            // 🌟 修改：将 % 3 改为 % 5，支持在 5 种模式之间循环切换
            overlayStyleMode = (overlayStyleMode + 1) % 5
            updateOverlayState()

            // 🌟 修改：增加了新模式的提示文案
            val modeName = arrayOf(
                "🎨 默认背景",
                "🎨 高透背景",
                "🎨 无背景(黑边)",
                "🎨 红字白边",
                "🎨 白字红边"
            )[overlayStyleMode]
            Toast.makeText(context, modeName, Toast.LENGTH_SHORT).show()
        }

        btnCopyAll.setOnClickListener {
            triggerVibration(40)
            it.animate().scaleX(0.9f).scaleY(0.9f).setDuration(100).withEndAction {
                it.animate().scaleX(1f).scaleY(1f).setDuration(100).start()
            }.start()

            val sb = java.lang.StringBuilder()
            for (region in regions) {
                val lineText = if (isShowingTranslated) region.translated else region.original
                if (lineText.isNotBlank()) {
                    sb.append(lineText).append("\n")
                }
            }
            val finalContent = sb.toString().trim()
            if (finalContent.isNotEmpty()) {
                copyToClipboard(if (isShowingTranslated) "整页译文" else "整页原文", finalContent)

                val originalText = "复制全文"
                val originalBg = btnCopyAll.background
                btnCopyAll.text = "✅ 复制成功"
                btnCopyAll.setTextColor(Color.parseColor("#1A1A1B"))
                btnCopyAll.background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(Color.parseColor("#00E676"))
                    cornerRadius = 50f
                }
                btnCopyAll.postDelayed({
                    btnCopyAll.text = originalText
                    btnCopyAll.setTextColor(Color.WHITE)
                    btnCopyAll.background = originalBg
                }, 800)
            } else {
                Toast.makeText(context, "⚠️ 图片中未识别到有效文本", Toast.LENGTH_SHORT).show()
            }
        }

        btnSaveNote.setOnClickListener {
            triggerVibration(40)
            it.animate().scaleX(0.9f).scaleY(0.9f).setDuration(100).withEndAction {
                it.animate().scaleX(1f).scaleY(1f).setDuration(100).start()
            }.start()

            val sb = java.lang.StringBuilder()
            for (region in regions) {
                val lineText = if (isShowingTranslated) region.translated else region.original
                if (lineText.isNotBlank()) {
                    sb.append(lineText).append("\n")
                }
            }
            val finalContent = sb.toString().trim()
            if (finalContent.isNotEmpty()) {
                val rawTitle = finalContent.replace("\n", " ").take(12)
                val title = if (finalContent.length > 12) "$rawTitle..." else rawTitle

                val timeFormatter = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
                val timeStr = timeFormatter.format(java.util.Date())

                val array = getNotebookData()
                val newObj = org.json.JSONObject().apply {
                    put("id", java.util.UUID.randomUUID().toString())
                    put("title", "📷 视觉提取: $title")
                    put("content", finalContent)
                    put("timestamp", timeStr)
                }

                val newArray = org.json.JSONArray()
                newArray.put(newObj)
                for (i in 0 until array.length()) newArray.put(array.getJSONObject(i))
                heavyTaskExecutor.execute { try { java.io.File(filesDir, "kane_notebook.json").writeText(newArray.toString(), Charsets.UTF_8) } catch (e: Exception) {} }

                val originalText = "存入笔记"
                val originalBg = btnSaveNote.background
                btnSaveNote.text = "✅ 已存入"
                btnSaveNote.setTextColor(Color.parseColor("#1A1A1B"))
                btnSaveNote.background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(Color.parseColor("#00BCFF"))
                    cornerRadius = 50f
                }
                btnSaveNote.postDelayed({
                    btnSaveNote.text = originalText
                    btnSaveNote.setTextColor(Color.WHITE)
                    btnSaveNote.background = originalBg
                }, 800)

            } else {
                Toast.makeText(context, "⚠️ 图片中未识别到有效文本", Toast.LENGTH_SHORT).show()
            }
        }

        frameLayout.post {
            val drawable = imageView.drawable ?: return@post

            val imageRect = android.graphics.RectF(0f, 0f, drawable.intrinsicWidth.toFloat(), drawable.intrinsicHeight.toFloat())
            imageView.imageMatrix.mapRect(imageRect)

            val trueImgWidth = imageRect.width()
            val trueImgHeight = imageRect.height()
            val offsetX = imageRect.left
            val offsetY = imageRect.top

            for (region in regions) {
                val left = offsetX + (region.xmin / 1000f) * trueImgWidth
                val top = offsetY + (region.ymin / 1000f) * trueImgHeight
                val right = offsetX + (region.xmax / 1000f) * trueImgWidth
                val bottom = offsetY + (region.ymax / 1000f) * trueImgHeight

                val tvOverlay = StrokeTextView(context).apply {
                    // 🌟 核心破壁：宽度和高度全都释放为 WRAP_CONTENT！让文字自己决定需要多大空间！
                    layoutParams = FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.WRAP_CONTENT,
                        FrameLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        leftMargin = left.toInt()
                        topMargin = top.toInt()
                    }

                    // 🌟 物理兜底：将“最小宽度和高度”设定为原文框的尺寸。
                    // 这样一来，文字短的时候它能完美遮住原文；文字长的时候，它就会像气球一样向右膨胀，绝不切断你的字！
                    minimumWidth = (right - left).toInt().coerceAtLeast(10)
                    minimumHeight = (bottom - top).toInt().coerceAtLeast(10)

                    gravity = android.view.Gravity.CENTER

                    setPadding(12, 6, 12, 6)
                    textSize = 13f
                    ellipsize = null // 严禁截断

                    setOnClickListener {
                        triggerVibration(40)
                        val currentText = if (isShowingTranslated) region.translated else region.original
                        val options = arrayOf("🔊 朗读", "📋 复制文本", "📋 复制另一语言")
                        androidx.appcompat.app.AlertDialog.Builder(context)
                            .setItems(options) { _, which ->
                                when (which) {
                                    0 -> {
                                        val voiceId = getSmartVoiceId(myVoiceName, myLangName)
                                        // 🌟 听从全局开关：因为这不算“对方的话”，所以不强制拦截，直接传 false
                                        edgeTts.speak(currentText, voiceId, false,
                                            onNodeSelected = {},
                                            onStart = { runOnUiThread { Toast.makeText(context, "🔊 播报中...", Toast.LENGTH_SHORT).show() } },
                                            onDone = {}
                                        )
                                    }
                                    1 -> copyToClipboard("文本", currentText)
                                    2 -> {
                                        val otherText = if (isShowingTranslated) region.original else region.translated
                                        copyToClipboard("对照文本", otherText)
                                    }
                                }
                            }.show()
                    }
                }
                overlayViews.add(tvOverlay)
                frameLayout.addView(tvOverlay)
            }
            updateOverlayState()
        }

        val scroll = ScrollView(context).apply {
            addView(frameLayout)
            clipChildren = false
            clipToPadding = false
        }
        layout.addView(scroll)

        val dialog = AlertDialog.Builder(this)
            .setCustomTitle(createCyberTitle("👁️ KANE AR视觉翻译"))
            .setView(layout)
            .setPositiveButton("完成") { _, _ ->
                edgeTts.stop()
                clearVisionCache(forceWipe = true)
            }
            .setCancelable(false)
            .create()

        if (!isFinishing && !isDestroyed) dialog.show()
    }

    private fun clearVisionCache(forceWipe: Boolean = false) {
        // 👇 【已修改】换成了单线程排队执行
        heavyTaskExecutor.execute {
            try {
                val cacheFolder = java.io.File(cacheDir, "kane_vision_cache")
                if (cacheFolder.exists()) {
                    if (forceWipe) {
                        cacheFolder.deleteRecursively()
                    } else {
                        val files = cacheFolder.listFiles() ?: return@execute // 👈 【已修改】同步更改为 return@execute
                        val now = System.currentTimeMillis()
                        for (file in files) {
                            if (now - file.lastModified() > 10 * 60 * 1000) file.delete()
                        }
                    }
                }
            } catch (e: Exception) {}
        } // 👈 【已修改】去掉了结尾的 .start()
    }
    // =================================================================================
    // 🌟 核心重构：彻底废弃谷歌网盘明文通道，全量走 GitHub CDN，并接入 AES 防白嫖与跳过更新机制
    // 🌟 核心重构：双通道航天级容灾机制 (GitHub CDN 主力 + Hugging Face 兜底)
    // =================================================================================
    private fun fetchTtsNodesSilently(onResult: ((Int, String) -> Unit)? = null) {
        val gitClient = okhttp3.OkHttpClient.Builder().connectTimeout(8, java.util.concurrent.TimeUnit.SECONDS).build()
        val urlGit = "https://cdn.jsdelivr.net/gh/nemo2014kk/Kane-firstAPP-Config@main/tts_nodes3.json"
        // 🌟 新增：Hugging Face 官方 Raw 数据兜底直链
        val urlHf = "https://huggingface.co/datasets/KANE-202666/face-to-face-Config/resolve/main/tts_nodes.json"

        fun parseAndSave(jsonStr: String, sourceName: String) {
            try {
                val jsonObj = org.json.JSONObject(jsonStr)

                // ----------------------------------------------------
                // 🔐 1. 检测更新与解锁机制 (增加跳过版本判断)
                // ----------------------------------------------------
                val remoteVersion = jsonObj.optString("version", "")
                val notice = jsonObj.optString("notice", "")
                val encryptedUrl = jsonObj.optString("download_url", "") // 👈 将字段名改成 download_url

                if (remoteVersion.isNotEmpty() && encryptedUrl.isNotEmpty()) {
                    val currentVersion = packageManager.getPackageInfo(packageName, 0).versionName ?: "1.0"
                    val skippedVersion = sharedPrefs.getString("skipped_update_version", "") // 读取被跳过的版本

                    // 🌟 核心逻辑：如果是新版本，且【不是用户明确要求跳过的那个版本】，才弹窗！
                    if (remoteVersion != skippedVersion && isNewerVersion(remoteVersion, currentVersion)) {
                        // 🌟 新增：将 sourceName 一并传给弹窗
                        runOnUiThread { showUpdateDialog(remoteVersion, notice, encryptedUrl, sourceName) }
                    }
                }

                // ----------------------------------------------------
                // 🌐 2. 正常解析并保存 TTS 节点
                // ----------------------------------------------------
                val nodesArray = jsonObj.optJSONArray("nodes")
                val urls = mutableSetOf<String>()
                if (nodesArray != null) {
                    for (i in 0 until nodesArray.length()) {
                        urls.add(nodesArray.getJSONObject(i).optString("url", ""))
                    }
                }
                if (urls.isNotEmpty()) {
                    sharedPrefs.edit().putStringSet("tts_cached_nodes", urls).apply()
                    onResult?.invoke(urls.size, sourceName)
                } else {
                    onResult?.invoke(0, sourceName)
                }
            } catch (e: Exception) {
                onResult?.invoke(0, sourceName)
            }
        }

        // 🌟 新增：Hugging Face 备用拉取通道
        fun fetchFromHuggingFace() {
            val reqHf = okhttp3.Request.Builder().url(urlHf).build()
            gitClient.newCall(reqHf).enqueue(object : okhttp3.Callback {
                override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
                    // 彻底没救了，双端全部失联
                    onResult?.invoke(0, "全部失联")
                }
                override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                    if (response.isSuccessful) {
                        response.body?.string()?.let { parseAndSave(it, "Hugging Face备用服务列表") }
                    } else {
                        onResult?.invoke(0, "全部失联")
                    }
                }
            })
        }

        // 🚀 优先发起主力请求 (GitHub CDN)
        val reqGit = okhttp3.Request.Builder().url(urlGit).build()
        gitClient.newCall(reqGit).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
                // ⚠️ 主力报错，静默转入 Hugging Face 兜底
                fetchFromHuggingFace()
            }
            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                if (response.isSuccessful) {
                    response.body?.string()?.let { parseAndSave(it, "GitHub CDN") }
                } else {
                    // ⚠️ 主力访问不通 (非 200 状态码)，静默转入 Hugging Face 兜底
                    fetchFromHuggingFace()
                }
            }
        })
    }

    // ==========================================
    // 🛡️ 航天级防白嫖模块：版本比对算法
    // ==========================================
    private fun isNewerVersion(remoteVersion: String, currentVersion: String): Boolean {
        try {
            val remoteParts = remoteVersion.split(".").map { it.toIntOrNull() ?: 0 }
            val currentParts = currentVersion.split(".").map { it.toIntOrNull() ?: 0 }
            val length = maxOf(remoteParts.size, currentParts.size)
            for (i in 0 until length) {
                val r = remoteParts.getOrElse(i) { 0 }
                val c = currentParts.getOrElse(i) { 0 }
                if (r > c) return true
                if (r < c) return false
            }
        } catch (e: Exception) {}
        return false
    }

    // ==========================================
    // 🔑 航天级防白嫖模块：本地静默 AES 解密器
    // ==========================================
    private fun decryptUpdateUrl(encryptedBase64: String, token: String): String? {
        try {
            val keyBytes = java.security.MessageDigest.getInstance("SHA-256").digest(token.toByteArray(Charsets.UTF_8))
            val decodedBytes = android.util.Base64.decode(encryptedBase64, android.util.Base64.DEFAULT)
            if (decodedBytes.size <= 16) return null

            val ivBytes = decodedBytes.copyOfRange(0, 16)
            val cipherBytes = decodedBytes.copyOfRange(16, decodedBytes.size)

            val cipher = javax.crypto.Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(
                javax.crypto.Cipher.DECRYPT_MODE,
                javax.crypto.spec.SecretKeySpec(keyBytes, "AES"),
                javax.crypto.spec.IvParameterSpec(ivBytes)
            )
            return String(cipher.doFinal(cipherBytes), Charsets.UTF_8)
        } catch (e: Exception) {
            return null
        }
    }

    // ==========================================
    // 🌌 赛博朋克风：专属更新拦截弹窗 (增加跳过与取消)
    // ==========================================
    // 🌟 修改参数：增加 sourceName 接收数据源名称
    private fun showUpdateDialog(newVersion: String, notice: String, encryptedUrl: String, sourceName: String) {
        if (isFinishing || isDestroyed) return

        val context = this
        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(60, 50, 60, 50)
            setBackgroundColor(Color.parseColor("#1A1A1B"))
        }

        val tvNotice = TextView(context).apply {
            text = notice.ifBlank { "发现新版本，建议立即升级体验最新功能！" }
            setTextColor(Color.parseColor("#CCCCCC"))
            textSize = 15f
            setLineSpacing(12f, 1.2f)
            setPadding(0, 0, 0, 15) // 🌟 稍微缩小原有的底部边距
        }
        layout.addView(tvNotice)

        // 🌟 新增 UI：专属的数据节点来源指示器
        val tvSource = TextView(context).apply {
            text = "🌍 升级信息来自：$sourceName"
            setTextColor(Color.parseColor("#888888")) // 赛博朋克暗灰色
            textSize = 12f
            setPadding(0, 0, 0, 30) // 保持与下方按钮的距离
        }
        layout.addView(tvSource)

        val btnUpdate = TextView(context).apply {
            text = "🚀 立即用暗号解锁升级"
            setTextColor(Color.parseColor("#1A1A1B"))
            textSize = 16f
            setTypeface(null, android.graphics.Typeface.BOLD)
            gravity = android.view.Gravity.CENTER
            setPadding(0, 35, 0, 35)
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.parseColor("#00E676"))
                cornerRadius = 20f
            }

            setOnClickListener {
                triggerVibration(40)
                val token = sharedPrefs.getString("tts_token", "") ?: ""
                if (token.isBlank()) {
                    Toast.makeText(context, "⚠️ 拦截：请先在设置面板中填写您的专属暗号！", Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }

                val url = decryptUpdateUrl(encryptedUrl, token)

                if (url != null && (url.startsWith("http://") || url.startsWith("https://"))) {
                    try {
                        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW)
                        intent.data = android.net.Uri.parse(url)
                        startActivity(intent)
                        Toast.makeText(context, "✅ 解密成功，正在前往专属更新通道", Toast.LENGTH_SHORT).show()
                    } catch (e: Exception) {
                        Toast.makeText(context, "⚠️ 系统无法拉起浏览器", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    val alert = androidx.appcompat.app.AlertDialog.Builder(context)
                        .setTitle("🛑 访问拒绝")
                        .setMessage("暗号校验失败，无法解锁下载通道！\n(请检查设置里填写的暗号是否准确)")
                        .setPositiveButton("我知道了", null)
                        .create()
                    alert.show()
                }
            }
        }
        layout.addView(btnUpdate)

        val dialog = androidx.appcompat.app.AlertDialog.Builder(context)
            .setCustomTitle(createCyberTitle("🎉 发现新版本 (v$newVersion)"))
            .setView(layout)
            .setCancelable(false) // 强制必须通过底部两个按钮进行选择
            // 🌟 新增选项 1：暂不更新 (取消，下次启动还会弹)
            .setNegativeButton("暂不更新", null)
            // 🌟 新增选项 2：跳过此版本 (永久记忆该版本，以后不再提示，直到出了更新的版本)
            .setNeutralButton("跳过此版本") { _, _ ->
                sharedPrefs.edit().putString("skipped_update_version", newVersion).apply()
                Toast.makeText(context, "✅ 已跳过 v$newVersion，下次启动将不再提示", Toast.LENGTH_SHORT).show()
            }
            .create()

        dialog.show()
    }
    private fun showEditDialog(msg: ChatMessage) {
        val context = this
        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(60, 40, 60, 40)
            setBackgroundColor(Color.parseColor("#1A1A1B"))
        }

        val hintText = TextView(context).apply {
            text = "✏️ 识别有误？可直接修改原文："
            setTextColor(Color.parseColor("#BBBBBB"))
            textSize = 14f
            setPadding(0, 0, 0, 20)
        }

        val etKeyword = EditText(context).apply {
            setText(msg.originalText)
            setTextColor(Color.WHITE)
            textSize = 16f
            setPadding(30, 30, 30, 30)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#0F0F0F"))
                setStroke(2, Color.parseColor("#00BCFF"))
                cornerRadius = 15f
            }
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 4
            maxLines = 18
        }

        layout.addView(hintText)
        layout.addView(etKeyword)

        val editDialog = AlertDialog.Builder(this)
            .setCustomTitle(createCyberTitle("📝 编辑原文"))
            .setView(layout)
            .setPositiveButton("重新翻译") { _, _ ->
                val newText = etKeyword.text.toString().trim()
                if (newText.isNotEmpty() && newText != msg.originalText) {
                    retranslateMessage(msg, newText)
                } else if (newText == msg.originalText) {
                    Toast.makeText(context, "未做任何修改", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("取消", null)
            .create()

        if (!isFinishing && !isDestroyed) editDialog.show()
    }

    private fun retranslateMessage(oldMsg: ChatMessage, newOriginalText: String) {
        // 1. 根据溯源标签，精准判断这句话当初是谁说的，防止由于当前选项卡被切换导致语种错乱
        val isTop = oldMsg.isTopSpeaker
        val sourceLangName = if (isTop) ptLangName else myLangName
        val targetLangName = if (isTop) myLangName else ptLangName

        val llmSourceEn = AppConstants.LANG_MAP_EN[sourceLangName] ?: "English"
        val llmTargetEn = AppConstants.LANG_MAP_EN[targetLangName] ?: "English"

        setIslandState("⏳ 正在重译修正...", "#FFFF00")

        aiEngine.translateText(newOriginalText, llmSourceEn, llmTargetEn,
            onFallback = { isModelDead ->
                runOnUiThread {
                    setIslandState("⚠️ 切换备用引擎重译...", "#FFA500")
                    if (isModelDead) {
                        Toast.makeText(this@MainActivity, "🚨 当前模型已失效下架！请去设置拉取最新模型库。", Toast.LENGTH_LONG).show()
                    }
                }
            }
        ) { llmSuccess, translated, engineName ->
            if (isDestroyed || isFinishing) return@translateText

            if (llmSuccess && translated.isNotBlank()) {
                // 2. 🌟 双轨联动：拿着统一的 ID，同时强制刷新上下屏幕的气泡数据！
                topAdapter.updateMessageById(oldMsg.id, translated, newOriginalText)
                bottomAdapter.updateMessageById(oldMsg.id, translated, newOriginalText)

                setIslandState("✅ 修正完毕 ($engineName)", "#00FF00")
                resetIslandDelayed()

                // 3. 自动触发语音修正播报体验
                if (isTtsEnabled && !oldMsg.voiceId.startsWith("hy-AM")) {
                    val forceHeadset = isTop && isHeadsetPluggedIn() // 🌟 强制私密播报
                    edgeTts.speak(translated, oldMsg.voiceId, forceHeadset,
                        onNodeSelected = { nodeName -> runOnUiThread { setIslandState("🎵 准备发音 [$nodeName]", "#00BCFF", animatePop = false) } },
                        onStart = {
                            setIslandState("🔊   修正播报中   ⏹️ ", "#00FF00", isTop = false)
                            tvDynamicIsland.isClickable = true
                            tvDynamicIsland.setOnClickListener {
                                triggerVibration(50)
                                edgeTts.stop()
                                resetIsland()
                            }
                        },
                        onDone = { resetIsland() }
                    )
                }
            } else {
                setIslandState("❌ 修改重译失败", "#FF4444")
                resetIslandDelayed(3000L)
            }
        }
    }
    // ==========================================
    // 🧠 核心外脑数据与子弹窗处理器
    // ==========================================
    private fun getNotebookData(): org.json.JSONArray {
        val file = java.io.File(filesDir, "kane_notebook.json")
        if (file.exists()) {
            return try {
                org.json.JSONArray(file.readText(Charsets.UTF_8))
            } catch (e: Exception) { org.json.JSONArray() }
        } else {
            // 🌟 无缝迁移机制：如果是老版本，把旧的 SP 数据搬运到无限容量的文件里
            val oldData = sharedPrefs.getString("kane_notebook_data", "[]") ?: "[]"
            if (oldData != "[]") {
                try {
                    file.writeText(oldData, Charsets.UTF_8)
                    sharedPrefs.edit().remove("kane_notebook_data").apply() // 卸下炸弹，释放内存
                } catch (e: Exception) {}
            }
            return try { org.json.JSONArray(oldData) } catch (e: Exception) { org.json.JSONArray() }
        }
    }
    private fun exportNotebook() {
        try {
            val data = getNotebookData().toString()
            // 复用视觉引擎已经开辟好的缓存沙盒，系统免权放行
            val cacheFolder = java.io.File(cacheDir, "kane_vision_cache")
            if (!cacheFolder.exists()) cacheFolder.mkdirs()

            val exportFile = java.io.File(cacheFolder, "Kane_Notebook_Backup.json")
            exportFile.writeText(data, Charsets.UTF_8)

            val uri = androidx.core.content.FileProvider.getUriForFile(this, "${packageName}.fileprovider", exportFile)
            val shareIntent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                type = "application/json" // 声明这是 JSON 文件
                putExtra(android.content.Intent.EXTRA_STREAM, uri)
                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(android.content.Intent.createChooser(shareIntent, "将快捷笔记本备份至..."))
        } catch (e: Exception) {
            Toast.makeText(this, "⚠️ 导出备份失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun processImportedFile(uri: android.net.Uri) {
        setIslandState("⏳ 正在读取备份...", "#00BCFF")

        // 🛡️ 航天级防御 3：强制在子线程执行 I/O 读取，彻底告别主线程 ANR 崩溃
        heavyTaskExecutor.execute { // 👈 【已修改】换成了单线程排队执行
            try {
                // 🛡️ 航天级防御 1.1：前置体积侦测。超大文件直接阻断，不给它吃内存的机会
                var fileSize = 0L
                contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    val sizeIndex = cursor.getColumnIndex(android.provider.OpenableColumns.SIZE)
                    if (cursor.moveToFirst() && sizeIndex != -1) {
                        fileSize = cursor.getLong(sizeIndex)
                    }
                }

                // 限制最大体积为 2MB (正常的 JSON 文本几百KB就顶天了)
                if (fileSize > 2 * 1024 * 1024) {
                    runOnUiThread {
                        Toast.makeText(this@MainActivity, "⚠️ 文件过大，拒绝读取！请选择正确的 KANE 备份文件", Toast.LENGTH_LONG).show()
                        resetIslandDelayed()
                    }
                    return@execute // 👈 【已修改】同步更改为 return@execute
                }

                // 安全读取流
                val inputStream = contentResolver.openInputStream(uri)
                val jsonStr = inputStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""

                // 验证 JSON 数组
                val importedArray = org.json.JSONArray(jsonStr)

                if (importedArray.length() == 0) {
                    runOnUiThread {
                        Toast.makeText(this@MainActivity, "⚠️ 导入中止：该备份文件内容为空", Toast.LENGTH_SHORT).show()
                        resetIslandDelayed()
                    }
                    return@execute // 👈 【已修改】同步更改为 return@execute
                }

                // 读取成功后，切回主线程展示策略选择弹窗
                runOnUiThread {
                    resetIslandDelayed()
                    showImportStrategyDialog(importedArray)
                }

            } catch (e: Throwable) {
                // 🛡️ 航天级防御 1.2：将 Exception 改为 Throwable，连 OOM 这种 Error 也能拦截死，保全 APP 命脉
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "⚠️ 文件格式损坏或非标准 JSON 备份，解析失败", Toast.LENGTH_LONG).show()
                    resetIslandDelayed()
                }
            }
        } // 👈 【已修改】因为用了 execute，所以去掉了结尾的 .start()
    }

    private fun showImportStrategyDialog(importedArray: org.json.JSONArray) {
        val options = arrayOf(
            "🧠 智能合并 (去重并基于时间保留最新)",
            "💥 覆盖本地 (清空现有笔记，仅保留备份)",
            "➕ 全部追加 (保留本地，直接将备份加入末尾)"
        )
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("✅ 成功读取 ${importedArray.length()} 条笔记，请选择：")
            .setItems(options) { _, which ->
                executeImportStrategy(importedArray, which)
            }
            .setNegativeButton("取消", null)
            .show()
    }
    // ==========================================
    // 🌟 赛博跳变引擎：UI 悬停与自动回弹机制
    // ==========================================
    private var volumeUiReboundRunnable: Runnable? = null

    private fun updateVolumeUI(isHeadset: Boolean, percent: Int, islandText: String, islandColor: String, autoRebound: Boolean) {
        volumeUiReboundRunnable?.let { tvDynamicIsland.removeCallbacks(it) }

        val colorHex = if (isHeadset) "#00E676" else "#00BCFF"
        val parsedColor = android.graphics.Color.parseColor(colorHex)

        btnTtsRoute.text = if (isHeadset) "🎧\n耳\n机" else "🔈\n外\n放"
        btnTtsRoute.setTextColor(parsedColor)
        (btnTtsRoute.background as GradientDrawable).setStroke(2, parsedColor)

        seekbarTtsVolume.progressTintList = android.content.res.ColorStateList.valueOf(parsedColor)
        seekbarTtsVolume.thumbTintList = android.content.res.ColorStateList.valueOf(parsedColor)

        seekbarTtsVolume.progress = percent
        setIslandState(islandText, colorHex, animatePop = false, isTop = false)

        if (autoRebound) {
            volumeUiReboundRunnable = Runnable { restoreVolumeUI() }
            tvDynamicIsland.postDelayed(volumeUiReboundRunnable, 1500L)
        } else {
            resetIslandDelayed(1500L)
        }
    }

    private fun restoreVolumeUI() {
        val am = getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
        if (edgeTts.isSpeakerRoute) {
            val color = android.graphics.Color.parseColor("#00BCFF")
            btnTtsRoute.text = "🔈\n外\n放"
            btnTtsRoute.setTextColor(color)
            (btnTtsRoute.background as GradientDrawable).setStroke(2, color)
            seekbarTtsVolume.progressTintList = android.content.res.ColorStateList.valueOf(color)
            seekbarTtsVolume.thumbTintList = android.content.res.ColorStateList.valueOf(color)

            seekbarTtsVolume.progress = edgeTts.speakerVolume
            setIslandState("🔈 恢复外放指示：${edgeTts.speakerVolume}%", "#00BCFF", animatePop = true, isTop = false)
            resetIslandDelayed(1000L)
        } else {
            val color = android.graphics.Color.parseColor("#00E676")
            btnTtsRoute.text = "🎧\n耳\n机"
            btnTtsRoute.setTextColor(color)
            (btnTtsRoute.background as GradientDrawable).setStroke(2, color)
            seekbarTtsVolume.progressTintList = android.content.res.ColorStateList.valueOf(color)
            seekbarTtsVolume.thumbTintList = android.content.res.ColorStateList.valueOf(color)

            val maxVol = am.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)
            val currentVol = am.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)
            val percent = ((currentVol.toFloat() / maxVol) * 100).toInt()
            seekbarTtsVolume.progress = percent

            setIslandState("🎧 恢复耳机指示：$percent%", "#00E676", animatePop = true, isTop = false)
            resetIslandDelayed(1000L)
        }
    }

    // ==========================================
    // 🧠 终极无死角音量按键 ( +/- ) 劫持状态机
    // ==========================================
    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent?): Boolean {
        if (keyCode == android.view.KeyEvent.KEYCODE_VOLUME_UP || keyCode == android.view.KeyEvent.KEYCODE_VOLUME_DOWN) {
            val am = getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
            val direction = if (keyCode == android.view.KeyEvent.KEYCODE_VOLUME_UP) 1 else -1

            // 🥇 第一优先级：TTS 正在发声中 (绝对精准打击：谁在出声调谁)
            if (edgeTts.isSpeaking) {
                // 🌟 核心判断：利用引擎内部刚计算出的“真实发声通道”
                val actualSpeakerRoute = edgeTts.isSpeakerRoute && !edgeTts.isPrivacyModeActive

                if (actualSpeakerRoute) {
                    val maxVol = am.getStreamMaxVolume(android.media.AudioManager.STREAM_ALARM)
                    var currentVol = am.getStreamVolume(android.media.AudioManager.STREAM_ALARM)
                    currentVol = (currentVol + direction).coerceIn(0, maxVol)
                    am.setStreamVolume(android.media.AudioManager.STREAM_ALARM, currentVol, 0)
                    val percent = ((currentVol.toFloat() / maxVol) * 100).toInt()

                    edgeTts.speakerVolume = percent // 记忆同步
                    sharedPrefs.edit().putInt("tts_speaker_volume", percent).apply()
                    updateVolumeUI(isHeadset = false, percent = percent, islandText = "🔊 正在播报(外放)：$percent%", islandColor = "#00BCFF", autoRebound = false)
                } else {
                    val maxVol = am.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)
                    var currentVol = am.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)
                    currentVol = (currentVol + direction).coerceIn(0, maxVol)
                    am.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, currentVol, 0)
                    val percent = ((currentVol.toFloat() / maxVol) * 100).toInt()

                    // 🌟 智能跳变与回弹：如果 UI 拨在外放，但被隐私模式强制拉到了耳机，调完必须回弹外放UI！
                    val needsRebound = edgeTts.isSpeakerRoute
                    updateVolumeUI(isHeadset = true, percent = percent, islandText = "🎧 正在播报(耳机)：$percent%", islandColor = "#00E676", autoRebound = needsRebound)
                }
                return true
            }

            // 🥈 第二优先级：同传模式运行中 (同传必定走耳机 MUSIC 流)
            if (isLiveTranslateEnabled) {
                val maxVol = am.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)
                var currentVol = am.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)
                currentVol = (currentVol + direction).coerceIn(0, maxVol)
                am.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, currentVol, 0)
                val percent = ((currentVol.toFloat() / maxVol) * 100).toInt()

                val needsRebound = edgeTts.isSpeakerRoute
                updateVolumeUI(isHeadset = true, percent = percent, islandText = "🎧 同传音量：$percent%", islandColor = "#00E676", autoRebound = needsRebound)
                return true
            }

            // 🥉 第三优先级：闲置状态 (严格跟随 UI 路由开关，废弃跳变魔法)
            if (edgeTts.isSpeakerRoute) {
                val maxVol = am.getStreamMaxVolume(android.media.AudioManager.STREAM_ALARM)
                val stepSize = 100f / maxVol
                val currentGear = Math.round(edgeTts.speakerVolume / stepSize)
                val targetGear = (currentGear + direction).coerceIn(0, maxVol)
                val percent = (targetGear * stepSize).toInt().coerceIn(0, 100)

                edgeTts.speakerVolume = percent // 记忆同步
                sharedPrefs.edit().putInt("tts_speaker_volume", percent).apply()
                updateVolumeUI(isHeadset = false, percent = percent, islandText = "🔈 外放预设：$percent%", islandColor = "#00BCFF", autoRebound = false)
                return true
            } else {
                val maxVol = am.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)
                var currentVol = am.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)
                currentVol = (currentVol + direction).coerceIn(0, maxVol)
                am.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, currentVol, 0)
                val percent = ((currentVol.toFloat() / maxVol) * 100).toInt()

                updateVolumeUI(isHeadset = true, percent = percent, islandText = "🎧 TTS耳机音量：$percent%", islandColor = "#00E676", autoRebound = false)
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun executeImportStrategy(importedArray: org.json.JSONArray, strategy: Int) {
        // 🌟 航天级防御：繁重的数据清洗、时间戳解析和排序，必须扔进单线程队列！绝不阻塞主 UI！
        // 👇 【已修改】换成了单线程排队执行
        heavyTaskExecutor.execute {
            try {
                val currentArray = getNotebookData()
                val newArray = org.json.JSONArray()
                val formatter = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())

                fun parseTimeSafe(timeStr: String): Long {
                    if (timeStr == "刚刚") return System.currentTimeMillis()
                    return try { formatter.parse(timeStr)?.time ?: 0L } catch (e: Exception) { 0L }
                }

                when (strategy) {
                    0 -> { // 🧠 智能合并
                        val mergedMap = mutableMapOf<String, org.json.JSONObject>()
                        // 读取本地
                        for (i in 0 until currentArray.length()) {
                            // 🛡️ 航天级防御 2.2：使用 optJSONObject 替代 getJSONObject，如果不是对象直接跳过，绝不报错
                            val item = currentArray.optJSONObject(i) ?: continue
                            mergedMap[item.optString("id")] = item
                        }
                        // 比对导入
                        for (i in 0 until importedArray.length()) {
                            val importedItem = importedArray.optJSONObject(i) ?: continue
                            val id = importedItem.optString("id")
                            if (id.isEmpty()) continue // 过滤掉没有 ID 的非法数据

                            if (mergedMap.containsKey(id)) {
                                val localTime = parseTimeSafe(mergedMap[id]!!.optString("timestamp", ""))
                                val importTime = parseTimeSafe(importedItem.optString("timestamp", ""))
                                if (importTime > localTime) mergedMap[id] = importedItem
                            } else {
                                mergedMap[id] = importedItem
                            }
                        }
                        val sortedList = mergedMap.values.toList().sortedByDescending { parseTimeSafe(it.optString("timestamp", "")) }
                        for (item in sortedList) newArray.put(item)
                    }
                    1 -> { // 💥 强制覆盖
                        for (i in 0 until importedArray.length()) {
                            val item = importedArray.optJSONObject(i) ?: continue
                            newArray.put(item)
                        }
                    }
                    2 -> { // ➕ 全部追加
                        for (i in 0 until currentArray.length()) {
                            val item = currentArray.optJSONObject(i) ?: continue
                            newArray.put(item)
                        }
                        for (i in 0 until importedArray.length()) {
                            val item = importedArray.optJSONObject(i) ?: continue
                            item.put("id", java.util.UUID.randomUUID().toString())
                            newArray.put(item)
                        }
                    }
                }

                // 数据落盘 (apply() 虽然是异步的，但序列化 toString() 很耗时，正好在子线程执行)
                heavyTaskExecutor.execute { try { java.io.File(filesDir, "kane_notebook.json").writeText(newArray.toString(), Charsets.UTF_8) } catch (e: Exception) {} }

                // 运算完成，切回主线程播放动画和刷新 UI
                runOnUiThread {
                    if (isDestroyed || isFinishing) return@runOnUiThread
                    triggerVibration(50)
                    Toast.makeText(this@MainActivity, "🎉 笔记导入成功！", Toast.LENGTH_SHORT).show()
                    activeNotebookCallback?.let { showNotebookSubDialog(it) }
                }

            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "⚠️ 数据清洗失败，备份文件中可能包含不合规的幽灵数据", Toast.LENGTH_LONG).show()
                }
            }
        } // 👈 【已修改】因为用了 execute，去掉了结尾的 .start()
    }

    private fun showNotebookSubDialog(onItemClicked: (String) -> Unit) {
        activeNotebookCallback = onItemClicked
        var dialog: androidx.appcompat.app.AlertDialog? = null
        var refreshList: () -> Unit = {}

        val context = this
        val density = context.resources.displayMetrics.density

        // 🌟 优化：显式指定 root 布局宽度为 MATCH_PARENT
        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((20 * density).toInt(), (15 * density).toInt(), (20 * density).toInt(), (15 * density).toInt())
            setBackgroundColor(Color.parseColor("#1A1A1B"))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val titleRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, (15 * density).toInt())
        }

        val tvTitle = TextView(context).apply {
            text = "📑 快捷笔记本"
            setTextColor(Color.parseColor("#00BCFF"))
            textSize = 16f
            setTypeface(null, android.graphics.Typeface.BOLD)
        }

        val spacer = android.widget.Space(context).apply { layoutParams = LinearLayout.LayoutParams(0, 1, 1f) }

        val btnMore = TextView(context).apply {
            text = "⋮"
            setTextColor(Color.parseColor("#00BCFF"))
            textSize = 22f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding((15 * density).toInt(), (5 * density).toInt(), (15 * density).toInt(), (5 * density).toInt())

            val outValue = android.util.TypedValue()
            context.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, outValue, true)
            setBackgroundResource(outValue.resourceId)

            setOnClickListener { anchor ->
                triggerVibration(30)
                val popup = android.widget.PopupMenu(context, anchor)
                popup.menu.add(0, 1, 0, "📥 导入笔记本")
                popup.menu.add(0, 2, 1, "📤 导出笔记本")
                popup.menu.add(0, 3, 2, "🧹 清空笔记本")
                popup.menu.add(0, 4, 3, "➕ 建立新笔记")

                popup.setOnMenuItemClickListener { menuItem ->
                    when (menuItem.itemId) {
                        1 -> { dialog?.dismiss(); try { importNotebookLauncher.launch("*/*") } catch (e: Exception) { Toast.makeText(context, "⚠️ 无法拉起文件管理器", Toast.LENGTH_LONG).show() }; true }
                        2 -> { exportNotebook(); true }
                        3 -> { showClearNotebookConfirmDialog { refreshList() }; true }
                        4 -> { showEditNotebookDialog("", "", "") { refreshList() }; true }
                        else -> false
                    }
                }
                popup.show()
            }
        }

        titleRow.addView(tvTitle)
        titleRow.addView(spacer)
        titleRow.addView(btnMore)
        layout.addView(titleRow)

        val screenHeight = android.content.res.Resources.getSystem().displayMetrics.heightPixels
        val maxRvHeight = (screenHeight * 0.55).toInt()

        val rvList = androidx.recyclerview.widget.RecyclerView(context).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, maxRvHeight)
            layoutManager = androidx.recyclerview.widget.LinearLayoutManager(context)
        }
        layout.addView(rvList)

        val tvEmpty = TextView(context).apply {
            text = "📭 笔记本空空如也\n点击右上角 ⋮ 建立或导入笔记吧"
            setTextColor(Color.parseColor("#555555"))
            textSize = 14f
            gravity = android.view.Gravity.CENTER
            setLineSpacing(10f, 1.2f)
            setPadding(0, 100, 0, 100)
            visibility = View.GONE
        }
        layout.addView(tvEmpty)

        val notebookItems = mutableListOf<org.json.JSONObject>()

        fun loadDataFromPrefs() {
            notebookItems.clear()
            val array = getNotebookData()
            for (i in 0 until array.length()) {
                notebookItems.add(array.getJSONObject(i))
            }
            if (notebookItems.isEmpty()) {
                rvList.visibility = View.GONE
                tvEmpty.visibility = View.VISIBLE
            } else {
                rvList.visibility = View.VISIBLE
                tvEmpty.visibility = View.GONE
            }
        }

        fun saveDataToPrefs() {
            val newArray = org.json.JSONArray()
            for (item in notebookItems) {
                newArray.put(item)
            }
            heavyTaskExecutor.execute { try { java.io.File(filesDir, "kane_notebook.json").writeText(newArray.toString(), Charsets.UTF_8) } catch (e: Exception) {} }
        }

        class NotebookAdapter : androidx.recyclerview.widget.RecyclerView.Adapter<NotebookAdapter.ViewHolder>() {
            inner class ViewHolder(val view: LinearLayout,
                                   val tvItemTitle: TextView,
                                   val tvItemTime: TextView,
                                   val btnItemMore: TextView,
                                   val tvItemContent: TextView,
                                   val btnEdit: TextView,
                                   val btnSend: TextView) : androidx.recyclerview.widget.RecyclerView.ViewHolder(view)

            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
                val row = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding((14 * density).toInt(), (10 * density).toInt(), (14 * density).toInt(), (10 * density).toInt())
                    background = GradientDrawable().apply {
                        setColor(Color.parseColor("#252526"))
                        cornerRadius = 15f
                    }
                    layoutParams = androidx.recyclerview.widget.RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                        bottomMargin = (10 * density).toInt()
                    }
                }

                val itemTitleRow = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                        bottomMargin = (4 * density).toInt()
                    }
                }

                val tvItemTitle = TextView(context).apply {
                    setTextColor(Color.parseColor("#00E676"))
                    textSize = 15f
                    setTypeface(null, android.graphics.Typeface.BOLD)
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                        marginEnd = (10 * density).toInt()
                    }
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                }

                val tvItemTime = TextView(context).apply {
                    setTextColor(Color.parseColor("#666666"))
                    textSize = 12f
                    setPadding(0, 0, (10 * density).toInt(), 0)
                }

                val btnItemMore = TextView(context).apply {
                    text = "⋮"
                    setTextColor(Color.parseColor("#888888"))
                    textSize = 20f
                    setTypeface(null, android.graphics.Typeface.BOLD)
                    setPadding((8 * density).toInt(), (2 * density).toInt(), (8 * density).toInt(), (2 * density).toInt())
                }

                itemTitleRow.addView(tvItemTitle)
                itemTitleRow.addView(tvItemTime)
                itemTitleRow.addView(btnItemMore)

                val tvItemContent = TextView(context).apply {
                    setTextColor(Color.parseColor("#AAAAAA"))
                    textSize = 13f
                    maxLines = 2
                    ellipsize = android.text.TextUtils.TruncateAt.END
                }

                val actionRow = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.END or android.view.Gravity.CENTER_VERTICAL
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                        topMargin = (15 * density).toInt()
                    }
                }

                val btnEdit = TextView(context).apply {
                    text = "✍️ 查看 编辑"
                    textSize = 12f
                    setTextColor(Color.parseColor("#00E676"))
                    setPadding((12 * density).toInt(), (6 * density).toInt(), (12 * density).toInt(), (6 * density).toInt())
                    background = GradientDrawable().apply {
                        setColor(Color.parseColor("#1A1A1B"))
                        setStroke(2, Color.parseColor("#00E676"))
                        cornerRadius = 30f
                    }
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                        marginEnd = (10 * density).toInt()
                    }
                }

                val btnSend = TextView(context).apply {
                    text = "🚀 翻译"
                    textSize = 12f
                    setTextColor(Color.parseColor("#00BCFF"))
                    setPadding((12 * density).toInt(), (6 * density).toInt(), (12 * density).toInt(), (6 * density).toInt())
                    background = GradientDrawable().apply {
                        setColor(Color.parseColor("#1A1A1B"))
                        setStroke(2, Color.parseColor("#00BCFF"))
                        cornerRadius = 30f
                    }
                }

                actionRow.addView(btnEdit)
                actionRow.addView(btnSend)

                row.addView(itemTitleRow)
                row.addView(tvItemContent)
                row.addView(actionRow)

                return ViewHolder(row, tvItemTitle, tvItemTime, btnItemMore, tvItemContent, btnEdit, btnSend)
            }

            override fun onBindViewHolder(holder: ViewHolder, position: Int) {
                val item = notebookItems[position]
                val id = item.optString("id")
                val title = item.optString("title")
                val content = item.optString("content")

                holder.tvItemTitle.text = title
                holder.tvItemTime.text = item.optString("timestamp", "刚刚")
                holder.tvItemContent.text = content

                holder.view.setOnClickListener {
                    triggerVibration(30)
                    dialog?.dismiss()
                    if (currentTextInputDialog?.isShowing == true) {
                        onItemClicked(content)
                    } else {
                        showTextInputDialog(content)
                    }
                }

                holder.btnItemMore.setOnClickListener {
                    triggerVibration(30)
                    val options = arrayOf("📋 复制内容", "✏️ 编辑笔记", "❌ 删除条目")
                    androidx.appcompat.app.AlertDialog.Builder(context)
                        .setItems(options) { _, which ->
                            when (which) {
                                0 -> copyToClipboard("笔记", content)
                                1 -> showEditNotebookDialog(id, title, content) { refreshList() }
                                2 -> {
                                    triggerVibration(30)
                                    deleteNotebookEntry(id) { refreshList() } // 🌟 将刷新放进回调里
                                }
                            }
                        }.show()
                }

                holder.btnEdit.setOnClickListener {
                    triggerVibration(30)
                    dialog?.dismiss()
                    showTextInputDialog(content)
                }

                holder.btnSend.setOnClickListener {
                    triggerVibration(40)
                    dialog?.dismiss()
                    currentTextInputDialog?.dismiss()
                    // 🌟 核心修复：不再写死 isTop = false，而是根据呼出时的面板焦点智能判断！
                    // 如果是从上方蓝框点开的，它就是 true (发给中文)
                    // 如果是从下方绿框点开，或者是长按呼出的，它就是 false (发给外语)
                    processTextPipeline(content, isTop = lastActiveInputIsTop)
                }
            }

            override fun getItemCount() = notebookItems.size
        }

        val adapter = NotebookAdapter()
        rvList.adapter = adapter
        loadDataFromPrefs()

        val itemTouchHelper = androidx.recyclerview.widget.ItemTouchHelper(object : androidx.recyclerview.widget.ItemTouchHelper.Callback() {
            private var dragStartPosition = -1

            override fun getMovementFlags(recyclerView: androidx.recyclerview.widget.RecyclerView, viewHolder: androidx.recyclerview.widget.RecyclerView.ViewHolder): Int {
                val dragFlags = androidx.recyclerview.widget.ItemTouchHelper.UP or androidx.recyclerview.widget.ItemTouchHelper.DOWN
                return makeMovementFlags(dragFlags, 0)
            }

            override fun onMove(recyclerView: androidx.recyclerview.widget.RecyclerView, viewHolder: androidx.recyclerview.widget.RecyclerView.ViewHolder, target: androidx.recyclerview.widget.RecyclerView.ViewHolder): Boolean {
                val fromPos = viewHolder.adapterPosition
                val toPos = target.adapterPosition
                if (fromPos == -1 || toPos == -1) return false

                java.util.Collections.swap(notebookItems, fromPos, toPos)
                adapter.notifyItemMoved(fromPos, toPos)
                return true
            }

            override fun onSwiped(viewHolder: androidx.recyclerview.widget.RecyclerView.ViewHolder, direction: Int) {}

            override fun onSelectedChanged(viewHolder: androidx.recyclerview.widget.RecyclerView.ViewHolder?, actionState: Int) {
                super.onSelectedChanged(viewHolder, actionState)
                if (actionState == androidx.recyclerview.widget.ItemTouchHelper.ACTION_STATE_DRAG) {
                    dragStartPosition = viewHolder?.adapterPosition ?: -1
                    viewHolder?.itemView?.alpha = 0.8f
                    viewHolder?.itemView?.scaleX = 1.02f
                    viewHolder?.itemView?.scaleY = 1.02f
                    triggerVibration(50)
                }
            }

            override fun clearView(recyclerView: androidx.recyclerview.widget.RecyclerView, viewHolder: androidx.recyclerview.widget.RecyclerView.ViewHolder) {
                super.clearView(recyclerView, viewHolder)
                viewHolder.itemView.alpha = 1.0f
                viewHolder.itemView.scaleX = 1.0f
                viewHolder.itemView.scaleY = 1.0f

                val dropPosition = viewHolder.adapterPosition

                if (dropPosition != androidx.recyclerview.widget.RecyclerView.NO_POSITION) {
                    if (dragStartPosition != dropPosition && dragStartPosition != -1) {
                        saveDataToPrefs()
                        triggerVibration(40)
                    }
                }
                dragStartPosition = -1
            }

            override fun isLongPressDragEnabled(): Boolean {
                return true
            }
        })

        itemTouchHelper.attachToRecyclerView(rvList)

        refreshList = {
            loadDataFromPrefs()
            adapter.notifyDataSetChanged()
        }

        dialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setView(layout)
            .create()

        dialog?.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))

        // 🌟 核心突破：在 show() 调用之后，强制锁死弹窗窗口的物理宽度
        if (!isFinishing && !isDestroyed) {
            dialog?.show()

            // 强行将弹窗的物理宽度锁定为屏幕宽度的 90%
            // 如此一来，RecyclerView 以及里面所有的卡片卡槽都会被强制“横向拉满”，长度绝对统一！
            val metrics = resources.displayMetrics
            val dialogWidth = (metrics.widthPixels * 0.9).toInt()
            dialog?.window?.setLayout(dialogWidth, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
    }

    private fun deleteNotebookEntry(targetId: String, onDeleted: () -> Unit) {
        val array = getNotebookData()
        val newArray = org.json.JSONArray()
        for (i in 0 until array.length()) {
            val item = array.getJSONObject(i)
            if (item.optString("id") != targetId) newArray.put(item)
        }
        heavyTaskExecutor.execute {
            try { java.io.File(filesDir, "kane_notebook.json").writeText(newArray.toString(), Charsets.UTF_8) } catch (e: Exception) {}
            runOnUiThread { onDeleted() } // 🌟 等删完写进硬盘再回调
        }
    }

    private fun showEditNotebookDialog(targetId: String, oldTitle: String, oldContent: String, onUpdated: () -> Unit) {
        val context = this
        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(60, 40, 60, 40)
            setBackgroundColor(Color.parseColor("#1A1A1B"))
        }

        // --- 📌 标题输入区 ---
        val tvTitleLabel = TextView(context).apply {
            text = "📌 笔记名称："
            setTextColor(Color.parseColor("#BBBBBB"))
            textSize = 13f
            setPadding(0, 0, 0, 15)
        }
        layout.addView(tvTitleLabel)

        val etNewTitle = EditText(context).apply {
            setText(oldTitle)
            setTextColor(Color.WHITE)
            textSize = 15f
            setPadding(30, 30, 30, 30)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#0F0F0F"))
                setStroke(2, Color.parseColor("#00BCFF")) // 蓝边框
                cornerRadius = 15f
            }
            setSelection(text.length)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = 40
            }
        }
        layout.addView(etNewTitle)

        // --- 📝 内容输入区 ---
        val tvContentLabel = TextView(context).apply {
            text = "📝 笔记内容："
            setTextColor(Color.parseColor("#BBBBBB"))
            textSize = 13f
            setPadding(0, 0, 0, 15)
        }
        layout.addView(tvContentLabel)

        val etNewContent = EditText(context).apply {
            setText(oldContent)
            setTextColor(Color.WHITE)
            textSize = 15f
            setPadding(30, 30, 30, 30)
            gravity = android.view.Gravity.TOP or android.view.Gravity.START
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 4
            maxLines = 10
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#0F0F0F"))
                setStroke(2, Color.parseColor("#00E676")) // 绿边框，区分视觉层次
                cornerRadius = 15f
            }
        }
        layout.addView(etNewContent)

        // 根据 targetId 决定大标题文案
        val dialogTitle = if (targetId.isEmpty()) "➕ 建立新笔记" else "✏️ 编辑与整理笔记"

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setCustomTitle(createCyberTitle(dialogTitle))
            .setView(layout)
            .setPositiveButton("保存更改") { _, _ ->
                val newTitle = etNewTitle.text.toString().trim()
                val newContent = etNewContent.text.toString().trim()

                if (newTitle.isNotEmpty() && newContent.isNotEmpty()) {
                    val array = getNotebookData()

                    if (targetId.isEmpty()) {
                        // 🌟 新建模式：自动获取当前最新时间，并置顶插入原数据中
                        val timeFormatter = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
                        val timeStr = timeFormatter.format(java.util.Date())
                        val newObj = org.json.JSONObject().apply {
                            put("id", java.util.UUID.randomUUID().toString())
                            put("title", newTitle)
                            put("content", newContent)
                            put("timestamp", timeStr)
                        }
                        val newArray = org.json.JSONArray()
                        newArray.put(newObj) // 新创建的文件置顶
                        for (i in 0 until array.length()) {
                            newArray.put(array.getJSONObject(i))
                        }

                        // 🌟 核心修复：等子线程文件真正写完落盘后，再切回主线程刷新 UI 列表
                        heavyTaskExecutor.execute {
                            try { java.io.File(filesDir, "kane_notebook.json").writeText(newArray.toString(), Charsets.UTF_8) } catch (e: Exception) {}
                            runOnUiThread { onUpdated() }
                        }
                    } else {
                        // ✏️ 编辑模式：正常比对并保存
                        for (i in 0 until array.length()) {
                            val item = array.getJSONObject(i)
                            if (item.optString("id") == targetId) {
                                item.put("title", newTitle)
                                item.put("content", newContent)
                                break
                            }
                        }

                        // 🌟 核心修复：等子线程文件真正写完落盘后，再切回主线程刷新 UI 列表
                        heavyTaskExecutor.execute {
                            try { java.io.File(filesDir, "kane_notebook.json").writeText(array.toString(), Charsets.UTF_8) } catch (e: Exception) {}
                            runOnUiThread { onUpdated() }
                        }
                    }
                } else {
                    Toast.makeText(context, "⚠️ 名称和内容均不能为空", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }
    // ==========================================
    // 🧠 塞尔维亚语 (拉丁) 专属发音拦截路由 (已修正)
    // ==========================================
    private fun getSmartVoiceId(voiceName: String, langName: String): String {
        // 废弃之前的身份证掉包逻辑，直接返回原始西里尔 ID，彻底消灭 500 崩溃
        return AppConstants.TTS_VOICES[voiceName] ?: "zh-CN-XiaoxiaoNeural"
    }
    private fun showQuickControlPanel() {
        val context = this
        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(60, 50, 60, 50)
            setBackgroundColor(Color.parseColor("#1A1A1B")) // 赛博朋克深黑背景
        }

        // ==========================================
        // 模块 1：TTS 语音自动播报快捷开关
        // ==========================================
        val ttsCard = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(40, 40, 40, 40)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = 50
            }
        }

        val tvTtsIcon = TextView(context).apply {
            textSize = 26f
            setPadding(0, 0, 30, 0)
        }

        val tvTtsText = TextView(context).apply {
            textSize = 15f
            setTypeface(null, android.graphics.Typeface.BOLD)
        }

        ttsCard.addView(tvTtsIcon)
        ttsCard.addView(tvTtsText)
        layout.addView(ttsCard)

        // 定义一个内部函数，用于刷新 TTS 开关的 UI 状态
        fun updateTtsUi() {
            if (isTtsEnabled) {
                tvTtsIcon.text = "🔊"
                tvTtsText.text = "自动语音播报：已开启"
                tvTtsText.setTextColor(Color.parseColor("#00E676")) // 亮绿色
                ttsCard.background = GradientDrawable().apply {
                    setColor(Color.parseColor("#121212"))
                    setStroke(3, Color.parseColor("#00E676"))
                    cornerRadius = 25f
                }
            } else {
                tvTtsIcon.text = "🔇"
                tvTtsText.text = "自动语音播报：已静音"
                tvTtsText.setTextColor(Color.parseColor("#888888")) // 暗灰色
                ttsCard.background = GradientDrawable().apply {
                    setColor(Color.parseColor("#252526"))
                    setStroke(3, Color.parseColor("#444444"))
                    cornerRadius = 25f
                }
            }
        }
        updateTtsUi() // 初始化时刷一次状态

        // 点击卡片直接切换开关，不需要关闭弹窗
        ttsCard.setOnClickListener {
            triggerVibration(40)
            isTtsEnabled = !isTtsEnabled // 翻转状态
            // 实时写入记忆体
            sharedPrefs.edit().putBoolean("tts_enabled", isTtsEnabled).apply()
            // 刷新面板 UI
            updateTtsUi()
            // 灵动岛顶部提示
            val msg = if (isTtsEnabled) "✅ 语音播报已开启" else "🔇 语音播报已静音"
            val color = if (isTtsEnabled) "#00E676" else "#888888"
            showTransientIslandMessage(msg, color)
        }

        // ==========================================
        // 模块 2：气泡视觉特效矩阵式选择器
        // ==========================================
        val tvEffectLabel = TextView(context).apply {
            text = "🎨 气泡视觉特效："
            setTextColor(Color.parseColor("#BBBBBB"))
            textSize = 13f
            setPadding(0, 0, 0, 20)
        }
        layout.addView(tvEffectLabel)

        val effectRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }

        val effects = listOf(
            Triple("A", "✨", "流光"),
            Triple("B", "⚡", "脉冲"),
            Triple("C", "🌊", "涟漪"),
            Triple("NONE", "🚫", "关闭")
        )

        var currentMode = sharedPrefs.getString("chat_effect_mode", "A") ?: "A"
        val effectButtons = mutableListOf<LinearLayout>()

        // 定义一个内部函数，用于刷新四个特效按钮的高亮状态
        fun updateEffectUi() {
            for (i in effects.indices) {
                val mode = effects[i].first
                val btn = effectButtons[i]
                val tvIcon = btn.getChildAt(0) as TextView
                val tvName = btn.getChildAt(1) as TextView

                if (mode == currentMode) {
                    // 被选中的按钮：亮蓝边框，文字亮起
                    btn.background = GradientDrawable().apply {
                        setColor(Color.parseColor("#222223"))
                        setStroke(3, Color.parseColor("#00BCFF"))
                        cornerRadius = 20f
                    }
                    tvIcon.alpha = 1f
                    tvName.setTextColor(Color.WHITE)
                } else {
                    // 未被选中的按钮：暗灰边框，文字变暗
                    btn.background = GradientDrawable().apply {
                        setColor(Color.parseColor("#121212"))
                        setStroke(2, Color.parseColor("#333333"))
                        cornerRadius = 20f
                    }
                    tvIcon.alpha = 0.4f
                    tvName.setTextColor(Color.parseColor("#666666"))
                }
            }
        }

        // 动态生成四个方块按钮
        for (effect in effects) {
            val mode = effect.first
            val icon = effect.second
            val name = effect.third

            val btnLayout = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = android.view.Gravity.CENTER
                // 使用 weight=1 平分宽度，除了最后一个外，其余加上右边距
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginEnd = if (mode != "NONE") 15 else 0
                }
                setPadding(0, 30, 0, 30)

                setOnClickListener {
                    triggerVibration(40)
                    currentMode = mode
                    // 保存记忆体
                    sharedPrefs.edit().putString("chat_effect_mode", mode).apply()
                    // 实时下发给两个聊天适配器
                    topAdapter.effectMode = mode
                    bottomAdapter.effectMode = mode
                    // 刷新四个按钮状态
                    updateEffectUi()
                    showTransientIslandMessage("🎨 特效已切换", "#00BCFF")
                }
            }

            val tvIcon = TextView(context).apply {
                text = icon
                textSize = 22f
                gravity = android.view.Gravity.CENTER
                setPadding(0, 0, 0, 15)
            }
            val tvName = TextView(context).apply {
                text = name
                textSize = 12f
                gravity = android.view.Gravity.CENTER
                setTypeface(null, android.graphics.Typeface.BOLD)
            }

            btnLayout.addView(tvIcon)
            btnLayout.addView(tvName)
            effectButtons.add(btnLayout)
            effectRow.addView(btnLayout)
        }

        updateEffectUi() // 初始化时刷一次状态
        layout.addView(effectRow)

        // ==========================================
        // 组装并显示最终的 Dialog
        // ==========================================
        val dialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setCustomTitle(createCyberTitle("⚡ 灵动控制台"))
            .setView(layout)
            .setPositiveButton("完成", null)
            .create()

        if (!isFinishing && !isDestroyed) dialog.show()
    }
    private fun showClearNotebookConfirmDialog(onCleared: () -> Unit) {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("⚠️ 警告")
            .setMessage("确定要清空当前的快捷笔记本吗？此操作将抹除所有记录且无法撤销！")
            .setPositiveButton("确定清空") { _, _ ->
                heavyTaskExecutor.execute {
                    try { java.io.File(filesDir, "kane_notebook.json").writeText("[]", Charsets.UTF_8) } catch (e: Exception) {}
                    runOnUiThread {
                        triggerVibration(60)
                        Toast.makeText(this@MainActivity, "🧹 笔记本已全部清空", Toast.LENGTH_SHORT).show()
                        onCleared() // 🌟 等文件真正被抹除后，再清空列表 UI
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }
    // 👇 将最底部的这个函数整段替换：
    private fun updateLiveTranslateButtonUI() {
        if (isHeadsetPluggedIn()) {
            // 🌟 插入耳机：进入耀眼流金状态
            btnLiveTranslate.setTextColor(android.graphics.Color.WHITE)
            btnLiveTranslate.setShadowLayer(12f, 0f, 0f, android.graphics.Color.parseColor("#FFD700"))

            // 🐛 核心修复：直接赋予一个新的圆形底板，绝不报错
            btnLiveTranslate.background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL // 👈 这句救命代码补回来了
                setStroke(5, android.graphics.Color.parseColor("#FFD700"))
                setColor(android.graphics.Color.parseColor("#1A1A1B"))
            }
        } else {
            // 🌚 拔掉耳机：进入暗灰失联状态
            btnLiveTranslate.setTextColor(android.graphics.Color.parseColor("#666666"))
            btnLiveTranslate.setShadowLayer(0f, 0f, 0f, android.graphics.Color.TRANSPARENT)

            // 🐛 核心修复：同上
            btnLiveTranslate.background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL // 👈 这句救命代码补回来了
                setStroke(2, android.graphics.Color.parseColor("#333333"))
                setColor(android.graphics.Color.parseColor("#121212"))
            }
        }
    }
    // 👆 替换到这里结束

}
// 🌟 放在 MainActivity.kt 文件的绝对最末尾
class StrokeTextView(context: Context) : androidx.appcompat.widget.AppCompatTextView(context) {
    private var strokeColor = android.graphics.Color.TRANSPARENT
    private var strokeWidthSize = 0f
    private var isDrawing = false // 增加一个状态锁

    fun setStroke(color: Int, width: Float) {
        strokeColor = color
        strokeWidthSize = width
        invalidate()
    }

    // 🌟 拦截刷新机制，防止因为在 onDraw 里改变颜色导致死循环崩溃
    override fun invalidate() {
        if (!isDrawing) super.invalidate()
    }

    override fun onDraw(canvas: android.graphics.Canvas) {
        if (strokeColor != android.graphics.Color.TRANSPARENT && strokeWidthSize > 0) {
            isDrawing = true // 锁住状态

            val originalColor = currentTextColor
            val p = paint
            val originalStyle = p.style
            val originalStrokeWidth = p.strokeWidth

            // 1. 画外圈实体描边
            p.style = android.graphics.Paint.Style.STROKE
            p.strokeWidth = strokeWidthSize
            p.strokeJoin = android.graphics.Paint.Join.ROUND // 🌟 让描边圆润，防止字母的直角产生尖刺
            p.strokeMiter = 10f
            setTextColor(strokeColor) // 🌟 骗过系统：临时把字体颜色变成描边颜色
            super.onDraw(canvas)

            // 2. 画内部实心文字
            p.style = android.graphics.Paint.Style.FILL
            p.strokeWidth = originalStrokeWidth
            setTextColor(originalColor) // 🌟 恢复：把字体颜色变回真实颜色
            super.onDraw(canvas)

            p.style = originalStyle
            isDrawing = false // 解锁
        } else {
            super.onDraw(canvas)
        }
    }

}