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

}
