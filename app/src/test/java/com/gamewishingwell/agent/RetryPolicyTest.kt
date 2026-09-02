package com.gamewishingwell.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class RetryPolicyTest {

    @Test
    fun `同签名错误只更新计数不重复占库`() {
        val n = ErrorSignature.normalize("x is not defined", "index.html", 1)
        val once = RetryBookkeeping.record(emptyList(), ErrorCategory.SYNTAX, n)
        val twice = RetryBookkeeping.record(once, ErrorCategory.SYNTAX, n)
        assertEquals(1, twice.size)
        assertEquals(2, twice.single().occurrences)
    }

    @Test
    fun `同一错误规范化后行号漂移不影响签名`() {
        val a = ErrorSignature.normalize("TypeError: cannot read x of undefined at index.html:120", "index.html", 120)
        val b = ErrorSignature.normalize("TypeError: cannot read y of undefined at index.html:312", "index.html", 312)
        assertEquals(ErrorSignature.hash(a), ErrorSignature.hash(b))
    }

    @Test
    fun `不同错误签名不同`() {
        val a = ErrorSignature.normalize("x is not defined", "index.html", 1)
        val b = ErrorSignature.normalize("y is not a function", "index.html", 1)
        assertNotEquals(ErrorSignature.hash(a), ErrorSignature.hash(b))
    }

    @Test
    fun `顽固错误追踪-不同变量的错误签名不同`() {
        // 保留标识符：不同未定义变量 = 不同一处失败，不会互相计入熔断
        val a = StubbornErrorTracker.issueSignature("static-runtime", "引用未定义变量/函数：jumpVel（no-undef）")
        val b = StubbornErrorTracker.issueSignature("static-runtime", "引用未定义变量/函数：score（no-undef）")
        assertNotEquals(a, b)
        // 同一条错误签名稳定（重复计算一致）
        assertEquals(a, StubbornErrorTracker.issueSignature("static-runtime", "引用未定义变量/函数：jumpVel（no-undef）"))
        // 不同类别同名消息也不同
        assertNotEquals(a, StubbornErrorTracker.issueSignature("syntax", "引用未定义变量/函数：jumpVel（no-undef）"))
    }

    @Test
    fun `顽固错误追踪-集合变化不清零顽固项`() {
        // A 一直没修掉；B 修好了、C 又新增——A 的连续计数不受影响
        val sigA = StubbornErrorTracker.issueSignature("static-runtime", "A")
        val sigB = StubbornErrorTracker.issueSignature("static-runtime", "B")
        val sigC = StubbornErrorTracker.issueSignature("static-runtime", "C")

        var counts = StubbornErrorTracker.update(emptyMap(), setOf(sigA, sigB))
        counts = StubbornErrorTracker.update(counts, setOf(sigA, sigC))
        counts = StubbornErrorTracker.update(counts, setOf(sigA, sigC))

        assertEquals(3, counts[sigA]) // 连续 3 轮存在
        assertEquals(2, counts[sigC]) // 第 2、3 轮存在（新增错误从 1 起连续累计）
        assertEquals(null, counts[sigB]) // 消失即移除
        assertEquals(sigA to 3, StubbornErrorTracker.worst(counts))
    }

    @Test
    fun `顽固错误追踪-错误消失后重现从头计数`() {
        val sigA = StubbornErrorTracker.issueSignature("static-runtime", "A")
        var counts = StubbornErrorTracker.update(emptyMap(), setOf(sigA))
        counts = StubbornErrorTracker.update(counts, setOf(sigA))
        counts = StubbornErrorTracker.update(counts, emptySet()) // 该轮错误消失（被修掉过）
        counts = StubbornErrorTracker.update(counts, setOf(sigA)) // 之后又复现
        assertEquals(1, counts[sigA])
    }

}
