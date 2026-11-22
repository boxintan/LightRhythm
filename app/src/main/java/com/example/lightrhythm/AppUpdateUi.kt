package com.example.lightrhythm

import android.content.ActivityNotFoundException
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.core.net.toUri

@Composable
internal fun AppUpdateBar(model: AppUpdateViewModel) {
    val state by model.state.collectAsState()
    val context = LocalContext.current
    var showDialog by rememberSaveable { mutableStateOf(false) }

    fun launchInstaller() {
        val file = model.state.value.apk
        if (file == null || !file.isFile) {
            model.invalidateDownload()
            return
        }
        try {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", file)
            context.startActivity(Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            })
            model.installationMessage("请在系统安装界面确认更新；若取消，可再次点击安装")
        } catch (_: ActivityNotFoundException) {
            model.installationMessage("当前系统没有可用的安装程序")
        } catch (_: SecurityException) {
            model.installationMessage("系统未允许安装更新，请允许光律安装应用后重试")
        } catch (_: IllegalArgumentException) {
            model.installationMessage("无法打开安装包，请重新下载")
        }
    }

    val installPermission = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (context.packageManager.canRequestPackageInstalls()) launchInstaller()
        else model.installationMessage("尚未允许光律安装应用，可再次点击安装进行授权")
    }

    Surface(tonalElevation = 2.dp) {
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().heightIn(min = 52.dp).padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) {
            Text("版本 ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.labelMedium)
            TextButton(onClick = {
                showDialog = true
                if (!state.busy && state.phase != UpdatePhase.READY) model.check()
            }) { Text(if (state.phase == UpdatePhase.DOWNLOADING) "更新下载中 ${state.percent}%" else "检查更新") }
        }
    }

    if (showDialog) {
        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = { Text("应用更新") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("当前版本：${BuildConfig.VERSION_NAME}")
                    when (state.phase) {
                        UpdatePhase.IDLE, UpdatePhase.CHECKING -> { Text("正在检查新版…"); LinearProgressIndicator(Modifier.fillMaxWidth()) }
                        UpdatePhase.CURRENT -> Text("已是最新版本")
                        UpdatePhase.AVAILABLE, UpdatePhase.DOWNLOADING, UpdatePhase.READY -> {
                            val metadata = state.metadata!!
                            Text("发现新版：${metadata.versionName}")
                            Text("安装包约 ${"%.1f".format(metadata.sizeBytes / 1048576.0)} MB")
                            if (metadata.notes.isNotBlank()) Text(metadata.notes)
                            if (state.phase == UpdatePhase.DOWNLOADING) {
                                LinearProgressIndicator(Modifier.fillMaxWidth())
                                Text(if (state.percent == 100) "正在校验安装包…" else "正在下载 ${state.percent}%")
                            }
                            if (state.phase == UpdatePhase.READY) Text("安装包已校验。点击安装后，由系统确认更新；首次可能需要允许光律安装应用。")
                        }
                        UpdatePhase.ERROR -> Unit
                    }
                    state.message?.let { Text(it) }
                }
            },
            confirmButton = {
                when (state.phase) {
                    UpdatePhase.AVAILABLE -> TextButton(onClick = model::download) { Text("下载新版") }
                    UpdatePhase.READY -> TextButton(onClick = {
                        if (context.packageManager.canRequestPackageInstalls()) launchInstaller()
                        else try {
                            installPermission.launch(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                "package:${context.packageName}".toUri()))
                        } catch (_: ActivityNotFoundException) {
                            model.installationMessage("请在系统设置中允许光律安装应用后重试")
                        }
                    }) { Text("安装更新") }
                    UpdatePhase.ERROR -> TextButton(onClick = model::check) { Text("重试") }
                    else -> TextButton(onClick = { showDialog = false }) { Text("关闭") }
                }
            },
            dismissButton = {
                if (state.phase == UpdatePhase.DOWNLOADING) TextButton(onClick = model::cancelDownload) { Text("取消下载") }
                else if (state.phase == UpdatePhase.AVAILABLE || state.phase == UpdatePhase.READY)
                    TextButton(onClick = { showDialog = false }) { Text("稍后") }
            },
        )
    }
}
