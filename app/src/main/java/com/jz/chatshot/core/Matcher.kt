package com.jz.chatshot.core

enum class Verdict { AUTO, CONFIRM, NONE }

data class Candidate(
    val contact: String,
    val score: Int,
    val nameScore: Int,
    val guardianScore: Int,
    val penalized: Boolean,
    val reasons: List<String>,
    val gradeMatch: Int = 0
)

// candidates 中正常候选在前，已使用的其次，年级不符的最后；组内按分数降序，只含 nameScore>=30 的
data class MatchResult(val verdict: Verdict, val candidates: List<Candidate>)

object Matcher {

    val SYNONYMS: List<Set<String>> = listOf(
        setOf("妈妈", "母亲", "妈"),
        setOf("爸爸", "父亲", "爸"),
        setOf("奶奶", "婆婆"),
        setOf("外婆", "姥姥"),
        setOf("爷爷"),
        setOf("外公", "姥爷")
    )

    private val COMPOUND_SURNAMES = listOf(
        "欧阳", "司马", "上官", "诸葛", "东方", "皇甫", "尉迟",
        "公孙", "慕容", "长孙", "夏侯", "轩辕", "令狐", "宇文"
    )

    private val ALL_TERMS: List<Pair<String, Int>> = SYNONYMS.indices
        .flatMap { index -> SYNONYMS[index].map { it to index } }
        .sortedByDescending { it.first.length }

    private val PHONE_SUFFIX = Regex("""\d{7,}$""")

    private data class ScoredCandidate(val candidate: Candidate, val used: Boolean)

    /** 一年级…九年级、1年级…9年级、初一/初二/初三。识别不了返回 null。 */
    fun gradeNumber(grade: String): Int? {
        val g = grade.filterNot { it.isWhitespace() }
        if (g.isEmpty()) return null
        when {
            g.startsWith("初一") -> return 7
            g.startsWith("初二") -> return 8
            g.startsWith("初三") -> return 9
        }
        val digit = when (g.first()) {
            '一' -> 1
            '二' -> 2
            '三' -> 3
            '四' -> 4
            '五' -> 5
            '六' -> 6
            '七' -> 7
            '八' -> 8
            '九' -> 9
            in '1'..'9' -> g.first().digitToInt()
            else -> return null
        }
        if (!g.substring(1).startsWith("年级")) return null
        return digit
    }

    /** 备注去掉首尾空白后，以 1 位数字 1–9 开头且下一个字符不是数字时返回该数字。 */
    fun contactGrade(contact: String): Int? {
        val text = contact.trim()
        if (text.isEmpty()) return null
        val first = text[0]
        if (first !in '1'..'9') return null
        if (text.length > 1 && text[1].isDigit()) return null
        return first.digitToInt()
    }

    fun searchTerms(name: String): List<String> {
        val n = clean(name)
        if (n.isEmpty()) return emptyList()
        val terms = ArrayList<String>()
        terms.add(n)
        val given = if (COMPOUND_SURNAMES.any { n.startsWith(it) }) {
            if (n.length > 2) n.substring(2) else ""
        } else {
            if (n.length >= 3) n.substring(1) else ""
        }
        if (given.length >= 2 && given != n) terms.add(given)
        if (n.length >= 3) {
            val firstTwo = n.take(2)
            if (firstTwo !in terms) terms.add(firstTwo)
        }
        return terms
    }

