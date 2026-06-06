package com.example.firstapp // 🌟 检查包名

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.TextView

class CategorizedAdapter(context: Context, private val items: List<String>) :
    ArrayAdapter<String>(context, android.R.layout.simple_spinner_dropdown_item, items) {

    // 🌟 核心：如果是标题，彻底禁止点击！
    override fun isEnabled(position: Int): Boolean {
        return !items[position].startsWith("━━")
    }

    // 🌟 展开下拉列表时的 UI 设计
    override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View {
        val view = super.getDropDownView(position, convertView, parent) as TextView
        val text = items[position]

        if (text.startsWith("━━")) {
            // 标题样式：居中、粗体、绿色
            view.setTextColor(Color.parseColor("#00FF00"))
            view.setTypeface(null, Typeface.BOLD)
            view.textAlignment = View.TEXT_ALIGNMENT_CENTER
            view.setBackgroundColor(Color.parseColor("#1A1A1B"))
            view.setPadding(0, 20, 0, 20)
        } else {
            // 子项样式：缩进、白字、深灰底
            view.setTextColor(Color.WHITE)
            view.setTypeface(null, Typeface.NORMAL)
            view.textAlignment = View.TEXT_ALIGNMENT_VIEW_START
            view.setBackgroundColor(Color.parseColor("#252526"))
            view.setPadding(40, 25, 20, 25)
        }
        return view
    }

    // 🌟 收起时（显示在输入框里）的 UI 设计
    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val view = super.getView(position, convertView, parent) as TextView
        view.setTextColor(Color.parseColor("#00FF00")) // 选中后呈绿色
        view.setTypeface(null, Typeface.BOLD)
        return view
    }
}