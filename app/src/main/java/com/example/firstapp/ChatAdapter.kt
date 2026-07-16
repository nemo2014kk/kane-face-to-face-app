package com.example.firstapp

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

// 🌟 核心升级：引入发言人溯源机制与气泡身份证 (id)
data class ChatMessage(val translatedText: String, val originalText: String, val isMe: Boolean, val voiceId: String, val isTopSpeaker: Boolean = false, val id: String = java.util.UUID.randomUUID().toString())

class ChatAdapter(
    private val messages: MutableList<ChatMessage> = mutableListOf(),
    private val onMessageLongClick: (ChatMessage) -> Unit,
    private val onMessageDoubleTap: (ChatMessage) -> Unit,
    private val onPlayClick: (String, String, Boolean) -> Unit, // 🌟 增加 Boolean 接收身份
    private val onEditClick: (ChatMessage) -> Unit
) : RecyclerView.Adapter<ChatAdapter.ChatViewHolder>() {

    // 🌟 视觉闹钟开关：改为公开变量，接受外部随时下发指令
    var effectMode: String = "A"

    private var primaryFontSize: Float = 20f
    private var secondaryFontSize: Float = 14f

    private val animatedIds = mutableSetOf<String>()

    fun updateFontSize(primary: Float, secondary: Float) {
        primaryFontSize = primary
        secondaryFontSize = secondary
        notifyDataSetChanged()
    }

    fun addMessage(msg: ChatMessage) {
        messages.add(msg)
        notifyItemInserted(messages.size - 1)
    }

    fun clearMessages() {
        messages.clear()
        animatedIds.clear()
        notifyDataSetChanged()
    }

    fun updateMessageById(targetId: String, newTranslated: String, newOriginal: String) {
        val index = messages.indexOfFirst { it.id == targetId }
        if (index != -1) {
            messages[index] = messages[index].copy(translatedText = newTranslated, originalText = newOriginal)
            notifyItemChanged(index)
        }
    }
    // 👇 🌟 这是需要新增的“气泡消除术”代码，直接复制粘贴到这里：
    fun removeMessageById(targetId: String) {
        val index = messages.indexOfFirst { it.id == targetId }
        if (index != -1) {
            messages.removeAt(index)         // 从数据源中删掉这个气泡
            animatedIds.remove(targetId)     // 清除动画记忆，保持干净
            notifyItemRemoved(index)         // 通知界面平滑移除
        }
    }
    // 👆 新增结束

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ChatViewHolder {
        val context = parent.context

        val container = LinearLayout(context).apply {
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            orientation = LinearLayout.VERTICAL
            setPadding(0, 10, 0, 10)
        }

        val bubbleWrapper = FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            clipChildren = false
            clipToPadding = false
        }

        val effectLayer = FrameLayout(context).apply {
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }

        val bubbleBox = LinearLayout(context).apply {
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            orientation = LinearLayout.VERTICAL
            setPadding(40, 25, 40, 25)
        }

        val sweepLayer = View(context).apply {
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            visibility = View.GONE
        }

        val topRowLayout = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }

        // 🌟 修复短气泡变短：将 width 改为 WRAP_CONTENT，结合 weight=1f，确保测绘时不崩塌
        val tvTrans = TextView(context).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setLineSpacing(6f, 1.15f)
            setPadding(0, 0, 0, 10)
        }

        val btnPlay = TextView(context).apply {
            text = "🔊"
            textSize = 22f
            setPadding(20, 0, 0, 0)
        }

        val bottomRowLayout = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = 12
            }
        }

        // 🌟 修复短气泡变短：同上
        val tvOrig = TextView(context).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setLineSpacing(2f, 1.05f)
            setPadding(0, 10, 0, 10)
        }

        val btnEdit = TextView(context).apply {
            text = "✏️"
            textSize = 22f
            setPadding(20, 10, 0, 0)
        }

        topRowLayout.addView(tvTrans)
        topRowLayout.addView(btnPlay)
        bottomRowLayout.addView(tvOrig)
        bottomRowLayout.addView(btnEdit)

        bubbleBox.addView(topRowLayout)
        bubbleBox.addView(bottomRowLayout)

        bubbleWrapper.addView(effectLayer)
        bubbleWrapper.addView(bubbleBox)
        bubbleWrapper.addView(sweepLayer)

        container.addView(bubbleWrapper)

        return ChatViewHolder(container, bubbleWrapper, effectLayer, bubbleBox, sweepLayer, tvTrans, btnPlay, tvOrig, topRowLayout, btnEdit, bottomRowLayout)
    }

    override fun onBindViewHolder(holder: ChatViewHolder, position: Int) {
        val msg = messages[position]

        holder.tvTrans.text = msg.translatedText
        holder.tvOrig.text = "🎙️: ${msg.originalText}"

        if (msg.isMe) {
            holder.tvOrig.textSize = primaryFontSize
            holder.tvOrig.setTextColor(Color.WHITE)
            holder.tvOrig.setTypeface(null, android.graphics.Typeface.BOLD)

            holder.tvTrans.textSize = secondaryFontSize
            holder.tvTrans.setTextColor(Color.parseColor("#BBBBBB"))
            holder.tvTrans.setTypeface(null, android.graphics.Typeface.NORMAL)
        } else {
            holder.tvTrans.textSize = primaryFontSize
            holder.tvTrans.setTextColor(Color.WHITE)
            holder.tvTrans.setTypeface(null, android.graphics.Typeface.BOLD)

            holder.tvOrig.textSize = secondaryFontSize
            holder.tvOrig.setTextColor(Color.parseColor("#BBBBBB"))
            holder.tvOrig.setTypeface(null, android.graphics.Typeface.NORMAL)
        }

        val bg = GradientDrawable().apply {
            cornerRadius = 35f
            setColor(Color.parseColor(if (msg.isMe) "#00AA00" else "#333333"))
        }
        holder.bubbleBox.background = bg

        val params = holder.bubbleWrapper.layoutParams as LinearLayout.LayoutParams
        params.gravity = if (msg.isMe) Gravity.END else Gravity.START
        holder.bubbleWrapper.layoutParams = params

        if (msg.isMe) {
            holder.topRowLayout.gravity = Gravity.CENTER_VERTICAL
            holder.bottomRowLayout.gravity = Gravity.CENTER_VERTICAL
            holder.btnEdit.visibility = View.VISIBLE
        } else {
            holder.topRowLayout.gravity = Gravity.TOP
            holder.bottomRowLayout.gravity = Gravity.TOP
            holder.btnEdit.visibility = View.GONE
        }

        holder.btnEdit.setOnClickListener { onEditClick(msg) }

        holder.bubbleBox.setOnLongClickListener {
            onMessageLongClick(msg)
            true
        }

        holder.bubbleBox.setOnClickListener {
            val clickTime = System.currentTimeMillis()
            if (clickTime - holder.lastClickTime < 300) {
                onMessageDoubleTap(msg)
            }
            holder.lastClickTime = clickTime
        }

        holder.btnPlay.setOnClickListener {
            onPlayClick(msg.translatedText, msg.voiceId, msg.isTopSpeaker) // 🌟 传出发言人身份
        }

        // ==========================================
        // 🌟 终极物理特效：弹射起步 + 赛博视觉闹钟
        // ==========================================
        if (!animatedIds.contains(msg.id)) {
            animatedIds.add(msg.id)

            holder.bubbleWrapper.alpha = 0f
            holder.bubbleWrapper.scaleX = 0.5f
            holder.bubbleWrapper.scaleY = 0.5f
            holder.bubbleWrapper.translationY = 150f

            holder.bubbleWrapper.animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .translationY(0f)
                .setDuration(450)
                .setInterpolator(android.view.animation.OvershootInterpolator(1.2f))
                .withEndAction {
                    playAttentionEffect(holder, msg.isMe)
                }
                .start()
        } else {
            // 🌟 核心修复 2：强行打断并销毁残留在这块复用气泡上的幽灵动画！
            holder.bubbleWrapper.animate().cancel()
            holder.sweepLayer.animate().cancel()

            // 🛡️ 状态复位：消除复用时的所有幽灵特效
            holder.bubbleWrapper.alpha = 1f
            holder.bubbleWrapper.scaleX = 1f
            holder.bubbleWrapper.scaleY = 1f
            holder.bubbleWrapper.translationY = 0f
            holder.sweepLayer.clipToOutline = false
            holder.effectLayer.removeAllViews()
            holder.sweepLayer.visibility = View.GONE
        }
    }

    // ==========================================
    // 🌟 赛博光效调度中心
    // ==========================================
    private fun playAttentionEffect(holder: ChatViewHolder, isMe: Boolean) {
        val context = holder.itemView.context
        val themeColor = if (isMe) "#00E676" else "#00BCFF"

        when (effectMode) {  // 👈 改为小写的 effectMode
            "A" -> { // 方案A：流光溢彩 (Neon Sweep)
                holder.sweepLayer.visibility = View.VISIBLE

                // 🌟 修复：只对光效层进行严格的圆角裁切，绝不误伤外层气泡！
                holder.sweepLayer.outlineProvider = object : android.view.ViewOutlineProvider() {
                    override fun getOutline(view: View, outline: android.graphics.Outline) {
                        outline.setRoundRect(0, 0, view.width, view.height, 35f)
                    }
                }
                holder.sweepLayer.clipToOutline = true

                holder.sweepLayer.background = GradientDrawable(
                    GradientDrawable.Orientation.LEFT_RIGHT,
                    intArrayOf(Color.TRANSPARENT, Color.parseColor("#99FFFFFF"), Color.TRANSPARENT)
                )
                holder.sweepLayer.translationX = -1000f
                holder.sweepLayer.animate()
                    .translationX(1000f)
                    .setDuration(700)
                    .setInterpolator(android.view.animation.AccelerateDecelerateInterpolator())
                    .withEndAction {
                        holder.sweepLayer.visibility = View.GONE
                        holder.sweepLayer.clipToOutline = false // 动画结束后释放裁切锁
                    }
                    .start()
            }

            "B" -> { // 方案B：核心强脉冲 (Neon Pulse) - 🌟 全新重构，极其猛烈！
                holder.sweepLayer.visibility = View.VISIBLE

                // 覆盖一层强烈的高亮膜 + 极度粗壮的荧光描边 (绝不使用 Scale 缩放，彻底杜绝括号感)
                holder.sweepLayer.background = GradientDrawable().apply {
                    cornerRadius = 35f
                    setColor(Color.parseColor(if (isMe) "#6600E676" else "#6600BCFF")) // 40% 的高亮膜
                    setStroke(10, Color.parseColor(themeColor)) // 10像素的高粗光边
                }
                holder.sweepLayer.alpha = 0f

                holder.sweepLayer.animate().alpha(1f).setDuration(60).withEndAction {
                    holder.sweepLayer.animate().alpha(0.2f).setDuration(60).withEndAction {
                        holder.sweepLayer.animate().alpha(1f).setDuration(100).withEndAction {
                            holder.sweepLayer.animate().alpha(0f).setDuration(300).withEndAction {
                                holder.sweepLayer.visibility = View.GONE
                            }.start()
                        }.start()
                    }.start()
                }.start()
            }

            "C" -> { // 方案C：微波涟漪 (Radar Ripple) - 🌟 保持原汁原味
                holder.effectLayer.removeAllViews()
                for (i in 0..1) {
                    val ripple = View(context).apply {
                        layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                        background = GradientDrawable().apply {
                            cornerRadius = 35f
                            setStroke(6, Color.parseColor(themeColor))
                        }
                        alpha = 0f
                    }
                    holder.effectLayer.addView(ripple)

                    ripple.animate()
                        .alpha(0.8f)
                        .scaleX(1.05f)
                        .scaleY(1.08f)
                        .setDuration(150)
                        .setStartDelay((i * 200).toLong())
                        .withEndAction {
                            ripple.animate()
                                .alpha(0f)
                                .scaleX(1.15f)
                                .scaleY(1.25f)
                                .setDuration(600)
                                .start()
                        }.start()
                }
            }
        }
    }

    override fun getItemCount() = messages.size

    class ChatViewHolder(
        val container: LinearLayout,
        val bubbleWrapper: FrameLayout,
        val effectLayer: FrameLayout,
        val bubbleBox: LinearLayout,
        val sweepLayer: View,
        val tvTrans: TextView,
        val btnPlay: TextView,
        val tvOrig: TextView,
        val topRowLayout: LinearLayout,
        val btnEdit: TextView,
        val bottomRowLayout: LinearLayout
    ) : RecyclerView.ViewHolder(container) {
        var lastClickTime: Long = 0
    }
}