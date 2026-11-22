package com.example.lightrhythm

import android.app.Application
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.io.File
import java.io.IOException

internal enum class UpdatePhase { IDLE, CHECKING, CURRENT, AVAILABLE, DOWNLOADING, READY, ERROR }

internal data class AppUpdateState(
    val phase: UpdatePhase = UpdatePhase.IDLE,
    val metadata: AppUpdateMetadata? = null,
    val percent: Int = 0,
    val apk: File? = null,
    val message: String? = null,
) {
    val busy get() = phase == UpdatePhase.CHECKING || phase == UpdatePhase.DOWNLOADING
}

internal class AppUpdateViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = AppUpdateRepository(application)
    private val mutableState = MutableStateFlow(AppUpdateState())
    val state = mutableState.asStateFlow()
    private var job: Job? = null

    fun check() {
        if (mutableState.value.busy) return
        job = viewModelScope.launch {
            mutableState.value = AppUpdateState(UpdatePhase.CHECKING)
            try {
                val metadata = withTimeout(60_000) { repository.check() }
                require(metadata.minSdk <= Build.VERSION.SDK_INT) { "新版需要更高的 Android 系统，当前手机暂不能安装" }
                mutableState.value = AppUpdateState(
                    if (metadata.isNewerThan(BuildConfig.VERSION_CODE.toLong())) UpdatePhase.AVAILABLE else UpdatePhase.CURRENT,
                    metadata,
                )
            } catch (_: TimeoutCancellationException) {
                mutableState.value = AppUpdateState(UpdatePhase.ERROR, message = "检查超时，请检查网络后重试。当前下载源为 GitHub，部分网络需要 VPN。")
            } catch (cancelled: CancellationException) {
                mutableState.value = AppUpdateState(UpdatePhase.ERROR, message = "检查已中断，请重试")
                throw cancelled
            } catch (error: Exception) { fail(error) }
        }
    }

    fun download() {
        val current = mutableState.value
        val metadata = current.metadata ?: return
        if (current.busy) return
        job = viewModelScope.launch {
            mutableState.value = current.copy(phase = UpdatePhase.DOWNLOADING, percent = 0, message = null, apk = null)
            try {
                val file = withTimeout(10 * 60_000) {
                    repository.download(metadata) { progress ->
                        mutableState.update { it.copy(percent = progress) }
                    }
                }
                mutableState.value = AppUpdateState(UpdatePhase.READY, metadata, 100, file)
            } catch (_: TimeoutCancellationException) {
                mutableState.value = AppUpdateState(UpdatePhase.AVAILABLE, metadata, message = "下载超时，可重新下载")
            } catch (cancelled: CancellationException) {
                mutableState.value = AppUpdateState(UpdatePhase.AVAILABLE, metadata, message = "下载已取消，可重新下载")
                throw cancelled
            } catch (error: Exception) { fail(error) }
        }
    }

    fun cancelDownload() { job?.cancel() }

    fun installationMessage(message: String) {
        mutableState.update { it.copy(message = message) }
    }

    fun invalidateDownload() {
        mutableState.value = AppUpdateState(UpdatePhase.ERROR, message = "安装包缓存已失效，请重新检查并下载")
    }

    private fun fail(error: Exception) {
        val message = when (error) {
            is IOException -> "无法连接更新服务，请检查网络后重试。当前下载源为 GitHub，部分网络需要 VPN。"
            is IllegalArgumentException, is IllegalStateException -> error.message ?: "更新失败，请重试"
            else -> "更新信息暂时不可用，请稍后重试"
        }
        mutableState.value = AppUpdateState(UpdatePhase.ERROR, message = message)
    }
}