    fun score(student: Student, contact: String, otherNames: Collection<String> = emptyList()): Candidate {
        val contactGradeValue = contactGrade(contact)
        val studentGradeValue = gradeNumber(student.grade)
        val gradeMatch = when {
            contactGradeValue == null || studentGradeValue == null -> 0
            contactGradeValue == studentGradeValue -> 1
            else -> -1
        }
        val matchText = matchingText(contact)

        val (nameScore, nameReason) = nameScore(student.name, matchText)
        val (guardianScore, guardianReason) = guardianScore(student.guardian, matchText)
        val reasons = ArrayList<String>()
        reasons.add(nameReason)
        reasons.add(guardianReason)

        val grade = student.grade.trim()
        val textualGrade = grade.isNotEmpty() &&
            (matchText.contains(grade) || matchText.contains(grade.first() + "年"))
        var contextBonus = 0
        if (gradeMatch == 1 || textualGrade) {
            contextBonus = 10
            reasons.add(
                if (gradeMatch == 1) "备注年级一致 +10"
                else "联系人包含年级信息 +10"
            )
        }

        val penaltyReasons = ArrayList<String>()
        if (gradeMatch == -1) {
            penaltyReasons.add(
                "年级不符（备注为 ${contactGradeValue ?: "-"}，学生为 ${student.grade}）"
            )
        }
        val originalPenalty = penalty(student.name, student.guardian, matchText, otherNames)
        if (originalPenalty != null) penaltyReasons.add(originalPenalty)
        reasons.addAll(penaltyReasons)
        val penalized = penaltyReasons.isNotEmpty()

        val total = (nameScore + guardianScore + contextBonus - (if (penalized) 40 else 0))
            .coerceIn(0, 100)
        return Candidate(
            contact = contact,
            score = total,
            nameScore = nameScore,
            guardianScore = guardianScore,
            penalized = penalized,
            reasons = reasons,
            gradeMatch = gradeMatch
        )
    }

    fun match(
        student: Student,
        contacts: List<String>,
        otherNames: Collection<String> = emptyList(),
        remembered: String? = null,
        sameNameExists: Boolean = false,
        usedContacts: Map<String, String> = emptyMap()
    ): MatchResult {
        val seen = HashSet<String>()
        val normalizedUsed = usedContacts.entries.associate { clean(it.key) to it.value }
        val candidates = ArrayList<ScoredCandidate>()
        for (contact in contacts) {
            val key = clean(contact)
            if (key.isEmpty() || !seen.add(key)) continue
            val candidate = score(student, contact, otherNames)
            if (candidate.nameScore < 30) continue
            val usedBy = normalizedUsed[key]
            val withUsedReason = if (usedBy != null) {
                candidate.copy(reasons = candidate.reasons + "已用于 $usedBy")
            } else {
                candidate
            }
            candidates.add(ScoredCandidate(withUsedReason, usedBy != null))
        }
        if (candidates.isEmpty()) return MatchResult(Verdict.NONE, emptyList())

        val sorted = candidates.sortedWith(
            compareBy<ScoredCandidate> { groupOf(it) }
                .thenByDescending { it.candidate.score }
        )
        val sortedCandidates = sorted.map { it.candidate }

        // remembered 规则保持不变，最优先；已用于其他学生的联系人仍不能 AUTO。
        if (!remembered.isNullOrBlank()) {
            val rememberedKey = clean(remembered)
            val index = sorted.indexOfFirst { clean(it.candidate.contact) == rememberedKey }
            if (index >= 0) {
                val pick = sorted[index]
                if (pick.used) return MatchResult(Verdict.CONFIRM, sortedCandidates)
                val reordered = ArrayList(sorted)
                reordered.removeAt(index)
                reordered.add(0, pick)
                return MatchResult(Verdict.AUTO, reordered.map { it.candidate })
            }
        }

        val first = sorted[0]
        val firstCandidate = first.candidate
        val firstIsEffective = firstCandidate.gradeMatch != -1 && !first.used
        val firstOk = firstIsEffective &&
            firstCandidate.nameScore == 60 &&
            !firstCandidate.penalized
        val effective = sorted.filter { it.candidate.gradeMatch != -1 && !it.used }
        val secondEffective = effective.getOrNull(1)

        val ruleA = firstOk &&
            firstCandidate.score >= 85 &&
            firstCandidate.guardianScore >= 25 &&
            (secondEffective == null ||
                firstCandidate.score - secondEffective.candidate.score >= 15)

        val uniqueGradeOne = effective.filter {
            it.candidate.gradeMatch == 1 && it.candidate.nameScore == 60
        }
        val ruleB = firstOk &&
            firstCandidate.gradeMatch == 1 &&
            firstCandidate.guardianScore >= 10 &&
            uniqueGradeOne.size == 1 &&
            effective.none {
                it !== first && it.candidate.guardianScore > firstCandidate.guardianScore
            }

        val sameNameOk = !sameNameExists || firstCandidate.gradeMatch == 1
        val auto = sameNameOk && (ruleA || ruleB)
        return MatchResult(if (auto) Verdict.AUTO else Verdict.CONFIRM, sortedCandidates)
    }

