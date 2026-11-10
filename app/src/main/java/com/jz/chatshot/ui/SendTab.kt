package com.jz.chatshot.ui

import android.content.ClipData
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.jz.chatshot.Store
import com.jz.chatshot.core.Archive
import com.jz.chatshot.core.Student
import java.io.File
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun SendTab() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var attachments by remember { mutableStateOf<List<File>>(emptyList()) }
    var generating by remember { mutableStateOf(false) }
    var verified by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        attachments = listAttachments()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Text("邮件内容", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(
            value = Store.recipient,
            onValueChange = {
                Store.recipient = it
                Store.save()
            },
            label = { Text("收件人") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = Store.subject,
            onValueChange = {
                Store.subject = it
                Store.save()
            },
            label = { Text("主题") },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp)
        )
        OutlinedTextField(
            value = Store.body,
            onValueChange = {
                Store.body = it
                Store.save()
            },
            label = { Text("正文") },
            minLines = 3,
            maxLines = 6,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp)
        )

        Spacer(modifier = Modifier.height(20.dp))
        Text("附件", style = MaterialTheme.typography.titleMedium)
        Spacer(modifier = Modifier.height(6.dp))
        Button(
            onClick = {
                generating = true
                val root = Store.shotsRoot()
                val out = Store.zipDir()
                val roster = Store.students.toList()
                scope.launch {
                    val result = withContext(Dispatchers.IO) {
                        runCatching {
                            Archive.zipGrades(root, out)
                            writeCsv(out, roster)
                        }
                    }
                    generating = false
                    result.onFailure {
                        Toast.makeText(
                            context,
                            "生成失败：${it.message ?: "未知错误"}",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                    if (result.isSuccess) {
                        attachments = listAttachments()
                        Toast.makeText(context, "压缩包已生成", Toast.LENGTH_SHORT).show()
                    }
                }
            },
            enabled = !generating,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (generating) "正在生成…" else "生成压缩包")
        }

        val zipFiles = attachments.filter { it.name.endsWith(".zip") }
        if (zipFiles.isEmpty()) {
            Text(
                "尚未生成压缩包。",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp)
            )
        } else {
            zipFiles.forEach { file ->
                val size = file.length()
                val mb = size / 1024.0 / 1024.0
                val tooBig = size > 20L * 1024 * 1024
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(file.name, modifier = Modifier.weight(1f), fontSize = 13.sp)
                    Text(
                        String.format(Locale.ROOT, "%.1f MB", mb),
                        fontSize = 13.sp,
                        color = if (tooBig) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (tooBig) {
                    Text(
                        "超过 20MB，可能无法作为附件发送。",
                        color = MaterialTheme.colorScheme.error,
                        fontSize = 12.sp
                    )
                }
            }
        }
        if (attachments.any { it.name == CSV_NAME }) {
            Text(
                "$CSV_NAME 会一并作为附件。",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp)
            )
        }

        Spacer(modifier = Modifier.height(16.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { verified = !verified },
            verticalAlignment = Alignment.CenterVertically
        ) {
            Checkbox(checked = verified, onCheckedChange = { verified = it })
            Text("我已核对收件人和附件")
        }
        Button(
            onClick = {
                val files = attachments.filter { it.exists() }
                if (files.isEmpty()) {
                    Toast.makeText(context, "请先生成压缩包", Toast.LENGTH_SHORT).show()
                    return@Button
                }
                Store.save()
                val uris = files.map {
                    FileProvider.getUriForFile(context, "com.jz.chatshot.files", it)
                }
                val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                    type = "*/*"
                    if (Store.recipient.isNotBlank()) {
                        putExtra(Intent.EXTRA_EMAIL, arrayOf(Store.recipient.trim()))
                    }
                    putExtra(Intent.EXTRA_SUBJECT, Store.subject)
                    putExtra(Intent.EXTRA_TEXT, Store.body)
                    putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                val clip = ClipData.newRawUri("附件", uris.first())
                uris.drop(1).forEach { clip.addItem(ClipData.Item(it)) }
                intent.clipData = clip
                runCatching {
                    context.startActivity(Intent.createChooser(intent, "发送邮件"))
                }.onFailure {
                    Toast.makeText(context, "没有可用的邮件应用", Toast.LENGTH_SHORT).show()
                }
            },
            enabled = verified,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp)
        ) {
            Text("发送邮件")
        }

        Spacer(modifier = Modifier.height(24.dp))
        OutlinedButton(
            onClick = { confirmClear = true },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("清空全部数据")
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("清空全部数据？") },
            text = { Text("将删除全部名单、截图和压缩包，邮件设置会保留。此操作不可恢复。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        Store.clearAll()
                        attachments = emptyList()
                        verified = false
                        confirmClear = false
                        Toast.makeText(context, "已清空", Toast.LENGTH_SHORT).show()
                    }
                ) { Text("清空") }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text("取消") }
            }
        )
    }
}

private const val CSV_NAME = "结果清单.csv"

private fun listAttachments(): List<File> {
    val dir = Store.zipDir()
    if (!dir.isDirectory) return emptyList()
    val zips = dir.listFiles { file -> file.isFile && file.name.endsWith(".zip") }
        ?.sortedBy { it.name }
        ?: emptyList()
    val csv = File(dir, CSV_NAME).takeIf { it.isFile }
    return if (csv != null) zips + csv else zips
}

private fun writeCsv(dir: File, students: List<Student>): File {
    dir.mkdirs()
    val builder = StringBuilder("\uFEFF")
    builder.append("年级,姓名,称呼,状态,联系人,截图数,备注\r\n")
    for (s in students) {
        val fields = listOf(
            s.grade,
            s.name,
            s.guardian,
            statusText(s.status),
            s.contact,
            s.shots.size.toString(),
            s.note
        )
        builder.append(fields.joinToString(",") { csvEscape(it) })
        builder.append("\r\n")
    }
    val file = File(dir, CSV_NAME)
    file.writeText(builder.toString(), Charsets.UTF_8)
    return file
}

private fun csvEscape(value: String): String {
    return if (value.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) {
        "\"" + value.replace("\"", "\"\"") + "\""
    } else {
        value
    }
}
