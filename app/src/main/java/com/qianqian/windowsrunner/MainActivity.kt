package com.qianqian.windowsrunner

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity() {
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        render()
        handleIncomingIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun render() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(32, 48, 32, 48)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        val title = TextView(this).apply {
            text = "Windows 软件运行器"
            textSize = 25f
            gravity = Gravity.CENTER
        }
        val intro = TextView(this).apply {
            text = "从 QQ、微信或文件管理器分享 Windows 安装包到这里，或手动选择文件。"
            textSize = 16f
            gravity = Gravity.CENTER
            setPadding(0, 24, 0, 24)
        }
        val choose = Button(this).apply {
            text = "导入电脑软件安装包"
            setOnClickListener { openFilePicker() }
        }
        status = TextView(this).apply {
            text = "尚未导入文件\n\n注意：当前为早期导入界面，尚未集成 Windows 兼容运行时。"
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(0, 24, 0, 0)
        }
        root.addView(title)
        root.addView(intro)
        root.addView(choose)
        root.addView(status)
        setContentView(root)
    }

    private fun openFilePicker() {
        val picker = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        startActivityForResult(picker, REQUEST_PICK)
    }

    @Deprecated("Handled for broad Android compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_PICK && resultCode == RESULT_OK) {
            data?.data?.let(::showSelectedFile)
        }
    }

    private fun handleIncomingIntent(intent: Intent?) {
        if (intent == null) return
        val uri = when (intent.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND -> intent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri
            else -> null
        }
        if (uri != null) showSelectedFile(uri)
    }

    private fun showSelectedFile(uri: Uri) {
        val name = queryDisplayName(uri) ?: uri.lastPathSegment ?: "未知文件"
        status.text = "已接收文件：\n$name\n\n下一步：检查安装包格式、架构与依赖。\n当前版本不会执行文件，也尚不能运行 Windows 程序。"
    }

    private fun queryDisplayName(uri: Uri): String? {
        return try {
            contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0) else null
                }
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        private const val REQUEST_PICK = 1001
    }
}
