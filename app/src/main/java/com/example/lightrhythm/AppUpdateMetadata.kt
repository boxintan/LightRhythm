package com.example.lightrhythm

import java.io.InputStream
import java.io.OutputStream
import java.net.URI
import java.security.MessageDigest

internal const val UPDATABLE_APPLICATION_ID = "com.example.lightrhythm"
internal const val MAX_UPDATE_BYTES = 200L * 1024 * 1024

internal fun requireHttps(url: String): String {
    val uri = URI(url)
    require(uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null) {
        "更新地址必须使用 HTTPS"
    }
    return url
}

internal data class AppUpdateMetadata(
    val applicationId: String,
    val versionCode: Long,
    val versionName: String,
    val apkUrl: String,
    val sha256: String,
    val sizeBytes: Long,
    val minSdk: Int,
    val notes: String,
) {
    init {
        require(applicationId == UPDATABLE_APPLICATION_ID) { "更新包不属于光律" }
        require(versionCode in 1..2_100_000_000L && versionName.isNotBlank()) { "更新版本信息无效" }
        requireHttps(apkUrl)
        require(sha256.matches(Regex("[a-fA-F0-9]{64}"))) { "更新包校验信息无效" }
        require(sizeBytes in 1..MAX_UPDATE_BYTES && minSdk >= 26) { "更新包大小或系统要求无效" }
    }

    fun isNewerThan(installedVersion: Long) = versionCode > installedVersion
}

/** Verify the entire transfer before callers make the file available to the installer. */
internal fun copyVerifiedUpdate(
    input: InputStream,
    output: OutputStream,
    metadata: AppUpdateMetadata,
    onProgress: (Int) -> Unit,
) {
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(32 * 1024)
    var total = 0L
    var lastProgress = -1
    while (true) {
        val count = input.read(buffer)
        if (count == -1) break
        total += count
        require(total <= metadata.sizeBytes) { "下载文件大小与更新信息不符" }
        output.write(buffer, 0, count)
        digest.update(buffer, 0, count)
        val progress = (total * 100 / metadata.sizeBytes).toInt()
        if (progress != lastProgress) {
            onProgress(progress)
            lastProgress = progress
        }
    }
    require(total == metadata.sizeBytes) { "安装包下载不完整，请重试" }
    val actual = digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    require(actual.equals(metadata.sha256, ignoreCase = true)) { "安装包校验失败，请重新下载" }
}
