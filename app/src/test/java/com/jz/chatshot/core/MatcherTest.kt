package com.jz.chatshot.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        assertEquals(listOf("欧阳娜娜", "娜娜"), Matcher.searchTerms("欧阳娜娜"))
        assertEquals(listOf("司马懿"), Matcher.searchTerms("司马懿"))
    }

    @Test
    fun searchTermsForRegularName() {
        assertEquals(listOf("张小明", "小明"), Matcher.searchTerms("张小明"))
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
}
