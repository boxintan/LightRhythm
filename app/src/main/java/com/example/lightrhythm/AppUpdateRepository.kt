package com.example.lightrhythm

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.pm.PackageInfoCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.ByteArrayOutputStream
import java.net.URI
import javax.net.ssl.HttpsURLConnection

internal class AppUpdateRepository(private val context: Context) {
    suspend fun check(): AppUpdateMetadata = withContext(Dispatchers.IO) {
        val endpoint = readJson(BuildConfig.UPDATE_METADATA_URL)
        // A custom HTTPS endpoint can serve the manifest directly. GitHub uses a
        // release asset tied to one immutable release, avoiding mixed versions.
        val manifest = if (endpoint.has("assets")) {
            val assets = endpoint.getJSONArray("assets")
            val asset = (0 until assets.length()).map { assets.getJSONObject(it) }
                .firstOrNull { it.optString("name") == "update.json" }
                ?: error("当前发布尚未提供 App 内更新信息，请稍后重试")
            readJson(asset.getString("browser_download_url"))
        } else endpoint
        AppUpdateMetadata(
            manifest.getString("applicationId"), manifest.getLong("versionCode"),
            manifest.getString("versionName"), manifest.getString("apkUrl"),
            manifest.getString("sha256"), manifest.getLong("sizeBytes"),
            manifest.getInt("minSdk"), manifest.optString("notes").take(2000),
        )
    }

    suspend fun download(metadata: AppUpdateMetadata, progress: (Int) -> Unit): File =
        withContext(Dispatchers.IO) {
            val folder = File(context.cacheDir, "updates").apply { mkdirs() }
            folder.listFiles()?.forEach { it.delete() }
            val partial = File(folder, "LightRhythm-${metadata.versionCode}.apk.part")
            val complete = File(folder, "LightRhythm-${metadata.versionCode}.apk")
            val coroutine = currentCoroutineContext()
            try {
                val connection = open(metadata.apkUrl)
                try {
                    val length = connection.contentLengthLong
                    require(length == -1L || length == metadata.sizeBytes) { "下载文件大小与更新信息不符" }
                    connection.inputStream.use { input ->
                        partial.outputStream().use { output ->
                            copyVerifiedUpdate(input, output, metadata) {
                                coroutine.ensureActive()
                                progress(it)
                            }
                        }
                    }
                } finally { connection.disconnect() }
                coroutine.ensureActive()
                check(partial.renameTo(complete)) { "无法保存安装包，请检查可用空间" }
                try { verifyArchive(complete, metadata) }
                catch (error: Exception) { complete.delete(); throw error }
                complete
            } finally { partial.delete() }
        }

    @Suppress("DEPRECATION")
    internal fun verifyArchive(file: File, metadata: AppUpdateMetadata) {
        val manager = context.packageManager
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val archive = manager.getPackageArchiveInfo(file.absolutePath, flags)
            ?: error("下载文件不是有效的安装包")
        require(archive.packageName == context.packageName) { "安装包应用名称不匹配，已停止更新" }
        require(PackageInfoCompat.getLongVersionCode(archive) == metadata.versionCode) { "安装包版本不匹配，已停止更新" }
        val installed = manager.getPackageInfo(context.packageName, flags)
        fun signatures(info: PackageInfo): Set<String> =
            (if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else info.signatures)
                ?.map { it.toCharsString() }?.toSet().orEmpty()
        val expected = signatures(installed)
        require(expected.isNotEmpty() && signatures(archive) == expected) {
            "安装包签名与当前版本不一致，不能直接更新"
        }
    }

    private fun readJson(url: String): JSONObject {
        val connection = open(url)
        return try {
            val bytes = connection.inputStream.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (output.size() <= 128 * 1024) {
                    val count = input.read(buffer)
                    if (count == -1) break
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
            require(bytes.size <= 128 * 1024) { "更新信息过大" }
            JSONObject(bytes.toString(Charsets.UTF_8))
        } finally { connection.disconnect() }
    }

    private fun open(url: String): HttpsURLConnection {
        var next = requireHttps(url)
        repeat(6) {
            val connection = URI(next).toURL().openConnection() as HttpsURLConnection
            connection.connectTimeout = 15_000
            connection.readTimeout = 20_000
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("User-Agent", "LightRhythm/${BuildConfig.VERSION_NAME}")
            connection.setRequestProperty("Accept", "application/json, application/octet-stream;q=0.9")
            try {
                when (connection.responseCode) {
                    200 -> return connection
                    301, 302, 303, 307, 308 -> {
                        val location = connection.getHeaderField("Location") ?: error("下载地址跳转无效")
                        next = requireHttps(URI(next).resolve(location).toString())
                    }
                    403, 429 -> error("更新服务暂时限制访问，请稍后重试")
                    404 -> error("暂时找不到更新文件，请等待新版本发布完成后重试")
                    else -> error("更新服务暂时不可用，请稍后重试")
                }
            } catch (error: Exception) {
                connection.disconnect()
                throw error
            }
            connection.disconnect()
        }
        error("更新地址跳转过多")
    }
}
