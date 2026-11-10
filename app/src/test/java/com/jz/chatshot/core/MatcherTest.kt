package com.jz.chatshot.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MatcherTest {

    private fun student(name: String, guardian: String = "", grade: String = ""): Student =
        Student(0, grade, name, guardian, null, null)

    @Test
    fun completeMatchIsAuto() {
        val result = Matcher.match(student("张小明", "妈妈"), listOf("张小明妈妈"))
        assertEquals(Verdict.AUTO, result.verdict)
        assertEquals(1, result.candidates.size)
        val candidate = result.candidates[0]
        assertEquals(90, candidate.score)
        assertEquals(60, candidate.nameScore)
        assertEquals(30, candidate.guardianScore)
        assertFalse(candidate.penalized)
    }

    @Test
    fun longerNameIsNotAuto() {
        val result = Matcher.match(student("张三", "妈妈"), listOf("张三丰妈妈"))
        assertEquals(Verdict.CONFIRM, result.verdict)
        val candidate = result.candidates[0]
        assertTrue(candidate.penalized)
        assertEquals(50, candidate.score)
    }

    @Test
    fun mismatchedGuardianNeedsConfirm() {
        val result = Matcher.match(student("张小明", "妈妈"), listOf("张小明爸爸"))
        assertEquals(Verdict.CONFIRM, result.verdict)
        assertEquals(0, result.candidates[0].guardianScore)
        assertEquals(60, result.candidates[0].score)
    }

    @Test
    fun grandmotherAndMotherInLawAreSynonyms() {
        val result = Matcher.match(student("李四", "奶奶"), listOf("李四婆婆"))
        assertEquals(25, result.candidates[0].guardianScore)
        assertEquals(85, result.candidates[0].score)
        assertEquals(Verdict.AUTO, result.verdict)
    }

    @Test
    fun maternalGrandmotherIsNotPaternalGrandmother() {
        val result = Matcher.match(student("张小明", "婆婆"), listOf("张小明外婆"))
        assertEquals(Verdict.CONFIRM, result.verdict)
        assertEquals(0, result.candidates[0].guardianScore)
    }

    @Test
    fun typoOnlyScoresThirtyAndNeedsConfirm() {
        val result = Matcher.match(student("张小明", "妈妈"), listOf("张晓明妈妈"))
        assertEquals(Verdict.CONFIRM, result.verdict)
        assertEquals(30, result.candidates[0].nameScore)
        assertEquals(60, result.candidates[0].score)
    }

    @Test
    fun closeCandidatesNeedConfirm() {
        val result = Matcher.match(
            student("张小明", "妈妈"),
            listOf("张小明妈妈", "张小明母亲")
        )
        assertEquals(Verdict.CONFIRM, result.verdict)
        assertEquals(2, result.candidates.size)
        assertEquals("张小明妈妈", result.candidates[0].contact)
        assertEquals(90, result.candidates[0].score)
        assertEquals(85, result.candidates[1].score)
    }

    @Test
    fun rememberedContactWinsAuto() {
        val result = Matcher.match(
            student("张小明", "妈妈"),
            listOf("张小明妈妈", "张小明母亲"),
            remembered = "张小明母亲"
        )
        assertEquals(Verdict.AUTO, result.verdict)
        assertEquals("张小明母亲", result.candidates[0].contact)
    }

    @Test
    fun otherStudentNameIsPenalized() {
        val result = Matcher.match(
            student("张三", "妈妈"),
            listOf("张三李四妈妈"),
            otherNames = listOf("李四")
        )
        assertEquals(Verdict.CONFIRM, result.verdict)
        assertTrue(result.candidates[0].penalized)
        assertEquals(50, result.candidates[0].score)
        assertTrue(result.candidates[0].reasons.any { it.contains("其他学生") })
    }

    @Test
    fun unrelatedContactsGiveNone() {
        val result = Matcher.match(
            student("张小明", "妈妈"),
            listOf("李四爸爸", "王五", "赵六奶奶")
        )
        assertEquals(Verdict.NONE, result.verdict)
        assertTrue(result.candidates.isEmpty())
    }

    @Test
    fun searchTermsForCompoundSurname() {
        assertEquals(listOf("欧阳娜娜", "娜娜", "欧阳"), Matcher.searchTerms("欧阳娜娜"))
        assertEquals(listOf("司马懿", "司马"), Matcher.searchTerms("司马懿"))
    }

    @Test
    fun searchTermsForRegularName() {
        assertEquals(listOf("张小明", "小明", "张小"), Matcher.searchTerms("张小明"))
        assertEquals(listOf("张三"), Matcher.searchTerms("张三"))
        assertTrue(Matcher.searchTerms("").isEmpty())
    }

    @Test
    fun emptyGuardianIsNotEnoughForAuto() {
        val result = Matcher.match(student("张小明"), listOf("张小明妈妈"))
        assertEquals(Verdict.CONFIRM, result.verdict)
        assertEquals(15, result.candidates[0].guardianScore)
        assertEquals(75, result.candidates[0].score)
    }

    @Test
    fun gradeInContactAddsBonus() {
        val result = Matcher.match(student("张小明", "妈妈", "六年级"), listOf("六年级张小明妈妈"))
        assertEquals(Verdict.AUTO, result.verdict)
        assertEquals(100, result.candidates[0].score)
        assertTrue(result.candidates[0].reasons.any { it.contains("年级") })
    }

    @Test
    fun whitespaceInContactIsIgnored() {
        val result = Matcher.match(student("张小明", "妈妈"), listOf("张小明  妈妈"))
        assertEquals(Verdict.AUTO, result.verdict)
        assertEquals(60, result.candidates[0].nameScore)
    }

    @Test
    fun sameNameStudentNeverAutoWithoutRemembered() {
        val result = Matcher.match(
            student("张小明", "妈妈"),
            listOf("张小明妈妈"),
            otherNames = listOf("张小明"),
            sameNameExists = true
        )
        assertEquals(Verdict.CONFIRM, result.verdict)
        assertFalse(result.candidates[0].penalized)
    }

    @Test
    fun rememberedContactStillAutoWithSameName() {
        val result = Matcher.match(
            student("张小明", "妈妈"),
            listOf("张小明妈妈", "张小明母亲"),
            remembered = "张小明母亲",
            sameNameExists = true
        )
        assertEquals(Verdict.AUTO, result.verdict)
        assertEquals("张小明母亲", result.candidates[0].contact)
    }

    @Test
    fun usedContactIsSortedLastAndAnnotated() {
        val used = mapOf("张小明妈妈" to "五年级 张小明")
        val result = Matcher.match(
            student("张小明", "妈妈"),
            listOf("张小明妈妈", "张小明母亲"),
            usedContacts = used
        )
        // 新规则下有效候选只有未使用的“张小明母亲”，因此可 AUTO；
        // 已使用的“张小明妈妈”仍排在后面且不能作为 AUTO 候选。
        assertEquals(Verdict.AUTO, result.verdict)
        assertEquals("张小明母亲", result.candidates[0].contact)
        assertEquals("张小明妈妈", result.candidates[1].contact)
        assertTrue(result.candidates[1].reasons.any { it == "已用于 五年级 张小明" })

        val onlyUsed = Matcher.match(
            student("张小明", "妈妈"),
            listOf("张小明妈妈"),
            usedContacts = used
        )
        assertEquals(Verdict.CONFIRM, onlyUsed.verdict)
    }

    @Test
    fun gradeNumberAndContactGradeParsing() {
        assertEquals(1, Matcher.gradeNumber("一年级"))
        assertEquals(1, Matcher.gradeNumber("1年级"))
        assertEquals(5, Matcher.gradeNumber("五年级"))
        assertEquals(9, Matcher.gradeNumber("9年级"))
        assertEquals(7, Matcher.gradeNumber("初一"))
        assertEquals(8, Matcher.gradeNumber("初二"))
        assertEquals(9, Matcher.gradeNumber("初三"))
        assertEquals(7, Matcher.gradeNumber("七年级"))
        assertNull(Matcher.gradeNumber("高一"))
        assertNull(Matcher.gradeNumber(""))

        assertEquals(5, Matcher.contactGrade("5 张小明妈妈"))
        assertEquals(5, Matcher.contactGrade("5张小明"))
        assertEquals(3, Matcher.contactGrade("3"))
        assertNull(Matcher.contactGrade("15张小明"))
        assertNull(Matcher.contactGrade("张小明"))
    }

    @Test
    fun gradeDigitContactAutoMatchesGrade() {
        val result = Matcher.match(
            student("张小明", "妈妈", "五年级"),
            listOf("5 张小明妈妈")
        )
        assertEquals(Verdict.AUTO, result.verdict)
        val candidate = result.candidates[0]
        assertEquals(1, candidate.gradeMatch)
        assertEquals(100, candidate.score)
    }

    @Test
    fun sameNameDifferentGradesAutoToOwnGrade() {
        val contacts = listOf("5 张小明妈妈", "3 张小明妈妈")

        val fifth = Matcher.match(
            student("张小明", "妈妈", "五年级"),
            contacts,
            sameNameExists = true
        )
        assertEquals(Verdict.AUTO, fifth.verdict)
        assertEquals("5 张小明妈妈", fifth.candidates[0].contact)
        assertEquals(1, fifth.candidates[0].gradeMatch)
        assertEquals(-1, fifth.candidates[1].gradeMatch)

        val third = Matcher.match(
            student("张小明", "妈妈", "三年级"),
            contacts,
            sameNameExists = true
        )
        assertEquals(Verdict.AUTO, third.verdict)
        assertEquals("3 张小明妈妈", third.candidates[0].contact)
        assertEquals(1, third.candidates[0].gradeMatch)
        assertEquals(-1, third.candidates[1].gradeMatch)
    }

    @Test
    fun gradeMismatchOnlyCandidateNotAuto() {
        val result = Matcher.match(
            student("张小明", "妈妈", "五年级"),
            listOf("3 张小明妈妈")
        )
        assertEquals(Verdict.CONFIRM, result.verdict)
        val candidate = result.candidates[0]
        assertEquals(-1, candidate.gradeMatch)
        assertTrue(candidate.penalized)
        assertEquals(50, candidate.score)
        assertTrue(
            candidate.reasons.any { it == "年级不符（备注为 3，学生为 五年级）" }
        )
    }

    @Test
    fun noGuardianGradeMatchAutoByRuleB() {
        val result = Matcher.match(
            student("赵小刚", "", "三年级"),
            listOf("3赵小刚")
        )
        assertEquals(Verdict.AUTO, result.verdict)
        val candidate = result.candidates[0]
        assertEquals(1, candidate.gradeMatch)
        assertEquals(15, candidate.guardianScore)
        assertEquals(85, candidate.score)
    }

    @Test
    fun guardianMismatchWithGradeMatchNeedsConfirm() {
        val result = Matcher.match(
            student("张小明", "婆婆", "五年级"),
            listOf("5张小明妈妈")
        )
        assertEquals(Verdict.CONFIRM, result.verdict)
        assertEquals(1, result.candidates[0].gradeMatch)
        assertEquals(0, result.candidates[0].guardianScore)
    }

    @Test
    fun deAndPhoneContactMatches() {
        val contact = "5 李小华的妈妈13600000000"
        val result = Matcher.match(
            student("李小华", "妈妈", "五年级"),
            listOf(contact)
        )
        assertEquals(Verdict.AUTO, result.verdict)
        val candidate = result.candidates[0]
        assertEquals(contact, candidate.contact)
        assertEquals(1, candidate.gradeMatch)
        assertEquals(60, candidate.nameScore)
        assertEquals(30, candidate.guardianScore)
    }

    @Test
    fun typoWithGradeMatchNeedsConfirm() {
        val result = Matcher.match(
            student("张小明", "妈妈", "五年级"),
            listOf("5张晓明妈妈")
        )
        assertEquals(Verdict.CONFIRM, result.verdict)
        assertEquals(1, result.candidates[0].gradeMatch)
        assertEquals(30, result.candidates[0].nameScore)
    }

    @Test
    fun candidateGroupOrderIsNormalThenUsedThenMismatch() {
        val used = mapOf("5 张小明妈妈" to "三年级 张小明")
        val result = Matcher.match(
            student("张小明", "妈妈", "五年级"),
            listOf("3 张小明妈妈", "5 张小明母亲", "5 张小明妈妈"),
            usedContacts = used
        )
        assertEquals(
            listOf("5 张小明母亲", "5 张小明妈妈", "3 张小明妈妈"),
            result.candidates.map { it.contact }
        )
        assertEquals(1, result.candidates[0].gradeMatch)
        assertEquals(1, result.candidates[1].gradeMatch)
        assertEquals(-1, result.candidates[2].gradeMatch)
        assertTrue(result.candidates[1].reasons.any { it == "已用于 三年级 张小明" })
        assertTrue(
            result.candidates[2].reasons.any { it == "年级不符（备注为 3，学生为 五年级）" }
        )
    }

    @Test
    fun searchTermsAddsFirstTwoCharactersLast() {
        assertEquals(listOf("张小明", "小明", "张小"), Matcher.searchTerms("张小明"))
        assertEquals(listOf("欧阳娜娜", "娜娜", "欧阳"), Matcher.searchTerms("欧阳娜娜"))
        assertEquals(listOf("司马懿", "司马"), Matcher.searchTerms("司马懿"))
        assertEquals(listOf("张三"), Matcher.searchTerms("张三"))
    }

    @Test
    fun gradeMismatchAndOriginalPenaltyDeductOnlyOnce() {
        val result = Matcher.match(
            student("张三", "妈妈", "五年级"),
            listOf("3张三李四妈妈"),
            otherNames = listOf("李四")
        )
        assertEquals(Verdict.CONFIRM, result.verdict)
        val candidate = result.candidates[0]
        assertEquals(-1, candidate.gradeMatch)
        assertTrue(candidate.penalized)
        assertEquals(50, candidate.score)
        assertTrue(candidate.reasons.any { it.startsWith("年级不符（备注为 3") })
        assertTrue(candidate.reasons.any { it.startsWith("联系人还包含其他学生姓名（李四）") })
    }
}
