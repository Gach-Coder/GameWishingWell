package com.gamewishingwell.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RetryPolicyTest {

    @Test
    fun `预算按类别消耗并在回合重置`() {
        val budget = RetryBudget().consume(ErrorCategory.SYNTAX).consume(ErrorCategory.SYNTAX)
        assertTrue(budget.canRetry(ErrorCategory.SYNTAX))
        val exhausted = budget.consume(ErrorCategory.SYNTAX)
        assertFalse(exhausted.canRetry(ErrorCategory.SYNTAX))
        assertEquals(0, exhausted.freshRound().syntaxUsed)
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
    fun `用户运行时同一错误重复两次直接降级`() {
        val n = ErrorSignature.normalize("boom", "index.html", 1)
        val once = RetryBookkeeping.record(emptyList(), ErrorCategory.USER_RUNTIME, n)
        val twice = RetryBookkeeping.record(once, ErrorCategory.USER_RUNTIME, n)
        assertFalse(RetryBookkeeping.shouldDegradeRuntime(once, n))
        assertTrue(RetryBookkeeping.shouldDegradeRuntime(twice, n))
    }
}
