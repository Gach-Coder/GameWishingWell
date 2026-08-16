package com.gamewishingwell.agent

import android.content.Context
import com.gamewishingwell.data.ChatMessage
import com.gamewishingwell.data.GameMeta
import com.gamewishingwell.data.GameRepository
import com.gamewishingwell.data.LlmSettings
import com.gamewishingwell.data.SettingsRepository
import com.gamewishingwell.llm.AnthropicClient
import com.gamewishingwell.llm.LlmClient
import com.gamewishingwell.llm.LlmError
import com.gamewishingwell.llm.OpenAiCompatibleClient
import com.gamewishingwell.llm.Protocol
import com.gamewishingwell.llm.ProviderPresets
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit

@Serializable
data class GameSession(
    val messages: List<ChatMessage> = emptyList(),
    val currentHtml: String? = null,
    /** 为兼容旧 UI 保留；新流程中前端只展示 [agentStage]，不再回显源代码文本。 */
    val streamingText: String? = null,
    val agentStage: String = AgentStage.IDLE,
    val pendingConfirmation: IntentConfirmation? = null,
    val lastWarning: String? = null,
    val isGenerating: Boolean = false,
    val error: String? = null,
    /** rolling summary：文件清单、设计 Schema、known-issues、上次错误、决策 log 摘要。 */
    val rollingSummary: String = "",
    /** Agent Loop 开始时确定的文件计划与依赖顺序；中途不换文件切分方案。 */
    val filePlan: List<String> = emptyList(),
    val fileManifest: FileManifest? = null,
    val designPlan: DesignPlan? = null,
    val knownIssues: List<String> = emptyList(),
    val lastError: String? = null,
    val lastErrorSignature: String? = null,
    val knownErrors: List<KnownError> = emptyList(),
    val decisionLog: List<String> = emptyList(),
    /** 每通过校验的版本快照（可回滚），记录文件名与内容哈希。 */
    val snapshots: List<String> = emptyList(),
    val schemaVersion: Int = 2,
    val budget: RetryBudget = RetryBudget(),
    val qualityVerdict: QualityVerdict? = null
)

object AgentStage {
    const val IDLE = "空闲"
    const val INTENT = "游戏识别中"
    const val CONFIRM = "待确认"
    const val PLANNING = "游戏策划中"
    const val CODE_GENERATION = "代码生成中"
    const val VALIDATION = "校验中"
    const val SMOKE = "冒烟测试中"
    const val DONE = "制作完成"
    const val FAILED = "制作失败"
    const val CHAT = "对话回复中"
    const val FIXING = "修复中"
}

/**
 * 游戏创作 Agent：意图层 → 确认门 → 策划层 → Prompt → Agent Loop（生成/校验/冒烟/重试）→ 持久化。
 *
 * 生成任务运行在 [agentScope]（全局协程）中：切换页面/对话只会取消 ViewModel 的等待，
 * 不会取消生成本身；生成完成后任何页面都能立即通过 [session] 看到结果。
 */
