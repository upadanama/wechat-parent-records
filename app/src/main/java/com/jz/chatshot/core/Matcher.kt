package com.jz.chatshot.core

enum class Verdict { AUTO, CONFIRM, NONE }

data class Candidate(
    val contact: String,
    val score: Int,
    val nameScore: Int,
    val guardianScore: Int,
    val penalized: Boolean,
    val reasons: List<String>
)

// candidates 中未使用的按分数降序，已用于其他学生的排在后面；只含 nameScore>=30 的
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

    private data class ScoredCandidate(val candidate: Candidate, val used: Boolean)

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
        return terms
    }

    fun score(student: Student, contact: String, otherNames: Collection<String> = emptyList()): Candidate {
        val cleaned = clean(contact)
        val (nameScore, nameReason) = nameScore(student.name, cleaned)
        val (guardianScore, guardianReason) = guardianScore(student.guardian, cleaned)
        val reasons = ArrayList<String>()
        reasons.add(nameReason)
        reasons.add(guardianReason)

        var contextBonus = 0
        val grade = student.grade.trim()
        if (grade.isNotEmpty() &&
            (cleaned.contains(grade) || cleaned.contains(grade.first() + "年"))
        ) {
            contextBonus = 10
            reasons.add("联系人包含年级信息 +10")
        }

        val penalty = penalty(student.name, student.guardian, cleaned, otherNames)
        if (penalty != null) reasons.add(penalty)

        val total = (nameScore + guardianScore + contextBonus - if (penalty == null) 0 else 40)
            .coerceIn(0, 100)
        return Candidate(
            contact = contact,
            score = total,
            nameScore = nameScore,
            guardianScore = guardianScore,
            penalized = penalty != null,
            reasons = reasons
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
            compareBy<ScoredCandidate> { if (it.used) 1 else 0 }
                .thenByDescending { it.candidate.score }
        )
        val sortedCandidates = sorted.map { it.candidate }

        if (!remembered.isNullOrBlank()) {
            val rememberedKey = clean(remembered)
            val index = sorted.indexOfFirst { clean(it.candidate.contact) == rememberedKey }
            if (index >= 0) {
                val pick = sorted[index]
                // 已用于其他学生的联系人即使命中 remembered 也不能 AUTO。
                if (pick.used) return MatchResult(Verdict.CONFIRM, sortedCandidates)
                val reordered = ArrayList(sorted)
                reordered.removeAt(index)
                reordered.add(0, pick)
                return MatchResult(Verdict.AUTO, reordered.map { it.candidate })
            }
        }

        val first = sorted[0]
        val second = sorted.getOrNull(1)
        val auto = !sameNameExists &&
            !first.used &&
            first.candidate.score >= 85 &&
            first.candidate.nameScore == 60 &&
            first.candidate.guardianScore >= 25 &&
            !first.candidate.penalized &&
            (second == null || first.candidate.score - second.candidate.score >= 15)
        return MatchResult(if (auto) Verdict.AUTO else Verdict.CONFIRM, sortedCandidates)
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
