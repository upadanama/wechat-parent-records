package com.jz.chatshot.ui

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import com.jz.chatshot.ShotService
import com.jz.chatshot.Store
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun CaptureTab(resumeTick: Int) {
    val context = LocalContext.current
    var accessibilityReady by remember { mutableStateOf(ShotService.instance != null) }
    var overlayReady by remember { mutableStateOf(Settings.canDrawOverlays(context)) }
    var batteryReady by remember { mutableStateOf(isIgnoringBattery(context)) }
    var previewPath by remember { mutableStateOf<String?>(null) }
    var deletePath by remember { mutableStateOf<String?>(null) }
    var diagnostics by remember { mutableStateOf<List<File>>(emptyList()) }

    LaunchedEffect(resumeTick) {
        accessibilityReady = ShotService.instance != null
        overlayReady = Settings.canDrawOverlays(context)
        batteryReady = isIgnoringBattery(context)
        diagnostics = diagnosticFiles()
    }

    val student = Store.students.getOrNull(Store.current)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Text("采集准备", style = MaterialTheme.typography.titleMedium)
        PermissionRow(
            title = "辅助功能服务",
            ready = accessibilityReady,
            onClick = { openSettings(context, Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        )
        PermissionRow(
            title = "悬浮窗",
            ready = overlayReady,
            onClick = {
                openSettings(
                    context,
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:${context.packageName}")
                    )
                )
            }
        )
        PermissionRow(
            title = "忽略电池优化",
            ready = batteryReady,
            onClick = {
                openSettings(context, Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }
        )

        Spacer(modifier = Modifier.height(12.dp))
        Button(
            onClick = {
                val service = ShotService.instance
                if (service == null) {
                    Toast.makeText(context, "请先开启辅助功能服务", Toast.LENGTH_SHORT).show()
                } else {
                    service.showBar()
                    val intent = context.packageManager.getLaunchIntentForPackage("com.tencent.mm")
                    if (intent != null) {
                        context.startActivity(intent)
                    } else {
                        Toast.makeText(context, "未安装微信", Toast.LENGTH_SHORT).show()
                    }
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("开始采集")
        }

        Spacer(modifier = Modifier.height(20.dp))
        if (student == null) {
            Text("请先在「名单」里选择一名学生。", color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            Text("当前学生", style = MaterialTheme.typography.titleMedium)
            val guardian = if (student.guardian.isEmpty()) "" else "（${student.guardian}）"
            Text("${student.grade}  ${student.name}$guardian", fontWeight = FontWeight.SemiBold)
            Text(
                "状态：${statusText(student.status)} | 联系人：${student.contact.ifBlank { "-" }} | 截图 ${student.shots.size}",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text("截图", fontWeight = FontWeight.SemiBold)
            if (student.shots.isEmpty()) {
                Text(
                    "还没有截图。",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )
            } else {
                student.shots.chunked(3).forEach { rowPaths ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        rowPaths.forEach { path ->
                            ShotThumbnail(
                                path = path,
                                onClick = { previewPath = path },
                                onLongClick = { deletePath = path }
                            )
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(20.dp))
        Text("诊断文件：${diagnostics.size} 个", fontSize = 13.sp)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                onClick = { shareDiagnostics(context, diagnostics) },
                enabled = diagnostics.isNotEmpty(),
                modifier = Modifier.weight(1f)
            ) {
                Text("分享诊断文件", fontSize = 13.sp)
            }
            OutlinedButton(
                onClick = {
                    diagnostics.forEach { it.delete() }
                    diagnostics = diagnosticFiles()
                    Toast.makeText(context, "已删除诊断文件", Toast.LENGTH_SHORT).show()
                },
                enabled = diagnostics.isNotEmpty(),
                modifier = Modifier.weight(1f)
            ) {
                Text("删除诊断文件", fontSize = 13.sp)
            }
        }
    }

    val preview = previewPath
    if (preview != null) {
        FullImageDialog(path = preview, onDismiss = { previewPath = null })
    }

    val target = deletePath
    if (target != null && student != null) {
        AlertDialog(
            onDismissRequest = { deletePath = null },
            title = { Text("删除截图") },
            text = { Text("确定删除这张截图吗？") },
            confirmButton = {
                TextButton(
                    onClick = {
                        File(Store.shotsRoot(), target).delete()
                        Store.update(student.id) { it.copy(shots = it.shots - target) }
                        deletePath = null
                    }
                ) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { deletePath = null }) { Text("取消") }
            }
        )
    }
}

@Composable
private fun PermissionRow(title: String, ready: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !ready, onClick = onClick)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, modifier = Modifier.weight(1f))
        Text(
            if (ready) "已就绪" else "未开启，点击设置",
            fontSize = 13.sp,
            color = if (ready) Color(0xFF2E7D32) else MaterialTheme.colorScheme.error
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ShotThumbnail(path: String, onClick: () -> Unit, onLongClick: () -> Unit) {
    val file = remember(path) { File(Store.shotsRoot(), path) }
    val bitmap by produceState<Bitmap?>(null, path, file.lastModified()) {
        value = withContext(Dispatchers.IO) { decodeSampled(file, 300) }
    }
    Box(
        modifier = Modifier
            .size(84.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(Color(0xFFE0E0E0))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        contentAlignment = Alignment.Center
    ) {
        val image = bitmap
        if (image != null) {
            Image(
                bitmap = image.asImageBitmap(),
                contentDescription = path,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Text("无图", fontSize = 11.sp)
        }
    }
}

@Composable
private fun FullImageDialog(path: String, onDismiss: () -> Unit) {
    val file = remember(path) { File(Store.shotsRoot(), path) }
    val bitmap by produceState<Bitmap?>(null, path) {
        value = withContext(Dispatchers.IO) { BitmapFactory.decodeFile(file.path) }
    }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .clickable(onClick = onDismiss),
            contentAlignment = Alignment.Center
        ) {
            val image = bitmap
            if (image != null) {
                Image(
                    bitmap = image.asImageBitmap(),
                    contentDescription = "截图预览",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                CircularProgressIndicator()
            }
        }
    }
}

private fun diagnosticFiles(): List<File> {
    val dir = Store.zipDir()
    if (!dir.isDirectory) return emptyList()
    return dir.listFiles { file ->
        file.isFile && file.name.startsWith("诊断_") && file.name.endsWith(".txt")
    }?.sortedBy { it.name } ?: emptyList()
}

private fun shareDiagnostics(context: Context, files: List<File>) {
    if (files.isEmpty()) {
        Toast.makeText(context, "没有诊断文件", Toast.LENGTH_SHORT).show()
        return
    }
    val uris = files.map { FileProvider.getUriForFile(context, "com.jz.chatshot.files", it) }
    val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
        type = "text/plain"
        putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    val clip = ClipData.newRawUri("诊断文件", uris.first())
    uris.drop(1).forEach { clip.addItem(ClipData.Item(it)) }
    intent.clipData = clip
    runCatching {
        context.startActivity(Intent.createChooser(intent, "分享诊断文件"))
    }.onFailure {
        Toast.makeText(context, "没有可用的分享应用", Toast.LENGTH_SHORT).show()
    }
}

private fun decodeSampled(file: File, target: Int): Bitmap? {
    if (!file.exists()) return null
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.path, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    while (bounds.outWidth / sample > target * 2 || bounds.outHeight / sample > target * 2) {
        sample *= 2
    }
    return BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
}

private fun isIgnoringBattery(context: Context): Boolean {
    val manager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return false
    return manager.isIgnoringBatteryOptimizations(context.packageName)
}

private fun openSettings(context: Context, intent: Intent) {
    runCatching { context.startActivity(intent) }
        .onFailure { Toast.makeText(context, "无法打开系统设置页", Toast.LENGTH_SHORT).show() }
}
