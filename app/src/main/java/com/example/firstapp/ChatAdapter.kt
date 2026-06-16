package com.example.firstapp

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.ViewGroup
import android.view.View   // 👈 手动加上这行
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

// 🌟 核心升级：引入发言人溯源机制 (isTopSpeaker) 与气泡身份证 (id)
data class ChatMessage(val translatedText: String, val originalText: String, val isMe: Boolean, val voiceId: String, val isTopSpeaker: Boolean = false, val id: String = java.util.UUID.randomUUID().toString())

class ChatAdapter(
    private val messages: MutableList<ChatMessage> = mutableListOf(),
    private val onMessageLongClick: (ChatMessage) -> Unit,
    private val onMessageDoubleTap: (ChatMessage) -> Unit,
    private val onPlayClick: (String, String) -> Unit,
    private val onEditClick: (ChatMessage) -> Unit // 👈 新增直达编辑的回调
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
    // 👇 新增：通过唯一 ID 瞬间定位并无痕刷新气泡 (不会导致整个列表闪烁)
    fun updateMessageById(targetId: String, newTranslated: String, newOriginal: String) {
        val index = messages.indexOfFirst { it.id == targetId }
        if (index != -1) {
            messages[index] = messages[index].copy(translatedText = newTranslated, originalText = newOriginal)
            notifyItemChanged(index)
        }
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
            // 👇 新增：适当放宽行距，并在底部加入安全留白，防止缩小时被系统意外裁剪
            setLineSpacing(6f, 1.15f)
            setPadding(0, 0, 0, 10)
        }

        val btnPlay = TextView(context).apply {
            text = "🔊"
            textSize = 22f
            setPadding(20, 0, 0, 0)
        }

        // 🌟 原文区 (改造成水平布局，原文占满左侧，右侧预留给小笔，与上方喇叭完美对齐)
        val bottomRowLayout = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = 12 // 👇 新增：用物理间距把“原文区”稍微往下推一点，告别拥挤
            }
        }

        val tvOrig = TextView(context).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            // 👇 修改：增加行距，底部同样留出安全区，保护英文 p/g/y 等下沉笔画
            setLineSpacing(2f, 1.05f) // 👈 收紧行距：额外加2个像素，倍数降为1.05
            setPadding(0, 10, 0, 10)
        }

        val btnEdit = TextView(context).apply {
            text = "✏️"
            textSize = 22f // 👈 和喇叭字号保持绝对一致
            setPadding(20, 10, 0, 0) // 👈 间距和喇叭保持绝对一致
        }

        topRowLayout.addView(tvTrans)
        topRowLayout.addView(btnPlay)

        bottomRowLayout.addView(tvOrig)
        bottomRowLayout.addView(btnEdit)

        bubbleBox.addView(topRowLayout)
        bubbleBox.addView(bottomRowLayout) // 👈 挂载整行
        container.addView(bubbleBox)

        return ChatViewHolder(container, bubbleBox, tvTrans, btnPlay, tvOrig, topRowLayout, btnEdit, bottomRowLayout)
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
            holder.topRowLayout.gravity = Gravity.CENTER_VERTICAL
            holder.bottomRowLayout.gravity = Gravity.CENTER_VERTICAL // 👈 新增：原文行也居中
        } else {
            holder.topRowLayout.gravity = Gravity.TOP
            holder.bottomRowLayout.gravity = Gravity.TOP // 👈 新增：原文行也贴顶
        }

        // 👇 🌟 核心 UX 优化：只有属于自己的绿色气泡才显示小笔，对方的灰色气泡隐藏小笔且不占位置
        if (msg.isMe) {
            holder.btnEdit.visibility = View.VISIBLE
        } else {
            holder.btnEdit.visibility = View.GONE
        }
        // 👇 新增：绑定小笔图标的点击事件
        holder.btnEdit.setOnClickListener {
            onEditClick(msg)
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
        val topRowLayout: LinearLayout,
        val btnEdit: TextView,             // 👈 新增
        val bottomRowLayout: LinearLayout  // 👈 新增
    ) : RecyclerView.ViewHolder(container) {
        // 🛡️ 修复：将双击时间戳绑定到实体 ViewHolder 上，免疫滑动复用导致的重置
        var lastClickTime: Long = 0
    }
}