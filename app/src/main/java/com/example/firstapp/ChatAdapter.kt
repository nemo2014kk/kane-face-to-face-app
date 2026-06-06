package com.example.firstapp

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

data class ChatMessage(val translatedText: String, val originalText: String, val isMe: Boolean, val voiceId: String)

class ChatAdapter(
    private val messages: MutableList<ChatMessage> = mutableListOf(),
    private val onMessageLongClick: (ChatMessage) -> Unit,
    private val onMessageDoubleTap: (ChatMessage) -> Unit,
    private val onPlayClick: (String, String) -> Unit
) : RecyclerView.Adapter<ChatAdapter.ChatViewHolder>() {

    // 🌟 核心：存储当前的全局字号状态
    private var primaryFontSize: Float = 20f
    private var secondaryFontSize: Float = 14f

    fun updateFontSize(primary: Float, secondary: Float) {
        primaryFontSize = primary
        secondaryFontSize = secondary
        notifyDataSetChanged() // 一键刷新全屏所有气泡
    }

    fun addMessage(msg: ChatMessage) {
        messages.add(msg)
        notifyItemInserted(messages.size - 1)
    }

    fun clearMessages() {
        messages.clear()
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ChatViewHolder {
        val context = parent.context

        val container = LinearLayout(context).apply {
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            orientation = LinearLayout.VERTICAL
            setPadding(0, 10, 0, 10)
        }

        val bubbleBox = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 25, 40, 25)
        }

        val topRowLayout = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }

        // 🌟 译文区 (固定在上方，取消写死的颜色和字号，改为动态下发)
        val tvTrans = TextView(context).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        val btnPlay = TextView(context).apply {
            text = "🔊"
            textSize = 22f
            setPadding(20, 0, 0, 0)
        }

        // 🌟 原文区 (固定在下方，取消写死的颜色和字号)
        val tvOrig = TextView(context).apply {
            setPadding(0, 10, 0, 0)
        }

        topRowLayout.addView(tvTrans)
        topRowLayout.addView(btnPlay)

        bubbleBox.addView(topRowLayout)
        bubbleBox.addView(tvOrig)
        container.addView(bubbleBox)

        return ChatViewHolder(container, bubbleBox, tvTrans, btnPlay, tvOrig, topRowLayout)
    }


    override fun onBindViewHolder(holder: ChatViewHolder, position: Int) {
        val msg = messages[position]

        // 1. 结构固定：译文永远在上面，原文永远在下面（且附带"原: "前缀）
        holder.tvTrans.text = msg.translatedText
        holder.tvOrig.text = "🎙️: ${msg.originalText}"

        // 2. 视觉焦点动态反转：谁是我的母语，谁就变大、变白、加粗！
        if (msg.isMe) {
            // 我说的话：原文是母语。
            // 👉 原文 (下方) 变为：大字号 + 纯白 + 加粗
            holder.tvOrig.textSize = primaryFontSize
            holder.tvOrig.setTextColor(Color.WHITE)
            holder.tvOrig.setTypeface(null, android.graphics.Typeface.BOLD)

            // 👉 译文 (上方) 变为：小字号 + 浅灰 + 常规
            holder.tvTrans.textSize = secondaryFontSize
            holder.tvTrans.setTextColor(Color.parseColor("#BBBBBB"))
            holder.tvTrans.setTypeface(null, android.graphics.Typeface.NORMAL)
        } else {
            // 对方说的话：译文是母语。
            // 👉 译文 (上方) 变为：大字号 + 纯白 + 加粗
            holder.tvTrans.textSize = primaryFontSize
            holder.tvTrans.setTextColor(Color.WHITE)
            holder.tvTrans.setTypeface(null, android.graphics.Typeface.BOLD)

            // 👉 原文 (下方) 变为：小字号 + 浅灰 + 常规
            holder.tvOrig.textSize = secondaryFontSize
            holder.tvOrig.setTextColor(Color.parseColor("#BBBBBB"))
            holder.tvOrig.setTypeface(null, android.graphics.Typeface.NORMAL)
        }

        // ==============================================================

        val bg = GradientDrawable().apply {
            cornerRadius = 35f
            setColor(Color.parseColor(if (msg.isMe) "#00AA00" else "#333333"))
        }
        holder.bubbleBox.background = bg

        val params = holder.bubbleBox.layoutParams as LinearLayout.LayoutParams
        params.gravity = if (msg.isMe) Gravity.END else Gravity.START
        holder.bubbleBox.layoutParams = params

        // 动态调整气泡内小喇叭的位置
        if (msg.isMe) {
            holder.topRowLayout.gravity = Gravity.CENTER_VERTICAL // 我方保持原本居中不变
        } else {
            holder.topRowLayout.gravity = Gravity.TOP // 对方全贴到右上角
        }

        // 绑定长按事件
        holder.bubbleBox.setOnLongClickListener {
            onMessageLongClick(msg)
            true
        }

        // 极简双击探测器 (升级版：免疫滑屏复用)
        holder.bubbleBox.setOnClickListener {
            val clickTime = System.currentTimeMillis()
            if (clickTime - holder.lastClickTime < 300) { // 300毫秒内连点两次触发
                onMessageDoubleTap(msg)
            }
            holder.lastClickTime = clickTime
        }

        // 绑定小喇叭点击事件（朗读位于上方的译文结果）
        holder.btnPlay.setOnClickListener {
            onPlayClick(msg.translatedText, msg.voiceId)
        }
    }

    override fun getItemCount() = messages.size

    class ChatViewHolder(
        val container: LinearLayout,
        val bubbleBox: LinearLayout,
        val tvTrans: TextView,
        val btnPlay: TextView,
        val tvOrig: TextView,
        val topRowLayout: LinearLayout
    ) : RecyclerView.ViewHolder(container) {
        // 🛡️ 修复：将双击时间戳绑定到实体 ViewHolder 上，免疫滑动复用导致的重置
        var lastClickTime: Long = 0
    }
}