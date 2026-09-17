package com.colorfuljuice.bluetoothbattery.utils

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** 更新过程中的失败原因，UI 层负责映射成多语言文案。 */
enum class UpdateError {
    NETWORK,    // 请求 GitHub API 失败
    NO_ASSET,   // 该 release 没有 apk 附件
    DOWNLOAD    // 下载安装包失败
}

sealed interface UpdateStatus {
    data object Idle : UpdateStatus
    data object Checking : UpdateStatus
    data object UpToDate : UpdateStatus

    data class Available(
        val version: String,
        val releaseNotes: String,
        val downloadUrl: String,
        val sizeBytes: Long
    ) : UpdateStatus

    data class Downloading(val percent: Int) : UpdateStatus

    data class Ready(val file: File, val version: String) : UpdateStatus

    data class Failed(val error: UpdateError) : UpdateStatus
}

/**
 * 应用内更新：检查 GitHub Release -> 下载 APK -> 通过 FileProvider 交给系统安装器。
 *
 * 只在用户主动触发时联网，不做后台轮询。
 */
class UpdateManager(private val context: Context) {

    companion object {
        private const val TAG = "UpdateManager"

        /** 与 SettingsScreen 里"源代码"指向同一个仓库。 */
        const val RELEASE_API =
            "https://api.github.com/repos/Kepler16f/ColorfulJuice/releases/latest"

        private const val USER_AGENT = "ColorfulJuice-Android"
        private const val APK_MIME = "application/vnd.android.package-archive"
        private const val MAX_REDIRECTS = 5
        private const val BUFFER_SIZE = 64 * 1024

        /**
         * 语义化版本比较，返回 >0 表示 a 比 b 新。
         * 支持 "v0.1.2"、"0.1.2_dev1" 这类 tag：数字段优先于后缀段，
         * 因此 0.1.2 > 0.1.2_dev1 > 0.1.2_dev0。
         */
        fun compareVersions(a: String, b: String): Int {
            val x = normalize(a)
            val y = normalize(b)
            for (i in 0 until maxOf(x.size, y.size)) {
                val s1 = x.getOrNull(i) ?: "0"
                val s2 = y.getOrNull(i) ?: "0"
                val n1 = s1.toIntOrNull()
                val n2 = s2.toIntOrNull()
                val cmp = when {
                    n1 != null && n2 != null -> n1.compareTo(n2)
                    n1 != null -> 1
                    n2 != null -> -1
                    else -> s1.compareTo(s2)
                }
                if (cmp != 0) return cmp
            }
            return 0
        }

        private fun normalize(version: String): List<String> =
            version.trim()
                .removePrefix("v")
                .removePrefix("V")
                .split(Regex("[^A-Za-z0-9]+"))
                .filter { it.isNotEmpty() }

        /** 系统是否允许本应用发起 APK 安装。 */
        fun canRequestPackageInstalls(context: Context): Boolean =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.packageManager.canRequestPackageInstalls()
            } else {
                true
            }

        /** 跳到"安装未知应用"授权页。 */
        fun openInstallPermissionSettings(context: Context) {
            try {
                val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
                    .setData(Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to open unknown-sources settings", e)
            }
        }
    }

    private val gson = Gson()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val _status = MutableStateFlow<UpdateStatus>(UpdateStatus.Idle)
    val status: StateFlow<UpdateStatus> = _status.asStateFlow()

    private var downloadJob: Job? = null
    private var pendingFile: File? = null

