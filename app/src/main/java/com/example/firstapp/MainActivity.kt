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

    private lateinit var btnTopMic: TextView
    private lateinit var btnBottomMic: TextView
    private lateinit var tvDynamicIsland: TextView
    private lateinit var btnSettings: TextView
    private lateinit var btnSwap: TextView
    private lateinit var btnClear: TextView
    private lateinit var btnKeyboard: TextView

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

        // 1. 告诉系统我们要接管全屏布局
        WindowCompat.setDecorFitsSystemWindows(window, false)

        // 2. 先把我们写好的 XML 界面挂载到屏幕上
        setContentView(R.layout.activity_main)

        // 3. 界面挂载完毕后，再执行隐藏系统状态栏和导航栏
        hideSystemUI() // ✅ 这次系统绝对听话了

        clearZombieCacheFiles()
        // ... 下面的代码保持不变

        sharedPrefs = getSharedPreferences("KaneAiPrefs", Context.MODE_PRIVATE)
        val defaultKillers = setOf("字幕", "谢谢观看", "点个赞", "Mingjing", "Subscribe", "watching")
        killerSet = sharedPrefs.getStringSet("killer_list", defaultKillers)?.toMutableSet() ?: mutableSetOf()

        vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        fallbackTts = android.speech.tts.TextToSpeech(this) {}
        edgeTts = EdgeTtsEngine(this, fallbackTts)
        // 🌟 新增：App开机时，兵分两路：一路去拉取更新缓存列表，另一路直接Ping上次存好的列表
        fetchTtsNodesSilently()

        // 🌟 新增：App冷启动时，立刻在后台静默唤醒HuggingFace容器
        edgeTts.pingServer()

        // 🌟 新增：App冷启动时，立刻在后台静默唤醒HuggingFace容器
        edgeTts.pingServer()

        btnTopMic = findViewById(R.id.btn_top_mic)
        btnBottomMic = findViewById(R.id.btn_bottom_mic)
        tvDynamicIsland = findViewById(R.id.tv_dynamic_island)
        btnSettings = findViewById(R.id.btn_settings)
        btnSwap = findViewById(R.id.btn_swap)
        btnClear = findViewById(R.id.btn_clear)
        btnKeyboard = findViewById(R.id.btn_keyboard)
        btnKeyboard.setOnClickListener { showTextInputDialog() }

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

        val onPlayClickAction = { text: String, voiceId: String ->
            if (voiceId.startsWith("hy-AM")) {
                setIslandState("⚠️ 亚美尼亚语暂不支持语音播报", "#FFA500")
                resetIslandDelayed()
            } else {
                edgeTts.speak(text, voiceId,
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

            val playParams = btnFullscreenPlay.layoutParams as FrameLayout.LayoutParams
            if (isTop) {
                playParams.gravity = android.view.Gravity.TOP or android.view.Gravity.END
                playParams.setMargins(0, 60, 40, 0)
            } else {
                playParams.gravity = android.view.Gravity.BOTTOM or android.view.Gravity.CENTER_HORIZONTAL
                playParams.setMargins(0, 0, 0, 80)
            }
            btnFullscreenPlay.layoutParams = playParams

            if (isTop) {
                tvFullscreenText.text = translatedText
                tvFullscreenText.setTextColor(Color.parseColor(color))
            } else {
                val spannable = android.text.SpannableStringBuilder()
                val transSpan = android.text.SpannableString(translatedText)
                transSpan.setSpan(android.text.style.ForegroundColorSpan(Color.parseColor(color)), 0, translatedText.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                spannable.append(transSpan)

                val origStr = "\n\n(原: $originalText)"
                val origSpan = android.text.SpannableString(origStr)
                origSpan.setSpan(android.text.style.ForegroundColorSpan(Color.parseColor("#888888")), 0, origStr.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                origSpan.setSpan(android.text.style.RelativeSizeSpan(0.6f), 0, origStr.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                origSpan.setSpan(android.text.style.StyleSpan(android.graphics.Typeface.NORMAL), 0, origStr.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                spannable.append(origSpan)

                tvFullscreenText.text = spannable
                tvFullscreenText.setTextColor(Color.WHITE)
            }

            layoutFullscreenOverlay.rotation = if (isTop) 180f else 0f
            layoutFullscreenOverlay.alpha = 0f
            layoutFullscreenOverlay.visibility = View.VISIBLE
            layoutFullscreenOverlay.animate().alpha(1f).setDuration(150).start()

            if (voiceId.startsWith("hy-AM")) {
                btnFullscreenPlay.visibility = View.GONE
            } else {
                btnFullscreenPlay.visibility = View.VISIBLE
                val strPlay = if (isTop) "🔊 Play Audio" else "🔊 播放语音"
                val strStop = if (isTop) "⏹️ Stop Audio" else "⏹️ 停止播报"

                btnFullscreenPlay.text = strPlay
                btnFullscreenPlay.setTextColor(Color.WHITE)
                (btnFullscreenPlay.background as GradientDrawable).setStroke(3, Color.parseColor("#444444"))

                btnFullscreenPlay.setOnClickListener {
                    triggerVibration(50)
                    if (edgeTts.isSpeaking) {
                        edgeTts.stop()
                        btnFullscreenPlay.text = strPlay
                        btnFullscreenPlay.setTextColor(Color.WHITE)
                        (btnFullscreenPlay.background as GradientDrawable).setStroke(3, Color.parseColor("#444444"))
                    } else {
                        edgeTts.speak(translatedText, voiceId,
                            onNodeSelected = { nodeName ->
                                runOnUiThread {
                                    // 🌟 按钮状态变更为：连接节点中
                                    btnFullscreenPlay.text = "⏳ 连接 $nodeName"
                                    btnFullscreenPlay.setTextColor(Color.parseColor("#00BCFF"))
                                    (btnFullscreenPlay.background as GradientDrawable).setStroke(3, Color.parseColor("#00BCFF"))
                                }
                            },
                            onStart = {
                                runOnUiThread {
                                    btnFullscreenPlay.text = strStop
                                    btnFullscreenPlay.setTextColor(Color.parseColor("#00FF00"))
                                    (btnFullscreenPlay.background as GradientDrawable).setStroke(3, Color.parseColor("#00FF00"))
                                }
                            },
                            onDone = {
                                runOnUiThread {
                                    btnFullscreenPlay.text = strPlay
                                    btnFullscreenPlay.setTextColor(Color.WHITE)
                                    (btnFullscreenPlay.background as GradientDrawable).setStroke(3, Color.parseColor("#444444"))
                                }
                            }
                        )
                    }
                }
            }
        }

        val onDoubleTapTop = { msg: ChatMessage -> showFullscreenDisplay(msg.translatedText, msg.originalText, msg.voiceId, "#00BCFF", true) }
        val onDoubleTapBottom = { msg: ChatMessage -> showFullscreenDisplay(msg.translatedText, msg.originalText, msg.voiceId, "#00E676", false) }

        topAdapter = ChatAdapter(
            onMessageLongClick = onLongClickAction,
            onMessageDoubleTap = onDoubleTapTop,
            onPlayClick = onPlayClickAction
        )
        bottomAdapter = ChatAdapter(
            onMessageLongClick = onLongClickAction,
            onMessageDoubleTap = onDoubleTapBottom,
            onPlayClick = onPlayClickAction
        )

        rvTopChat.layoutManager = LinearLayoutManager(this).apply { stackFromEnd = true }
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
                setIslandState("⚠️ 已经是最大字号了", "#FFA500", isTop = false)
                resetIslandDelayed()
            }
        }

        btnFontMinus.setOnClickListener {
            if (chatTransSize > 14f) {
                triggerVibration(20)
                applyChatFontSize(chatTransSize - 2f, chatOrigSize - 2f)
            } else {
                setIslandState("⚠️ 已经是最小字号了", "#FFA500", isTop = false)
                resetIslandDelayed()
            }
        }

        btnFontReset.setOnClickListener {
            triggerVibration(40)
            applyChatFontSize(20f, 14f)
            setIslandState("✅ 恢复默认排版", "#00FF00", isTop = false)
            resetIslandDelayed()
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
            setIslandState("🔄 双方语种已互换", "#00BCFF")
            resetIslandDelayed()
        }

        btnClear.setOnClickListener {
            if (topAdapter.itemCount > 0 || bottomAdapter.itemCount > 0) {
                val clearDialog = AlertDialog.Builder(this)
                    .setCustomTitle(createCyberTitle("🧹 一键清屏"))
                    .setMessage("确定要清空当前的对话记录吗？")
                    .setPositiveButton("清空") { _, _ ->
                        topAdapter.clearMessages()
                        bottomAdapter.clearMessages()

                        triggerVibration(50)
                        setIslandState("✨ 已清屏", "#00FF00")
                        resetIslandDelayed()
                    }
                    .setNegativeButton("取消", null)
                    .create()
                if (!isFinishing && !isDestroyed) clearDialog.show()
            } else {
                setIslandState("✨ 屏幕已经是干净的啦", "#00FF00")
                resetIslandDelayed()
            }
        }

        loadSettings()
        requestAudioPermission()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemUI()
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
        // 🏅 【已置顶】我方语种 (下半屏) 卡片
        // ==========================================
        val myCard = createCard()
        layout.addView(myCard)
        addTitle("🗣️ 我方语种 (下半屏)", null, myCard, "#00E676")
        val spMe = addSpinner(langItems, myLangName, myCard)
        addTitle("🔊 我方发音音色", null, myCard, "#00E676")
        val spMeVoice = addSpinner(voiceItems, myVoiceName, myCard)

        // ==========================================
        // 🏅 【已置顶】对方语种 (上半屏) 卡片
        // ==========================================
        val ptCard = createCard()
        layout.addView(ptCard)
        addTitle("🗣️ 对方语种 (上半屏)", null, ptCard, "#00BCFF")
        val spPt = addSpinner(langItems, ptLangName, ptCard)
        addTitle("🔊 对方发音音色", null, ptCard, "#00BCFF")
        val spPtVoice = addSpinner(voiceItems, ptVoiceName, ptCard)

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

        addTitle("🧠 Groq 主力引擎", null, engineCard)
        val spGroqModel = Spinner(context).apply {
            val initList = mutableListOf(aiEngine.currentGroqModel)
            adapter = createModelAdapter(initList)
            background = GradientDrawable().apply { setColor(Color.parseColor("#0F0F0F")); setStroke(2, Color.DKGRAY); cornerRadius = 15f }
        }
        engineCard.addView(spGroqModel)

        val btnFetchModels = Button(context).apply {
            text = "🔄 联网拉取Groq最新模型"
            setBackgroundColor(Color.parseColor("#00BCFF"))
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = 20 }
            setOnClickListener {
                this.text = "拉取中..."
                this.isEnabled = false
                aiEngine.groqApiKey = etGroq.text.toString().trim()
                aiEngine.fetchGroqModels { success, models ->
                    if (isDestroyed || isFinishing) return@fetchGroqModels
                    this.text = "🔄 联网拉取Groq最新模型"
                    this.isEnabled = true
                    if (success && models.isNotEmpty()) {
                        spGroqModel.adapter = createModelAdapter(models)
                        val targetIndex = models.indexOfFirst { it.contains("qwen", ignoreCase = true) }.takeIf { it >= 0 } ?: 0
                        spGroqModel.setSelection(targetIndex)
                        Toast.makeText(context, "Groq模型库已更新！", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(context, "拉取失败，请检查 Key 或网络", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
        engineCard.addView(btnFetchModels)

        addTitle("🔑 Gemini API Key【🌐 前往官网获取】", "https://aistudio.google.com/app/apikey", engineCard)
        val etGemini = addInput("AIzaSy...", aiEngine.geminiApiKey, engineCard)

        addTitle("🧠 Gemini 备用引擎", null, engineCard)
        val spGeminiModel = Spinner(context).apply {
            val initList = mutableListOf(aiEngine.currentGeminiModel)
            adapter = createModelAdapter(initList)
            background = GradientDrawable().apply { setColor(Color.parseColor("#0F0F0F")); setStroke(2, Color.DKGRAY); cornerRadius = 15f }
        }
        engineCard.addView(spGeminiModel)

        val btnFetchGeminiModels = Button(context).apply {
            text = "🔄 联网拉取 Gemini 最新模型"
            setBackgroundColor(Color.parseColor("#00BCFF"))
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = 20 }
            setOnClickListener {
                this.text = "拉取中..."
                this.isEnabled = false
                aiEngine.geminiApiKey = etGemini.text.toString().trim()
                aiEngine.fetchGeminiModels { success, models ->
                    if (isDestroyed || isFinishing) return@fetchGeminiModels
                    this.text = "🔄 联网拉取 Gemini 最新模型"
                    this.isEnabled = true
                    if (success && models.isNotEmpty()) {
                        spGeminiModel.adapter = createModelAdapter(models)
                        val targetIndex = models.indexOfFirst { it == "gemini-3.1-flash-lite" }
                            .takeIf { it >= 0 }
                            ?: models.indexOfFirst { it.contains("gemini-3.1-flash", ignoreCase = true) }
                                .takeIf { it >= 0 }
                            ?: models.indexOfFirst { it.contains("gemini-3-flash", ignoreCase = true) }
                                .takeIf { it >= 0 }
                            ?: 0
                        spGeminiModel.setSelection(targetIndex)
                        Toast.makeText(context, "Gemini 模型库已更新！", Toast.LENGTH_SHORT).show()
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

        // --- 模块 C：共享的暗号区域 ---
        addTitle("🔑 暗号 (Hugging Face秘密，找KANE获取)", null, ttsNodeCard, "#BBBBBB")
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
            .setCustomTitle(createCyberTitle("KANE Face-to-Face v2.5 pro 设置面板"))
            .setView(scroll)
            .setPositiveButton("保存") { _, _ ->
                val editor = sharedPrefs.edit()
                editor.putString("groq_key", etGroq.text.toString().trim())
                editor.putString("groq_model", spGroqModel.selectedItem?.toString() ?: "qwen/qwen3-32b")
                editor.putString("gemini_key", etGemini.text.toString().trim())
                editor.putString("gemini_model", spGeminiModel.selectedItem?.toString() ?: "gemini-3.1-flash-lite")

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
                setIslandState("✅ 设置已保存", "#00FF00")
                resetIslandDelayed()
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

        // ================= 📝 正文区 (保持不变) =================
        addSection("🎙️ 基础对讲与 UI 控件", """
            <b>• 语音输入：</b>长按下方/上方麦克风录音，松开即触发翻译与播报。<br>
            <b>• 滑动取消：</b>长按录音时，手指向上滑动至指定距离即可安全取消发送。<br>
            <b>• 键盘输入：</b>点击中央 <b>[ ✍️ ]</b> 呼出键盘，应对嘈杂环境或生僻词汇。<br>
            <b>• 语种互换：</b>点击中央 <b>[ ↕️ ]</b> 快速对调双方语种与音色。<br>
            <b>• 一键清屏：</b>点击中央 <b>[ 🗑️ ]</b> 可彻底清空当前所有聊天记录。<br>
            <b>• 字号调节：</b>点击右下角 <b>[ A+ / A- ]</b> 胶囊控制器，无级调节全局气泡字号。
        """.trimIndent(), "#00E676")

        addSection("✨ 气泡手势与视觉翻译", """
            <b>• 双击全屏展示：</b>对任意气泡<b>快速双击</b>，唤出高对比度「全屏大字报」，支持双指缩放，便于向他人展示。<br>
            <b>• 长按快捷菜单：</b><b>长按</b>聊天气泡，可一键复制原文、译文，或将其打上幻听标签。<br>
            <b>• 相机视觉提取：</b>点击左下角 <b>[ 📷 ]</b>，支持拍照或选图。利用裁剪框精准圈选文本，AI 将自动进行 OCR 提取并翻译，甚至支持直接语音朗读图片内容。
        """.trimIndent(), "#00BCFF")

        addSection("⚙️ 设置面板：AI 引擎配置", """
            <b>• 双向联动绑定：</b>在顶部的语种与音色列表中，切换任意一项，系统会自动推导并匹配另一项，无需繁琐设置。<br>
            <b>• Groq 主力引擎：</b>提供极速推理。需自行前往官网申请 API Key。点击「拉取最新模型」可实时更新云端可用模型 (默认推荐 Qwen 系列)。<br>
            <b>• Gemini 备用/视觉引擎：</b>当主力网络阻断时，系统将静默无缝切换至 Gemini 兜底；此外，所有的图片翻译均由 Gemini 引擎独立完成。同样需自备 API Key。
        """.trimIndent(), "#FFA500")

        addSection("🌐 设置面板：云端 TTS 配置", """
            <b>• 语音开关：</b>可自由勾选是否开启「自动语音播报 (TTS)」。<br>
            <b>• 节点拉取机制：</b>采用微软高保真发音。当发音失效时，点击<b>「一键获取最新可用线路」</b>，系统会优先从 Google Drive 极速拉取最新节点；若超时将自动降级至 GitHub 备用通道。<br>
            <b>• 自定义 URL 与暗号：</b>支持手动填入私人部署的 TTS 节点地址。<b>「专属访问暗号 (Token)」</b>用于验证身份，防止私人云端节点被非法盗刷接口额度。
        """.trimIndent(), "#00BCFF")

        addSection("🛡️ 设置面板：幻听防火墙", """
            <b>• 触发原理：</b>在极端安静环境下，AI 偶会将底噪强行解析为“字幕”、“谢谢观看”等无意义的“幻觉词汇”。<br>
            <b>• 拦截与管理：</b>您可以在主界面长按气泡快速拉黑，或进入设置面板点击<b>「管理幻听词黑名单」</b>进行手动添加/删除。一旦识别结果包含黑名单词汇，系统将在 0.1 秒内拦截并静默抛弃。<br>
            <b>• 紧急洗牌：</b>若规则混乱，可点击底部按钮一键恢复系统内置的 6 个底层防护规则。
        """.trimIndent(), "#FF4444")

        addSection("⚖️ 隐私合规与免责声明 (GDPR & TOU)", """
            本软件架构与数据处理流程严格遵从《欧盟通用数据保护条例》(GDPR) 规范，请您在使用前知悉并同意以下条款：<br><br>
            <b>1. 数据处理与零留存 (Zero Retention)：</b><br>
            本应用作为纯本地端请求工具运行。麦克风音频、相机图像及文本数据仅在设备内存中进行短暂的加密封装，并直接通过 HTTPS 协议传输至第三方 API 服务商 (Groq/Google/Microsoft)。应用不在本地持久化存储、记录或向任何其他未经授权的服务器上传用户的个人隐私数据。视觉缓存文件严格执行即时销毁 (阅后即焚) 机制。<br><br>
            <b>2. 责任隔离与自备密钥 (BYOK Liability)：</b><br>
            本应用强制采用自备密钥 (Bring Your Own Key) 模式运行。用户自主填写的 API Key 所产生的数据传输、存储规范及合规性，受该 API 供应商 (如 Google LLC, Groq Inc.) 的最终用户服务条款约束。因输出违规内容或滥用接口导致的账户封禁、法律纠纷及一切财务损失，完全由使用者个人独立承担。<br><br>
            <b>3. 按原样提供与免责 (AS-IS Disclaimer)：</b><br>
            本应用代码遵循开源软件惯例，按“原样 (AS-IS)”提供，不附带任何明示或暗示的法律担保。开发者不对因本地网络阻断、API 供应商策略变动、第三方 TTS 节点失效或任何不可抗力导致的可用性中断承担连带维护义务或侵权赔偿责任。
        """.trimIndent(), "#888888")

        // 署名留白区
        val tvFooter = TextView(context).apply {
            text = "Designed & Developed by KANE\nVer 2.0 Pro"
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
    private fun showTextInputDialog() {
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

        var dialog: AlertDialog? = null

        fun createInputModule(titleText: String, isTop: Boolean, activeColor: String): View {
            val moduleLayout = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                layoutTransition = android.animation.LayoutTransition()
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    bottomMargin = 50
                }
            }

            val title = TextView(context).apply {
                text = titleText
                setTextColor(Color.parseColor(activeColor))
                textSize = 14f
                setPadding(10, 0, 0, 15)
                setTypeface(null, android.graphics.Typeface.BOLD)
            }
            moduleLayout.addView(title)

            val inputRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                gravity = android.view.Gravity.BOTTOM
                isBaselineAligned = false
                layoutTransition = android.animation.LayoutTransition()
                clipChildren = false
                clipToPadding = false
            }

            val et = EditText(context).apply {
                hint = "输入文字 / Type here..."
                setHintTextColor(Color.parseColor("#555555"))
                setTextColor(Color.WHITE)
                textSize = 16f
                setPadding(35, 30, 35, 30)
                gravity = android.view.Gravity.TOP or android.view.Gravity.START
                inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
                minLines = 1
                maxLines = 18
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginEnd = 25
                }
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#0F0F0F"))
                    setStroke(2, Color.DKGRAY)
                    cornerRadius = 20f
                }
            }

            et.setOnFocusChangeListener { _, hasFocus ->
                val color = if (hasFocus) activeColor else "#444444"
                val width = if (hasFocus) 5 else 2
                et.background = GradientDrawable().apply {
                    setColor(Color.parseColor("#0F0F0F"))
                    setStroke(width, Color.parseColor(color))
                    cornerRadius = 20f
                }
                if (hasFocus) {
                    et.minLines = 18
                } else {
                    et.minLines = 1
                }
            }

            val density = context.resources.displayMetrics.density
            val btnSize = (45 * density).toInt()

            val sendBtn = TextView(context).apply {
                text = "🚀"
                textSize = 20f
                gravity = android.view.Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(btnSize, btnSize).apply {
                    bottomMargin = (8 * density).toInt()
                    marginStart = (12 * density).toInt()
                }
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.parseColor("#252526"))
                    setStroke((2 * density).toInt(), Color.parseColor(activeColor))
                }

                setOnClickListener {
                    val input = et.text.toString().trim()
                    if (input.isNotEmpty()) {
                        triggerVibration(50)

                        // 🌟 修复：发送文本前，执行航天级强制收回软键盘指令，杜绝幽灵键盘残留
                        try {
                            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
                            imm.hideSoftInputFromWindow(et.windowToken, 0)
                        } catch (e: Exception) {}

                        dialog?.dismiss()
                        processTextPipeline(input, isTop)
                    }
                }
            }
            inputRow.addView(et)
            inputRow.addView(sendBtn)
            moduleLayout.addView(inputRow)

            return moduleLayout
        }

        layout.addView(createInputModule("✍️ 对方文字输入 [ ${ptLangName} ]", isTop = true, activeColor = "#00BCFF"))
        layout.addView(createInputModule("✍️ 我方文字输入[ ${myLangName} ]", isTop = false, activeColor = "#00E676"))

        dialog = AlertDialog.Builder(this)
            .setView(layout)
            .create()

        dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
        dialog.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        if (!isFinishing && !isDestroyed) dialog.show()
    }

    private fun loadSettings() {
        aiEngine.groqApiKey = sharedPrefs.getString("groq_key", "") ?: ""
        aiEngine.currentGroqModel = sharedPrefs.getString("groq_model", "qwen/qwen3-32b") ?: "qwen/qwen3-32b"
        aiEngine.geminiApiKey = sharedPrefs.getString("gemini_key", "") ?: ""
        aiEngine.currentGeminiModel = sharedPrefs.getString("gemini_model", "gemini-3.1-flash-lite") ?: "gemini-3.1-flash-lite"
        myLangName = sharedPrefs.getString("lang_me", "中文") ?: "中文"
        ptLangName = sharedPrefs.getString("lang_pt", "英语") ?: "英语"
        myVoiceName = sharedPrefs.getString("voice_me", "晓晓 (中&英·温柔女声)") ?: "晓晓 (中&英·温柔女声)"
        ptVoiceName = sharedPrefs.getString("voice_pt", "Ava (英文·自然女声)") ?: "Ava (英文·自然女声)"
        isTtsEnabled = sharedPrefs.getBoolean("tts_enabled", true)

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
                    setIslandState("🎯 规则已生效", "#FF4444")
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
                .setMessage("确定要清空所有自定义规则，并恢复系统默认的 6 个底层规则吗？\n(这将会清除你此前手动添加的所有黑名单)")
                .setPositiveButton("确定恢复") { _, _ ->
                    killerSet.clear()
                    killerSet.addAll(setOf("字幕", "谢谢观看", "点个赞", "mingjing", "subscribe", "watching"))
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
        val options = arrayOf("📋 复制译文", "📄 复制原文", "🎯 标记为幻听拉黑")

        val optionsDialog = AlertDialog.Builder(this)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> copyToClipboard("译文", msg.translatedText)
                    1 -> copyToClipboard("原文", msg.originalText)
                    2 -> showKillerDialog(msg.originalText)
                }
            }
            .create()

        if (!isFinishing && !isDestroyed) optionsDialog.show()
    }

    private fun showImageResultDialog(croppedBitmap: android.graphics.Bitmap?, originalText: String, translatedText: String) {
        val context = this
        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(50, 40, 50, 40)
            setBackgroundColor(Color.parseColor("#1A1A1B"))
        }

        if (croppedBitmap != null) {
            val imgContainer = LinearLayout(context).apply {
                gravity = android.view.Gravity.CENTER
                setPadding(0, 0, 0, 30)
            }
            val imageView = ImageView(context).apply {
                setImageBitmap(croppedBitmap)
                layoutParams = LinearLayout.LayoutParams(
                    (150 * resources.displayMetrics.density).toInt(),
                    (100 * resources.displayMetrics.density).toInt()
                )
                scaleType = ImageView.ScaleType.CENTER_CROP
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#0F0F0F"))
                    setStroke(3, Color.parseColor("#333333"))
                    cornerRadius = 20f
                }
                clipToOutline = true
            }
            imgContainer.addView(imageView)
            layout.addView(imgContainer)
        }

        val tvOriginalHeader = TextView(context).apply {
            text = "📄 识别原文：(点击复制)"
            setTextColor(Color.parseColor("#555555"))
            textSize = 12f
            setPadding(0, 0, 0, 10)
        }
        val tvOriginalBody = TextView(context).apply {
            text = originalText
            setTextColor(Color.parseColor("#888888"))
            textSize = 14f
            setPadding(30, 20, 30, 20)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#121212"))
                cornerRadius = 15f
            }
            setOnClickListener {
                copyToClipboard("识别原文", originalText)
                animate().alpha(0.3f).setDuration(100).withEndAction {
                    animate().alpha(1f).setDuration(100).start()
                }.start()
            }
        }
        layout.addView(tvOriginalHeader)
        layout.addView(tvOriginalBody)

        val tvTranslatedHeader = TextView(context).apply {
            text = "✨ 智能翻译：(点击复制)"
            setTextColor(Color.parseColor("#00E676"))
            textSize = 12f
            setPadding(0, 30, 0, 10)
        }
        val tvTranslatedBody = TextView(context).apply {
            text = translatedText
            setTextColor(Color.WHITE)
            textSize = 20f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(30, 30, 30, 30)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#222223"))
                cornerRadius = 25f
                setStroke(2, Color.parseColor("#00E676"))
            }
            setOnClickListener {
                copyToClipboard("智能翻译", translatedText)
                animate().alpha(0.3f).setDuration(100).withEndAction {
                    animate().alpha(1f).setDuration(100).start()
                }.start()
            }
        }
        layout.addView(tvTranslatedHeader)
        layout.addView(tvTranslatedBody)

        val actionRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER
            setPadding(0, 40, 0, 10)
        }

        val btnPlay = TextView(context).apply {
            text = "🔊 语音播报"
            setTextColor(Color.WHITE)
            textSize = 16f
            gravity = android.view.Gravity.CENTER
            setPadding(40, 20, 40, 20)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#00AA00"))
                cornerRadius = 50f
            }
            setOnClickListener {
                triggerVibration(40)
                if (edgeTts.isSpeaking) {
                    edgeTts.stop()
                    text = "🔊 语音播报"
                    setTextColor(Color.WHITE)
                    (background as GradientDrawable).setColor(Color.parseColor("#00AA00"))
                } else {
                    val voiceId = AppConstants.TTS_VOICES[myVoiceName] ?: "zh-CN-XiaoxiaoNeural"
                    edgeTts.speak(translatedText, voiceId,
                        onNodeSelected = { nodeName ->
                            runOnUiThread {
                                // 🌟 按钮状态变更为：连接节点中
                                text = "⏳ 连接 $nodeName"
                                setTextColor(Color.parseColor("#00BCFF"))
                                (background as GradientDrawable).setColor(Color.parseColor("#121212"))
                            }
                        },
                        onStart = {
                            runOnUiThread {
                                text = "⏹️ 停止播报"
                                setTextColor(Color.parseColor("#FF4444"))
                                (background as GradientDrawable).setColor(Color.parseColor("#331111"))
                            }
                        },
                        onDone = {
                            runOnUiThread {
                                text = "🔊 语音播报"
                                setTextColor(Color.WHITE)
                                (background as GradientDrawable).setColor(Color.parseColor("#00AA00"))
                            }
                        }
                    )
                }
            }
        }
        actionRow.addView(btnPlay)
        layout.addView(actionRow)

        val scroll = ScrollView(context).apply { addView(layout) }

        val dialog = AlertDialog.Builder(this)
            .setCustomTitle(createCyberTitle("👁️ KANE 视觉翻译"))
            .setView(scroll)
            .setPositiveButton("完成") { _, _ ->
                edgeTts.stop()
                clearVisionCache(forceWipe = true)
            }
            .setCancelable(false)
            .create()

        if (!isFinishing && !isDestroyed) dialog.show()
    }

    private fun copyToClipboard(label: String, text: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val clip = android.content.ClipData.newPlainText(label, text)
        clipboard.setPrimaryClip(clip)

        triggerVibration(50)
        Toast.makeText(this, "✅ $label 已复制", Toast.LENGTH_SHORT).show()
        setIslandState("✅ 复制成功", "#00FF00")
        resetIslandDelayed()
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
                    resetIslandDelayed()
                }
            } else {
                setIslandState(rawText, "#FF4444", isTop = isTop)
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
        val voiceId = AppConstants.TTS_VOICES[voiceName] ?: "en-US-AvaNeural"

        val transMsg = if (isTop) "⏳ Translating..." else "⏳ 正在翻译..."
        setIslandState(transMsg, "#FFFF00", isTop = isTop)

        aiEngine.translateText(text, llmSourceEn, llmTargetEn,
            onFallback = {
                runOnUiThread {
                    val fallbackMsg = if (isTop) "⚠️ Using Backup AI..." else "⚠️ 切换备用引擎..."
                    setIslandState(fallbackMsg, "#FFA500", isTop = isTop)
                }
            }
        ) { llmSuccess, translated, engineName ->
            if (isDestroyed || isFinishing) return@translateText

            if (llmSuccess) {
                if (translated.isBlank()) {
                    val noiseMsg = if (isTop) "🎯 Noise Filtered" else "🎯 已过滤杂音"
                    setIslandState(noiseMsg, "#888888", isTop = isTop)
                    resetIslandDelayed()
                    return@translateText
                }

                topAdapter.addMessage(ChatMessage(translated, text, isMe = isTop, voiceId = voiceId))
                bottomAdapter.addMessage(ChatMessage(translated, text, isMe = !isTop, voiceId = voiceId))

                rvTopChat.post {
                    val topScroller = object : androidx.recyclerview.widget.LinearSmoothScroller(this@MainActivity) {
                        override fun calculateDyToMakeVisible(view: View, snapPreference: Int): Int {
                            val layoutManager = layoutManager ?: return 0
                            val params = view.layoutParams as RecyclerView.LayoutParams
                            val viewTop = layoutManager.getDecoratedTop(view) - params.topMargin
                            val viewBottom = layoutManager.getDecoratedBottom(view) + params.bottomMargin
                            val rvTop = layoutManager.paddingTop
                            val rvBottom = layoutManager.height - layoutManager.paddingBottom

                            val viewHeight = viewBottom - viewTop
                            val rvHeight = rvBottom - rvTop

                            if (viewHeight > rvHeight || viewTop < rvTop) {
                                return rvTop - viewTop
                            }
                            return super.calculateDyToMakeVisible(view, snapPreference)
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
                        edgeTts.speak(translated, voiceId,
                            onNodeSelected = { nodeName ->
                                runOnUiThread {
                                    val waitingMsg = if (isTop) "🎵 Preparing [$nodeName]" else "🎵 准备发音 [$nodeName]"
                                    setIslandState(waitingMsg, "#00BCFF", isTop = isTop, animatePop = false)
                                }
                            },
                            onStart = {
                                setIslandState("🔊   正在播报   ⏹️ ", "#00FF00", isTop = false)
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
                    val doneMsg = if (isTop) "⚡ Translated via $engineName" else "⚡ 翻译成功 (由 $engineName 提供)"
                    setIslandState(doneMsg, "#00FF00", isTop = isTop)
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

    private fun setIslandState(text: String, colorStr: String, animatePop: Boolean = true, isTop: Boolean? = null) {
        if (isDestroyed || isFinishing) return

        tvDynamicIsland.removeCallbacks(resetIslandRunnable)

        if (isTop != null) {
            tvDynamicIsland.rotation = if (isTop) 180f else 0f
        }

        val parsedColor = Color.parseColor(colorStr)
        tvDynamicIsland.text = text
        tvDynamicIsland.setTextColor(parsedColor)
        (tvDynamicIsland.background as GradientDrawable).setStroke(3, parsedColor)

        if (animatePop) {
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

                    if (edgeTts.isSpeaking) edgeTts.stop()
                    startY = event.y; isCancelled = false; triggerVibration(50)

                    val msg = if (isTop) "🔴 Listening..." else "🔴 翻译员正在聆听..."
                    setIslandState(msg, "#FF4444", isTop = isTop)
                    bg.setStroke(6, Color.parseColor("#FF4444"))
                    button.setTextColor(Color.parseColor("#FF4444"))

                    button.animate().scaleX(0.85f).scaleY(0.85f).setDuration(150).start()
                    audioProcessor.startRecording()
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
                // ... 下面的 ACTION_UP 逻辑不用变，保持原样即可 ...
                MotionEvent.ACTION_MOVE -> {
                    val deltaY = if (isTop) event.y - startY else startY - event.y
                    if (deltaY > 150) {
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
                        val wavData = audioProcessor.stopAndProcess()
                        runOnUiThread {
                            if (isDestroyed || isFinishing) return@runOnUiThread
                            isProcessingAudio = false

                            if (!isCancelled && wavData != null) {
                                val recogMsg = if (isTop) "⏳ Recognizing..." else "⏳ 正在识别语音..."
                                setIslandState(recogMsg, "#FFFF00", isTop = isTop)
                                processAiPipeline(wavData, isTop)
                            } else if (isCancelled) {
                                val cancelMsg = if (isTop) "🚫 Cancelled" else "🚫 已取消"
                                setIslandState(cancelMsg, "#FF4444", isTop = isTop)
                                resetIslandDelayed()
                            } else {
                                val shortMsg = if (isTop) "⚠️ Too short" else "⚠️ 录音时间太短"
                                setIslandState(shortMsg, "#FFA500", isTop = isTop)
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

    override fun onDestroy() {
        edgeTts.stop()
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
            setToolbarTitle("✨ 框选需要破译的文字")
        }

        val uCropIntent = com.yalantis.ucrop.UCrop.of(sourceUri, destUri)
            .withOptions(options)
            .getIntent(this)

        cropLauncher.launch(uCropIntent)
    }

    private fun processCroppedImage(uri: android.net.Uri) {
        setIslandState("👁️ 视觉AI正在破译...", "#00BCFF")
        triggerVibration(50)

        Thread {
            try {
                val path = uri.path ?: return@Thread
                val bitmap = android.graphics.BitmapFactory.decodeFile(path) ?: return@Thread

                val sourceLangEn = AppConstants.LANG_MAP_EN[ptLangName] ?: "English"
                val targetLangEn = AppConstants.LANG_MAP_EN[myLangName] ?: "Chinese"

                aiEngine.translateImageWithGemini(bitmap, sourceLangEn, targetLangEn) { success, orig, trans ->
                    if (isDestroyed || isFinishing) return@translateImageWithGemini
                    if (success) {
                        setIslandState("✨ 破译完成", "#00FF00")
                        resetIslandDelayed()
                        showImageResultDialog(bitmap, orig, trans)
                    } else {
                        setIslandState(trans, "#FF4444")
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
        }.start()
    }

    private fun clearVisionCache(forceWipe: Boolean = false) {
        Thread {
            try {
                val cacheFolder = java.io.File(cacheDir, "kane_vision_cache")
                if (cacheFolder.exists()) {
                    if (forceWipe) {
                        cacheFolder.deleteRecursively()
                    } else {
                        val files = cacheFolder.listFiles() ?: return@Thread
                        val now = System.currentTimeMillis()
                        for (file in files) {
                            if (now - file.lastModified() > 10 * 60 * 1000) file.delete()
                        }
                    }
                }
            } catch (e: Exception) {}
        }.start()
    }
    // =================================================================================
    // 🌟 核心重构：彻底废弃谷歌网盘明文通道，全量走 GitHub CDN，并接入 AES 防白嫖与跳过更新机制
    // 🌟 核心重构：双通道航天级容灾机制 (GitHub CDN 主力 + Hugging Face 兜底)
    // =================================================================================
    private fun fetchTtsNodesSilently(onResult: ((Int, String) -> Unit)? = null) {
        val gitClient = okhttp3.OkHttpClient.Builder().connectTimeout(8, java.util.concurrent.TimeUnit.SECONDS).build()
        val urlGit = "https://cdn.jsdelivr.net/gh/nemo2014kk/Kane-firstAPP-Config@main/tts_nodes.json"
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
                val encryptedUrl = jsonObj.optString("google_drive_encrypted", "")

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
}