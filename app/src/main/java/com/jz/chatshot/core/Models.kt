package com.jz.chatshot.core

import java.time.LocalDate

enum class Status { PENDING, MATCHED, SHOT, NOT_FOUND, SKIPPED }

data class Student(
    val id: Int,              // 从 0 开始的序号
    val grade: String,        // 规范化后的年级，如 "一年级"
    val name: String,
    val guardian: String,     // 监护人称呼，可为空串
    val dateFrom: LocalDate?, // 已含偏差后的起止（闭区间），无日期则为 null
    val dateTo: LocalDate?,
    val time: String = "",    // 沟通时间 HH:mm，没有则为空串
    val status: Status = Status.PENDING,
    val contact: String = "", // 已确认的微信联系人显示名
    val note: String = "",    // 异常原因等
    val shots: List<String> = emptyList() // 截图文件相对路径
)

data class RosterIssue(val row: Int, val message: String) // row 为 Excel 行号（从 1 起）

data class RosterResult(val students: List<Student>, val issues: List<RosterIssue>)
