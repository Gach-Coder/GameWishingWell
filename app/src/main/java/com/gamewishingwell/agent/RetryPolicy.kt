package com.gamewishingwell.agent

import kotlinx.serialization.Serializable
import java.security.MessageDigest

enum class ErrorCategory(val wireName: String) {
    SYNTAX("syntax"),
    STATIC_RUNTIME("static_runtime"),
    USER_RUNTIME("user_runtime"),
    DESIGN_SCOPE("design_scope")
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

/**
 * 错误签名记账库：只记录错误历史（同签名累计次数），不再设任何重试预算或自动降级。
 */
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

}

/**
 * “顽固错误”追踪：熔断语义是“同一条错误连续 N 轮未被修掉”，而不是“整个错误
 * 集合恰好完全相同”。单条签名 = hash(类别|消息原文)——
 * - 保留变量名等标识符：同名未定义变量才算同一处失败，不同变量的错误签名不同；
 * - 不含行号：行号随编辑漂移，不代表错误本身变化（消息文本里本就不含行号）。
 * 聚合式签名有两个已知缺陷，本追踪器都规避：标识符归一化会把不同来源合并成
 * 同一签名（误熔断）；整集合比较在“修好一条+新增一条”时清零计数，真正顽固的
 * 错误永远数不满（漏熔断）。
 */
object StubbornErrorTracker {

    /** 单条错误签名：同一条错误的签名跨轮稳定，不同错误（不同类别或不同消息）签名不同。 */
    fun issueSignature(category: String, message: String): String =
        ErrorSignature.hash("issue|" + category.trim() + "|" + message.trim())

    /**
     * 用本轮仍存在的错误更新连续计数：存在 +1（首次出现计 1），消失即移除。
     * 集合的其它变化（修好一条/新增一条）不影响某条错误自己的连续计数。
     */
    fun update(prev: Map<String, Int>, currentSignatures: Set<String>): Map<String, Int> =
        currentSignatures.associateWith { sig -> (prev[sig] ?: 0) + 1 }

    /** 最顽固的错误：(签名, 连续轮数)；空集合返回 null。 */
    fun worst(counts: Map<String, Int>): Pair<String, Int>? =
        counts.maxByOrNull { it.value }?.toPair()
}
