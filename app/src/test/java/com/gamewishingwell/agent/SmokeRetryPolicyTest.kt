package com.gamewishingwell.agent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 沙箱设施失败的重试判定：瞬时设施问题重试，游戏自身错误不重试。 */
class SmokeRetryPolicyTest {

    @Test
    fun `infra failures are retryable`() {
        // 零帧冻结（活性早检终止）
        assertTrue(isRetryableInfraFailure(SmokeTestResult(false, framesRun = 0, errors = listOf("冒烟测试超时"), message = "smoke-frozen")))
        // 结果不可读
        assertTrue(isRetryableInfraFailure(SmokeTestResult(false, errors = emptyList(), message = "probe-result-unavailable")))
        // 零帧超时且无真实错误
        assertTrue(isRetryableInfraFailure(SmokeTestResult(false, framesRun = 0, errors = listOf("冒烟测试超时"), message = "smoke-timeout")))
    }

    @Test
    fun `game failures and successes are not retryable`() {
        // 游戏自身错误（有错误文本）：重跑结果相同，不重试
        assertFalse(isRetryableInfraFailure(SmokeTestResult(false, framesRun = 24, errors = listOf("playability-blank-screen: 白屏"), message = "smoke-failed")))
        // 有帧推进的超时（慢环境语义）：交由上层按慢环境处理
        assertFalse(isRetryableInfraFailure(SmokeTestResult(false, framesRun = 12, errors = listOf("冒烟测试超时"), message = "smoke-timeout")))
        assertFalse(isRetryableInfraFailure(SmokeTestResult(false, framesRun = 24, errors = listOf("scenario-fail[战斗/击杀]: 断言不满足"), message = "smoke-failed")))
        assertFalse(isRetryableInfraFailure(SmokeTestResult(true, message = "smoke-ok")))
    }
}