    val currentVersionName: String
        get() = try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "0.0.0"
        } catch (e: Exception) {
            "0.0.0"
        }

    // ---------------------------------------------------------------- 检查更新

    fun checkForUpdates() {
        if (_status.value is UpdateStatus.Checking) return
        _status.value = UpdateStatus.Checking
        scope.launch {
            try {
                val json = httpGetText(RELEASE_API, "application/vnd.github+json")
                val release = gson.fromJson(json, GhRelease::class.java)
                    ?: throw IOException("Empty release payload")
                val tag = release.tagName?.takeIf { it.isNotBlank() }
                    ?: throw IOException("Missing tag_name")

                val remoteVersion = tag.removePrefix("v").removePrefix("V")
                if (compareVersions(remoteVersion, currentVersionName) <= 0) {
                    _status.value = UpdateStatus.UpToDate
                    return@launch
                }

                val asset = release.assets
                    ?.firstOrNull { it.downloadUrl?.endsWith(".apk", ignoreCase = true) == true }
                val url = asset?.downloadUrl
                if (url.isNullOrBlank()) {
                    Log.w(TAG, "Release $tag has no apk asset")
                    _status.value = UpdateStatus.Failed(UpdateError.NO_ASSET)
                    return@launch
                }

                _status.value = UpdateStatus.Available(
                    version = remoteVersion,
                    releaseNotes = release.body.orEmpty(),
                    downloadUrl = url,
                    sizeBytes = asset.size
                )
            } catch (e: Exception) {
                Log.e(TAG, "checkForUpdates failed", e)
                _status.value = UpdateStatus.Failed(UpdateError.NETWORK)
            }
        }
    }

    // ------------------------------------------------------------------ 下载

    fun startDownload() {
        val available = _status.value as? UpdateStatus.Available ?: return
        if (downloadJob?.isActive == true) return

        downloadJob = scope.launch {
            var connection: HttpURLConnection? = null
            try {
                val dir = apkDir()
                if (!dir.exists() && !dir.mkdirs()) throw IOException("Cannot create ${dir.path}")
                // 清掉上一次残留的安装包
                val target = File(dir, "ColorfulJuice-${available.version}.apk")
                dir.listFiles()?.forEach { if (it.name.endsWith(".apk") && it != target) it.delete() }

                connection = openConnection(available.downloadUrl)
                val total = connection.contentLengthLong
                    .takeIf { it > 0 } ?: available.sizeBytes

                var lastPercent = -1
                connection.inputStream.use { input ->
                    FileOutputStream(target).use { output ->
                        val buffer = ByteArray(BUFFER_SIZE)
                        var downloaded = 0L
                        while (true) {
                            val read = input.read(buffer)
                            if (read <= 0) break
                            output.write(buffer, 0, read)
                            downloaded += read
                            if (total > 0) {
                                val percent = ((downloaded * 100) / total).toInt().coerceIn(0, 100)
                                if (percent != lastPercent) {
                                    lastPercent = percent
                                    _status.value = UpdateStatus.Downloading(percent)
                                }
                            }
                        }
                        output.flush()
                    }
                }

                if (!target.exists() || target.length() == 0L) {
                    throw IOException("Downloaded file is empty")
                }

                pendingFile = target
                _status.value = UpdateStatus.Ready(target, available.version)
            } catch (e: Exception) {
                Log.e(TAG, "download failed", e)
                pendingFile = null
                _status.value = UpdateStatus.Failed(UpdateError.DOWNLOAD)
            } finally {
                try {
                    connection?.disconnect()
                } catch (_: Exception) {
                }
            }
        }
    }

    fun cancelDownload() {
        downloadJob?.cancel()
        downloadJob = null
        pendingFile = null
        _status.value = UpdateStatus.Idle
    }

    // ------------------------------------------------------------------ 安装

    /**
     * 拉起系统安装器。返回 false 表示没装成——要么没有已下载的文件，
     * 要么缺"安装未知应用"权限（此时会自动跳到授权页）。
     */
    fun installPending(): Boolean {
        val file = pendingFile ?: (_status.value as? UpdateStatus.Ready)?.file
        if (file == null || !file.exists()) {
            Log.w(TAG, "installPending: no downloaded apk")
            return false
        }

        if (!canRequestPackageInstalls(context)) {
            openInstallPermissionSettings(context)
            return false
        }

        return try {
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, APK_MIME)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            Log.e(TAG, "installPending failed", e)
            false
        }
    }

    fun reset() {
        if (downloadJob?.isActive == true) return
        _status.value = UpdateStatus.Idle
    }

    // --------------------------------------------------------------- 内部工具

    /**
     * APK 存放目录。优先用应用专属外部目录（无需存储权限），
     * 外部存储不可用时退回内部 files 目录。
     */
    private fun apkDir(): File =
        context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?: File(context.filesDir, "updates")

    private fun httpGetText(url: String, accept: String): String {
        val connection = openConnection(url, accept)
        return try {
            connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            try {
                connection.disconnect()
            } catch (_: Exception) {
            }
        }
    }

    /** 打开连接并手动跟随重定向（GitHub Release 会 302 到 objects.githubusercontent.com）。 */
    private fun openConnection(
        urlString: String,
        accept: String = "application/octet-stream"
    ): HttpURLConnection {
        var current = URL(urlString)
        var redirects = 0
        while (true) {
            val connection = (current.openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 30_000
                instanceFollowRedirects = false
                setRequestProperty("User-Agent", USER_AGENT)
                setRequestProperty("Accept", accept)
            }
            when (val code = connection.responseCode) {
                200 -> return connection
                in 300..399 -> {
                    val location = connection.getHeaderField("Location")
                    connection.disconnect()
                    if (location.isNullOrBlank() || redirects++ >= MAX_REDIRECTS) {
                        throw IOException("Too many redirects")
                    }
                    current = URL(current, location)
                }
                else -> {
                    connection.disconnect()
                    throw IOException("HTTP $code for $urlString")
                }
            }
        }
    }

    // ------------------------------------------------------- GitHub API 模型

    private data class GhRelease(
        @SerializedName("tag_name") val tagName: String?,
        @SerializedName("name") val name: String?,
        @SerializedName("body") val body: String?,
        @SerializedName("html_url") val htmlUrl: String?,
        val assets: List<GhAsset>?
    )

    private data class GhAsset(
        val name: String?,
        @SerializedName("browser_download_url") val downloadUrl: String?,
        val size: Long = 0
    )
}
