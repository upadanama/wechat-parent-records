package com.jz.chatshot

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.jz.chatshot.core.Status
import com.jz.chatshot.core.Student
import java.io.File
import java.time.LocalDate
import org.json.JSONArray
import org.json.JSONObject

/** 进程内单例，所有状态集中在这里，并持久化到 filesDir/state.json。 */
object Store {

    val students = mutableStateListOf<Student>()
    var current by mutableStateOf(0)
    var recipient by mutableStateOf("")
    var subject by mutableStateOf("")
    var body by mutableStateOf("")
    val memory = mutableStateMapOf<String, String>()

    private var appContext: Context? = null

    fun memKey(s: Student): String = "${s.grade}|${s.name}|${s.guardian}"

    fun init(ctx: Context) {
        if (appContext != null) return
        appContext = ctx.applicationContext
        load()
    }

    fun save() {
        val ctx = appContext ?: return
        val json = JSONObject()
        json.put("current", current)
        json.put("recipient", recipient)
        json.put("subject", subject)
        json.put("body", body)

        val array = JSONArray()
        for (s in students) {
            val o = JSONObject()
            o.put("id", s.id)
            o.put("grade", s.grade)
            o.put("name", s.name)
            o.put("guardian", s.guardian)
            o.put("dateFrom", s.dateFrom?.toString() ?: JSONObject.NULL)
            o.put("dateTo", s.dateTo?.toString() ?: JSONObject.NULL)
            o.put("time", s.time)
            o.put("status", s.status.name)
            o.put("contact", s.contact)
            o.put("note", s.note)
            val shots = JSONArray()
            s.shots.forEach { shots.put(it) }
            o.put("shots", shots)
            array.put(o)
        }
        json.put("students", array)

        val mem = JSONObject()
        for ((key, value) in memory) mem.put(key, value)
        json.put("memory", mem)

        runCatching { File(ctx.filesDir, "state.json").writeText(json.toString()) }
    }

    fun setRoster(list: List<Student>) {
        students.clear()
        students.addAll(list)
        current = 0
        save()
    }

    fun update(id: Int, f: (Student) -> Student) {
        val index = students.indexOfFirst { it.id == id }
        if (index < 0) return
        students[index] = f(students[index])
        save()
    }

    fun shotsRoot(): File {
        val ctx = checkNotNull(appContext) { "Store 未初始化" }
        return File(ctx.filesDir, "shots")
    }

    fun zipDir(): File {
        val ctx = checkNotNull(appContext) { "Store 未初始化" }
        return File(ctx.cacheDir, "zips")
    }

    /** 删除截图、压缩包和名单，保留 memory 与邮件设置。 */
    fun clearAll() {
        shotsRoot().deleteRecursively()
        zipDir().deleteRecursively()
        students.clear()
        current = 0
        save()
    }

    private fun load() {
        val ctx = appContext ?: return
        val file = File(ctx.filesDir, "state.json")
        if (!file.exists()) return
        try {
            val json = JSONObject(file.readText())
            val array = json.optJSONArray("students") ?: JSONArray()
            val loaded = ArrayList<Student>()
            for (i in 0 until array.length()) {
                val o = array.getJSONObject(i)
                val status = runCatching {
                    Status.valueOf(o.optString("status", Status.PENDING.name))
                }.getOrDefault(Status.PENDING)
                val shots = ArrayList<String>()
                val shotsArray = o.optJSONArray("shots")
                if (shotsArray != null) {
                    for (j in 0 until shotsArray.length()) shots.add(shotsArray.getString(j))
                }
                loaded.add(
                    Student(
                        id = o.optInt("id", i),
                        grade = o.optString("grade", ""),
                        name = o.optString("name", ""),
                        guardian = o.optString("guardian", ""),
                        dateFrom = jsonDate(o, "dateFrom"),
                        dateTo = jsonDate(o, "dateTo"),
                        time = o.optString("time", ""),
                        status = status,
                        contact = o.optString("contact", ""),
                        note = o.optString("note", ""),
                        shots = shots
                    )
                )
            }
            students.clear()
            students.addAll(loaded)
            current = if (loaded.isEmpty()) 0 else json.optInt("current", 0).coerceIn(0, loaded.lastIndex)
            recipient = json.optString("recipient", "")
            subject = json.optString("subject", "")
            body = json.optString("body", "")
            memory.clear()
            val mem = json.optJSONObject("memory")
            if (mem != null) {
                val keys = mem.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    memory[key] = mem.optString(key, "")
                }
            }
        } catch (_: Exception) {
            // 状态文件损坏时从空状态开始，不阻塞应用启动。
        }
    }

    private fun jsonDate(o: JSONObject, key: String): LocalDate? {
        if (!o.has(key) || o.isNull(key)) return null
        return runCatching { LocalDate.parse(o.getString(key)) }.getOrNull()
    }
}
