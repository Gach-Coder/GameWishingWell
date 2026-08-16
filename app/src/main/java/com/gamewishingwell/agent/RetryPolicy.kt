package com.gamewishingwell.agent

import kotlinx.serialization.Serializable
import java.security.MessageDigest

enum class ErrorCategory(val wireName: String) {
    SYNTAX("syntax"),
    STATIC_RUNTIME("static_runtime"),
    USER_RUNTIME("user_runtime"),
    DESIGN_SCOPE("design_scope")
}

/**
 * 失败自动重试预算。
 * 记账按“每次用户请求（回合）”计：新一轮用户消息开始前重置；
 * 但预算结构本身随 GameSession 持久化，切换页面/会话不丢进程状态。
 */
@Serializable
data class RetryBudget(
    val syntaxLimit: Int = 3,
    val staticRuntimeLimit: Int = 3,
    val userRuntimeLimit: Int = 3,
    val designScopeLimit: Int = 2,
    val syntaxUsed: Int = 0,
    val staticRuntimeUsed: Int = 0,
    val userRuntimeUsed: Int = 0,
    val designScopeUsed: Int = 0,
    /** 本轮是否已进入 P0-only 降级（降级只允许一次，避免无限循环）。 */
    val fallbackUsed: Boolean = false
) {
    fun canRetry(category: ErrorCategory): Boolean = when (category) {
        ErrorCategory.SYNTAX -> syntaxUsed < syntaxLimit
        ErrorCategory.STATIC_RUNTIME -> staticRuntimeUsed < staticRuntimeLimit
        ErrorCategory.USER_RUNTIME -> userRuntimeUsed < userRuntimeLimit
        ErrorCategory.DESIGN_SCOPE -> designScopeUsed < designScopeLimit
    }

    fun consume(category: ErrorCategory): RetryBudget = when (category) {
        ErrorCategory.SYNTAX -> copy(syntaxUsed = syntaxUsed + 1)
        ErrorCategory.STATIC_RUNTIME -> copy(staticRuntimeUsed = staticRuntimeUsed + 1)
        ErrorCategory.USER_RUNTIME -> copy(userRuntimeUsed = userRuntimeUsed + 1)
        ErrorCategory.DESIGN_SCOPE -> copy(designScopeUsed = designScopeUsed + 1)
    }

    fun freshRound(): RetryBudget = copy(
        syntaxUsed = 0,
        staticRuntimeUsed = 0,
        userRuntimeUsed = 0,
        designScopeUsed = 0,
        fallbackUsed = false
    )
}

@Serializable
data class KnownError(
    val category: ErrorCategory,
    val signature: String,
    val normalizedMessage: String,
    val occurrences: Int = 1
)

/**
 * “同一错误”用规范化签名哈希判定：
 * file:line 中的行号允许漂移（不参与签名），消息做变量名/数字/引号/空白正则化。
 */
object ErrorSignature {
    fun normalize(message: String, file: String? = null, line: Int? = null): String {
        var m = message.replace(Regex("""\b\d+(?:\.\d+)?\b"""), "<n>")
            .replace(Regex("""(['"])(?:\\.|(?!\1).)*\1"""), "<str>")
            .replace(Regex("""[A-Za-z_$][A-Za-z0-9_$]{2,}"""), "<id>")
            .replace(Regex("""\b[A-Za-z_$][A-Za-z0-9_$]?\b"""), "<id>")
            .replace(Regex("""\s+"""), " ")
            .trim()
        if (file != null) m = "file:$file " + m
        return m.take(200)
    }

    fun hash(normalized: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(normalized.toByteArray(Charsets.UTF_8))
            .take(16)
            .joinToString("") { "%02x".format(it) }
}

object RetryBookkeeping {
    /** 同签名只更新计数，不重复占库。 */
    fun record(existing: List<KnownError>, category: ErrorCategory, normalized: String): List<KnownError> {
        val old = existing.firstOrNull { it.signature == ErrorSignature.hash(normalized) }
        return if (old == null) {
            existing + KnownError(category, ErrorSignature.hash(normalized), normalized)
        } else {
            existing.map {
                if (it === old) it.copy(occurrences = it.occurrences + 1) else it
            }
        }
    }

    /** 用户回传运行时错误：同一错误已出现 2 次（即将第 3 次）则直接降级，不再重复修。 */
    fun shouldDegradeRuntime(existing: List<KnownError>, normalized: String): Boolean {
        val signature = ErrorSignature.hash(normalized)
        return existing.any { it.signature == signature && it.category == ErrorCategory.USER_RUNTIME && it.occurrences >= 2 }
    }
}
