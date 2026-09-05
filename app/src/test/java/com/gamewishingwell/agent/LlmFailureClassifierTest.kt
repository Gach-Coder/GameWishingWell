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
        // 真传输类（IOException）没有服务文案——走通用网络失败文案
        assertNull(LlmFailureClassifier.userMessage(IOException("Connection reset")))
        // 分钟级 429 若重试耗尽：如实归类为 LLM 服务限流，不再伪装成网络异常
        assertTrue(LlmFailureClassifier.userMessage(LlmError("API 错误 429：rate limit"))!!.contains("LLM 服务"))
    }

    @Test
    fun `每日免费配额耗尽不可重试并给出换模型指引`() {
        // 实测样本（agent.log 2026-09-05，OpenRouter 免费档）：重置点为次日——
        // 此前被当可重试网络问题：5 次尝试烧掉 110s 后仍以"网络连接异常"失败（双重误导）。
        val quota = LlmError(
            "API 错误 429：{\"error\":{\"message\":\"Rate limit exceeded: free-models-per-day. " +
                "Add 10 credits to unlock 1000 free model requests per day\",\"code\":429," +
                "\"metadata\":{\"headers\":{\"X-RateLimit-Limit\":\"50\",\"X-RateLimit-Remaining\":\"0\"}," +
                "\"limit_source\":\"openrouter_free_tier_daily\"}}}"
        )
        assertFalse(LlmFailureClassifier.isRetryable(quota))
        val msg = LlmFailureClassifier.userMessage(quota)
        assertNotNull(msg)
        assertTrue(msg!!.contains("免费模型额度"))
        assertTrue(msg.contains("明日"))

        // 分钟级真限流（带 retry 提示、无每日配额标记）不得被误伤
        assertTrue(LlmFailureClassifier.isRetryable(LlmError("API 错误 429：rate limit exceeded, retry in 60s")))
    }

    @Test
    fun `鉴权与模型类给出对应的设置指引`() {
        val auth = LlmError("API 错误 401：{\"error\":{\"message\":\"invalid api key\"}}")
        assertFalse(LlmFailureClassifier.isRetryable(auth))
        assertTrue(LlmFailureClassifier.userMessage(auth)!!.contains("API Key"))

        val notFound = LlmError("API 错误 404：{\"error\":{\"message\":\"model not found\"}}")
        assertFalse(LlmFailureClassifier.isRetryable(notFound))
        assertTrue(LlmFailureClassifier.userMessage(notFound)!!.contains("模型"))

        // 其它 4xx（请求格式/网关拒绝/上游 Provider returned error）：不可重试，
        // 且必须如实归类为 LLM 服务问题（曾一律伪装成"网络连接异常"）
        val bad = LlmError("API 错误 400：bad request")
        assertFalse(LlmFailureClassifier.isRetryable(bad))
        assertTrue(LlmFailureClassifier.userMessage(bad)!!.contains("服务方拒绝"))
    }
    @Test
    fun `上下文超长不可重试并指引换大上下文模型`() {
        // OpenAI 系措辞 + Anthropic 措辞 + 网关 413
        val samples = listOf(
            "API 错误 400：This model's maximum context length is 8192 tokens. However, you requested 10485 tokens",
            "API 错误 (invalid_request_error)：prompt is too long: 210000 tokens > 200000 maximum",
            "API 错误 413：payload too large"
        )
        samples.forEach { m ->
            val e = LlmError(m)
            assertFalse(LlmFailureClassifier.isRetryable(e))
            assertTrue(LlmFailureClassifier.userMessage(e)!!.contains("上下文上限"))
        }
    }

    @Test
    fun `模型名无效措辞变体给出模型指引`() {
        val samples = listOf(
            "API 错误 400：Unknown model: glm-9.9-ultra",
            "API 错误 404：{\"error\":{\"code\":\"model_not_found\"}}",
            "API 错误 400：Invalid model ID"
        )
        samples.forEach { m ->
            val e = LlmError(m)
            assertFalse(LlmFailureClassifier.isRetryable(e))
            assertTrue(LlmFailureClassifier.userMessage(e)!!.contains("模型"))
        }
    }

    @Test
    fun `OpenRouter credits 耗尽归余额类而非网络类`() {
        val credits = LlmError("API 错误 402：{\"error\":{\"message\":\"Insufficient credits: check your billing\"}}")
        assertFalse(LlmFailureClassifier.isRetryable(credits))
        assertTrue(LlmFailureClassifier.userMessage(credits)!!.contains("充值"))
    }

    @Test
    fun `内容安全拦截给出调整指引`() {
        val e = LlmError("API 错误 400：Your request was rejected as a result of content moderation policy")
        assertFalse(LlmFailureClassifier.isRetryable(e))
        assertTrue(LlmFailureClassifier.userMessage(e)!!.contains("内容安全"))
    }

    @Test
    fun `实测 OpenRouter 上游 Provider returned error 不再伪装成网络问题`() {
        // agent.log 2026-09-05 实测样本：此前 FAIL 文案为"网络或服务连接异常"
        val e = LlmError("API 错误 400：{\"error\":{\"message\":\"Provider returned error\",\"code\":400,\"metadata\":{\"raw\":\"6006c58b\"}}}")
        assertFalse(LlmFailureClassifier.isRetryable(e))
        val msg = LlmFailureClassifier.userMessage(e)
        assertNotNull(msg)
        assertTrue(msg!!.contains("服务方拒绝"))
    }
}