    private fun groupOf(scored: ScoredCandidate): Int = when {
        scored.candidate.gradeMatch == -1 -> 2
        scored.used -> 1
        else -> 0
    }

    private fun matchingText(contact: String): String {
        var text = contact.filterNot { it.isWhitespace() }
        if (contactGrade(contact) != null && text.isNotEmpty()) {
            text = text.substring(1)
        }
        return text.replace(PHONE_SUFFIX, "")
    }

    private fun nameScore(rawName: String, contact: String): Pair<Int, String> {
        val name = clean(rawName)
        if (name.isEmpty()) return 0 to "姓名为空 +0"
        if (contact.contains(name)) return 60 to "包含完整姓名 +60"
        val given = givenName(name)
        if (given.length >= 2 && contact.contains(given)) {
            return 40 to "包含名字（去姓） +40"
        }
        if (name.length >= 3) {
            for (start in 0..(contact.length - name.length)) {
                var diff = 0
                for (i in name.indices) {
                    if (contact[start + i] != name[i]) diff++
                    if (diff > 1) break
                }
                if (diff == 1) return 30 to "姓名有 1 个字不同 +30"
            }
        }
        return 0 to "姓名不匹配 +0"
    }

    private fun guardianScore(rawGuardian: String, contact: String): Pair<Int, String> {
        val guardian = clean(rawGuardian)
        if (guardian.isEmpty()) return 15 to "名单未填称呼，基础分 +15"
        if (contact.contains(guardian)) return 30 to "称呼一致（$guardian） +30"

        val matchedGroups = matchedGroups(contact)
        val ownGroup = SYNONYMS.indexOfFirst { guardian in it }
        if (ownGroup >= 0 && ownGroup in matchedGroups) {
            return 25 to "称呼同义（$guardian） +25"
        }
        if (matchedGroups.isNotEmpty()) {
            return 0 to "联系人称呼与 $guardian 不符 +0"
        }
        return 10 to "联系人未出现称呼词，基础分 +10"
    }

    private fun matchedGroups(contact: String): Set<Int> {
        var work = contact
        val groups = HashSet<Int>()
        for ((term, group) in ALL_TERMS) {
            if (work.contains(term)) {
                groups.add(group)
                work = work.replace(term, " ".repeat(term.length))
            }
        }
        return groups
    }

    private fun penalty(
        rawName: String,
        rawGuardian: String,
        contact: String,
        otherNames: Collection<String>
    ): String? {
        val name = clean(rawName)
        if (name.isEmpty()) return null
        val guardian = clean(rawGuardian)

        val other = otherNames.asSequence()
            .map { clean(it) }
            .filter { it.isNotEmpty() && it != name }
            .distinct()
            .firstOrNull { contact.contains(it) }
        if (other != null) return "联系人还包含其他学生姓名（$other），合计扣 40 分"

        var start = contact.indexOf(name)
        while (start >= 0) {
            val next = start + name.length
            if (next < contact.length && isHan(contact[next])) {
                val rest = contact.substring(next)
                val allowed = rest.startsWith("家长") ||
                    rest.startsWith("同学") ||
                    rest.startsWith("的") ||
                    (guardian.isNotEmpty() && rest.startsWith(guardian)) ||
                    ALL_TERMS.any { rest.startsWith(it.first) }
                if (!allowed) return "姓名后紧跟汉字（${rest.take(4)}），疑似更长姓名，合计扣 40 分"
            }
            start = contact.indexOf(name, start + 1)
        }
        return null
    }

    private fun givenName(name: String): String {
        if (COMPOUND_SURNAMES.any { name.startsWith(it) }) {
            return if (name.length > 2) name.substring(2) else ""
        }
        return if (name.length >= 3) name.substring(1) else ""
    }

    private fun isHan(ch: Char): Boolean = ch.code in 0x4E00..0x9FFF

    private fun clean(s: String): String = s.filterNot { it.isWhitespace() }
}
