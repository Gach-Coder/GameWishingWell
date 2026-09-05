package com.gamewishingwell.agent

import com.gamewishingwell.llm.LlmError
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException

/**
 * LLM 失败分类：账户额度类（GLM 429+code1113 / DeepSeek 402）不可重试且给如实的
 * "LLM 服务不可用"文案——不再伪装成网络波动重试后误报网络异常。
 */
class LlmFailureClassifierTest {

    @Test
    fun `账户额度类不可重试并给出充值文案`() {
        // 实测样本：智谱 GLM 以 429 + code 1113 表达余额不足
        val glm = LlmError("API 错误 429：{\"error\":{\"code\":\"1113\",\"message\":\"余额不足或无可用资源包,请充值。\"}}")
        assertFalse(LlmFailureClassifier.isRetryable(glm))
        val msg = LlmFailureClassifier.userMessage(glm)
        assertNotNull(msg)
        assertTrue(msg!!.contains("余额"))
        assertTrue(msg.contains("充值"))

        // 实测样本：DeepSeek 以 402 表达 Insufficient Balance
        val deepseek = LlmError("API 错误 402：{\"error\":{\"message\":\"Insufficient Balance\"}}")
        assertFalse(LlmFailureClassifier.isRetryable(deepseek))
        assertTrue(LlmFailureClassifier.userMessage(deepseek)!!.contains("余额"))
    }

    @Test
    fun `真限流 429 与网络类仍可重试`() {
        assertTrue(LlmFailureClassifier.isRetryable(LlmError("API 错误 429：rate limit exceeded")))
        assertTrue(LlmFailureClassifier.isRetryable(LlmError("API 错误 500：internal error")))
        assertTrue(LlmFailureClassifier.isRetryable(SocketTimeoutException("Read timed out")))
        assertTrue(LlmFailureClassifier.isRetryable(IOException("Connection reset")))
        assertTrue(LlmFailureClassifier.isRetryable(LlmError("overloaded_error")))
        // 可重试类没有"服务不可用"文案（走通用网络失败文案）
        assertNull(LlmFailureClassifier.userMessage(IOException("Connection reset")))
        assertNull(LlmFailureClassifier.userMessage(LlmError("API 错误 429：rate limit")))
    }

    @Test
    fun `鉴权与模型类给出对应的设置指引`() {
        val auth = LlmError("API 错误 401：{\"error\":{\"message\":\"invalid api key\"}}")
        assertFalse(LlmFailureClassifier.isRetryable(auth))
        assertTrue(LlmFailureClassifier.userMessage(auth)!!.contains("API Key"))

        val notFound = LlmError("API 错误 404：{\"error\":{\"message\":\"model not found\"}}")
        assertFalse(LlmFailureClassifier.isRetryable(notFound))
        assertTrue(LlmFailureClassifier.userMessage(notFound)!!.contains("模型"))

        // 其它 4xx（请求格式等）：不可重试、无专属文案（走通用失败）
        val bad = LlmError("API 错误 400：bad request")
        assertFalse(LlmFailureClassifier.isRetryable(bad))
        assertNull(LlmFailureClassifier.userMessage(bad))
    }
}
