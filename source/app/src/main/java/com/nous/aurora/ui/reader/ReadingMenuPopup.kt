package com.nous.aurora.ui.reader

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.PopupWindow
import com.nous.aurora.R

/**
 * 阅读菜单弹窗（PopupWindow） — 点击书籍中任何非正文部分时弹出。
 *
 * 弹窗显示在屏幕底部，大按钮布局，非常醒目。
 * 每个菜单项都设置了 contentDescription，方便读屏软件通过触摸找到。
 *
 * 功能：
 * - 为当前段落添加批注
 * - 为当前段落添加书签
 * - 跳转到批注列表
 * - 跳转到书签列表
 * - 打开电子书目录（支持折叠/展开）
 */
class ReadingMenuPopup(
    private val activity: Activity,
    private val callbacks: Callbacks
) {

    interface Callbacks {
        /** 添加批注 */
        fun onAddAnnotation()
        /** 添加书签 */
        fun onAddBookmark()
        /** 查看批注列表 */
        fun onShowAnnotations()
        /** 查看书签列表 */
        fun onShowBookmarks()
        /** 打开目录 */
        fun onShowToc()
    }

    private var popupWindow: PopupWindow? = null

    /** 在屏幕底部显示弹窗 */
    fun showAtBottom() {
        dismiss()

        val inflater = LayoutInflater.from(activity)
        val contentView = inflater.inflate(R.layout.popup_reading_menu, null)

        // 测量内容高度
        contentView.measure(
            View.MeasureSpec.makeMeasureSpec(
                activity.window.decorView.rootView.width,
                View.MeasureSpec.AT_MOST
            ),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val contentHeight = contentView.measuredHeight

        popupWindow = PopupWindow(
            contentView,
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            true
        ).apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            isOutsideTouchable = true
            isFocusable = true
            // 从底部滑入动画
            animationStyle = android.R.style.Animation_InputMethod
        }

        // 绑定菜单项点击事件
        contentView.findViewById<View>(R.id.menu_add_annotation).setOnClickListener {
            callbacks.onAddAnnotation()
            dismiss()
        }
        contentView.findViewById<View>(R.id.menu_add_bookmark).setOnClickListener {
            callbacks.onAddBookmark()
            dismiss()
        }
        contentView.findViewById<View>(R.id.menu_view_annotations).setOnClickListener {
            callbacks.onShowAnnotations()
            dismiss()
        }
        contentView.findViewById<View>(R.id.menu_view_bookmarks).setOnClickListener {
            callbacks.onShowBookmarks()
            dismiss()
        }
        contentView.findViewById<View>(R.id.menu_toc).setOnClickListener {
            callbacks.onShowToc()
            dismiss()
        }

        // 在屏幕底部显示
        popupWindow?.showAtLocation(
            activity.window.decorView.rootView,
            Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL,
            0, 0
        )
    }

    /** 关闭弹窗 */
    fun dismiss() {
        popupWindow?.dismiss()
        popupWindow = null
    }
}