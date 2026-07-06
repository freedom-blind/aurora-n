package com.nous.aurora.ui.debug

import android.content.Intent
import android.os.Bundle
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.io.PrintWriter
import java.io.StringWriter

class ErrorActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_ERROR = "error_trace"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val trace = intent.getStringExtra(EXTRA_ERROR) ?: "未知错误"

        val scroll = ScrollView(this)
        val text = TextView(this).apply {
            this.text = """
                Aurora 遇到错误并已退出
                
                错误详情：
                $trace
                
                请尝试重启应用。如果问题持续，请联系开发者。
            """.trimIndent()
            textSize = 13f
            setPadding(32, 32, 32, 32)
            setTextIsSelectable(true)
        }
        scroll.addView(text)
        setContentView(scroll)
    }
}
