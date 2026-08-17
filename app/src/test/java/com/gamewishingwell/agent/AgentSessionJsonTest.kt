package com.gamewishingwell.agent

import com.gamewishingwell.data.ChatMessage
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentSessionJsonTest {

    @Test
    fun `旧版 IntentSchema 缺少 excludedSystems 仍可解码`() {
        val json = Json { ignoreUnknownKeys = true }
        val legacy = """{"intent":"new_game","visualDimension":"2D","screenOrientation":"竖版","gameSystems":["战斗"],"referenceGame":"打地鼠","templateId":"whack_a_mole","templateSimilarity":0.9,"confidence":0.8}"""
        val decoded = json.decodeFromString<IntentSchema>(legacy)
        assertEquals(emptyList<String>(), decoded.excludedSystems)
        assertTrue(decoded.gameSystems.contains("战斗"))
    }

    @Test
    fun `GameSession 完整状态可持久化往返`() {
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        val intent = IntentEngine.infer("做一个打地鼠游戏", null)
        val plan = PlanningEngine.build(intent)
        val state = GameSession(
            messages = listOf(ChatMessage("user", "做一个打地鼠游戏"), ChatMessage("assistant", "已识别")),
            currentHtml = "<html></html>",
            agentStage = AgentStage.CONFIRM,
            pendingConfirmation = IntentEngine.buildConfirmation("做一个打地鼠游戏", intent),
            rollingSummary = "summary",
            fileManifest = FileManifest(pointer = "index.html", files = listOf(WorkspaceFile("index.html", 1, "hash", 4))),
            designPlan = plan,
            knownIssues = listOf("warn"),
            lastError = "last",
            lastErrorSignature = "sig",
            knownErrors = listOf(KnownError(ErrorCategory.SYNTAX, "sig", "norm")),
            decisionLog = listOf("d1"),
            snapshots = listOf("index.html:hash"),
            qualityVerdict = QualityVerdict(true, note = "ok")
        )
        val encoded = json.encodeToString(state)
        val decoded = json.decodeFromString<GameSession>(encoded)
        assertEquals(state, decoded)
    }
}
