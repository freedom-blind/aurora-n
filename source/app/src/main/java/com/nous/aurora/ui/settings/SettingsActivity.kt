package com.nous.aurora.ui.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import android.widget.ArrayAdapter as SpinnerAdapter
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.nous.aurora.R
import com.nous.aurora.util.EasterEgg
import com.nous.aurora.util.LocaleManager

class SettingsActivity : AppCompatActivity() {

    private val prefs by lazy { getSharedPreferences("settings", Context.MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val ctx = LocaleManager.applyLocale(this)
        setContentView(R.layout.activity_settings)

        setupSmartChapter()
        setupFocusScheme()
        setupDefaultEncoding()
        setupCheckBoxes()

        val scanFolder = prefs.getString("scan_folder", "/storage/emulated/0/Books") ?: "/storage/emulated/0/Books"
        findViewById<EditText>(R.id.et_scan_folder).setText(scanFolder)

        // 版本信息
        val versionInfo = try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: "1.0.2a3c"
        } catch (_: Exception) { "1.0.2a3c" }
        findViewById<TextView>(R.id.tv_version).text = "版本号：$versionInfo"

        // 用户协议按钮
        findViewById<Button>(R.id.btn_show_eula).setOnClickListener { showEula() }

        // QQ群按钮
        findViewById<Button>(R.id.btn_qq_group).setOnClickListener {
            openQqGroup()
        }

        findViewById<Button>(R.id.btn_save_settings).setOnClickListener {
            prefs.edit()
                .putString("scan_folder", findViewById<EditText>(R.id.et_scan_folder).text.toString().trim())
                .apply()
            EasterEgg.onSettingChanged()
            finish()
        }
    }

    private fun openQqGroup() {
        try {
            val intent = Intent(Intent.ACTION_VIEW)
            intent.data = Uri.parse(
                "http://qm.qq.com/cgi-bin/qm/qr?_wv=1027&k=4kDSM0v6KF738_9I1lzivgBW2csV91P7" +
                "&authKey=7MWYwT5C3VPKxWdwXb4Xw%2FejpXCyiJ%2Fg32ZnExhCXX5bg9xgx3wjoo41SXNyUc8F" +
                "&noverify=0&group_code=1048703601"
            )
            startActivity(intent)
        } catch (e: Exception) {
            // 如果没有浏览器，复制群号到剪贴板
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("QQ群号", "1048703601"))
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("加入QQ群")
                .setMessage("无法打开链接，QQ群号已复制到剪贴板：1048703601")
                .setPositiveButton("确定", null)
                .show()
        }
    }

    private fun showEula() {
        val eulaText = """
Aurora 用户协议

1. 开源许可
Aurora 是一款开源软件。任何人都可以自由获取、分发和修改本应用。
本应用及其一切衍生产品必须保持开源，并以相同的许可协议发布。

2. 隐私说明
Aurora 完全离线运行，不向外发送任何用户数据。
软件仅在本地保存必要的文档信息（如书名、作者等），
以及用户的阅读位置、书签和批注。
这些数据仅存储在本地设备上，不会上传至任何服务器。

3. 权限说明
Aurora 会请求"所有文件访问权限"，
以便您可以浏览存储空间并导入电子书文件。
该权限仅用于读取您指定的电子书文件，不会收集或上传任何信息。

4. 免责声明
本应用按"原样"提供，不提供任何明示或暗示的保证。
使用本应用所产生的任何风险由用户自行承担。
        """.trimIndent()

        AlertDialog.Builder(this)
            .setTitle("用户协议")
            .setMessage(eulaText)
            .setPositiveButton("关闭", null)
            .setCancelable(true)
            .show()
    }

    private fun setupSmartChapter() {
        findViewById<CheckBox>(R.id.cb_smart_chapter).isChecked =
            prefs.getBoolean("smart_chapter", true)
        findViewById<CheckBox>(R.id.cb_smart_chapter).setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean("smart_chapter", checked).apply()
        }
    }

    private fun setupFocusScheme() {
        val spinner = findViewById<Spinner>(R.id.spinner_focus_scheme)
        val items = arrayOf("段落", "句子")
        val adapter = SpinnerAdapter(this, android.R.layout.simple_spinner_item, items)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinner.adapter = adapter
        spinner.setSelection(if (prefs.getString("focus_scheme", "paragraph") == "sentence") 1 else 0)
        spinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                prefs.edit().putString("focus_scheme", if (position == 1) "sentence" else "paragraph").apply()
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }
    }

    private fun setupDefaultEncoding() {
        val spinner = findViewById<Spinner>(R.id.spinner_default_encoding)
        val encodings = arrayOf("UTF-8", "GBK", "GB2312", "GB18030", "BIG5", "Shift_JIS", "EUC-JP", "ISO-8859-1")
        val current = prefs.getString("default_encoding", "UTF-8") ?: "UTF-8"
        val adapter = SpinnerAdapter(this, android.R.layout.simple_spinner_item, encodings)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinner.adapter = adapter
        spinner.setSelection(encodings.indexOfFirst { it == current }.coerceAtLeast(0))
        spinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                prefs.edit().putString("default_encoding", encodings[position]).apply()
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }
    }

    private fun setupCheckBoxes() {
        findViewById<CheckBox>(R.id.cb_announce_paragraph).isChecked =
            prefs.getString("reader_prefs", null)?.contains("announce_paragraph") == true
        findViewById<CheckBox>(R.id.cb_announce_paragraph).setOnCheckedChangeListener { _, checked ->
            val readerPrefs = getSharedPreferences("reader_prefs", Context.MODE_PRIVATE)
            readerPrefs.edit().putBoolean("announce_paragraph", checked).apply()
        }
        findViewById<CheckBox>(R.id.cb_auto_import).isChecked =
            prefs.getBoolean("auto_import", false)
        findViewById<CheckBox>(R.id.cb_auto_scroll).isChecked =
            prefs.getBoolean("auto_scroll", false)
    }
}