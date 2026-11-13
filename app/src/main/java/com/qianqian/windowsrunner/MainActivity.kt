package com.qianqian.windowsrunner

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

class MainActivity : Activity() {
    private lateinit var root: LinearLayout
    private lateinit var status: TextView
    private lateinit var importsContainer: LinearLayout
    private var selectedAvatarUri: String? = null

    private val preferences by lazy {
        getSharedPreferences("windows_runner_local_data", MODE_PRIVATE)
    }

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
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(28), dp(20), dp(24))
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        val title = TextView(this).apply {
            text = "Windows 软件运行器"
            textSize = 25f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(12))
        }
        val intro = TextView(this).apply {
            text = "从 QQ、微信或文件管理器导入电脑软件、AE 插件和压缩包。导入后先保存在本机，不会自动执行未知文件。"
            textSize = 15f
            gravity = Gravity.CENTER
            setPadding(dp(4), 0, dp(4), dp(18))
        }

        val choose = Button(this).apply {
            text = "导入电脑软件 / AE 插件"
            setOnClickListener { openFilePicker() }
        }
        val account = Button(this).apply {
            text = "本地账号注册 / 资料"
            setOnClickListener { showLocalRegistrationDialog() }
        }

        status = TextView(this).apply {
            textSize = 14f
            setPadding(dp(4), dp(14), dp(4), dp(14))
            gravity = Gravity.CENTER
        }

        val sectionTitle = TextView(this).apply {
            text = "本机已导入文件"
            textSize = 18f
            setPadding(0, dp(12), 0, dp(8))
        }
        importsContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        val scrollContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(title)
            addView(intro)
            addView(choose, matchWidth())
            addView(account, matchWidth())
            addView(status)
            addView(sectionTitle)
            addView(importsContainer)
        }
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(scrollContent)
        }
        setContentView(scroll)
        refreshScreen()
    }

    private fun openFilePicker() {
        val picker = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        startActivityForResult(picker, REQUEST_PICK_FILE)
    }

    private fun openAvatarPicker() {
        val picker = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "image/*"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        startActivityForResult(picker, REQUEST_PICK_AVATAR)
    }

    @Deprecated("Handled for Android compatibility with document providers")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK || data?.data == null) return

        val uri = data.data!!
        if (requestCode == REQUEST_PICK_FILE) {
            tryPersistPermission(uri, data)
            importUri(uri)
        } else if (requestCode == REQUEST_PICK_AVATAR) {
            tryPersistPermission(uri, data)
            selectedAvatarUri = uri.toString()
            Toast.makeText(this, "头像已选择，保存资料时生效", Toast.LENGTH_SHORT).show()
        }
    }

    private fun tryPersistPermission(uri: Uri, result: Intent) {
        val granted = result.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION
        if (granted == 0) return
        try {
            contentResolver.takePersistableUriPermission(uri, granted)
        } catch (_: SecurityException) {
            // Some share providers grant temporary access only; files are copied immediately.
        } catch (_: IllegalArgumentException) {
            // The provider did not offer persistable permissions.
        }
    }

    private fun handleIncomingIntent(incoming: Intent?) {
        if (incoming == null) return
        when (incoming.action) {
            Intent.ACTION_VIEW -> incoming.data?.let(::importUri)
            Intent.ACTION_SEND -> {
                val uri = getSingleSharedUri(incoming) ?: incoming.clipData?.getItemAt(0)?.uri
                uri?.let(::importUri)
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                val clip = incoming.clipData
                if (clip != null) {
                    for (index in 0 until clip.itemCount) {
                        clip.getItemAt(index).uri?.let(::importUri)
                    }
                } else {
                    @Suppress("DEPRECATION")
                    val uris = incoming.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)
                    uris?.forEach(::importUri)
                }
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun getSingleSharedUri(incoming: Intent): Uri? {
        return if (Build.VERSION.SDK_INT >= 33) {
            incoming.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            incoming.getParcelableExtra(Intent.EXTRA_STREAM)
        }
    }

    private fun importUri(uri: Uri) {
        val metadata = queryMetadata(uri)
        val originalName = metadata.first ?: uri.lastPathSegment ?: "imported-file"
        val safeName = originalName
            .replace(Regex("[^A-Za-z0-9._\\-\\u4e00-\\u9fff ]"), "_")
            .take(120)
            .ifBlank { "imported-file" }

        val importDir = File(filesDir, "imports").apply { mkdirs() }
        val destination = File(importDir, "${System.currentTimeMillis()}_$safeName")
        val temporary = File(importDir, "${destination.name}.part")

        try {
            val digest = MessageDigest.getInstance("SHA-256")
            val input = contentResolver.openInputStream(uri)
                ?: throw IllegalStateException("无法读取所选文件")
            input.use { source ->
                temporary.outputStream().buffered().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val count = source.read(buffer)
                        if (count < 0) break
                        digest.update(buffer, 0, count)
                        output.write(buffer, 0, count)
                    }
                }
            }
            if (!temporary.renameTo(destination)) {
                throw IllegalStateException("无法保存导入文件")
            }

            val sha256 = digest.digest().joinToString("") { byte -> "%02x".format(byte) }
            val actualSize = destination.length()
            saveImportRecord(
                JSONObject()
                    .put("name", originalName)
                    .put("storedName", destination.name)
                    .put("size", actualSize)
                    .put("sha256", sha256)
                    .put("importedAt", System.currentTimeMillis())
            )

            status.text = "已安全保存到本机：$originalName\n大小：${formatSize(actualSize)}\nSHA-256：$sha256\n\n文件尚未执行。下一步应先检查格式、架构、依赖和兼容性。"
            Toast.makeText(this, "文件已导入并保存到本机", Toast.LENGTH_SHORT).show()
            refreshImportList()
        } catch (error: Exception) {
            temporary.delete()
            destination.delete()
            status.text = "导入失败：${error.message ?: "无法读取该文件"}"
            Toast.makeText(this, "导入失败，请检查文件访问权限", Toast.LENGTH_LONG).show()
        }
    }

    private fun queryMetadata(uri: Uri): Pair<String?, Long?> {
        return try {
            contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                null,
                null,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                    val name = if (nameIndex >= 0) cursor.getString(nameIndex) else null
                    val size = if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) cursor.getLong(sizeIndex) else null
                    name to size
                } else null to null
            } ?: (null to null)
        } catch (_: Exception) {
            null to null
        }
    }

    private fun saveImportRecord(record: JSONObject) {
        val old = readImportRecords()
        val updated = JSONArray()
        updated.put(record)
        for (index in 0 until old.length()) {
            if (updated.length() >= MAX_IMPORT_RECORDS) break
            updated.put(old.getJSONObject(index))
        }
        preferences.edit().putString(KEY_IMPORTS, updated.toString()).apply()
    }

    private fun readImportRecords(): JSONArray {
        return try {
            JSONArray(preferences.getString(KEY_IMPORTS, "[]") ?: "[]")
        } catch (_: Exception) {
            JSONArray()
        }
    }

    private fun refreshScreen() {
        val account = readLocalAccount()
        status.text = if (account == null) {
            "尚未注册本地账号。没有网络时也可以先保存本地账号资料。\n\n注意：当前版本负责安全导入和本机保存；尚未集成 Windows 兼容运行时，不能直接运行 Windows 软件。"
        } else {
            "本地账号：${account.optString("nickname")}\n状态：本机已保存 · 待 HTTPS 云端同步\n\n当前版本不会把“本地保存”显示成“云端注册成功”。\n\n尚未集成 Windows 兼容运行时，不能直接运行 Windows 软件。"
        }
        refreshImportList()
    }

    private fun refreshImportList() {
        importsContainer.removeAllViews()
        val records = readImportRecords()
        if (records.length() == 0) {
            importsContainer.addView(TextView(this).apply {
                text = "暂无文件。你可以从 QQ / 微信的“分享”菜单发送文件到本应用。"
                textSize = 14f
                setPadding(0, dp(8), 0, dp(8))
            })
            return
        }

        for (index in 0 until records.length()) {
            val record = records.optJSONObject(index) ?: continue
            val name = record.optString("name", "未知文件")
            val details = "$name\n${formatSize(record.optLong("size", 0L))} · SHA-256 ${record.optString("sha256").take(16)}…\n仅保存于本机，未执行"
            val item = TextView(this).apply {
                text = details
                textSize = 14f
                setPadding(dp(12), dp(12), dp(12), dp(12))
                setBackgroundColor(0xFFF1F3F5.toInt())
            }
            val params = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) }
            importsContainer.addView(item, params)
        }
    }

    private fun showLocalRegistrationDialog() {
        val existing = readLocalAccount()
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(8), dp(22), 0)
        }

        val nickname = EditText(this).apply {
            hint = "昵称"
            setSingleLine(true)
            setText(existing?.optString("nickname", "") ?: "")
        }
        val qq = EditText(this).apply {
            hint = "QQ 号（与微信号二选一即可）"
            setSingleLine(true)
            setText(existing?.optString("qq", "") ?: "")
            inputType = android.text.InputType.TYPE_CLASS_TEXT
        }
        val wechat = EditText(this).apply {
            hint = "微信号（与 QQ 号二选一即可）"
            setSingleLine(true)
            setText(existing?.optString("wechat", "") ?: "")
        }
        val avatarButton = Button(this).apply {
            text = if (existing?.optString("avatarUri").isNullOrBlank()) "从手机相册选择头像（可选）" else "重新选择头像（可选）"
            setOnClickListener { openAvatarPicker() }
        }
        val note = TextView(this).apply {
            text = "离线时先保存在本机，并标记为“待云端同步”。当前仓库尚未配置 HTTPS 账号服务器；在服务器接通前不会假称同步成功。"
            textSize = 13f
            setPadding(0, dp(8), 0, dp(8))
        }
        form.addView(nickname)
        form.addView(qq)
        form.addView(wechat)
        form.addView(avatarButton)
        form.addView(note)

        val dialog = AlertDialog.Builder(this)
            .setTitle(if (existing == null) "本地注册" else "本地账号资料")
            .setView(form)
            .setNegativeButton("取消", null)
            .setPositiveButton("保存到本机", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val nameValue = nickname.text.toString().trim()
                val qqValue = qq.text.toString().trim()
                val wechatValue = wechat.text.toString().trim()
                if (nameValue.isBlank()) {
                    nickname.error = "请填写昵称"
                    return@setOnClickListener
                }
                if (qqValue.isBlank() && wechatValue.isBlank()) {
                    qq.error = "QQ 或微信至少填写一项"
                    wechat.error = "QQ 或微信至少填写一项"
                    return@setOnClickListener
                }

                val avatar = selectedAvatarUri
                    ?: existing?.optString("avatarUri")?.takeIf { it.isNotBlank() }
                    ?: ""
                val profile = JSONObject()
                    .put("localId", existing?.optString("localId") ?: java.util.UUID.randomUUID().toString())
                    .put("nickname", nameValue)
                    .put("qq", qqValue)
                    .put("wechat", wechatValue)
                    .put("avatarUri", avatar)
                    .put("syncState", "PENDING")
                    .put("savedAt", System.currentTimeMillis())

                preferences.edit().putString(KEY_ACCOUNT, profile.toString()).apply()
                selectedAvatarUri = null
                dialog.dismiss()
                refreshScreen()
                Toast.makeText(this, "本地资料已保存，等待云端同步", Toast.LENGTH_LONG).show()
            }
        }
        dialog.show()
    }

    private fun readLocalAccount(): JSONObject? {
        return try {
            preferences.getString(KEY_ACCOUNT, null)?.let(::JSONObject)
        } catch (_: Exception) {
            null
        }
    }

    private fun formatSize(bytes: Long): String {
        if (bytes < 1024) return "${bytes} B"
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0)
        if (bytes < 1024L * 1024L * 1024L) return String.format("%.1f MB", bytes / (1024.0 * 1024.0))
        return String.format("%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0))
    }

    private fun matchWidth() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT
    )

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val REQUEST_PICK_FILE = 1001
        private const val REQUEST_PICK_AVATAR = 1002
        private const val KEY_IMPORTS = "import_records"
        private const val KEY_ACCOUNT = "local_account"
        private const val MAX_IMPORT_RECORDS = 50
    }
}
