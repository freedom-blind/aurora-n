package com.nous.aurora.util

import android.content.Context
import android.view.View
import android.view.accessibility.AccessibilityEvent

object AccessibilityUtil {

    fun announce(context: Context, message: String) {
        val root = (context as? android.app.Activity)?.window?.decorView ?: return
        root.announceForAccessibility(message)
    }

    fun setAccessibilityFocusable(view: View, description: String, isHeading: Boolean = false) {
        view.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        view.contentDescription = description
        view.isFocusable = true
        view.isClickable = true
        view.isFocusableInTouchMode = true
        if (isHeading) {
            view.isAccessibilityHeading = true
        }
    }

    fun setClickable(view: View, action: () -> Unit) {
        view.isClickable = true
        view.isFocusable = true
        view.isFocusableInTouchMode = true
        view.setOnClickListener { action() }
    }

    fun requestFocus(view: View) {
        view.isFocusableInTouchMode = true
        view.requestFocus()
        view.sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_FOCUSED)
        view.sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED)
    }
}
