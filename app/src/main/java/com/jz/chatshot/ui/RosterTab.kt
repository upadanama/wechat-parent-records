package com.jz.chatshot.ui

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import com.jz.chatshot.Store
import com.jz.chatshot.core.Roster
import com.jz.chatshot.core.Sheets
import com.jz.chatshot.core.Status
import com.jz.chatshot.core.Student
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun RosterTab() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pendingRows by remember { mutableStateOf<List<List<String>>?>(null) }
    var menuOpenId by remember { mutableStateOf<Int?>(null) }
    var onlyUnfinished by remember { mutableStateOf(false) }
    val shown = Store.students.withIndex().filter { entry ->
        !onlyUnfinished ||
            entry.value.status == Status.PENDING ||
            entry.value.status == Status.MATCHED
    }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val outcome = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openInputStream(uri)?.use { Sheets.read(it) }
                        ?: throw IllegalArgumentException("无法读取文件")
                }
            }
            outcome.onSuccess { pendingRows = it }
                .onFailure {
                    Toast.makeText(
                        context,
                        "读取失败：${it.message ?: "未知错误"}",
                        Toast.LENGTH_LONG
                    ).show()
                }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Stat("总数", Store.students.size)
            Stat(
                "未完成",
                Store.students.count {
                    it.status == Status.PENDING || it.status == Status.MATCHED
                }
            )
            Stat("已匹配", Store.students.count { it.status == Status.MATCHED })
            Stat("已截图", Store.students.count { it.status == Status.SHOT })
            Stat("未找到", Store.students.count { it.status == Status.NOT_FOUND })
            Stat("待处理", Store.students.count { it.status == Status.PENDING })
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("只看未完成", modifier = Modifier.weight(1f), fontSize = 13.sp)
            Switch(checked = onlyUnfinished, onCheckedChange = { onlyUnfinished = it })
        }

        Button(
            onClick = {
                launcher.launch(
                    arrayOf(
                        "application/vnd.ms-excel",
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                        "application/octet-stream"
                    )
                )
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
        ) {
            Text("导入 Excel")
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp)
        ) {
            if (Store.students.isEmpty()) {
                item {
                    Text(
                        "还没有名单，请先导入 Excel。",
                        modifier = Modifier.padding(16.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else if (shown.isEmpty()) {
                item {
                    Text(
                        "没有未完成的学生。",
                        modifier = Modifier.padding(16.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            itemsIndexed(shown, key = { _, entry -> entry.value.id }) { _, entry ->
                StudentRow(
                    index = entry.index,
                    student = entry.value,
                    menuOpen = menuOpenId == entry.value.id,
                    onOpenMenu = { menuOpenId = entry.value.id },
                    onCloseMenu = { if (menuOpenId == entry.value.id) menuOpenId = null }
                )
            }
        }
    }

    val rows = pendingRows
    if (rows != null) {
        ImportDialog(
            rows = rows,
            onDismiss = { pendingRows = null },
            onConfirm = { students ->
                Store.setRoster(students)
                pendingRows = null
                Toast.makeText(context, "已导入 ${students.size} 名学生", Toast.LENGTH_SHORT).show()
            }
        )
    }
}

@Composable
private fun Stat(label: String, count: Int) {
    Text("$label $count", fontSize = 13.sp)
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun StudentRow(
    index: Int,
    student: Student,
    menuOpen: Boolean,
    onOpenMenu: () -> Unit,
    onCloseMenu: () -> Unit
) {
    val selected = index == Store.current
    val background = if (selected) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surface
    }
    Box {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(background)
                .combinedClickable(
                    onClick = { Store.current = index },
                    onLongClick = onOpenMenu
                )
                .padding(horizontal = 16.dp, vertical = 10.dp)
        ) {
            val guardianText = if (student.guardian.isEmpty()) "" else "（${student.guardian}）"
            val sameName = Store.students.any { it.id != student.id && it.name == student.name }
            val nameText = if (sameName) "${student.name}（同名）" else student.name
            Text(
                "${student.grade}  $nameText$guardianText",
                fontWeight = FontWeight.SemiBold
            )
            val dateText = buildString {
                append(dateRangeText(student))
                val time = hourLabel(student.time)
                if (time.isNotEmpty()) append("  ").append(time)
            }
            Text(
                "$dateText | ${statusText(student.status)} | 联系人：${student.contact.ifBlank { "-" }} | 截图 ${student.shots.size}",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = onCloseMenu) {
            DropdownMenuItem(
                text = { Text("标记未找到") },
                onClick = {
                    Store.update(student.id) { it.copy(status = Status.NOT_FOUND) }
                    onCloseMenu()
                }
            )
            DropdownMenuItem(
                text = { Text("标记跳过") },
                onClick = {
                    Store.update(student.id) { it.copy(status = Status.SKIPPED) }
                    onCloseMenu()
                }
            )
            DropdownMenuItem(
                text = { Text("重置为待处理") },
                onClick = {
                    Store.update(student.id) { it.copy(status = Status.PENDING) }
                    onCloseMenu()
                }
            )
        }
    }
}

@Composable
private fun ImportDialog(
    rows: List<List<String>>,
    onDismiss: () -> Unit,
    onConfirm: (List<Student>) -> Unit
) {
    var fromText by remember { mutableStateOf("") }
    var toText by remember { mutableStateOf("") }
    val from = parseDateInput(fromText)
    val to = parseDateInput(toText)
    val result = remember(rows, from, to) { Roster.parse(rows, from, to) }
    val fromInvalid = fromText.isNotBlank() && from == null
    val toInvalid = toText.isNotBlank() && to == null

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("导入预览") },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text("学生数：${result.students.size}", fontWeight = FontWeight.SemiBold)
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = fromText,
                    onValueChange = { fromText = it },
                    label = { Text("默认开始日期") },
                    placeholder = { Text("yyyy-MM-dd，可留空") },
                    singleLine = true,
                    isError = fromInvalid,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = toText,
                    onValueChange = { toText = it },
                    label = { Text("默认结束日期") },
                    placeholder = { Text("yyyy-MM-dd，可留空") },
                    singleLine = true,
                    isError = toInvalid,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                )
                if (fromInvalid || toInvalid) {
                    Text(
                        "日期格式应为 yyyy-MM-dd",
                        color = MaterialTheme.colorScheme.error,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
                if (result.issues.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Text("需要留意的问题：", fontWeight = FontWeight.SemiBold)
                    result.issues.forEach { issue ->
                        val prefix = if (issue.row > 0) "第 ${issue.row} 行：" else ""
                        Text(
                            "$prefix${issue.message}",
                            color = MaterialTheme.colorScheme.error,
                            fontSize = 13.sp,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(result.students) }) { Text("确认导入") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

private fun parseDateInput(text: String): LocalDate? {
    val value = text.trim()
    if (value.isEmpty()) return null
    return runCatching { LocalDate.parse(value) }.getOrNull()
}

internal fun statusText(status: Status): String = when (status) {
    Status.PENDING -> "待处理"
    Status.MATCHED -> "已匹配"
    Status.SHOT -> "已截图"
    Status.NOT_FOUND -> "未找到"
    Status.SKIPPED -> "已跳过"
}

private fun hourLabel(time: String): String {
    val hour = time.substringBefore(':').trim().toIntOrNull() ?: return ""
    return if (hour in 0..23) "${hour}点" else ""
}

private fun dateRangeText(student: Student): String {
    val from = student.dateFrom
    val to = student.dateTo
    return when {
        from != null && to != null && from == to -> from.toString()
        from != null && to != null -> "$from~$to"
        from != null -> from.toString()
        to != null -> to.toString()
        else -> "无日期"
    }
}
