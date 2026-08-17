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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
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
    /** 仅用于兼容旧会话；简化流程不再运行两段式质量自检，因此新状态中保持 null。 */
    val qualityVerdict: QualityVerdict? = null
)

object AgentStage {
    const val IDLE = "空闲"
    const val INTENT = "游戏识别中"
    const val CONFIRM = "待确认"
    const val PLANNING = "游戏策划中"
    const val CODE_GENERATION = "代码生成中"
    const val VALIDATION = "校验中"
    @Deprecated("简化后的主流程不再执行冒烟测试，保留常量仅为兼容旧状态。")
    const val SMOKE = "冒烟测试中"
    const val DONE = "制作完成"
    const val FAILED = "制作失败"
    const val CHAT = "对话回复中"
    const val FIXING = "修复中"
    const val INTERRUPTED = "已中断"
}

/**
 * 游戏创作 Agent：意图层 → 策划草案 → 确认门 → 决策层 → Prompt → Agent Loop（生成/基础校验/修复）→ 持久化。
 *
 * 简化后的 Agent Loop 只做语法与基本逻辑校验：通过后立即交付玩家试玩，
 * 运行错误和玩家反馈再回到 Agent Loop 修复，避免自动测试流程过长。
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
    /** 兼容旧测试的冒烟测试器注入点；主流程已不执行冒烟测试。 */
    private val injectedSmokeRunner: SmokeTestRunner? = null
) {

    private val _session = MutableStateFlow(GameSession())
    val session: StateFlow<GameSession> = _session.asStateFlow()

    /** 当前会话是否绑定到某个已保存的游戏（编辑模式），null 表示草稿模式。 */
    private var editingGameId: Long? = null

    private val agentScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val turnMutex = Mutex()

    /** 当前正在执行的 Agent Loop 任务；停止键取消该任务。 */
    @Volatile
    private var activeGenerationJob: Job? = null
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

    private fun trackGenerationJob(job: Job): Job {
        activeGenerationJob = job
        job.invokeOnCompletion {
            if (activeGenerationJob === job) activeGenerationJob = null
        }
        return job
    }

    /**
     * 停止键：随时中断当前 Agent Loop。
     * 中断后保留最近一次已抽取出的 HTML 候选（如果有），玩家可立即尝试游玩；
     * 该候选可能尚未通过基础校验，运行错误属于正常现象。
     */
    fun stopGeneration() {
        val current = _session.value
        val job = activeGenerationJob
        if (!current.isGenerating) return
        if (job?.isActive == true) job.cancel()
        activeGenerationJob = null
        val interrupted = current.copy(
            isGenerating = false,
            streamingText = null,
            agentStage = AgentStage.INTERRUPTED,
            error = null,
            qualityVerdict = null,
            lastWarning = if (current.currentHtml != null) {
                "已手动中断：当前保留中断前的代码版本，可能尚未通过基础校验，尝试游玩可能出现运行错误。"
            } else {
                "已手动中断：尚未生成可运行的代码，可以继续补充需求后重新开始。"
            }
        )
        _session.value = interrupted
    }

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

        // 确认门期间输入框发来的任何文字都是“补充/修正”，不会当作确认语句；
        // 进入决策层的唯一入口是界面上的确认按钮（confirmIntent）。
        val priorConfirmation = current.pendingConfirmation
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
        val job = trackGenerationJob(agentScope.launch {
            runUserTurn(trimmed, priorConfirmation)
        })
        job.join()
    }

    /** 确认门唯一入口：“按此方案生成”按钮；点击后进入决策层并开始生成代码。 */
    suspend fun confirmIntent() {
        val pending = _session.value.pendingConfirmation ?: return
        if (_session.value.isGenerating) return

        // 决策层：根据玩家最后确认的完整信息输出最终 DesignPlan，
        // 已确认系统/实现方法写入 implementations，排除系统和方案写入 excluded_*。
        // 局部修改旧游戏时继承上一版系统范围，避免把“加连击计分”误判成新游戏方案。
        val finalIntent = PlanningEngine.inheritExistingDesign(pending.intent, _session.value.designPlan)
        val finalPlan = PlanningEngine.finalize(finalIntent, _session.value.designPlan?.title)

        val base = _session.value.copy(
            isGenerating = true,
            error = null,
            streamingText = null,
            agentStage = AgentStage.PLANNING,
            pendingConfirmation = null,
            designPlan = finalPlan,
            budget = RetryBudget(),
            qualityVerdict = null
        )
        _session.value = base
        val job = trackGenerationJob(agentScope.launch {
            turnMutex.withLock {
                generateFromIntent(pending.userRequest, finalIntent, _session.value.currentHtml, finalPlan)
            }
        })
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
        val job = trackGenerationJob(agentScope.launch {
            turnMutex.withLock {
                generateFromIntent(
                    instruction = instruction,
                    intent = intent,
                    existingHtml = html,
                    forcePlan = s.designPlan,
                    p0Only = degrade
                )
            }
        })
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

    private suspend fun runUserTurn(instruction: String, priorConfirmation: IntentConfirmation?) {
        turnMutex.withLock {
            currentCoroutineContext().ensureActive()
            val s = _session.value
            if (!isConfigured()) {
                failTurn(s, "请先在「设置」中配置 API Key、地址和模型")
                return@withLock
            }

            // 意图层：正则 + Lite LLM，再过 JSON Schema + 枚举白名单。
            val local = IntentEngine.infer(instruction, s.currentHtml)
            val lite = try {
                requestIntentFromLiteLlm(instruction)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            val current = IntentEngine.merge(local, lite)
            // 确认门上的补充/修正：合并回已确认 Intent Schema，并再次回显等待确认。
            val schema = if (priorConfirmation != null) {
                IntentEngine.mergeCorrection(priorConfirmation.intent, current, instruction)
            } else {
                current
            }

            if (schema.intent == IntentSchema.INTENT_CHAT) {
                generateChatReply(s, instruction)
                return@withLock
            }

            // 新流程：意图层之后先出策划草案，确认门回显草案玩法；点击确认按钮后，
            // 决策层才根据玩家确认的完整信息输出最终 DesignPlan 并进入代码生成。
            val planningIntent = PlanningEngine.inheritExistingDesign(schema, s.designPlan, instruction)
            val draftPlan = PlanningEngine.draft(planningIntent, s.designPlan?.title)
            currentCoroutineContext().ensureActive()

            // 有确认门遗留时，任何文字输入都视为修正；重新生成摘要，直到点击确认按钮才进入决策层。
            val effectiveRequest = if (priorConfirmation != null) {
                priorConfirmation.userRequest + "\n补充/修正：" + instruction
            } else {
                instruction
            }
            val confirmation = IntentEngine.buildConfirmation(effectiveRequest, planningIntent, draftPlan)
            val assistantReply = confirmationMessage(
                confirmation,
                if (priorConfirmation != null) "已根据你的补充重新整理需求" else "已识别需求"
            )
            val next = _session.value.copy(
                messages = _session.value.messages + ChatMessage("assistant", assistantReply),
                pendingConfirmation = confirmation,
                isGenerating = false,
                streamingText = null,
                agentStage = AgentStage.CONFIRM,
                error = null,
                designPlan = draftPlan,
                qualityVerdict = null,
                budget = RetryBudget()
            )
            currentCoroutineContext().ensureActive()
            _session.value = next
            persistSessionMessages(next)
        }
    }

    /** 确认门消息：摘要 + 每个系统的具体玩法与验收边界 + 操作提示。 */
    private fun confirmationMessage(confirmation: IntentConfirmation, prefix: String): String = buildString {
        append(prefix)
        append("：\n")
        append(confirmation.summary)
        if (confirmation.systemExplanations.isNotEmpty()) {
            append("\n\n游戏系统玩法与验收边界：\n")
            confirmation.systemExplanations.forEach { append("· $it\n") }
        }
        if (confirmation.excludedSystems.isNotEmpty()) {
            append("\n已排除系统：${confirmation.excludedSystems.joinToString("、")}")
        }
        append("\n请点击下方唯一的确认按钮，确认后我会按此方案生成代码；如需修改，直接在底部输入框输入新的要求。")
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
        } catch (e: CancellationException) {
            throw e
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

        // 简化后的 Agent Loop：先出文件计划 → implement → 基础校验。
        // 校验通过立即退出并交付玩家试玩；冒烟测试、两段式自检等自动流程已移除。
        var plan = forcePlan ?: PlanningEngine.finalize(intent)
        if (p0Only) plan = PlanningEngine.p0Only(plan)
        val planningStage = if (plan.gameSystems.isEmpty()) {
            "游戏策划中：正在根据确认结果定稿 ${plan.primarySystem} 的方案"
        } else {
            "游戏策划中：正在根据确认结果定稿 ${plan.gameSystems.joinToString("、")}（已排除 ${plan.excludedSystems.size} 个系统）"
        }
        _session.value = _session.value.copy(
            isGenerating = true,
            error = null,
            streamingText = null,
            agentStage = planningStage,
            designPlan = plan,
            filePlan = listOf("index.html"),
            decisionLog = _session.value.decisionLog + "决策层定稿：${plan.templateClass}，实现 ${plan.implementations.size} 个系统、排除 ${plan.excludedSystems.size} 个系统；P0=${plan.p0Features.size} P1=${plan.p1Features.size} P2=${plan.p2Features.size}；文件计划 index.html（HTML→CSS→JS）"
        )

        var workingHtml = existingHtml
        var budget = RetryBudget().let { if (p0Only) it.copy(fallbackUsed = true) else it }
        var feedback = ""
        var fallbackUsed = p0Only
        var round = 0
        var acceptedHtml: String? = null
        var lastReport: ValidationReport? = null

        while (round < 6) {
            round++
            currentCoroutineContext().ensureActive()

            val module = moduleForRound(plan, round)
            val genStage = if (existingHtml.isNullOrBlank()) {
                "代码生成中：正在生成「$module」系统模块"
            } else {
                "代码生成中：正在修改「$module」系统模块"
            }
            _session.value = _session.value.copy(
                agentStage = genStage,
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
                    stageLabel = genStage
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failTurn(_session.value, "生成失败：${e.message}")
                return
            }

            currentCoroutineContext().ensureActive()
            val extracted = HtmlExtractor.extract(reply)
            currentCoroutineContext().ensureActive()
            if (extracted.html == null) {
                val category = ErrorCategory.SYNTAX
                val normalized = ErrorSignature.normalize(extracted.error ?: "no-html")
                val known = RetryBookkeeping.record(_session.value.knownErrors, category, normalized)
                saveGlobalErrorSignatures(known)
                feedback = "上一条回复无法抽取到完整 HTML（${extracted.error ?: "缺少 ```html 围栏或 <html> 标签"}）。请只输出一个完整 HTML 文件。"
                val action = consumeOrFallback(budget, category)
                if (action == null) {
                    failTurn(_session.value, "代码抽取错误超过重试预算，已无法自动恢复")
                    return
                }
                budget = action.budget
                fallbackUsed = action.fallback
                if (action.fallback) plan = PlanningEngine.p0Only(plan)
                _session.value = _session.value.copy(
                    budget = budget,
                    knownErrors = known,
                    lastError = feedback,
                    agentStage = "校验中：正在检查 Agent 输出是否为完整 HTML"
                )
                continue
            }

            val candidate = extracted.html
            // 一旦抽出候选 HTML 就发布到 session：用户中断后可立即尝试游玩该中间版本。
            currentCoroutineContext().ensureActive()
            val validationModule = moduleForRound(plan, round + 1)
            _session.value = _session.value.copy(
                currentHtml = candidate,
                agentStage = "校验中：正在对「$validationModule」做语法与基本逻辑校验",
                streamingText = null
            )
            val report = GameValidator.validate(candidate)
            currentCoroutineContext().ensureActive()
            lastReport = report

            if (report.hasErrors) {
                val category = classifyFailure(report)
                val normalized = ErrorSignature.normalize(
                    report.errors.joinToString(";") { it.message }
                )
                val signature = ErrorSignature.hash(normalized)
                val known = RetryBookkeeping.record(_session.value.knownErrors, category, normalized)
                saveGlobalErrorSignatures(known)
                feedback = validationFeedback(report, signature)

                val action = consumeOrFallback(budget, category)
                if (action == null) {
                    failTurn(_session.value, "基础校验错误超过重试预算：${report.errors.firstOrNull()?.message ?: "校验未通过"}")
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
                    agentStage = "校验中：正在为「$module」系统生成修复方案"
                )
                continue
            }

            // 基础校验通过即退出 Agent Loop，不再执行冒烟测试或质量自检；
            // 实际运行问题由玩家试玩后回传（game screen 报错 → 让 AI 修复 / 输入改进需求）。
            acceptedHtml = candidate
            break
        }

        currentCoroutineContext().ensureActive()
        if (acceptedHtml == null) {
            failTurn(_session.value, "Agent Loop 达到最大轮次仍未产出通过基础校验的游戏")
            return
        }

        val assistantText = if (existingHtml.isNullOrBlank()) {
            "游戏已通过基础校验。点击「立即游玩」试玩吧——如遇报错可让 AI 修复，也可以直接在这里继续提改进需求。"
        } else {
            "游戏已按你的要求更新并通过基础校验。点击「立即游玩」确认效果；如遇报错或想继续调整，随时告诉我。"
        }
        val report = lastReport ?: GameValidator.validate(acceptedHtml)
        val snapshot = "index.html:${GameFileWorkspace.sha256(acceptedHtml)}"
        val summary = buildRollingSummary(plan, report, acceptedHtml)
        val next = _session.value.copy(
            messages = _session.value.messages + ChatMessage("assistant", assistantText),
            currentHtml = acceptedHtml,
            streamingText = null,
            isGenerating = false,
            error = null,
            agentStage = AgentStage.DONE,
            pendingConfirmation = null,
            lastWarning = report.warnings.firstOrNull()?.message
                ?: "建议先点击「立即游玩」实际试玩，运行问题可直接让 AI 修复。",
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
            qualityVerdict = null,
            decisionLog = _session.value.decisionLog + listOf(
                "第 $round 轮基础校验通过：${report.errors.size} error / ${report.warnings.size} warning",
                "基础校验通过，退出 Agent Loop；实际运行问题由玩家试玩后回传"
            )
        )
        currentCoroutineContext().ensureActive()
        _session.value = next
        persistSession(next)
    }

    /** 只把校验器发现的事实回喂给 LLM，不再引入质量自检翻案或策划范围重做环节。 */
    private fun validationFeedback(report: ValidationReport, signature: String): String = buildString {
        append("基础校验未通过（错误签名 $signature），请修复以下问题后重新输出完整 HTML：\n")
        report.errors.take(10).forEach { issue ->
            append("- [${issue.category}] ${issue.file}:${issue.line} ${issue.message}\n")
        }
        if (report.warnings.isNotEmpty()) {
            append("警告（可作为优化项一并处理）：\n")
            report.warnings.take(5).forEach { issue ->
                append("- [${issue.category}] ${issue.file}:${issue.line} ${issue.message}\n")
            }
        }
    }.trimEnd()

    /** 子阶段显示的当前系统模块：按轮次在系统列表中轮换，避免只显示固定主系统。 */
    private fun moduleForRound(plan: DesignPlan, round: Int): String {
        val systems = plan.gameSystems
        if (systems.isEmpty()) return plan.primarySystem.ifBlank { "核心玩法" }
        val index = ((round - 1) % systems.size + systems.size) % systems.size
        return systems[index]
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

    private suspend fun failTurn(s: GameSession, message: String) {
        currentCoroutineContext().ensureActive()
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

    private fun buildRollingSummary(plan: DesignPlan, report: ValidationReport, html: String): String = buildString {
        append("文件计划与清单：${_session.value.filePlan.joinToString(" → ")}（index.html ${html.length} 字符）\n")
        append("设计Schema：${plan.templateClass} / ${plan.gameSystems.joinToString("、")} / P0=${plan.p0Features.size} P1=${plan.p1Features.size} P2=${plan.p2Features.size}\n")
        append("实现清单：${plan.implementations.joinToString("；") { "${it.system}(${it.methods.joinToString("|")})" }}\n")
        append("排除清单：系统[${plan.excludedSystems.joinToString("、")}]；方案[${plan.excludedApproaches.joinToString("、")}]\n")
        append("known-issues：${if (report.warnings.isEmpty()) "无" else report.warnings.joinToString("；") { it.message }}\n")
        append("上次改进对应缺陷：无\n")
        append("决策：基础校验通过（error=${report.errors.size} warning=${report.warnings.size}），已退出 Agent Loop 并交由玩家实际试玩")
    }

    private fun defaultTitle(s: GameSession): String =
        s.designPlan?.title ?: s.messages.firstOrNull { it.isUser }?.content?.take(20) ?: "未命名游戏"

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
        try {
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
        } catch (e: CancellationException) {
            // 代码生成流被停止键中断时，抢救未收完的 HTML，让玩家仍可“立即游玩”中间版本。
            if (stageLabel != null) {
                HtmlExtractor.extractPartial(sb.toString())?.let { partial ->
                    _session.value = _session.value.copy(
                        currentHtml = partial,
                        lastWarning = "生成已中断，当前为未完成代码；立即游玩可能出现运行错误。",
                        agentStage = AgentStage.INTERRUPTED
                    )
                }
            }
            throw e
        }
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
