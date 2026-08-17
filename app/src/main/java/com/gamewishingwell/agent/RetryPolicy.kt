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