class GameAgent(
    private val appContext: Context,
    private val repository: GameRepository,
    private val settingsRepository: SettingsRepository,
    /** 测试注入点；为 null 时按 [LlmSettings] 创建真实厂商客户端。 */
    private val injectedClientFactory: ((LlmSettings) -> LlmClient?)? = null,
    /** 测试注入点；为 null 时使用 Android WebView 冒烟测试器。 */
    private val injectedSmokeRunner: SmokeTestRunner? = null
) {

    private val _session = MutableStateFlow(GameSession())
    val session: StateFlow<GameSession> = _session.asStateFlow()

    /** 当前会话是否绑定到某个已保存的游戏（编辑模式），null 表示草稿模式。 */
    private var editingGameId: Long? = null

    private val agentScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val turnMutex = Mutex()
    private val sessionJson = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    private val okHttp by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(180, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    private val smokeRunner by lazy { injectedSmokeRunner ?: AndroidSmokeTestRunner(appContext) }

    // ---------- 会话管理 ----------

    suspend fun loadDraftSession() {
        if (_session.value.isGenerating) return
        withContext(Dispatchers.IO) {
            val html = repository.loadDraftHtml()
            val messages = repository.loadDraftSession()
            val persisted = repository.loadDraftAgentState()
            val decoded = decodeSession(persisted)
            editingGameId = null
            _session.value = decoded.copy(
                messages = messages.ifEmpty { decoded.messages },
                currentHtml = html ?: decoded.currentHtml
            )
        }
    }

    suspend fun loadGameSession(gameId: Long) {
        if (_session.value.isGenerating) return
        withContext(Dispatchers.IO) {
            val html = repository.loadGameHtml(gameId)
            val messages = repository.loadGameSession(gameId)
            val persisted = repository.loadGameAgentState(gameId)
            val decoded = decodeSession(persisted)
            editingGameId = gameId
            _session.value = decoded.copy(
                messages = messages.ifEmpty { decoded.messages },
                currentHtml = html ?: decoded.currentHtml
            )
        }
    }

    /**
     * 开始一个空会话。
     * [clearDraft] 为 true 时同时删除草稿文件；从底部“创作”进入新对话时传 false，
     * 这样不会破坏“我的游戏”页的“继续上次创作”入口。
     * 跨会话的错误签名库会保留，只用于识别“同一错误”，不消耗新回合预算。
     */
    suspend fun newSession(clearDraft: Boolean = true) {
        if (_session.value.isGenerating) return
        if (clearDraft) repository.clearDraft()
        editingGameId = null
        val signatures = withContext(Dispatchers.IO) { loadGlobalErrorSignatures() }
        _session.value = GameSession(knownErrors = signatures)
    }

    // ---------- 用户回合入口 ----------

    /**
     * 独立协程中执行用户回合；调用方协程被取消（例如用户切走页面）时，
     * agentScope 中的任务继续运行，保证“生成任务在全局 GameAgent 中继续”。
     */
    suspend fun sendUserMessage(text: String) {
        val current = _session.value
        if (current.isGenerating) return
        val trimmed = text.trim()
        if (trimmed.isBlank()) return

        val priorConfirmation = current.pendingConfirmation
        val isConfirmPhrase = priorConfirmation != null && isConfirmationPhrase(trimmed)
        val last = current.messages.lastOrNull()
        val duplicate = last != null && last.isUser && last.content == trimmed

        val messages = if (duplicate) current.messages else current.messages + ChatMessage("user", trimmed)
        val base = current.copy(
            messages = messages,
            isGenerating = true,
            error = null,
            streamingText = null,
            agentStage = AgentStage.INTENT,
            pendingConfirmation = null,
            budget = RetryBudget(),
            qualityVerdict = null
        )
        _session.value = base
        val job = agentScope.launch {
            runUserTurn(trimmed, priorConfirmation, isConfirmPhrase)
        }
        job.join()
    }

    /** 确认门：“按此方案生成”。 */
    suspend fun confirmIntent() {
        val pending = _session.value.pendingConfirmation ?: return
        if (_session.value.isGenerating) return
        val base = _session.value.copy(
            isGenerating = true,
            error = null,
            streamingText = null,
            agentStage = AgentStage.PLANNING,
            pendingConfirmation = null,
            budget = RetryBudget(),
            qualityVerdict = null
        )
        _session.value = base
        val job = agentScope.launch {
            turnMutex.withLock {
                generateFromIntent(pending.userRequest, pending.intent, _session.value.currentHtml, null)
            }
        }
        job.join()
    }

    /** 确认门：用户提交修正/补充，合并后直接进入策划与生成。 */
    suspend fun correctIntent(correction: String) {
        val pending = _session.value.pendingConfirmation ?: return
        if (_session.value.isGenerating) return
        val merged = IntentEngine.merge(pending.intent, IntentEngine.infer(correction, _session.value.currentHtml))
        val userRequest = pending.userRequest + "\n补充/修正：" + correction.trim()
        val confirmation = IntentEngine.buildConfirmation(userRequest, merged)
        val base = _session.value.copy(
            messages = _session.value.messages + ChatMessage("assistant", confirmation.summary),
            isGenerating = true,
            error = null,
            streamingText = null,
            agentStage = AgentStage.PLANNING,
            pendingConfirmation = null,
            budget = RetryBudget(),
            qualityVerdict = null
        )
        _session.value = base
        val job = agentScope.launch {
            turnMutex.withLock {
                generateFromIntent(userRequest, merged, _session.value.currentHtml, null)
            }
        }
        job.join()
    }

    /** 把游戏运行时的 JS 错误交给 AI 修复（运行时错误格式：file:line + stack + console 片段）。 */
    suspend fun fixWithError(jsError: String) {
        if (_session.value.isGenerating) return
        val s = _session.value
        val html = s.currentHtml ?: run {
            _session.value = s.copy(error = "当前没有可修复的游戏代码")
            return
        }
        val file = Regex("""(index\.html|[A-Za-z0-9_.-]+\.(?:html|js|css))""").find(jsError)?.value ?: "index.html"
        val line = Regex("""[:@](\d+)""").find(jsError)?.groupValues?.get(1)?.toIntOrNull()
        val normalized = ErrorSignature.normalize(jsError, file = file, line = line)
        val degrade = RetryBookkeeping.shouldDegradeRuntime(s.knownErrors, normalized)
        val known = RetryBookkeeping.record(s.knownErrors, ErrorCategory.USER_RUNTIME, normalized)
        saveGlobalErrorSignatures(known)

        val instruction = if (degrade) {
            "该运行错误已经是第 3 次出现，请直接降级：移除或大幅简化触发该错误的系统，只输出 P0 核心玩法版本。\n$jsError"
        } else {
            "游戏运行时报错，请修复并输出完整新版代码（保持原有玩法与 P0 特性）：\n$jsError"
        }
        val messages = s.messages + ChatMessage("user", instruction)
        val base = s.copy(
            messages = messages,
            isGenerating = true,
            error = null,
            streamingText = null,
            agentStage = AgentStage.FIXING,
            pendingConfirmation = null,
            budget = RetryBudget(),
            knownErrors = known,
            lastError = jsError.take(500),
            lastErrorSignature = ErrorSignature.hash(normalized),
            qualityVerdict = null
        )
        _session.value = base

        val intent = IntentSchema(
            intent = IntentSchema.INTENT_MODIFY_GAME,
            visualDimension = s.designPlan?.visualDimension ?: IntentSchema.DIMENSION_2D,
            screenOrientation = s.designPlan?.screenOrientation ?: IntentSchema.ORIENTATION_PORTRAIT,
            gameSystems = s.designPlan?.gameSystems ?: emptyList(),
            confidence = 1.0
        )
        val job = agentScope.launch {
            turnMutex.withLock {
                generateFromIntent(
                    instruction = instruction,
                    intent = intent,
                    existingHtml = html,
                    forcePlan = s.designPlan,
                    p0Only = degrade
                )
            }
        }
        job.join()
    }

    // ---------- 保存 ----------

    suspend fun saveCurrentGame(title: String): GameMeta? {
        val s = _session.value
        val html = s.currentHtml ?: return null
        val editingId = editingGameId
        val cleanTitle = title.trim().ifBlank { defaultTitle(s) }
        if (editingId != null) {
            repository.updateGameHtml(editingId, html, s.messages, cleanTitle)
            persistAgentState(editingId, s.copy(designPlan = s.designPlan?.copy(title = cleanTitle)))
            return repository.listGames().firstOrNull { it.id == editingId }
        }
        val description = s.messages.firstOrNull {
            it.isUser && !it.content.contains("推荐的游戏框架")
        }?.content?.trim()?.replace(Regex("\\s+"), " ")?.take(60) ?: ""
        val meta = repository.saveGame(cleanTitle, description, html, s.messages)
        editingGameId = meta.id
        repository.clearDraft()
        persistAgentState(meta.id, s.copy(designPlan = s.designPlan?.copy(title = cleanTitle)))
        return meta
    }

    suspend fun testConnection(): String {
        val llm = createClient() ?: throw LlmError("配置不完整")
        val sb = StringBuilder()
        llm.streamChat(
            listOf(ChatMessage("user", "请回复：连接成功")),
            onDelta = { sb.append(it) },
            onDone = {}
        )
        return sb.toString().trim()
    }

    // ---------- Agent Loop 内部 ----------

    private suspend fun runUserTurn(instruction: String, priorConfirmation: IntentConfirmation?, isConfirmPhrase: Boolean) {
        turnMutex.withLock {
            val s = _session.value
            if (!isConfigured()) {
                failTurn(s, "请先在「设置」中配置 API Key、地址和模型")
                return@withLock
            }

            if (isConfirmPhrase && priorConfirmation != null) {
                generateFromIntent(priorConfirmation.userRequest, priorConfirmation.intent, s.currentHtml, null)
                return@withLock
            }

            // 意图层：正则 + Lite LLM，再过 JSON Schema + 枚举白名单。
            val local = IntentEngine.infer(instruction, s.currentHtml)
            val lite = try {
                requestIntentFromLiteLlm(instruction)
            } catch (_: Exception) {
                null
            }
            val current = IntentEngine.merge(local, lite)
            // 确认门上的补充/修正：把新实体合并进原 Intent Schema，避免丢失已确认系统。
            val schema = if (priorConfirmation != null && !isConfirmPhrase) {
                IntentEngine.merge(priorConfirmation.intent, current)
            } else {
                current
            }

            if (schema.intent == IntentSchema.INTENT_CHAT) {
                generateChatReply(s, instruction)
                return@withLock
            }

            // 有确认门遗留时，用户输入即视为修正/确认，直接进入策划层。
            val effectiveRequest = if (priorConfirmation != null && !isConfirmPhrase) {
                priorConfirmation.userRequest + "\n补充/修正：" + instruction
            } else {
                instruction
            }
            val confirmation = IntentEngine.buildConfirmation(effectiveRequest, schema)
            val next = _session.value.copy(
                messages = _session.value.messages + ChatMessage("assistant", "已识别需求，请在下方确认，或直接补充修正。"),
                pendingConfirmation = confirmation,
                isGenerating = false,
                streamingText = null,
                agentStage = AgentStage.CONFIRM,
                error = null,
                designPlan = null,
                qualityVerdict = null,
                budget = RetryBudget()
            )
            _session.value = next
            persistSessionMessages(next)
        }
    }

    private suspend fun requestIntentFromLiteLlm(userText: String): IntentSchema? {
        val local = IntentEngine.infer(userText, _session.value.currentHtml)
        if (local.confidence >= 0.8) return local
        val llm = createClient() ?: return null
        val reply = collectReply(
            llm,
            listOf(
                ChatMessage("system", "你是意图抽取器，只输出合法 JSON。"),
                ChatMessage("user", IntentEngine.liteLlmPrompt(userText))
            )
        )
        return IntentSchemaValidator.parseLiteLlmReply(reply)
    }

    private suspend fun generateChatReply(s: GameSession, instruction: String) {
        val llm = createClient()
        if (llm == null) {
            failTurn(s, "请先在「设置」中配置 API Key、地址和模型")
            return
        }
        _session.value = s.copy(isGenerating = true, agentStage = AgentStage.CHAT, streamingText = null)
        val reply = try {
            collectReply(
                llm,
                listOf(
                    ChatMessage("system", GamePrompt.chatSystemPrompt()),
                    ChatMessage("user", instruction)
                )
            )
        } catch (e: Exception) {
            failTurn(_session.value, "对话失败：${e.message}")
            return
        }
        val text = HtmlExtractor.nonCodeText(reply).ifBlank { "（空回复）" }
        val next = _session.value.copy(
            messages = _session.value.messages + ChatMessage("assistant", text.take(1000)),
            isGenerating = false,
            agentStage = AgentStage.DONE,
            error = null
        )
        _session.value = next
        persistSessionMessages(next)
    }

    private suspend fun generateFromIntent(
        instruction: String,
        intent: IntentSchema,
        existingHtml: String?,
        forcePlan: DesignPlan?,
        p0Only: Boolean = false
    ) {
        val llm = createClient()
        if (llm == null) {
            failTurn(_session.value, "请先在「设置」中配置 API Key、地址和模型")
            return
        }

        var plan = forcePlan ?: PlanningEngine.build(intent)
        if (p0Only) plan = PlanningEngine.p0Only(plan)
        _session.value = _session.value.copy(
            isGenerating = true,
            error = null,
            streamingText = null,
            agentStage = AgentStage.PLANNING,
            designPlan = plan,
            filePlan = listOf("index.html"),
            decisionLog = _session.value.decisionLog + "策划定稿：${plan.templateClass}，P0=${plan.p0Features.size} P1=${plan.p1Features.size} P2=${plan.p2Features.size}；文件计划 index.html（HTML→CSS→JS）"
        )

        var workingHtml = existingHtml
        var budget = RetryBudget().let { if (p0Only) it.copy(fallbackUsed = true) else it }
        var feedback = ""
        var fallbackUsed = p0Only
        var round = 0
        var acceptedHtml: String? = null
        var lastVerdict: QualityVerdict? = null
        var lastReport: ValidationReport? = null

        while (round < 10) {
            round++
            _session.value = _session.value.copy(
                agentStage = AgentStage.CODE_GENERATION,
                budget = budget,
                streamingText = null
            )

            val reply = try {
                collectReply(
                    llm,
                    buildGenerationMessages(
                        instruction = instruction,
                        plan = plan,
                        existingHtml = workingHtml,
                        feedback = feedback,
                        p0Only = fallbackUsed
                    ),
                    stageLabel = AgentStage.CODE_GENERATION
                )
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                failTurn(_session.value, "生成失败：${e.message}")
                return
            }

            val extracted = HtmlExtractor.extract(reply)
            if (extracted.html == null) {
                val category = ErrorCategory.SYNTAX
                val normalized = ErrorSignature.normalize(extracted.error ?: "no-html")
                val known = RetryBookkeeping.record(_session.value.knownErrors, category, normalized)
                saveGlobalErrorSignatures(known)
                feedback = "上一条回复无法抽取到完整 HTML（${extracted.error ?: "缺少 ```html 围栏或 <html> 标签"}）。请只输出一个完整 HTML 文件。"
                val action = consumeOrFallback(budget, category)
                if (action == null) {
                    failTurn(_session.value, "代码抽取/语法错误超过重试预算，已无法自动恢复")
                    return
                }
                budget = action.budget
                fallbackUsed = action.fallback
                if (action.fallback) plan = PlanningEngine.p0Only(plan)
                _session.value = _session.value.copy(budget = budget, knownErrors = known, lastError = feedback, agentStage = AgentStage.VALIDATION)
                continue
            }

            val candidate = extracted.html
            _session.value = _session.value.copy(agentStage = AgentStage.VALIDATION, streamingText = null)
            val report = GameValidator.validate(candidate)
            lastReport = report

            val verdict = QualityGate.evaluate(report, plan, candidate)
            lastVerdict = verdict
            _session.value = _session.value.copy(qualityVerdict = verdict)

            if (report.hasErrors || !verdict.pass) {
                // 静态校验全过、仅质量自检不达标 → 视为设计范围错误，回策划层重做（≤2 轮）。
                val category = if (report.errors.isEmpty()) ErrorCategory.DESIGN_SCOPE else classifyFailure(report)
                val normalized = ErrorSignature.normalize(
                    report.errors.firstOrNull()?.message ?: verdict.fails.firstOrNull()?.item ?: "validation-failed"
                )
                val signature = ErrorSignature.hash(normalized)
                val known = RetryBookkeeping.record(_session.value.knownErrors, category, normalized)
                saveGlobalErrorSignatures(known)
                feedback = if (category == ErrorCategory.DESIGN_SCOPE) {
                    buildString {
                        append("策划回炉（错误签名 $signature）：\n")
                        append(QualityGate.promptFeedback(verdict, report))
                        append("\nP0 清单（回炉必须覆盖，不得扩大范围）：\n")
                        plan.p0Features.forEach { append("- $it\n") }
                    }
                } else {
                    QualityGate.promptFeedback(verdict, report)
                }
                val action = consumeOrFallback(budget, category)
                if (action == null) {
                    failTurn(_session.value, "校验错误超过重试预算：${report.errors.firstOrNull()?.message ?: "质量自检未通过"}")
                    return
                }
                budget = action.budget
                fallbackUsed = action.fallback
                if (action.fallback) plan = PlanningEngine.p0Only(plan)
                _session.value = _session.value.copy(
                    budget = budget,
                    knownErrors = known,
                    knownIssues = report.warnings.map { "${it.category}:${it.message}" },
                    lastError = feedback,
                    agentStage = AgentStage.CODE_GENERATION
                )
                continue
            }

            // 静态校验全过后才允许冒烟测试。
            _session.value = _session.value.copy(agentStage = AgentStage.SMOKE)
            val smoke = runSmoke(candidate)
            if (!smoke.passed) {
                val category = ErrorCategory.STATIC_RUNTIME
                val normalized = ErrorSignature.normalize(smoke.errors.joinToString(";").ifBlank { "smoke-failed" })
                val known = RetryBookkeeping.record(_session.value.knownErrors, category, normalized)
                saveGlobalErrorSignatures(known)
                feedback = "冒烟测试失败（${smoke.errors.take(3).joinToString("；")}）。请修复后重新输出完整 HTML。"
                val action = consumeOrFallback(budget, category)
                if (action == null) {
                    failTurn(_session.value, "冒烟测试失败且重试预算已耗尽：${smoke.errors.take(3).joinToString("；")}")
                    return
                }
                budget = action.budget
                fallbackUsed = action.fallback
                if (action.fallback) plan = PlanningEngine.p0Only(plan)
                _session.value = _session.value.copy(budget = budget, knownErrors = known, lastError = feedback)
                continue
            }

            acceptedHtml = candidate
            break
        }

        if (acceptedHtml == null) {
            failTurn(_session.value, "Agent Loop 达到最大轮次仍未产出可运行游戏")
            return
        }

        val assistantText = if (existingHtml.isNullOrBlank()) {
            "游戏已生成并通过校验，点击「立即游玩」。"
        } else {
            "游戏已按你的要求更新并通过校验。"
        }
        val report = lastReport ?: GameValidator.validate(acceptedHtml)
        val verdict = lastVerdict ?: QualityGate.evaluate(report, plan, acceptedHtml)
        val snapshot = "index.html:${GameFileWorkspace.sha256(acceptedHtml)}"
        val summary = buildRollingSummary(plan, report, acceptedHtml, verdict)
        val next = _session.value.copy(
            messages = _session.value.messages + ChatMessage("assistant", assistantText),
            currentHtml = acceptedHtml,
            streamingText = null,
            isGenerating = false,
            error = null,
            agentStage = AgentStage.DONE,
            pendingConfirmation = null,
            lastWarning = report.warnings.firstOrNull()?.message,
            rollingSummary = summary,
            fileManifest = FileManifest(
                pointer = "index.html",
                files = listOf(
                    WorkspaceFile(
                        "index.html",
                        _session.value.snapshots.size + 1,
                        GameFileWorkspace.sha256(acceptedHtml),
                        acceptedHtml.toByteArray(Charsets.UTF_8).size
                    )
                )
            ),
            snapshots = _session.value.snapshots + snapshot,
            designPlan = plan,
            knownIssues = report.warnings.map { "${it.category}:${it.message}" },
            lastError = null,
            budget = budget,
            qualityVerdict = verdict,
            decisionLog = _session.value.decisionLog + listOf(
                "第 $round 轮生成通过：${report.errors.size} error / ${report.warnings.size} warning",
                "冒烟测试通过"
            )
        )
        _session.value = next
        persistSession(next)
    }

    private fun consumeOrFallback(budget: RetryBudget, category: ErrorCategory): BudgetAction? {
        return if (budget.canRetry(category)) {
            BudgetAction(budget.consume(category), fallback = false)
        } else {
            // 同一类预算耗尽：确定性裁剪 P1/P2，只保留 P0 机制；如果已经裁剪过则放弃。
            if (!budget.fallbackUsed) {
                BudgetAction(budget.copy(fallbackUsed = true), fallback = true)
            } else {
                null
            }
        }
    }

    private fun classifyFailure(report: ValidationReport): ErrorCategory {
        val messages = report.errors.joinToString(" ") { it.category + ":" + it.message }
        return if (Regex("语法|syntax|解析失败").containsMatchIn(messages)) {
            ErrorCategory.SYNTAX
        } else {
            ErrorCategory.STATIC_RUNTIME
        }
    }

    private suspend fun runSmoke(html: String): SmokeTestResult = try {
        smokeRunner.run(html)
    } catch (e: Exception) {
        SmokeTestResult(passed = false, errors = listOf(e.message ?: "smoke-runner-error"))
    }

    private suspend fun failTurn(s: GameSession, message: String) {
        val failed = s.copy(
            isGenerating = false,
            streamingText = null,
            error = message,
            agentStage = AgentStage.FAILED,
            lastError = message
        )
        _session.value = failed
        persistSessionMessages(failed)
    }

    private suspend fun persistSessionMessages(s: GameSession) {
        withContext(Dispatchers.IO) {
            val editingId = editingGameId
            if (editingId != null) {
                repository.updateGameSessionOnly(editingId, s.messages)
                repository.saveGameAgentState(editingId, sessionJson.encodeToString(s))
            } else {
                repository.saveDraftSessionOnly(s.messages)
                repository.saveDraftAgentState(sessionJson.encodeToString(s))
            }
        }
    }

    private suspend fun persistSession(s: GameSession) {
        withContext(Dispatchers.IO) {
            val html = s.currentHtml ?: return@withContext
            val editingId = editingGameId
            if (editingId != null) {
                repository.updateGameHtml(editingId, html, s.messages)
                repository.saveGameAgentState(editingId, sessionJson.encodeToString(s))
            } else {
                repository.saveDraft(html, s.messages)
                repository.saveDraftAgentState(sessionJson.encodeToString(s))
            }
        }
    }

    private suspend fun persistAgentState(gameId: Long, s: GameSession) {
        withContext(Dispatchers.IO) {
            repository.saveGameAgentState(gameId, sessionJson.encodeToString(s))
        }
    }

    private fun buildRollingSummary(plan: DesignPlan, report: ValidationReport, html: String, verdict: QualityVerdict): String = buildString {
        append("文件计划与清单：${_session.value.filePlan.joinToString(" → ")}（index.html ${html.length} 字符）\n")
        append("设计Schema：${plan.templateClass} / ${plan.gameSystems.joinToString("、")} / P0=${plan.p0Features.size} P1=${plan.p1Features.size} P2=${plan.p2Features.size}\n")
        append("known-issues：${if (report.warnings.isEmpty()) "无" else report.warnings.joinToString("；") { it.message }}\n")
        append("上次错误：无\n")
        append("决策：静态校验通过（error=${report.errors.size} warning=${report.warnings.size}），冒烟通过，verdict=${verdict.pass}")
    }

    private fun defaultTitle(s: GameSession): String =
        s.designPlan?.title ?: s.messages.firstOrNull { it.isUser }?.content?.take(20) ?: "未命名游戏"

    private fun isConfirmationPhrase(text: String): Boolean =
        Regex("""^(确认|可以|好的|好|行|没问题|ok|yes|按此|就这样|开始制作|生成吧)[!！。.~～\s]*$""", RegexOption.IGNORE_CASE).matches(text.trim())

    // ---------- LLM ----------

    private fun createClient(): LlmClient? {
        val s: LlmSettings = settingsRepository.settings.value
        if (!settingsRepository.isConfigured()) return null
        val preset = ProviderPresets.byId(s.providerId) ?: ProviderPresets.DEFAULT
        return injectedClientFactory?.invoke(s) ?: if (preset.protocol == Protocol.ANTHROPIC) {
            AnthropicClient(okHttp, s.apiKey, s.baseUrl, s.model, preset.maxTokens)
        } else {
            OpenAiCompatibleClient(okHttp, s.apiKey, s.baseUrl, s.model, preset.maxTokens, preset.disableThinking)
        }
    }

    private fun isConfigured(): Boolean = settingsRepository.isConfigured()

    private fun buildGenerationMessages(
        instruction: String,
        plan: DesignPlan,
        existingHtml: String?,
        feedback: String,
        p0Only: Boolean
    ): List<ChatMessage> {
        val customSystem = settingsRepository.settings.value.systemPrompt.trim()
        val msgs = mutableListOf(ChatMessage("system", GamePrompt.systemPrompt(customSystem.ifBlank { null })))

        if (existingHtml.isNullOrBlank()) {
            // 首次生成：模板作为参考文件，策划 Schema 作为唯一需求上下文。
            msgs += ChatMessage(
                "user",
                "参考模板（这是可读取的 game_template.html，不是用户需求）：\n\n<game_template>\n${GamePrompt.readTemplate(appContext)}\n</game_template>"
            )
        } else {
            val rolling = _session.value.rollingSummary.takeIf { it.isNotBlank() }
            msgs += ChatMessage(
                "user",
                buildString {
                    if (rolling != null) append("rolling summary：\n$rolling\n\n")
                    append("本次要修改的文件内容（其余文件按需从工作区读取）：\n\n<game_code file=\"index.html\">\n$existingHtml\n</game_code>")
                }
            )
        }

        val body = buildString {
            append("用户指令：$instruction\n\n")
            append(GamePrompt.planContext(plan))
            if (p0Only) {
                append("\n【降级指令】预算已耗尽，进入 P0-only 模式：只实现 P0 特性，P1/P2 全部裁剪，资产用几何占位符。\n")
            }
            if (feedback.isNotBlank()) {
                append("\n【上一轮校验失败，必须修复】\n$feedback\n")
            }
            append(GamePrompt.codeContract())
        }
        msgs += ChatMessage("user", body)
        return msgs
    }

    private suspend fun collectReply(
        llm: LlmClient,
        messages: List<ChatMessage>,
        stageLabel: String? = null
    ): String {
        val sb = StringBuilder()
        var lastProgressMark = 0
        llm.streamChat(
            messages,
            onDelta = { delta ->
                sb.append(delta)
                // SSE 打字机状态：只回显 Agent 阶段 + 接收进度，绝不复述源代码。
                if (stageLabel != null && sb.length - lastProgressMark >= 200) {
                    lastProgressMark = sb.length
                    _session.value = _session.value.copy(agentStage = "$stageLabel · 已接收 ${sb.length} 字符")
                }
            },
            onThinking = { /* 思考过程属于内部信号，不回显到前端 */ },
            onDone = {}
        )
        return sb.toString()
    }

    // ---------- 错误签名库（跨会话持久化，只用于识别“同一错误”，不消耗预算） ----------

    private fun errorLibraryFile(): File =
        File(appContext.filesDir, "agent/error_signatures.json").also { it.parentFile?.mkdirs() }

    private fun loadGlobalErrorSignatures(): List<KnownError> {
        val f = errorLibraryFile()
        if (!f.isFile) return emptyList()
        return runCatching { sessionJson.decodeFromString<List<KnownError>>(f.readText(Charsets.UTF_8)) }
            .getOrDefault(emptyList())
    }

    private fun saveGlobalErrorSignatures(list: List<KnownError>) {
        runCatching { errorLibraryFile().writeText(sessionJson.encodeToString(list), Charsets.UTF_8) }
    }

    private fun decodeSession(persisted: String?): GameSession =
        if (persisted.isNullOrBlank()) GameSession()
        else runCatching { sessionJson.decodeFromString<GameSession>(persisted) }.getOrDefault(GameSession())

    private data class BudgetAction(val budget: RetryBudget, val fallback: Boolean)
}
