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
import com.gamewishingwell.llm.LlmResponse
import com.gamewishingwell.llm.OpenAiCompatibleClient
import com.gamewishingwell.llm.Protocol
import com.gamewishingwell.llm.ProviderPresets
import com.gamewishingwell.llm.ToolSpec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
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
import java.io.IOException
import java.util.concurrent.TimeUnit

@Serializable
data class GameSession(
    val messages: List<ChatMessage> = emptyList(),
    val currentHtml: String? = null,
    val agentStage: String = AgentStage.IDLE,
    val pendingConfirmation: IntentConfirmation? = null,
    /** 会话级 Game Schema JSON：一个对话框只制作一个游戏，识别层与下游共享。 */
    val gameSchema: GameSchema? = null,
    val lastWarning: String? = null,
    val isGenerating: Boolean = false,
    val error: String? = null,
    /** rolling summary：文件清单、设计 Schema、known-issues、上次错误、决策 log 摘要。 */
    val rollingSummary: String = "",
    /** Agent Loop 开始时确定的文件计划与依赖顺序；中途不换文件切分方案。 */
    val filePlan: List<String> = emptyList(),
    val fileManifest: FileManifest? = null,
    val designPlan: DesignPlan? = null,
    /** game_generated：已产出通过验收的游戏。此后 dev 消息跳过识别/策划/确认门直达 Agent Loop。 */
    val gameGenerated: Boolean = false,
    val knownIssues: List<String> = emptyList(),
    val lastError: String? = null,
    val lastErrorSignature: String? = null,
    val knownErrors: List<KnownError> = emptyList(),
    val decisionLog: List<String> = emptyList(),
    /** 每通过校验的版本快照（可回滚），记录文件名与内容哈希。 */
    val snapshots: List<String> = emptyList(),
    val schemaVersion: Int = 2,
    /** 质量档位（确认门四挡 fast/light/balanced/premium），默认均衡：驱动档位提示词、自检轮数与沙箱深度。 */
    val qualityTier: String = QualityTier.BALANCED
) {
    /** 兼容旧会话：持久化标志为 false 但已存在游戏代码时，同样视为已生成。 */
    fun effectiveGameGenerated(): Boolean = gameGenerated || !currentHtml.isNullOrBlank()
}

object AgentStage {
    const val IDLE = "空闲"
    const val INTENT = "意图识别中"
    const val RECOGNITION = "游戏识别中"
    const val CONFIRM = "待确认"
    const val PLANNING = "游戏策划中"
    const val CODE_GENERATION = "代码生成中"
    const val VALIDATION = "校验中"
    const val SMOKE = "冒烟测试中"
    const val DONE = "制作完成"
    const val FAILED = "制作失败"
    const val CHAT = "对话回复中"
    const val FIXING = "修复中"
    const val INTERRUPTED = "已中断"
}

/** 用户可见的耗时文案（中文口语化）：不足 1 秒 / N 秒 / N 分 N 秒 / N 小时 N 分。 */
internal fun formatAgentDuration(ms: Long): String {
    val totalSec = ms / 1000
    return when {
        totalSec < 1 -> "不足 1 秒"
        totalSec < 60 -> "$totalSec 秒"
        totalSec < 3600 -> "${totalSec / 60} 分 ${totalSec % 60} 秒"
        else -> "${totalSec / 3600} 小时 ${(totalSec % 3600) / 60} 分"
    }
}

/**
 * LLM 失败分类（internal 供单测）：
 * - 账户额度/计费类不可重试且需要如实的用户文案——GLM 以 429+code1113 表达"余额不足"、
 *   DeepSeek 以 402 表达 Insufficient Balance；这类确定性失败若按 429 当限流重试，
 *   会白烧退避时间并把"服务不可用"伪装成"网络波动"（实测连重试 4 次后仍报网络异常）。
 * - 鉴权/模型不存在类同理：重试无意义，直接以服务状态文案失败。
 */
internal object LlmFailureClassifier {

    private val BALANCE_PATTERN = Regex(
        "API 错误 402|余额|insufficient[_\\s]?balance|arrear|欠费|充值|资源包|\"code\":\"1113\"",
        RegexOption.IGNORE_CASE
    )

    fun isBalanceExhausted(e: Exception): Boolean {
        val msg = e.message ?: return false
        return BALANCE_PATTERN.containsMatchIn(msg)
    }

    /** 可重试判定：IO 异常、超时/连接类文本、服务端过载（5xx / 429 真限流 / overloaded）。 */
    fun isRetryable(e: Exception): Boolean {
        if (isBalanceExhausted(e)) return false
        if (e is IOException) return true
        val msg = e.message ?: ""
        if (Regex("API 错误 5\\d\\d").containsMatchIn(msg)) return true
        if (msg.contains("429")) return true
        // Anthropic SSE error 事件（529 overloaded_error 等）没有数字状态码，按类型识别。
        if (msg.contains("overloaded_error", ignoreCase = true)) return true
        return Regex("timeout|timed out|connection|reset|unreachable|broken pipe|EOF", RegexOption.IGNORE_CASE)
            .containsMatchIn(msg)
    }

    /**
     * 非重试失败的如实用户文案（LLM 服务状态，不是技术细节）；null=走通用网络失败文案。
     */
    fun userMessage(e: Exception): String? {
        val msg = e.message ?: return null
        return when {
            isBalanceExhausted(e) ->
                "LLM 服务不可用：账户余额不足或配额已耗尽，进度已保留。请到服务商账户充值后重试。"
            Regex("API 错误 40[13]").containsMatchIn(msg) ||
                msg.contains(Regex("invalid[_\\s]?api[_\\s]?key|authentication|unauthorized", RegexOption.IGNORE_CASE)) ->
                "LLM 服务不可用：API Key 无效或无访问权限，进度已保留。请在设置中检查密钥。"
            Regex("API 错误 404").containsMatchIn(msg) ||
                msg.contains(Regex("model.*(not.*(found|exist)|unavailable)", RegexOption.IGNORE_CASE)) ->
                "LLM 服务不可用：模型或地址不可用，进度已保留。请在设置中检查模型名与 Base URL。"
            else -> null
        }
    }
}

/**
 * 游戏创作 Agent。流程按 game_generated 分流：
 * false（首次创建）：意图层(dev/chat) → 识别层(Game Schema JSON) → 策划草案 → 确认门 → 决策层 → Agent Loop；
 * true（已产出游戏）：dev 消息跳过识别/策划/确认门直达 Agent Loop（修复快车道为其中的状态特例）。
 * chat 为纯文本回复（携带 rolling summary 作只读上下文）。
 *
 * Agent Loop 主路径为工具模式：模型经 readfile/writefile/editfile 读写工作区，
 * 写后自动校验并作为观察回传，模型不再调用工具即自然终止；静态校验通过后跑
 * 同 WebView 内核的确定性 tick 冒烟测试验证可运行性；不支持 function calling
 * 的网关自动降级为全量重写回环。验收通过即交付玩家试玩，运行错误和玩家
 * 反馈再回到 Agent Loop 修复。
 *
 * 生成任务运行在 [agentScope]（全局协程）中：切换页面/对话只会取消 ViewModel 的等待，
 * 不会取消生成本身；生成完成后任何页面都能立即通过 [session] 看到结果。
 *
 * Agent Loop 不做带错交付，防死循环的熔断口径：
 * - 工具模式：连续 24 轮无任何文件修改（空转熔断）即如实终止；修复轮数本身不设上限
 *   （有文件修改即有进展，同类错误 3/6 次仅注入"换思路/重写模块"升级提示，不终止）；
 * - 兼容模式（每轮全量重写，最贵路径）：总轮次有硬上限，超限如实失败并保留最新候选；
 * 任一熔断退出都保留中间版本；停止键是用户的主动中断方式。
 *
 * 会话状态并发纪律：agent 协程（IO）、流式 onDelta 回调（OkHttp 线程）与停止键
 * （主线程）都会写 [session]，一律经 `_session.update {}` 原子读-改-写——直接
 * `value = value.copy(...)` 的快照覆盖会互相丢更新（如停止后的 INTERRUPTED 状态
 * 被飞行中的进度回调覆盖回"生成中"）。
 */
class GameAgent(
    private val appContext: Context,
    private val repository: GameRepository,
    private val settingsRepository: SettingsRepository,
    /** 测试注入点；为 null 时按 [LlmSettings] 创建真实厂商客户端。 */
    private val injectedClientFactory: ((LlmSettings) -> LlmClient?)? = null,
    /** 冒烟测试器注入点（测试/诊断用）；为 null 时按 [AndroidSmokeTestRunner] 创建真实实现。 */
    private val injectedSmokeRunner: SmokeTestRunner? = null
) {

    private val _session = MutableStateFlow(GameSession())
    val session: StateFlow<GameSession> = _session.asStateFlow()

    /**
     * 原子更新并返回更新后的终态（updateAndGet 语义；kotlinx.coroutines 未提供该扩展）。
     * 供"更新后还要把同一份终态落盘"的路径使用，避免再读一次 _session.value
     * 拿到的是被其它线程覆盖过的中间态。
     */
    private fun updateSession(transform: (GameSession) -> GameSession): GameSession {
        var applied: GameSession? = null
        _session.update { s -> transform(s).also { applied = it } }
        return applied ?: _session.value
    }

    /** 当前会话是否绑定到某个已保存的游戏（编辑模式），null 表示草稿模式。 */
    @Volatile
    private var editingGameId: Long? = null

    // CoroutineExceptionHandler 是最后防线：回合协程已用 runTurnSafely 兜底，
    // 这里只拦截兜底路径自身（如 failTurn 落盘）抛出的意外异常——SupervisorJob
    // 无 handler 时未捕获异常会走 defaultUncaughtExceptionHandler 直接崩溃进程。
    private val agentScope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, e ->
            runCatching { alog("UNCAUGHT ${e.javaClass.simpleName}: ${e.message}") }
        }
    )
    private val turnMutex = Mutex()

    /** 当前正在执行的 Agent Loop 任务；停止键取消该任务。 */
    @Volatile
    private var activeGenerationJob: Job? = null

    /**
     * 回合计时打点：在四个回合入口（发送消息/确认门确认/报障修复/再试一次）起算，
     * 回复（成功/失败/聊天）结算附带"本轮耗时"。确认门等待用户思考的时间不计入
     * （确认时重新打点）；0 表示未打点（防御，按不足 1 秒展示）。
     */
    @Volatile
    private var turnStartedAtMs: Long = 0L

    private fun markTurnStart() {
        turnStartedAtMs = System.currentTimeMillis()
    }

    private fun turnElapsedMs(): Long =
        if (turnStartedAtMs <= 0L) 0L else (System.currentTimeMillis() - turnStartedAtMs).coerceAtLeast(0L)

    /** 本轮 Agent Loop 已运行时长（发送/确认/修复/再试一次起算，确认门等待不计入）；供前端停止键计时显示。 */
    fun currentTurnElapsedMs(): Long = turnElapsedMs()
    private val sessionJson = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    private companion object {
        /** 用户可见失败文案：零技术细节（错误内容/轮次/工具名只进决策日志），只说结果与下一步；按退出原因区分。 */
        const val USER_MSG_CHANGE_MODEL = "本轮制作未能推进。你可以重试；若反复出现，请在设置中更换模型。"
        const val USER_MSG_NETWORK = "网络或服务连接异常，进度已保留。请检查网络与模型配置后重试。"
        const val USER_MSG_SANDBOX_ENV = "运行验证环境异常，进度已保留。请重试；若反复出现，请重启应用后再试。"
        /** 回合内未被业务 try/catch 覆盖的意外异常（文件 IO/序列化等）：如实失败收尾，绝不带崩进程。 */
        const val USER_MSG_UNEXPECTED = "本轮出现意外错误，进度已保留。请重试；若反复出现，请重启应用后再试。"

        /** 会话内版本快照的保留上限：防 agent_state.json 无限膨胀（每次成功回合只追加不清理）。 */
        const val MAX_SNAPSHOTS = 50

        /**
         * LLM 网络重试的递增退避表（5/15/30/60s）：表长即重试次数（首次 + 4 次重试 = 5 次尝试）。
         * 后台瞬断（射频切换/Doze 间隙/网关限流）恢复常需 30~60s，短退避跨不过窗口。
         */
        val LLM_RETRY_BACKOFF_MS = longArrayOf(5_000L, 15_000L, 30_000L, 60_000L)

        /**
         * 兼容回环（每轮全量重写）的总轮次上限：最贵路径的止损熔断。工具模式靠
         * "连续 24 轮无文件修改"熔断（有写入即有进展），兼容模式每轮都产出新候选、
         * 空转熔断不适用，必须以总轮次封顶。上限需覆盖自检轮（≤2）+ 丰富度增强（1）
         * + 常规修复轮次后仍有余量。
         */
        const val LEGACY_MAX_ROUNDS = 30
    }

    /**
     * 沙箱失败的"可修错误"提取：过滤空白与超时哨兵后剩余的真实错误文本。
     * 为空 = 超时且无任何真实错误（慢环境时序问题而非游戏缺陷，按通过处理，
     * 零帧推进除外——那是探针从未运行的设施异常）。
     */
    private fun smokeActionableErrors(smoke: SmokeTestResult): List<String> =
        smoke.errors
            .filter { it.isNotBlank() }
            .filterNot { it == "smoke-timeout" || it == "冒烟测试超时" }

    private val okHttp by lazy {
        OkHttpClient.Builder()
            // 网络层超时是"死连接探测器"，不是对 LLM 的时长限制——三者都不约束模型生成：
            // 1) connect/write 只管建连与请求体上传（模型尚未开始输出），超时=网络不可达；
            // 2) read 是字节间静默超时（单次阻塞读的等待上限，非整流时长）。阈值取 12 分钟：
            //    部分网关对推理模型不回传 reasoning_content 增量（思考期间连接完全静默），
            //    复杂任务（首对话整游戏生成/修复轮大上下文推理）思考超过 5 分钟并不罕见——
            //    此前 5 分钟阈值会把正常思考判成 SocketTimeout，三次重试全部同样超时，
            //    最终以"网络或服务连接异常"失败（后台长任务的高频失败来源）。
            //    只要流上有任何字节（content/reasoning/SSE 心跳）就永不触发；真正死连接
            //    （蜂窝 NAT 回收）12 分钟内也必然暴露，代价可接受；
            // 3) 触发后不判死：callLlm 对网络类异常自动重试（重连新连接），
            //    模型输出不会被截断；callTimeout=0 保证整体时长不设限，停止键是用户侧唯一主动中断。
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.MINUTES)
            .writeTimeout(60, TimeUnit.SECONDS)
            .callTimeout(0, TimeUnit.SECONDS)
            .build()
    }

    /** 本地文件调试日志（AgentLog），scope 区分草稿/已保存游戏会话；仅 adb 调试用，前端不展示。 */
    private fun alog(msg: String) {
        AgentLog.log(if (editingGameId != null) "game-$editingGameId" else "draft", msg)
    }

    private fun trackGenerationJob(job: Job): Job {
        // 后台保活（前台服务）：生成任务开始即启动 dataSync 前台服务（进度通知 + 停止入口 + wakelock）；
        // 服务自行观察 isGenerating 收尾，Agent 侧不主动 stop——避免上回合取消回调与下回合启动的
        // 竞态把新回合的服务关掉。启动失败绝不影响生成本体，但必须留痕（后台保活降级
        // 直接表现为后台网络受限，诊断时需要这条线索）。
        runCatching { GenerationForeground.start(appContext) }
            .onFailure { alog("前台服务启动失败（后台保活降级，后台网络可能受限）：${it.message}") }
        activeGenerationJob = job
        job.invokeOnCompletion {
            if (activeGenerationJob === job) activeGenerationJob = null
        }
        return job
    }

    /**
     * 回合级异常兜底（agentScope.launch 的统一包裹层）：LLM 调用各有局部 try/catch，
     * 但工作区读写、持久化序列化等仍可能抛 IOException/SerializationException——
     * 不兜底会穿透 SupervisorJob 崩溃进程。任何意外异常都转为如实失败收尾
     * （会话状态正确翻回空闲、决策日志留痕），进行中的中间版本保留。
     */
    private suspend fun runTurnSafely(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            alog("turn crashed: ${e.javaClass.simpleName}: ${e.message}")
            runCatching { failTurn(USER_MSG_UNEXPECTED, internalDetail = "回合意外异常：${e.javaClass.name}: ${e.message}") }
        }
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
        alog("STOP by user")
        if (job?.isActive == true) {
            job.cancel()
            // cancel 异步生效：回合协程在"最后一次 ensureActive 之后"飞行中的进度
            // 更新可能落在 INTERRUPTED 之后，把终态盖回"第 N 轮生成中"。回合协程
            // 完全退出后不再有任何写方，此时把终态复位（新回合已启动/已失败则不动）。
            job.invokeOnCompletion {
                _session.update { s ->
                    if (!s.isGenerating && s.error == null &&
                        s.agentStage != AgentStage.INTERRUPTED && s.agentStage != AgentStage.DONE
                    ) s.copy(agentStage = AgentStage.INTERRUPTED) else s
                }
            }
        }
        activeGenerationJob = null
        _session.update { s ->
            s.copy(
                isGenerating = false,
                agentStage = AgentStage.INTERRUPTED,
                error = null,
                lastWarning = if (s.currentHtml != null) {
                    "已中断。你可以试玩当前版本，或继续描述需求。"
                } else {
                    "已中断。请继续描述你的需求。"
                }
            )
        }
    }

    // ---------- 会话管理 ----------

    /**
     * 会话加载的乐观提交（并发纪律根修复）：入口快照 [before]，读文件期间若有
     * 其它入口（发送消息翻转 isGenerating / 停止 / 下一回合）改写了会话——引用
     * 必然变化——则放弃本次覆盖。此前直接 `_session.value = 新值` 会把读文件
     * 窗口内刚追加的用户消息与生成状态整体吞掉。所有会话写入都走 `_session.update`
     * 原子读-改-写，引用比对才有意义。
     */
    private fun commitSessionIfUntouched(before: GameSession, build: (GameSession) -> GameSession): Boolean {
        var committed = false
        _session.update { cur ->
            if (cur === before) {
                committed = true
                build(cur)
            } else {
                cur
            }
        }
        return committed
    }

    suspend fun loadDraftSession() {
        val before = _session.value
        if (before.isGenerating) return
        withContext(Dispatchers.IO) {
            val html = repository.loadDraftHtml()
            val messages = repository.loadDraftSession()
            val persisted = repository.loadDraftAgentState()
            val decoded = decodeSession(persisted)
            if (commitSessionIfUntouched(before) {
                    decoded.copy(
                        messages = messages.ifEmpty { decoded.messages },
                        currentHtml = html ?: decoded.currentHtml
                    )
                }
            ) {
                editingGameId = null
            }
        }
    }

    suspend fun loadGameSession(gameId: Long) {
        val before = _session.value
        if (before.isGenerating) return
        withContext(Dispatchers.IO) {
            val savedHtml = repository.loadGameHtml(gameId)
            val messages = repository.loadGameSession(gameId)
            val persisted = repository.loadGameAgentState(gameId)
            val decoded = decodeSession(persisted)
            // 编辑区优先：编辑文件夹（agent/workspace/game-<id>）里可能有未保存的修改，
            // 重新进入编辑对话从编辑区续作；运行区（games/<id>）只被保存按钮覆盖。
            val editingHtml = editingWorkspaceHtml(gameId)
            if (commitSessionIfUntouched(before) {
                    decoded.copy(
                        messages = messages.ifEmpty { decoded.messages },
                        currentHtml = editingHtml ?: savedHtml ?: decoded.currentHtml
                    )
                }
            ) {
                editingGameId = gameId
            }
        }
    }

    /**
     * 开始一个空会话。
     * [clearDraft] 为 true 时同时删除草稿文件；从底部"创作"进入新对话时传 false，
     * 这样不会破坏"我的游戏"页的"继续上次创作"入口。
     * 跨会话的错误签名库会保留，只用于错误历史记录；Agent Loop 不设重试上限。
     */
    suspend fun newSession(clearDraft: Boolean = true) {
        val before = _session.value
        if (before.isGenerating) return
        if (clearDraft) repository.clearDraft()
        val signatures = withContext(Dispatchers.IO) { loadGlobalErrorSignatures() }
        if (commitSessionIfUntouched(before) { GameSession(knownErrors = signatures) }) {
            editingGameId = null
        }
    }

    // ---------- 用户回合入口 ----------

    /**
     * 独立协程中执行用户回合；调用方协程被取消（例如用户切走页面）时，
     * agentScope 中的任务继续运行，保证"生成任务在全局 GameAgent 中继续"。
     */
    suspend fun sendUserMessage(
        text: String,
        qualityTier: String? = null,
        dimensionOverride: String? = null,
        orientationOverride: String? = null
    ) {
        val current = _session.value
        if (current.isGenerating) return
        val trimmed = text.trim()
        if (trimmed.isBlank()) return

        // 确认门期间输入框发来的任何文字都是"补充/修正"，不会当作确认语句；
        // 进入决策层的唯一入口是界面上的确认按钮（confirmIntent）。
        // 修正回合重建卡片时继承用户当前选择的质量档位（否则选择被重置回上一张卡的持久值）。
        val priorConfirmation = current.pendingConfirmation?.let { p ->
            qualityTier?.let { p.copy(qualityTier = QualityTier.normalize(it)) } ?: p
        }
        val last = current.messages.lastOrNull()
        val duplicate = last != null && last.isUser && last.content == trimmed

        val messages = if (duplicate) current.messages else current.messages + ChatMessage("user", trimmed)
        // 输入区形态/档位选择（新契约：首次对话在输入框周围直接确定游戏基本信息）：
        // 档位即时写入会话；形态作为玩家显式指定在识别结果之上覆盖（玩家主权）。
        _session.update {
            it.copy(
                messages = messages,
                isGenerating = true,
                error = null,
                agentStage = AgentStage.INTENT,
                qualityTier = qualityTier?.let { q -> QualityTier.normalize(q) } ?: it.qualityTier,
            )
        }
        markTurnStart()
        val job = trackGenerationJob(agentScope.launch {
            runTurnSafely { runUserTurn(trimmed, priorConfirmation, dimensionOverride, orientationOverride) }
        })
        job.join()
    }

    /**
     * 确认门唯一入口："按此方案生成"按钮；点击后执行策划 LLM（延迟到此刻——
     * 玩家确认后的范围才是策划输入，改主意不再白跑策划）并开始生成代码。
     * [uncheckedModules] 为卡片上被取消勾选的 module：本轮不实现、直接从方案中
     * 剔除（无 LLM 调用），但不视作玩家明确排除——不进任何"禁止实现"清单，
     * 后续文本点名该系统会自动恢复到范围。
     * [orientationOverride]/[dimensionOverride] 为卡片上的画面形态选择器：识别
     * 错了方向/维度（一票否决下游的错误）玩家在卡上直接改，优先级高于识别结果。
     */
    suspend fun confirmIntent(
        uncheckedModules: Set<String> = emptySet(),
        qualityTier: String = QualityTier.BALANCED,
        orientationOverride: String? = null,
        dimensionOverride: String? = null
    ) {
        val pending = _session.value.pendingConfirmation ?: return
        if (_session.value.isGenerating) return
        val safeTier = QualityTier.normalize(qualityTier)

        // 卡片形态选择器优先（玩家主权）：同值 no-op、同义归一（"横屏"→横板）、非法值忽略。
        val baseSchema = RecognitionEngine.applyOrientationDimension(
            pending.schema,
            orientationOverride,
            dimensionOverride
        )
        val schema = PlanningEngine.schemaApplyingUnchecked(baseSchema, uncheckedModules)

        // 立即生效：确认点击的第一时间翻转生成状态并锁定卡片——策划 LLM 需要
        // 2~40 秒，此前这段时间（isGenerating 仍 false、卡片仍 enabled、会话无
        // 任何变化）用户感知"点了没反应"并连点，造成多个确认回合并发（实测 4 连点
        // 启动了 4 个生成任务）。翻转后按钮即时禁用、聊天流出现"策划中"气泡。
        val pendingKey = IntentConfirmation.cardContent(pending)
        val lockedContent = IntentConfirmation.cardContent(
            pending.copy(
                uncheckedModules = uncheckedModules.sorted().distinct(),
                qualityTier = safeTier,
                visualDimension = schema.visualDimension,
                screenOrientation = schema.screenOrientation
            )
        )
        _session.update { s ->
            s.copy(
                messages = s.messages.map { msg ->
                    if (msg.isConfirmCard && msg.content == pendingKey) msg.copy(content = lockedContent) else msg
                },
                isGenerating = true,
                error = null,
                agentStage = "游戏策划中：正在定稿每个系统的实现方案",
                pendingConfirmation = null,
                gameSchema = schema,
                qualityTier = safeTier,
            )
        }
        alog("confirm: unchecked=[${uncheckedModules.joinToString(",")}] systems=${schema.gameSystems.joinToString(",")} tier=$safeTier form=${schema.visualDimension}/${schema.screenOrientation}")
        // 确认点击即起算（在即时翻转处，而非策划完成后）：策划属于本生成回合的
        // 耗时；若放在策划后重置，停止键计时会在策划完成瞬间跳回 0 秒。
        markTurnStart()

        // 回合所有权（根修复）：策划 LLM（2~40s）+ 代码生成作为同一个 job 运行在
        // agentScope 内并持有 turnMutex——此前策划跑在调用方协程（viewModelScope），
        // 用户切页清掉 ViewModel 会把策划砍断（会话已锁死在 isGenerating=true，
        // 生成永远不启动）；策划窗口内按停止键也取消不到它，停止后回合照样跑完。
        // 整体进 agentScope 后，停止键取消的就是完整回合，切页/息屏不再影响。
        val job = trackGenerationJob(agentScope.launch {
            turnMutex.withLock {
                runTurnSafely {
                    // 策划 LLM 异常时 draftWithLlm 内部回退静态种子（不抛出、不阻塞生成）；
                    // 不对策划调用设任何超时——停止键是用户侧唯一中断手段。
                    val llm = createClient()
                    if (llm == null) {
                        failTurn("请先在「设置」中配置 API Key、地址和模型")
                        return@runTurnSafely
                    }
                    val draftPlan = PlanningEngine.draftWithLlm(
                        schema = schema,
                        llm = llm,
                        existingTitle = _session.value.designPlan?.title,
                        currentUserRequest = pending.userRequest
                    )
                    currentCoroutineContext().ensureActive()
                    val finalPlan = PlanningEngine.finalize(
                        schema = schema,
                        confirmedDraft = draftPlan,
                        existingTitle = _session.value.designPlan?.title,
                        uncheckedModules = uncheckedModules
                    )
                    _session.update { s ->
                        s.copy(
                            agentStage = AgentStage.PLANNING,
                            designPlan = finalPlan
                        )
                    }
                    generateFromIntent(pending.userRequest, schema, _session.value.currentHtml, finalPlan)
                }
            }
        })
        job.join()
    }

    /**
     * 「再试一次」：重做上一条制作指令。
     * 已产出过游戏时若只是原样重发指令，模型会认为"已完成"而不做任何修改
     * （实测走 iterateDirect 后原样返回，触发"未做实际修改"失败）——因此明确
     * 要求忽略现有版本、用 writefile 写全新完整版本（允许整量替换）。
     * 尚无游戏/确认门待确认时，等价于重发原始指令走完整管线。
     */
    suspend fun regenerate(lastInstruction: String) {
        val s = _session.value
        if (s.isGenerating) return
        val trimmed = lastInstruction.trim()
        if (trimmed.isBlank()) return
        if (s.pendingConfirmation != null || !s.effectiveGameGenerated()) {
            sendUserMessage(trimmed)
            return
        }
        val html = s.currentHtml ?: return
        val schema = s.gameSchema
            ?: legacyAnchoredSchema(s)
            ?: GameSchema(confidence = 1.0)
        alog("regenerate: full remake of last instruction")
        _session.update { s ->
            s.copy(
                messages = s.messages + ChatMessage("user", "再试一次：重新制作"),
                isGenerating = true,
                error = null,
                agentStage = "代码生成中：正在重新制作",
                pendingConfirmation = null,
                gameSchema = schema,
            )
        }
        val job = trackGenerationJob(agentScope.launch {
            turnMutex.withLock {
                runTurnSafely {
                    markTurnStart()
                    generateFromIntent(
                        instruction = "用户点击了「再试一次」。请重新制作这款游戏：忽略现有版本，" +
                            "用 writefile 写入全新的完整版本（允许整量替换），并确保沙箱可运行。；原始需求：$trimmed",
                        intent = schema,
                        existingHtml = html,
                        forcePlan = s.designPlan ?: PlanningEngine.finalize(schema),
                        fixTurn = false,
                        // 用户主动要求的整体重做：修改范围契约对此放行。
                        allowEntryRewrite = true
                    )
                }
            }
        })
        job.join()
    }

    /** 把游戏运行时的 JS 错误交给 AI 修复（运行时错误格式：file:line + stack + console 片段）。 */
    suspend fun fixWithError(jsError: String) {
        if (_session.value.isGenerating) return
        val s = _session.value
        val html = s.currentHtml ?: run {
            _session.update { it.copy(error = "当前没有可修复的游戏代码") }
            return
        }
        val file = Regex("""(index\.html|[A-Za-z0-9_.-]+\.(?:html|js|css))""").find(jsError)?.value ?: "index.html"
        val line = Regex("""[:@](\d+)""").find(jsError)?.groupValues?.get(1)?.toIntOrNull()
        val normalized = ErrorSignature.normalize(jsError, file = file, line = line)
        val known = RetryBookkeeping.record(s.knownErrors, ErrorCategory.USER_RUNTIME, normalized)
        saveGlobalErrorSignatures(known)

        // 运行错误不做次数预算或自动降级：每次都以完整玩法为上下文继续修复，用户可无限等待。
        val instruction = "游戏运行时报错，请精准修复该错误（修改范围契约：只改与报错直接相关的代码，" +
            "其余一切游戏特性保持原样，不得顺带优化或重构）：\n$jsError"
        val messages = s.messages + ChatMessage("user", instruction)
        // schema 推导与其它回合同源：gameSchema → 旧版会话兼容推导 → 兜底空 Schema。
        val schema = legacyAnchoredSchema(s) ?: GameSchema(confidence = 1.0)
        _session.update { cur ->
            cur.copy(
                messages = messages,
                isGenerating = true,
                error = null,
                agentStage = AgentStage.FIXING,
                // 残留的待确认卡片随修复回合作废：否则修复完成后旧门重新可交互，
                // 与本轮已发生的修复语义脱节（其 pendingKey 也不再匹配历史卡）。
                pendingConfirmation = null,
                gameSchema = schema,
                knownErrors = known,
                lastError = jsError.take(500),
                lastErrorSignature = ErrorSignature.hash(normalized),
            )
        }
        markTurnStart()
        val job = trackGenerationJob(agentScope.launch {
            turnMutex.withLock {
                runTurnSafely {
                    generateFromIntent(
                        instruction = instruction,
                        intent = schema,
                        existingHtml = html,
                        forcePlan = s.designPlan,
                        fixTurn = true
                    )
                }
            }
        })
        job.join()
    }

    // ---------- 保存 ----------

    /**
     * 保存按钮：把编辑区（工作区）的游戏文件整体覆盖到运行区。
     * - 编辑已保存游戏：overwriteGameFiles 覆盖 games/<id>（版本化保留回滚历史）；
     * - 草稿入库：saveGame 新建 games/<id>。
     * 入口文件以会话当前发布版本为准（兼容回环的候选不经过工作区文件），
     * 其余工作区文件（scenarios.json 等回归断言）随保存一并入库。
     * 同时把当前会话上下文冻结为保存点（.savepoint），供"撤销"恢复。
     */
    suspend fun saveCurrentGame(title: String): GameMeta? {
        val s = _session.value
        // 生成中拒绝保存：此刻的 currentHtml 是未验收的中间版本，会话上下文也在
        // 流转中——存进运行区等于把半成品固化为"已保存版本"（撤销锚点同样失真）。
        // UI 侧已禁用入口，这里是最终闸门。
        if (s.isGenerating) return null
        val html = s.currentHtml ?: return null
        val editingId = editingGameId
        val cleanTitle = title.trim().ifBlank { defaultTitle(s) }
        // 先取编辑区文件（必须在切换 editingGameId 之前：草稿入库后工作区路径随 id 变化）
        val files = withContext(Dispatchers.IO) { currentWorkspaceFiles() } +
            (GameFileWorkspaceEntryPoint.DEFAULT to html)
        val savedState = s.copy(designPlan = s.designPlan?.copy(title = cleanTitle))
        if (editingId != null) {
            repository.overwriteGameFiles(editingId, files, s.messages, cleanTitle)
            persistAgentState(editingId, savedState)
            repository.writeSavepoint(editingId, s.messages, sessionJson.encodeToString(savedState))
            return repository.listGames().firstOrNull { it.id == editingId }
        }
        val description = s.messages.firstOrNull {
            it.isUser && !it.content.contains("推荐的游戏框架")
        }?.content?.trim()?.replace(Regex("\\s+"), " ")?.take(60) ?: ""
        val meta = repository.saveGame(cleanTitle, description, files, s.messages)
        editingGameId = meta.id
        repository.clearDraft()
        persistAgentState(meta.id, savedState)
        repository.writeSavepoint(meta.id, s.messages, sessionJson.encodeToString(savedState))
        return meta
    }

    /**
     * 撤销（保存的逆操作）：把保存区（games/<id>）的游戏文件覆盖回编辑区（工作区），
     * 会话上下文回滚到保存点（保存时冻结的快照）——未保存的修改与其后的对话记录被丢弃。
     * 仅对已保存游戏的编辑会话有意义（草稿没有保存点）。
     * 旧版本保存的游戏没有保存点：保存时的上下文不可恢复，撤销时清空对话
     * （只保留一条说明气泡），避免"文件已回滚、气泡还在描述未保存修改"的误导。
     * 返回 false 表示当前已在保存的版本上（无可撤销的修改）。
     */
    suspend fun undoToSaved(): Boolean {
        val editingId = editingGameId ?: return false
        val before = _session.value
        if (before.isGenerating) return false
        // 撤销要整体覆盖编辑区工作区文件，必须与回合互斥：tryLock 而非阻塞等待——
        // 等待意味着撞上正在启动的回合（isGenerating 翻转与回合抢锁之间的空窗），
        // 用户会被 UI 挂住整轮生成时长；拿不到锁直接返回"无可撤销"由用户重试。
        if (!turnMutex.tryLock()) return false
        var changed = false
        var committed = false
        try {
            withContext(Dispatchers.IO) {
                val savedHtml = repository.loadGameHtml(editingId) ?: return@withContext
                restoreWorkspaceFromSaved(editingId)
                val hasSavepoint = repository.loadSavepointAgentState(editingId) != null
                var restored: GameSession
                if (hasSavepoint) {
                    val spMessages = repository.loadSavepointSession(editingId).orEmpty()
                    restored = decodeSession(repository.loadSavepointAgentState(editingId))
                    if (spMessages.isNotEmpty()) restored = restored.copy(messages = spMessages)
                } else {
                    // 旧版本保存的游戏：保存点缺失，保存时的对话不可回放——清空对话与
                    // 过期摘要（摘要描述的是未保存版本），保留 Schema/策划稿供继续编辑。
                    // 对话既已清空，待确认门（若残留）一并作废，避免 ActionBar 被隐藏。
                    restored = decodeSession(repository.loadGameAgentState(editingId)).copy(
                        messages = emptyList(),
                        rollingSummary = "",
                        knownIssues = emptyList(),
                        lastError = null,
                        lastErrorSignature = null,
                        pendingConfirmation = null
                    )
                }
                changed = restored.messages != before.messages || before.currentHtml != savedHtml
                if (changed) {
                    val note = if (hasSavepoint) {
                        ChatMessage("assistant", "已撤销未保存的修改，回到上次保存的版本。")
                    } else {
                        ChatMessage(
                            "assistant",
                            "已回到上次保存的版本。该游戏由旧版本应用保存（无保存点），此前的对话记录已随撤销清空；" +
                                "游戏文件已恢复为保存区版本，可继续描述需求修改。"
                        )
                    }
                    restored = restored.copy(messages = restored.messages + note)
                }
                val finalState = restored.copy(
                    currentHtml = savedHtml,
                    isGenerating = false,
                    error = null,
                    agentStage = AgentStage.IDLE,
                    lastWarning = if (changed) "已撤销未保存的修改，回到上次保存的版本。" else null
                )
                // 乐观提交：读文件期间用户发起了新回合（引用已变化）则放弃会话回滚，
                // 避免吞掉其消息与生成状态；工作区文件已恢复无害——回合会用
                // currentHtml 重新对齐工作区（seedWorkspace）。
                committed = commitSessionIfUntouched(before) { finalState }
            }
        } finally {
            turnMutex.unlock()
        }
        if (committed) {
            alog("undo: workspace + context restored (game-$editingId, savepoint=$changed)")
            // 撤销结果同步进保存区实时会话副本：应用若在撤销后立即重启，重进编辑对话看到的也是回滚后的上下文。
            persistSessionMessages(_session.value)
        }
        return committed && changed
    }

    /**
     * 保存区文件整体覆盖编辑区工作区：保存区不存在的编辑区文件（未保存修改新增的
     * scenarios.json 等）一并清除，保证编辑区与保存区完全一致。
     */
    private suspend fun restoreWorkspaceFromSaved(gameId: Long) {
        val root = editingWorkspaceDir(gameId)
        val workspace = GameFileWorkspace(root)
        val savedNames = repository.listGameFiles(gameId)
            .filter { !it.isDirectory }
            .map { it.name }
            // 排除簿记与会话文件：current.txt/.sha256 是保存区平台的版本化簿记，
            // session/agent_state 是实时上下文（不是游戏文件）。
            .filter {
                it != "session.json" && it != "agent_state.json" &&
                    it != GameFileWorkspace.POINTER_FILE && !it.endsWith(".sha256")
            }
            .toSet()
        root.walkTopDown()
            .filter { it.isFile }
            .map { it.relativeTo(root).invariantSeparatorsPath }
            .filter { rel -> rel != GameFileWorkspace.POINTER_FILE && !rel.startsWith(".versions/") }
            .forEach { rel -> if (rel !in savedNames) workspace.delete(rel) }
        savedNames.forEach { name ->
            val content = repository.loadGameFile(gameId, name) ?: return@forEach
            if (name == GameFileWorkspaceEntryPoint.DEFAULT) {
                val current = workspace.read(name)
                when {
                    current == null -> workspace.writeInitial(name, content)
                    current != content -> workspace.writeUpdated(name, content)
                }
            } else {
                val f = File(root, name)
                f.parentFile?.mkdirs()
                f.writeText(content, Charsets.UTF_8)
            }
        }
    }

    /** 编辑区（工作区）的全部游戏文件；排除平台簿记（.versions 归档与 current.txt 指针）。 */
    private fun currentWorkspaceFiles(): Map<String, String> {
        val root = gameWorkspaceDir()
        val workspace = GameFileWorkspace(root)
        return root.walkTopDown()
            .filter { it.isFile }
            .map { it.relativeTo(root).invariantSeparatorsPath }
            .filter { rel -> rel != GameFileWorkspace.POINTER_FILE && !rel.startsWith(".versions/") }
            .associateWith { rel -> workspace.read(rel) ?: "" }
    }

    /** 游戏被删除后清理其编辑区目录，避免孤儿编辑文件夹残留。 */
    suspend fun deleteWorkspace(gameId: Long) {
        withContext(Dispatchers.IO) {
            runCatching { editingWorkspaceDir(gameId).deleteRecursively() }
        }
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

    private suspend fun runUserTurn(
        instruction: String,
        priorConfirmation: IntentConfirmation?,
        dimensionOverride: String? = null,
        orientationOverride: String? = null
    ) {
        turnMutex.withLock {
            alog("turn start: ${instruction.take(120)} priorConfirm=${priorConfirmation != null}")

            currentCoroutineContext().ensureActive()
            val s = _session.value
            if (!isConfigured()) {
                failTurn("请先在「设置」中配置 API Key、地址和模型")
                return@withLock
            }

            val llm = createClient()
            if (llm == null) {
                failTurn("请先在「设置」中配置 API Key、地址和模型")
                return@withLock
            }

            // 1) 意图层：dev / chat 二分类（只回答"要不要动游戏"一个问题）。
            //    确认门待确认期间的任何文本输入一律判定为"修正"：跳过意图分类，
            //    不落入 chat 回退，只会回到策划层重组摘要再次回显。
            val intent = if (priorConfirmation != null) {
                IntentDecision(
                    intent = IntentDecision.INTENT_DEV,
                    confidence = 1.0,
                    reason = "确认门待确认期间的文本输入一律视为修正"
                )
            } else {
                _session.update { it.copy(agentStage = AgentStage.INTENT) }
                classifyIntent(instruction, llm)
            }
            alog("intent=${intent.intent} conf=${intent.confidence} reason=${intent.reason}")
            currentCoroutineContext().ensureActive()
            if (intent.isChat) {
                generateChatReply(s, instruction, llm)
                return@withLock
            }

            // 修复快车道：不经文本分类，由会话状态推导——已锚定游戏且存在
            // 未消化的运行时错误时（成功回合会把 lastError 清空，非空即"新鲜"），
            // 跳过识别/策划/确认门直通 Agent Loop，指令以用户本轮诉求为准。
            if (hasAnchoredGame(s) && hasFreshRuntimeError(s)) {
                alog("route: fix fast-lane (fresh runtime error)")
                fixReportedIssue(s, instruction)
                return@withLock
            }

            // game_generated == true：会话已产出过游戏，dev 消息（修复或改进）
            // 跳过识别层/策划层/确认门，prompt（rolling summary + readfile 指引）
            // 后直达 Agent Loop；feature/bug 之分由模型结合代码与诉求在循环内判断。
            if (s.effectiveGameGenerated()) {
                alog("route: iterateDirect (gameGenerated)")
                iterateDirectly(s, instruction)
                return@withLock
            }

            // game_generated == false（首次创建）走完整管线：
            // 2) 识别层：检测会话级 Game Schema JSON：
            //    null  → 本对话还没有游戏 → 默认为首次制作；
            //    非 null → 已锚定一个游戏 → 在本轮消息上补全/覆盖同一份 JSON。
            _session.update { it.copy(agentStage = AgentStage.RECOGNITION) }
            val previousSchema = s.gameSchema ?: legacyAnchoredSchema(s)
            val localPatch = RecognitionEngine.extractLocal(instruction)
            val litePatch = if (localPatch.confidence < 0.8) {
                requestRecognitionFromLiteLlm(instruction, llm)
            } else {
                null
            }
            val recognition = RecognitionEngine.recognize(instruction, previousSchema, litePatch)
            currentCoroutineContext().ensureActive()

            // 意图层可能误判；识别层抽不到任何游戏实体时回退为普通文本聊天。
            // 确认门修正回合例外：即使本轮没抽到新实体（如口语化补充），也沿用
            // 上一版策划草案重组摘要、再次回显待确认，绝不退化为聊天回复。
            if (recognition.emptyEntities && priorConfirmation == null) {
                generateChatReply(s, instruction, llm)
                return@withLock
            }
            val baseSchema = if (recognition.emptyEntities) {
                priorConfirmation?.schema ?: recognition.gameSchema
            } else {
                recognition.gameSchema
            }
            // 识别结果先写回会话，后续策划/确认/生成都共享同一份 JSON。
            // 输入区形态选择器（玩家显式）优先于识别结果。
            val schema = RecognitionEngine.applyOrientationDimension(
                baseSchema, orientationOverride, dimensionOverride
            )
            _session.update { it.copy(gameSchema = schema) }

            // 契约分流：消息里没有显性的游戏特征系统（如"制作一个我的世界游戏"——
            // 只有对标名/裸需求）→ 直接开始制作，系统由生成 LLM 按档位与指令原话
            // 自行推导（识别层/Lite 的抽取仅作参考附注，不构成硬清单）；提到了
            // 具体机制和系统（如"塔防游戏，塔台4种，可升级和回收"）→ 走确认门，
            // 针对点名机制策划并用卡片回显确认。
            val explicitSystems = GameSystemCatalog.extract(instruction)
            if (explicitSystems.isEmpty() && priorConfirmation == null) {
                alog("route: direct-make (no explicit systems, ${schema.gameSystems.size} suggestions as reference)")
                val directSchema = schema.copy(gameSystems = emptyList(), requestedSystems = emptyList())
                val reference = schema.gameSystems.takeIf { it.isNotEmpty() }?.joinToString("、")
                val directInstruction = if (reference != null) {
                    instruction + "\n（识别层推测的游戏系统仅供参考、非实现清单：" + reference +
                        "。请按用户需求与指令原话自行推导最合适的系统组合并实现。）"
                } else {
                    instruction
                }
                generateFromIntent(
                    instruction = directInstruction,
                    intent = directSchema,
                    existingHtml = null,
                    forcePlan = PlanningEngine.build(directSchema, allowEmptySystems = true)
                )
                return@withLock
            }

            // 确认门（策划延迟）：卡片只回显识别结果与静态玩法说明，玩家确认后
            // 才执行策划 LLM——此前策划在确认前跑，玩家改主意/全不勾时这次策划
            // 就白跑了；修正回合的重组策划同样合并到确认时的一次调用里。
            // 确认门期间的任何文字输入都视为补充/修正；直到点击确认按钮才进入决策层。
            val effectiveRequest = if (priorConfirmation != null) {
                priorConfirmation.userRequest + "\n补充/修正：" + instruction
            } else {
                instruction
            }
            val confirmation = RecognitionEngine.buildConfirmation(
                userRequest = effectiveRequest,
                schema = schema,
                isNewGame = recognition.isNewGame,
                revised = priorConfirmation != null
            ).let { built ->
                priorConfirmation?.let { prior -> built.copy(qualityTier = prior.qualityTier) } ?: built
            }
            // 确认卡作为消息写入聊天流：确认/重组后卡片始终保留在对话中，
            // 仅最后一张与 pendingConfirmation 匹配的卡片可交互，其余只读回显。
            currentCoroutineContext().ensureActive()
            alog("gate card built: systems=${schema.gameSystems.joinToString(",")}")
            val next = updateSession { s2 ->
                s2.copy(
                    messages = s2.messages + ChatMessage(
                        ChatMessage.ROLE_CONFIRM_CARD,
                        IntentConfirmation.cardContent(confirmation)
                    ),
                    pendingConfirmation = confirmation,
                    gameSchema = schema,
                    isGenerating = false,
                    agentStage = AgentStage.CONFIRM,
                    error = null,
                )
            }
            persistSessionMessages(next)
        }
    }

    /** 意图层：正则优先，低置信度时用 Lite LLM 复核 new_feature / fix_bug / chat。 */
    private suspend fun classifyIntent(userText: String, llm: LlmClient): IntentDecision {
        val local = IntentLayer.inferLocally(userText)
        if (local.confidence >= 0.8) return local
        val reply = try {
            collectTextReply(
                llm,
                listOf(
                    ChatMessage("system", "你是意图分类器，只输出合法 JSON。"),
                    ChatMessage("user", IntentLayer.liteLlmPrompt(userText))
                )
            )
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
        return IntentLayer.parseLiteLlmReply(reply ?: "") ?: local
    }

    /** 会话是否已锚定一个游戏（有代码 / Game Schema / 设计稿 / 编辑中的已保存游戏）。 */
    private fun hasAnchoredGame(s: GameSession): Boolean =
        !s.currentHtml.isNullOrBlank() || s.gameSchema != null ||
            s.designPlan != null || editingGameId != null

    /**
     * 是否存在"未消化"的运行时错误：成功结束的回合会把 lastError 清空
     * （仅同签名熔断交付时保留），因此该字段非空即代表玩家回传过报错且
     * 尚未被成功修复——用它做修复快车道的确定性判据，替代文本猜测。
     */
    private fun hasFreshRuntimeError(s: GameSession): Boolean =
        s.lastError != null && s.lastErrorSignature != null

    /**
     * 修复快车道回合：会话有未消化的运行时错误 + 已锚定游戏时，带着现有代码
     * 直通 Agent Loop。指令不预设"功能特性不变"——玩家描述的"异常"可能
     * 实际是想要行为调整，以用户本轮诉求为准，优先最小改动修复。
     */
    private suspend fun fixReportedIssue(s: GameSession, instruction: String) {
        val html = s.currentHtml
        if (html.isNullOrBlank()) {
            failTurn("当前没有可修复的游戏代码；如想制作或改进游戏，请直接描述你的需求。")
            return
        }
        val schema = s.gameSchema
            ?: legacyAnchoredSchema(s)
            ?: GameSchema(confidence = 1.0)
        val fixInstruction = "玩家报告了游戏异常，请优先按异常修复处理（最小改动）；若玩家实际想要的是功能调整，以玩家本轮诉求为准：\n$instruction"
        _session.update { s ->
            s.copy(
                isGenerating = true,
                error = null,
                agentStage = AgentStage.FIXING,
                // 修复快车道可能越过待确认卡片（确认门期间报错回传的场景）：
                // 修复回合作废残留的确认门，避免修复完成后旧门重新可交互。
                pendingConfirmation = null,
                gameSchema = schema,
            )
        }
        generateFromIntent(
            instruction = fixInstruction,
            intent = schema,
            existingHtml = html,
            forcePlan = s.designPlan,
            fixTurn = true
        )
    }

    /**
     * 迭代直达回合（game_generated == true 的 dev 消息）：跳过识别层/策划层/
     * 确认门，以既有 Game Schema 与设计稿为上下文直通 Agent Loop——迭代频率
     * 远高于首次创建，每轮都过确认门的成本大于收益。需求解析由模型在循环内
     * 结合 rolling summary 与代码自行完成。
     */
    private suspend fun iterateDirectly(s: GameSession, instruction: String) {
        val html = s.currentHtml
        if (html.isNullOrBlank()) {
            failTurn("当前没有可编辑的游戏代码；如想制作游戏，请直接描述你的需求。")
            return
        }
        val schema = s.gameSchema
            ?: legacyAnchoredSchema(s)
            ?: GameSchema(confidence = 1.0)
        _session.update { s ->
            s.copy(
                isGenerating = true,
                error = null,
                agentStage = "游戏修改中：正在准备迭代上下文",
                pendingConfirmation = null,
                gameSchema = schema,
            )
        }
        generateFromIntent(
            instruction = instruction,
            intent = schema,
            existingHtml = html,
            forcePlan = s.designPlan ?: PlanningEngine.finalize(schema),
            fixTurn = false
        )
    }

    /** 识别层 Lite LLM：只补全 Game Schema 补丁 JSON，不含意图字段。 */
    private suspend fun requestRecognitionFromLiteLlm(
        userText: String,
        llm: LlmClient
    ): GameSchemaPatch? {
        val reply = try {
            collectTextReply(
                llm,
                listOf(
                    ChatMessage("system", "你是游戏特征识别器，只输出合法 JSON。"),
                    ChatMessage("user", RecognitionEngine.liteLlmPrompt(userText))
                )
            )
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
        return GameSchemaValidator.parseLiteLlmReply(reply ?: "")
    }

    /** 兼容旧会话：没有 gameSchema 但已有设计稿或代码时，视为已经锚定了一个游戏。 */
    private fun legacyAnchoredSchema(session: GameSession): GameSchema? {
        session.gameSchema?.let { return it }
        val plan = session.designPlan
        if (plan != null) {
            return GameSchema(
                visualDimension = plan.visualDimension,
                screenOrientation = plan.screenOrientation,
                gameSystems = plan.gameSystems,
                requestedSystems = plan.gameSystems,
                excludedSystems = plan.excludedSystems,
                confidence = 1.0
            )
        }
        return if (!session.currentHtml.isNullOrBlank() || editingGameId != null) {
            GameSchema(confidence = 1.0)
        } else {
            null
        }
    }

    private suspend fun generateChatReply(s: GameSession, instruction: String, llm: LlmClient) {
        _session.update { it.copy(isGenerating = true, agentStage = AgentStage.CHAT) }
        // chat 虽然不改文件，但回答要贴着当前会话：把 rolling summary 作为
        // 只读上下文注入，避免"它能联机吗"这类问题答非所问；同时附最近几轮
        // 对话——rolling summary 只描述游戏不描述聊天，缺它时多轮问答的第二问
        // 会丢失第一问的上下文。
        val summary = s.rollingSummary.takeIf { it.isNotBlank() }
        val reply = try {
            collectTextReply(
                llm,
                listOf(
                    ChatMessage("system", GamePrompt.chatSystemPrompt(summary)),
                    *chatHistoryMessages(s.messages),
                    ChatMessage("user", instruction)
                )
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failTurn(LlmFailureClassifier.userMessage(e) ?: "回复失败，请重试。", internalDetail = "对话 LLM 调用失败：${e.message}")
            return
        }
        val text = HtmlExtractor.nonCodeText(reply).ifBlank { "（空回复）" }
        val next = updateSession { s ->
            s.copy(
                messages = s.messages + ChatMessage(
                    "assistant",
                    text + "（耗时 ${formatAgentDuration(turnElapsedMs())}）"
                ),
                isGenerating = false,
                agentStage = AgentStage.DONE,
                error = null
            )
        }
        persistSessionMessages(next)
    }

    /**
     * chat 上下文的最近对话历史：仅取用户与 assistant 气泡（确认卡是结构化 UI 不进
     * 模型上下文），丢弃末位（那是本轮指令，已单独作为 user 消息发送）；单条截断
     * 防超长气泡撑爆上下文。条数取少（6 条）：chat 只需要"刚才聊了什么"的连续性。
     */
    private fun chatHistoryMessages(messages: List<ChatMessage>, limit: Int = 6): Array<ChatMessage> {
        val chatOnly = messages
            .filter { (it.isUser || it.role == "assistant") && !it.isConfirmCard }
        if (chatOnly.isEmpty()) return emptyArray()
        return chatOnly
            .takeLast(limit + 1)
            .dropLast(1)
            .map { it.copy(content = it.content.take(800)) }
            .toTypedArray()
    }

    /** Agent Loop 单轮产出的三种结局。 */
    private sealed class GenOutcome {
        data class Accepted(
            val html: String,
            val finalText: String,
            val rounds: Int,
            val report: ValidationReport,
            /** 回合结构指标（readfile 占比/断言失败/沙箱重试），进决策日志供跨游戏横向调优。 */
            val metricsNote: String? = null
        ) : GenOutcome()

        /**
         * 模型/网关不支持工具（首轮直接在正文给出整份代码）：降级为旧全量重写回环。
         * [roundsConsumed] 为工具模式已消耗的 LLM 轮次——兼容回环从这里续数，
         * 保证最终"共 N 轮"与实际 LLM 调用次数对齐（首个候选是这些轮次的产物）。
         */
        data class LegacyFallback(val firstHtml: String, val roundsConsumed: Int) : GenOutcome()

        /** 已通过 failTurn 通知用户，本轮结束。 */
        object Failed : GenOutcome()
    }

    private suspend fun generateFromIntent(
        instruction: String,
        intent: GameSchema,
        existingHtml: String?,
        forcePlan: DesignPlan?,
        fixTurn: Boolean = false,
        /** 用户明确要求重做的回合（再试一次）：入口文件允许整量重写。 */
        allowEntryRewrite: Boolean = false
    ) {
        val llm = createClient()
        if (llm == null) {
            failTurn("请先在「设置」中配置 API Key、地址和模型")
            return
        }

        // Agent Loop 主路径为工具模式：模型通过 readfile/writefile/editfile 亲自读写游戏
        // 工作区，每次写入后宿主自动运行基础校验并把报告作为观察回传；静态校验通过后
        // 跑同 WebView 内核的确定性 tick 冒烟测试验证可运行性；模型不再调用工具即自然
        // 终止。网关不支持 function calling 时按 LegacyFallback 降级为旧回环。
        val plan = forcePlan ?: PlanningEngine.finalize(intent)
        // 修复轮（运行时报错回传或修复快车道）：沿用已确认方案，只修异常，不重新定稿；
        // 迭代轮（game_generated 后的直达消息）同样沿用既有方案，由模型按诉求改代码。
        val isFixTurn = fixTurn
        val planningStage = when {
            isFixTurn -> "修复中：正在定位问题并保持玩法与系统特性不变"
            plan.gameSystems.isEmpty() -> "游戏策划中：未勾选具体系统，以用户原话为准准备生成"
            else -> "游戏策划中：正在根据确认结果定稿 ${plan.gameSystems.joinToString("、")}（已排除 ${plan.excludedSystems.size} 个系统）"
        }
        val decisionEntry = if (isFixTurn) {
            "修复轮：沿用已确认方案（${plan.templateClass}，${plan.implementations.size} 个系统不变），只修复异常、功能特性不变；文件计划 index.html"
        } else {
            "决策层定稿：${plan.templateClass}，实现 ${plan.implementations.size} 个系统、排除 ${plan.excludedSystems.size} 个系统；P0=${plan.p0Features.size} P1=${plan.p1Features.size} P2=${plan.p2Features.size}；文件计划 index.html（HTML→CSS→JS）"
        }
        _session.update { s ->
            s.copy(
                isGenerating = true,
                error = null,
                agentStage = planningStage,
                gameSchema = intent,
                designPlan = plan,
                filePlan = listOf("index.html"),
                decisionLog = s.decisionLog + decisionEntry
            )
        }

        // 工具沙箱工作区：会话级目录（草稿或已保存游戏），种子为当前代码版本。
        val workspace = GameFileWorkspace(gameWorkspaceDir())
        seedWorkspace(workspace, existingHtml)
        // 回归断言随保存入库、随编辑恢复：编辑会话的工作区缺失 scenarios.json 时
        // 从保存区带回（保存后继续编辑不丢断言套件——"修好新问题弄坏旧功能"的回归保护）。
        val editingIdForWs = editingGameId
        if (editingIdForWs != null && workspace.read(GameScenarios.FILE) == null) {
            repository.loadGameFile(editingIdForWs, GameScenarios.FILE)?.let { saved ->
                runCatching { workspace.writeInitial(GameScenarios.FILE, saved) }
            }
        }
        // 条件引入机制：本轮允许的内置引擎只算一次（提示词/执行器/写入校验/验收同源共用）。
        val allowedEngines = GameEngines.allowedFor(
            plan.visualDimension,
            plan.gameSystems,
            QualityTier.normalize(_session.value.qualityTier)
        )
        // 修改范围契约只在提示词层约束（scopeFence 注入 SCOPE_FENCE_RULE）：
        // 执行层不做检查或撤回——LLM 若仍主动发挥，由用户以停止键/撤销兜底。
        val scopeFence = existingHtml != null && !allowEntryRewrite
        val executor = GameToolExecutor(
            workspace,
            validate = { GameValidator.validate(it, allowedEngines) }
        )

        var outcome = runToolLoop(
            llm = llm,
            workspace = workspace,
            executor = executor,
            instruction = instruction,
            plan = plan,
            firstGeneration = existingHtml.isNullOrBlank(),
            seedHash = existingHtml?.let { GameFileWorkspace.sha256(it) },
            allowedEngines = allowedEngines,
            fixTurn = isFixTurn,
            scopeFence = scopeFence
        )
        if (outcome is GenOutcome.LegacyFallback) {
            outcome = runLegacyRewriteLoop(
                llm, instruction, plan, existingHtml, outcome.firstHtml,
                seedHash = existingHtml?.let { GameFileWorkspace.sha256(it) },
                startRound = outcome.roundsConsumed,
                allowedEngines = allowedEngines,
                fixTurn = isFixTurn,
                scopeFence = scopeFence
            )
        }
        val accepted = when (outcome) {
            is GenOutcome.Accepted -> outcome
            is GenOutcome.LegacyFallback -> return // 不可达：上面已消费
            GenOutcome.Failed -> return
        }

        // 编辑区与发布版本对齐：兼容回环的候选不经过工作区文件，验收通过后写回工作区——
        // 保证编辑文件夹始终持有当前版本（保存按钮从编辑区整体覆盖运行区）。
        syncWorkspaceToPublished(accepted.html)

        currentCoroutineContext().ensureActive()
        val elapsedMs = turnElapsedMs()
        // 结算后缀（轮次/耗时）直接拼接，不做任何长度裁切——对模型输出与用户可见
        // 信息都不截断（停止键是用户侧唯一干预手段）。
        val summaryBody = HtmlExtractor.nonCodeText(accepted.finalText).trim().ifBlank {
            if (existingHtml.isNullOrBlank()) {
                "游戏已制作完成，并通过沙箱运行验证。点击「立即游玩」试玩吧——如遇问题可让 AI 修复，也可以直接在这里继续提改进需求。"
            } else {
                "游戏已按你的要求更新，并通过沙箱运行验证。点击「立即游玩」确认效果；如遇问题或想继续调整，随时告诉我。"
            }
        }
        val assistantText = summaryBody + "（本次 Agent Loop 共 ${accepted.rounds} 轮，耗时 ${formatAgentDuration(elapsedMs)}）"
        val summary = buildRollingSummary(plan, accepted.report, accepted.html)
        currentCoroutineContext().ensureActive()
        alog("turn reply: rounds=${accepted.rounds} elapsedMs=$elapsedMs")
        val next = updateSession { s ->
            s.copy(
                messages = s.messages + ChatMessage("assistant", assistantText),
                currentHtml = accepted.html,
                gameGenerated = true,
                isGenerating = false,
                error = null,
                agentStage = AgentStage.DONE,
                pendingConfirmation = null,
                lastWarning = run {
                    // UI 零技术细节：不给用户展示校验 warning 原文（DOM id/资源缺失等
                    // 是内部检查项，修复是 Agent 职责），只保留结果性提示。
                    "已通过自动检查，建议实际试玩确认效果。"
                },
                rollingSummary = summary,
                fileManifest = workspace.manifest(),
                designPlan = plan,
                knownIssues = accepted.report.warnings.map { "${it.category}:${it.message}" },
                // 成功回合把"未消化的运行时错误"整体清空（lastError 与签名成对置空），
                // 修复快车道判据不再被陈旧签名污染；快照滚动保留上限条防会话无限膨胀。
                lastError = null,
                lastErrorSignature = null,
                snapshots = (s.snapshots + "index.html:${GameFileWorkspace.sha256(accepted.html)}").takeLast(MAX_SNAPSHOTS),
                decisionLog = s.decisionLog + listOf(
                    "第 ${accepted.rounds} 轮结束：基础校验 0 error / ${accepted.report.warnings.size} warning，沙箱冒烟通过，回合耗时 ${formatAgentDuration(elapsedMs)}（elapsedMs=$elapsedMs）",
                    accepted.metricsNote ?: "",
                    "已退出 Agent Loop 并交付；实际体验问题由玩家试玩后回传"
                ).filter { it.isNotBlank() },
            )
        }
        persistSession(next)
    }

    /**
     * 工具模式 Agent Loop：
     * 终止 = 模型某轮不再发起工具调用；验收 = 工作区当前 index.html 通过基础校验
     * 与沙箱冒烟测试，且修改轮（[seedHash] 非 null）相对种子版本发生了实际内容
     * 变化——防止模型不做任何 editfile 就空口宣称完成。
     * 交付约束：不存在"带错交付"——同一错误签名 3 次注入"换思路"、6 次起强制
     * "重写问题模块/换实现"，连续 10 次同一签名或总轮次达 40 时如实失败终止
     * （保留中间版本可试玩，属主动止损而非带错交付）；用户停止键可随时中断。
     */
    private suspend fun runToolLoop(
        llm: LlmClient,
        workspace: GameFileWorkspace,
        executor: GameToolExecutor,
        instruction: String,
        plan: DesignPlan,
        firstGeneration: Boolean,
        seedHash: String? = null,
        /** 本轮允许的内置引擎集合（generateFromIntent 一次算好传入，验收与写入校验同源）。 */
        allowedEngines: Set<String> = GameEngines.BUNDLED.keys,
        /** 修复轮（运行报错回传/修复快车道）：自检降为一轮回归检查，且不参与预算复核。 */
        fixTurn: Boolean = false,
        /** 修改范围契约：修改回合且未获重做授权时注入提示词（仅提示词层约束，执行层不检查）。 */
        scopeFence: Boolean = false
    ): GenOutcome {
        val customSystem = settingsRepository.settings.value.systemPrompt.trim()
        val messages = mutableListOf(
            ChatMessage("system", GamePrompt.systemPrompt(customSystem.ifBlank { null }, toolMode = true)),
            ChatMessage("user", buildToolContextMessage(instruction, plan, firstGeneration, scopeFence))
        )

        var round = 0
        var mutatedOnce = false
        var lastReport: ValidationReport? = null
        var stubborn: Map<String, Int> = emptyMap()
        var idleRounds = 0
        var nudgesLeft = 3
        // 可玩性自检轮随质量档位与回合类型分档：快速/轻量无自检、均衡一轮、精品两轮；
        // 修复轮除快速档外仅一轮回归自检。每轮自检后重新走校验+沙箱验收。
        val tier = QualityTier.normalize(_session.value.qualityTier)
        val selfReviews = ArrayDeque(GamePrompt.selfReviewPrompts(tier, fixTurn))
        // 精品档防敷衍增强（一次性）：自检期间零修改直通时注入丰富度增强指令。
        var enrichNudged = false
        var firstPassDone = false
        var mutatedSinceFirstPass = false
        // 功能断言（均衡/精品档）：scenarios.json 缺失时一次性 nudge 补写；
        // 带断言跑通后把结果作为有据自检的证据前缀（自检不再是无执行依据的自我感觉）。
        var scenarioNudged = false
        var scenarioEvidence: String? = null
        // 回合结构指标（跨游戏横向度量迭代健康度）：readfile 调用次数、断言失败累计、
        // 沙箱总尝试次数；同断言连续失败计数用于"修断言还是修游戏"的对齐路由。
        val scenarioFailCounts = mutableMapOf<String, Int>()
        var scenarioFailTotal = 0
        var readfileCalls = 0
        var sandboxAttempts = 0
        // 连续"既无工具调用也无代码输出"的轮数：≥2 判定模型不会 function calling，
        // 工具模式提示词禁止正文出代码会把它锁死——整体降级到兼容回环。
        var toollessRounds = 0
        // 已记录的 readfile 结果在消息历史中的下标：文件一旦被修改即失效，
        // 替换为过期提示——既防上下文膨胀（每读一次多几万 token 拷贝），
        // 也防模型拿旧拷贝当编辑依据导致 old_string 失配、浪费轮次。
        val readResultIndices = mutableListOf<Int>()

        while (true) {
            round++
            currentCoroutineContext().ensureActive()
            val file = GameFileWorkspaceEntryPoint.DEFAULT
            // 轮次动词按入口文件当前是否已存在判定（而非回合级 firstGeneration）：
            // 同一回合内首次写入 index.html 之后的轮次都是 editfile 增量修改，应显示"正在修改"。
            val fileExists = workspace.read(file) != null
            val genStage = if (fileExists) {
                "第 $round 轮 - 代码修改中（工具模式）：正在修改 $file"
            } else {
                "第 $round 轮 - 代码生成中（工具模式）：正在生成 $file"
            }
            _session.update { it.copy(agentStage = genStage) }

            val resp = try {
                callLlm(llm, messages, GameTools.specs(), stageLabel = genStage)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 账户额度/鉴权/模型类失败给出如实的"LLM 服务不可用"文案，不再误报为网络异常
                failTurn(LlmFailureClassifier.userMessage(e) ?: USER_MSG_NETWORK, internalDetail = "LLM 调用失败：${e.javaClass.simpleName}: ${e.message}")
                return GenOutcome.Failed
            }
            currentCoroutineContext().ensureActive()
            alog("round $round: textLen=${resp.text.length} toolCalls=[${resp.toolCalls.joinToString(",") { it.name }}]")

            // 终止分支：模型不再发起工具调用。
            if (resp.toolCalls.isEmpty()) {
                toollessRounds++
                val directHtml = HtmlExtractor.extract(resp.text).html
                val current = workspace.read(GameFileWorkspaceEntryPoint.DEFAULT)

                // 从未通过工具写入且工作区为空：要么降级（模型直接给了整份代码），
                // 要么提示模型必须用工具产出文件。
                // 工具失效降级：连续多轮无工具调用也无整份代码输出（不支持/未启用
                // function calling 的模型）。兼容回环允许正文输出完整 HTML，对这类
                // 模型是唯一可用通路（首次制作正是靠它成功的）。
                if (!mutatedOnce && toollessRounds >= 2) {
                    alog("route: legacy fallback (tool-less model, $toollessRounds rounds no tools)")
                    return GenOutcome.LegacyFallback(current ?: directHtml ?: "", round)
                }
                if (!mutatedOnce && current == null) {
                    if (directHtml != null) {
                        alog("route: legacy fallback (text html, no tools)")
                        return GenOutcome.LegacyFallback(directHtml, round)
                    }
                    if (nudgesLeft <= 0) {
                        failTurn(
                            USER_MSG_CHANGE_MODEL,
                            internalDetail = "模型未通过工具产出游戏代码（可能不支持 function calling）"
                        )
                        return GenOutcome.Failed
                    }
                    nudgesLeft--
                    messages += ChatMessage("user", "还没有任何游戏文件。请调用 writefile 写入完整 index.html；完成后停止调用工具并输出给玩家的总结。")
                    continue
                }

                // 中途退化：模型在正文直接给出整份代码 → 按隐式写入处理（等价 overwrite 写入）。
                // 修改范围契约只在提示词层约束，正文写入不做检查或撤回。
                var content = current
                if (directHtml != null && directHtml != current) {
                    val saved = if (current == null) {
                        workspace.writeInitial(GameFileWorkspaceEntryPoint.DEFAULT, directHtml)
                    } else {
                        workspace.writeUpdated(GameFileWorkspaceEntryPoint.DEFAULT, directHtml)
                    }
                    if (saved != null) {
                        content = directHtml
                        mutatedOnce = true
                        // 发布保留更大候选：修复提示后的赶工短版往往残缺（白屏），
                        // 中断试玩应拿到此前最完整的版本。
                        val published = if ((current?.length ?: 0) >= directHtml.length) current else directHtml
                        _session.update { it.copy(currentHtml = published) }
                    }
                }

                // 模型声称完成：以工作区内容为准重新校验，通过且（修改轮）确实
                // 发生了内容变化才接受；防止模型不做任何修改就宣称完成。
                if (content != null) {
                    val report = GameValidator.validate(content, allowedEngines)
                    lastReport = report
                    if (!report.hasErrors) {
                        val changed = seedHash == null || GameFileWorkspace.sha256(content) != seedHash
                        if (changed) {
                            if (!firstPassDone) {
                                firstPassDone = true
                                mutatedSinceFirstPass = false
                            }
                            // 快速档不做任何测试：跳过沙箱直接走交付判定；其余档位跑冒烟验证
                            // （失败则把错误作为观察回填继续修，同签名熔断同样生效）；
                            // 均衡/精品档携带工作区功能断言（scenarios.json）一并执行；
                            // 横板游戏以横屏视口运行（与真实游戏页一致）并断言主画布方向。
                            val expectLandscape = plan.screenOrientation == GameSchema.ORIENTATION_LANDSCAPE
                            val scenariosJson = smokeScenariosJson(workspace, tier)
                            val smoke = if (tier == QualityTier.FAST) null else runSmokeTest(content, scenariosJson, expectLandscape)
                            sandboxAttempts += smoke?.attempts ?: 0
                            if (smoke != null && smoke.message?.startsWith("sandbox-infra") == true) {
                                failTurn(
                                    USER_MSG_SANDBOX_ENV,
                                    internalDetail = "沙箱设施异常（${smoke.message}·尝试 ${smoke.attempts} 次），未交付"
                                )
                                return GenOutcome.Failed
                            }
                            alog("sandbox: passed=${smoke?.passed} frames=${smoke?.framesRun} attempts=${smoke?.attempts} errors=${smoke?.errors?.joinToString(";")?.take(400)}")
                            if (smoke != null && !smoke.passed) {
                                val smokeErrors = smokeActionableErrors(smoke)
                                // 超时且无任何真实错误：帧有推进=慢环境时序问题，按通过处理（落入下方
                                // 自检轮/交付流程），绝不作为可修错误回传；零帧推进=探针从未运行
                                //（离屏页面事件/定时器被冻结等设施问题），判运行验证环境异常，不放行交付。
                                if (smokeErrors.isEmpty() && smoke.framesRun <= 0) {
                                    alog("sandbox timeout with ZERO frames -> infra failure")
                                    failTurn(
                                        USER_MSG_SANDBOX_ENV,
                                        internalDetail = "沙箱超时且零帧推进（探针未运行，疑似离屏页面冻结），未交付"
                                    )
                                    return GenOutcome.Failed
                                }
                                if (smokeErrors.isEmpty()) alog("sandbox timeout WITHOUT errors -> treat as pass (slow env, frames=${smoke.framesRun})")
                                if (smokeErrors.isNotEmpty()) {
                                    // 断言失败记账与对齐路由：同一条断言连续多次失败时，
                                    // 大概率是断言与实现不对齐（字段名/阈值/帧预算）而非游戏 bug——
                                    // 快照显示系统行为合理时应修断言，别让模型反复改游戏逻辑空烧轮次。
                                    val failedLabels = smokeErrors.mapNotNull { err ->
                                        Regex("""scenario-fail\[([^\]]+)]""").find(err)?.groupValues?.get(1)
                                    }
                                    if (failedLabels.isNotEmpty()) {
                                        scenarioFailTotal += failedLabels.size
                                        failedLabels.forEach { label ->
                                            scenarioFailCounts[label] = (scenarioFailCounts[label] ?: 0) + 1
                                        }
                                    }
                                    val repeated = scenarioFailCounts.filter { it.value >= 3 }
                                        .map { (label, count) -> "「$label」已连续 $count 轮未通过" }
                                    val alignmentHint = if (repeated.isNotEmpty()) {
                                        "\n【断言对齐判定】${repeated.joinToString("；")}。请对照最近回传的实际快照重新判定：" +
                                            "若游戏状态本身合理（其它断言通过、系统按验收行为运行），是断言字段名/阈值/steps 帧预算与实现不符——" +
                                            "应修改 scenarios.json 对应条目（expect 或 steps），而不是继续改游戏逻辑；" +
                                            "确属游戏行为缺陷才修 index.html。"
                                    } else ""
                                    val normalized = ErrorSignature.normalize("冒烟测试失败:" + smokeErrors.joinToString(";"))
                                    val known = RetryBookkeeping.record(_session.value.knownErrors, ErrorCategory.USER_RUNTIME, normalized)
                                    saveGlobalErrorSignatures(known)
                                    // 写入 lastError：冒烟类熔断后的重试轮才有具体问题可修，
                                    // 否则重试轮上下文看不到任何遗留问题（rolling summary 是旧的"通过"）。
                                    _session.update { s ->
                                        s.copy(
                                            knownErrors = known,
                                            lastError = smokeErrors.firstOrNull() ?: "沙箱运行未通过",
                                            knownIssues = smokeErrors.take(5)
                                        )
                                    }
                                    stubborn = StubbornErrorTracker.update(
                                        stubborn,
                                        // 记账与反查必须用同一前缀（此前 sandbox/smoke 各一个，
                                        // worst 签名永远反查不回原文，升级提示丢失错误详情）。
                                        smokeErrors.map { StubbornErrorTracker.issueSignature("smoke", it) }.toSet()
                                    )
                                    val worst = StubbornErrorTracker.worst(stubborn)
                                    val maxRepeat = worst?.second ?: 0
                                    val stubbornDetail = worst?.let { (topSig, _) ->
                                        smokeErrors.firstOrNull { StubbornErrorTracker.issueSignature("smoke", it) == topSig }
                                    }
                                    appendEscalation(messages, maxRepeat, "沙箱运行", stubbornDetail)
                                    val scenarioHint = if (smokeErrors.any { it.startsWith("scenario-fail") }) {
                                        "\n其中 scenario-fail 为功能断言未通过：先对照实际快照判断是游戏行为问题（修 index.html 逻辑）" +
                                            "还是断言与实现不对齐（同步修 scenarios.json 的 expect/字段名），不要为通过断言而弱化游戏逻辑。"
                                    } else ""
                                    messages += ChatMessage(
                                        "user",
                                        "基础校验已通过，但冒烟测试未通过（确定性 tick ${smoke.framesRun} 帧）：" +
                                            smokeErrors.take(5).joinToString("；") +
                                            scenarioHint + alignmentHint +
                                            "\n请用 editfile 修复；沙箱跑通前不要结束。"
                                    )
                                    continue
                                }
                            }
                            // 沙箱通过（或慢环境超时无错误按通过）：先补功能断言缺口
                            // （一次性），再消费可玩性自检轮（内容完整性→体验与平台合规），
                            // 每轮自检后重新走校验+沙箱验收。
                            if (smoke != null && smoke.scenarioTotal > 0) {
                                scenarioEvidence = "沙箱功能断言已通过 ${smoke.scenarioPassed}/${smoke.scenarioTotal} 条：" +
                                    smoke.scenarioResults.joinToString("；") { it.take(80) } +
                                    "。以下自查请基于以上运行证据进行，不要重复已被断言验证的内容。"
                                alog("scenarios: ${smoke.scenarioPassed}/${smoke.scenarioTotal} " +
                                    smoke.scenarioResults.joinToString("|") { it.take(60) })
                            }
                            if (!fixTurn && !scenarioNudged && scenariosJson == null &&
                                GameScenarios.enabledForTier(tier)
                            ) {
                                scenarioNudged = true
                                alog("scenario nudge: scenarios.json missing (tier=$tier)")
                                _session.update { it.copy(agentStage = "校验中：正在补写功能断言") }
                                messages += ChatMessage("user", GamePrompt.scenarioNudgePrompt())
                                continue
                            }
                            val review = selfReviews.removeFirstOrNull()
                            if (review != null) {
                                alog("self-review round: ${review.first} (rounds so far=$round)")
                                _session.update { it.copy(agentStage = review.first) }
                                messages += ChatMessage("user", (scenarioEvidence?.plus("\n\n") ?: "") + review.second)
                                continue
                            }
                            // 精品档防敷衍：自检期间零修改直通时，一次性注入丰富度增强指令
                            //（仅生成轮；修复轮诉求是"最小改动修好"，不应被要求扩内容）。
                            if (!fixTurn && tier == QualityTier.PREMIUM && !enrichNudged && !mutatedSinceFirstPass) {
                                enrichNudged = true
                                alog("premium enrich nudge: no mutation during reviews")
                                _session.update { it.copy(agentStage = "校验中：丰富度增强（精品档）") }
                                messages += ChatMessage("user", GamePrompt.premiumEnrichPrompt())
                                continue
                            }
                            alog("ACCEPTED(tool): rounds=$round bytes=${content.toByteArray(Charsets.UTF_8).size} warnings=${report.warnings.size}")
                            val rfRatio = if (round > 0) readfileCalls * 100 / round else 0
                            val flowFlag = if (readfileCalls * 2 > round && round >= 10) {
                                "；readfile 占比偏高（切片重读浪费轮次，应整读+一轮并行）"
                            } else ""
                            val metricsNote = "结构指标：共 $round 轮（readfile 调用 $readfileCalls 次，占轮次 $rfRatio%）、" +
                                "场景断言失败累计 $scenarioFailTotal 次、沙箱尝试 $sandboxAttempts 次$flowFlag"
                            alog(metricsNote)
                            return GenOutcome.Accepted(content, resp.text, round, report, metricsNote)
                        }
                        if (nudgesLeft <= 0) {
                            failTurn(
                                "本轮没有产生新的修改。你可以重试，或直接描述想要的效果（如「再简单一点」「加个计分板」）。",
                                internalDetail = "模型未成功执行任何 editfile 即宣称完成（nudge 耗尽）"
                            )
                            return GenOutcome.Failed
                        }
                        nudgesLeft--
                        messages += ChatMessage(
                            "user",
                            "本轮还没有对 index.html 做任何实际修改，不能就此结束。请先 readfile 获取最新代码，" +
                                "再用 editfile 落实以下需求：$instruction"
                        )
                        continue
                    }
                    recordKnownErrors(report)
                    // 顽固错误追踪：同一条错误（类别+消息签名）连续 N 轮未被修掉才熔断；
                    // 集合的其它变化（修好一条/新增一条）不影响该条自身的连续计数。
                    stubborn = StubbornErrorTracker.update(
                        stubborn,
                        report.errors.map { StubbornErrorTracker.issueSignature(it.category, it.message) }.toSet()
                    )
                    val worst = StubbornErrorTracker.worst(stubborn)
                    val maxRepeat = worst?.second ?: 0
                    val stubbornDetail = worst?.let { (topSig, _) ->
                        report.errors.firstOrNull { StubbornErrorTracker.issueSignature(it.category, it.message) == topSig }?.message
                    }
                    appendEscalation(messages, maxRepeat, "校验", stubbornDetail)
                    alog("contract errors: ${report.errors.joinToString(";") { it.category + ":" + it.message.take(120) }}")
                    messages += ChatMessage(
                        "user",
                        validationFeedback(
                            report,
                            ErrorSignature.hash(ErrorSignature.normalize(report.errors.joinToString(";") { it.message }))
                        ) + "\n请继续用 editfile 修复上述问题；修复通过前不要结束。"
                    )
                    continue
                }

                // content == null 且 mutatedOnce：防御分支（工具写入后文件不应消失）。
                if (nudgesLeft <= 0) {
                    failTurn(
                        USER_MSG_CHANGE_MODEL,
                        internalDetail = "模型未通过工具产出游戏代码（可能不支持 function calling）"
                    )
                    return GenOutcome.Failed
                }
                nudgesLeft--
                messages += ChatMessage("user", "请调用 writefile 写入完整 index.html；完成后停止调用工具并输出给玩家的总结。")
                continue
            }

            // 工具执行分支：assistant 的调用请求 + 每个调用的观察结果回填进消息历史。
            // 有工具调用的轮次重置无工具轮计数：判定"模型不会 function calling"必须是
            // 连续无工具（此前只增不减，readfile 一轮夹在两次纯文本之间就会被误判降级）。
            toollessRounds = 0
            messages += ChatMessage("assistant", resp.text, toolCalls = resp.toolCalls)
            var mutatedThisRound = false
            for (call in resp.toolCalls) {
                currentCoroutineContext().ensureActive()
                _session.update { it.copy(agentStage = "工具执行中：${call.name}") }
                val outcome = executor.execute(call)
                if (outcome.name == GameTools.READ_FILE) readfileCalls++
                // 空转计数看"是否写入过任何文件"（含 scenarios.json）；
                // currentHtml 发布与读结果过期只由入口文件（index.html）变更触发。
                if (outcome.workspaceTouched) {
                    mutatedThisRound = true
                    mutatedOnce = true
                    if (firstPassDone) mutatedSinceFirstPass = true
                }
                if (outcome.mutated) {
                    lastReport = outcome.report
                    // 中间版本即刻发布：中断后玩家可立即试玩该版本。
                    _session.update { it.copy(currentHtml = outcome.html) }
                }
                alog("tool ${call.name}: ok=${outcome.ok} mutated=${outcome.mutated} obs=${outcome.observation.take(200)}")

                messages += ChatMessage(
                    ChatMessage.ROLE_TOOL,
                    outcome.observation,
                    toolCallId = outcome.callId
                )
                if (outcome.ok && outcome.name == GameTools.READ_FILE) {
                    readResultIndices += messages.lastIndex
                }
                if (outcome.mutated) {
                    // 此前所有 readfile 结果均已过期：折叠为提示，避免历史里堆积
                    // 多份完整文件拷贝（上下文膨胀）并误导后续 old_string。
                    for (i in readResultIndices) {
                        messages[i] = ChatMessage(
                            ChatMessage.ROLE_TOOL,
                            "（此读取结果已过期：文件已被修改。请依据最近一次 editfile 返回的修改点上下文继续编辑；如需全貌再重新 readfile。）",
                            toolCallId = messages[i].toolCallId
                        )
                    }
                    readResultIndices.clear()
                }
            }
            lastReport?.takeIf { it.hasErrors }?.let { recordKnownErrors(it) }
            idleRounds = if (mutatedThisRound) 0 else idleRounds + 1
            when {
                // 空转熔断：模型长时间只读不写（探索/兜圈）时如实终止回合——
                // 不是交付，也没有带错交付；用户可重试或换模型。
                idleRounds >= 24 -> {
                    failTurn(
                        USER_MSG_CHANGE_MODEL,
                        internalDetail = "连续 $idleRounds 轮无文件修改（空转熔断）"
                    )
                    return GenOutcome.Failed
                }
                idleRounds > 0 && idleRounds % 8 == 0 -> {
                    _session.update {
                        it.copy(
                            agentStage = "代码生成中：已连续 $idleRounds 轮没有文件修改，正在催促模型落盘"
                        )
                    }
                    messages += ChatMessage("user", "已连续 $idleRounds 轮没有文件修改。请直接完成 writefile / editfile 修改，或停止调用工具并输出最终总结。")
                }
            }
        }
    }

    /**
     * 兼容降级回环：网关/模型不支持 function calling（首轮即在正文输出整份 HTML）时，
     * 退回"全量重写 → 抽取 → 校验 → 文本反馈"循环；每轮重建 prompt，不累积历史。
     *
     * 兼容模式每轮都是整文件再生成（最贵路径），必须有硬上限：总轮次达
     * [LEGACY_MAX_ROUNDS] 即如实失败并保留最新候选版本，不做带错交付。
     */
    private suspend fun runLegacyRewriteLoop(
        llm: LlmClient,
        instruction: String,
        plan: DesignPlan,
        existingHtml: String?,
        firstCandidate: String,
        seedHash: String? = null,
        /** 工具模式降级前已消耗的轮次：兼容回环续数，保证总轮数与 LLM 调用次数对齐。 */
        startRound: Int = 1,
        /** 本轮允许的内置引擎集合（generateFromIntent 一次算好传入，与写入校验同源）。 */
        allowedEngines: Set<String> = GameEngines.BUNDLED.keys,
        /** 修复轮：自检降为一轮回归检查，且不参与预算复核（与工具模式同规则）。 */
        fixTurn: Boolean = false,
        /** 修改范围契约：修改回合且未获重做授权时注入提示词。 */
        scopeFence: Boolean = false
    ): GenOutcome {
        var workingHtml = existingHtml
        var feedback = ""
        // 兼容模式与工具模式共用同一组可玩性自检轮提示（随质量档位与回合类型分档），全部消费完才交付。
        val tier = QualityTier.normalize(_session.value.qualityTier)
        val legacyReviews = ArrayDeque(GamePrompt.selfReviewPrompts(tier, fixTurn))
        var round = startRound
        var lastReport: ValidationReport = ValidationReport()
        // 精品档防敷衍增强（一次性，与工具模式同款）：兼容模式同样堵"零修改直通自检"的敷衍交付。
        var legacyEnrichNudged = false
        val expectLandscape = plan.screenOrientation == GameSchema.ORIENTATION_LANDSCAPE

        /** 交付判定：精品档生成轮首次交付前一次性注入丰富度增强指令并返回 null（外层继续落实）；否则返回 Accepted。 */
        fun deliverOrNudge(html: String, reason: String): GenOutcome? {
            if (!fixTurn && tier == QualityTier.PREMIUM && !legacyEnrichNudged) {
                legacyEnrichNudged = true
                alog("premium enrich nudge (legacy/$reason)")
                _session.update { it.copy(agentStage = "校验中：丰富度增强（精品档）") }
                feedback = GamePrompt.premiumEnrichPrompt() + "\n请输出增强后的完整 HTML。"
                workingHtml = html
                return null
            }
            alog("ACCEPTED($reason): rounds=$round bytes=${html.toByteArray(Charsets.UTF_8).size}")
            return GenOutcome.Accepted(html, "", round, lastReport)
        }

        /** 通过（或慢环境超时按通过）后：先消费剩余自检轮，全部消费完才允许交付。 */
        fun consumeReviewOrDeliver(html: String, reason: String): GenOutcome? {
            val review = legacyReviews.removeFirstOrNull()
            if (review != null) {
                alog("self-review round (legacy/$reason): ${review.first}")
                feedback = review.second + "\n请输出修复或确认后的完整 HTML。"
                workingHtml = html
                _session.update { it.copy(agentStage = review.first) }
                return null
            }
            return deliverOrNudge(html, reason)
        }

        /**
         * 单个候选的统一验证分流（首候选与循环体共用一份实现）：
         * - 校验错误 / 沙箱可修错误 → 写好 feedback 返回 null（外层继续重写循环）；
         * - 通过或慢环境超时 → 消费自检轮或交付（非 null 为终局）；
         * - 设施异常 / 零帧超时 → failTurn 后返回 Failed，不放行交付。
         */
        suspend fun evaluateCandidate(html: String, reason: String, isSeedCandidate: Boolean): GenOutcome? {
            _session.update {
                it.copy(
                    currentHtml = html,
                    agentStage = "校验中：正在对 ${GameFileWorkspaceEntryPoint.DEFAULT} 做语法与基本逻辑校验",
                )
            }
            lastReport = GameValidator.validate(html, allowedEngines)
            currentCoroutineContext().ensureActive()
            if (lastReport.hasErrors) {
                recordKnownErrors(lastReport)
                feedback = validationFeedback(
                    lastReport,
                    ErrorSignature.hash(ErrorSignature.normalize(lastReport.errors.joinToString(";") { it.message }))
                )
                workingHtml = html
                _session.update { it.copy(agentStage = "校验中：正在为 ${GameFileWorkspaceEntryPoint.DEFAULT} 生成修复方案") }
                return null
            }
            // 防假修改闸（仅种子候选）：工具失效降级进来时 firstCandidate 可能就是种子原文——
            // 与种子完全一致的"成功"等于没改，要求输出包含修改的新版本。
            if (isSeedCandidate && seedHash != null && GameFileWorkspace.sha256(html) == seedHash) {
                feedback = "输出与当前版本完全一致。请在保持未要求部分不变的前提下，输出包含本次修改的完整 HTML。"
                workingHtml = html
                return null
            }
            // 静态校验通过：轻量及以上档位过沙箱冒烟才能交付（快速档跳过测试）；横板按横屏视口。
            val smoke = if (tier == QualityTier.FAST) null else runSmokeTest(html, landscape = expectLandscape)
            if (smoke != null && smoke.message?.startsWith("sandbox-infra") == true) {
                failTurn(USER_MSG_SANDBOX_ENV, internalDetail = "兼容模式沙箱设施异常（${smoke.message}·尝试 ${smoke.attempts} 次），未交付")
                return GenOutcome.Failed
            }
            if (smoke != null && !smoke.passed) {
                val smokeErrors = smokeActionableErrors(smoke)
                if (smokeErrors.isEmpty()) {
                    // 超时且无真实错误：零帧推进=探针从未运行（设施问题）不交付；
                    // 有帧推进=慢环境时序问题，按通过处理走自检/交付。
                    if (smoke.framesRun <= 0) {
                        alog("legacy sandbox timeout with ZERO frames -> infra failure ($reason)")
                        failTurn(USER_MSG_SANDBOX_ENV, internalDetail = "兼容模式沙箱超时且零帧推进（$reason），未交付")
                        return GenOutcome.Failed
                    }
                    return consumeReviewOrDeliver(html, "$reason-timeout-pass")
                }
                workingHtml = html
                _session.update { s ->
                    s.copy(
                        agentStage = "沙箱运行未通过：正在生成修复方案",
                        lastError = smokeErrors.firstOrNull() ?: "沙箱运行未通过",
                        knownIssues = smokeErrors.take(5)
                    )
                }
                feedback = "基础校验已通过，但冒烟测试未通过（确定性 tick ${smoke.framesRun} 帧）：" +
                    smokeErrors.take(5).joinToString("；") + "。请修复后重新输出完整 HTML。"
                return null
            }
            return consumeReviewOrDeliver(html, reason)
        }

        // 首候选（工具模式降级前已产出的正文 HTML）：直接进入统一验证分流。
        evaluateCandidate(firstCandidate, "legacy-first", isSeedCandidate = true)?.let { return it }

        while (true) {
            round++
            // 兼容模式每轮都是整文件再生成（最贵路径），必须有硬上限：
            // 超限如实失败并保留最新候选版本，不做带错交付。
            if (round > LEGACY_MAX_ROUNDS) {
                alog("legacy loop round cap reached: $round")
                failTurn(
                    USER_MSG_CHANGE_MODEL,
                    internalDetail = "兼容模式达轮次上限（$LEGACY_MAX_ROUNDS 轮，每轮全量重写的最贵路径止损熔断），已保留最新候选"
                )
                return GenOutcome.Failed
            }
            currentCoroutineContext().ensureActive()
            val file = GameFileWorkspaceEntryPoint.DEFAULT
            // 与工具模式同理：回合内已产出过候选版本（或本就有种子）后的轮次为"修改"
            val genStage = if (workingHtml.isNullOrBlank()) {
                "第 $round 轮 - 代码生成中（兼容模式）：正在生成 $file"
            } else {
                "第 $round 轮 - 代码修改中（兼容模式）：正在修改 $file"
            }
            _session.update { it.copy(agentStage = genStage) }

            val reply = try {
                callLlm(
                    llm,
                    buildGenerationMessages(
                        instruction = instruction,
                        plan = plan,
                        existingHtml = workingHtml,
                        feedback = feedback,
                        scopeFence = scopeFence
                    ),
                    emptyList(),
                    stageLabel = genStage,
                    salvageHtmlOnCancel = true
                ).text
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failTurn(LlmFailureClassifier.userMessage(e) ?: USER_MSG_NETWORK, internalDetail = "兼容模式 LLM 调用失败：${e.javaClass.simpleName}: ${e.message}")
                return GenOutcome.Failed
            }

            currentCoroutineContext().ensureActive()
            val extracted = HtmlExtractor.extract(reply)
            currentCoroutineContext().ensureActive()
            val candidate = extracted.html
            if (candidate == null) {
                feedback = "上一条回复无法抽取到完整 HTML（${extracted.error ?: "缺少 ```html 围栏或 <html> 标签"}）。请只输出一个完整 HTML 文件。"
                _session.update {
                    it.copy(lastError = feedback, agentStage = "校验中：正在检查输出是否为完整 HTML")
                }
                continue
            }

            evaluateCandidate(candidate, "legacy", isSeedCandidate = false)?.let { return it }
        }
    }

    /** 策划上下文 + 引擎范式契约 + 质量档位口径（工具模式与兼容回环共用一份组装）。 */
    private fun gameContractBlock(plan: DesignPlan): String = buildString {
        append(GamePrompt.planContext(plan))
        append("\n\n")
        append(
            GamePrompt.engineContract(
                plan.visualDimension,
                plan.gameSystems,
                QualityTier.normalize(_session.value.qualityTier)
            )
        )
        append("\n\n")
        append(GamePrompt.tierPrompt(QualityTier.normalize(_session.value.qualityTier)))
    }

    /** 工具模式上下文（最小化注入）：模板或 rolling summary + 指令 + 策划 Schema + 工作流。 */
    private fun buildToolContextMessage(
        instruction: String,
        plan: DesignPlan,
        firstGeneration: Boolean,
        /** 修改范围契约：修改回合且用户未明确要求重做时注入（仅提示词层约束，执行层不检查）。 */
        scopeFence: Boolean = false
    ): String = buildString {
        if (firstGeneration) {
            append("参考模板（这是可读取的 game_template.html，不是用户需求）：\n\n<game_template>\n")
            append(GamePrompt.readTemplate(appContext))
            append("\n</game_template>\n\n")
        } else {
            _session.value.rollingSummary.takeIf { it.isNotBlank() }?.let {
                append("rolling summary：\n$it\n\n")
            }
            // 上一轮失败的遗留问题：rolling summary 只在成功回合更新，失败后仍是
            // 旧的"全部通过"。不注入遗留问题，重试轮的模型会以为无事可做、
            // 空口宣称完成，然后死在"未做实际修改"上。
            val leftovers = leftoverIssues()
            if (leftovers.isNotBlank()) {
                append("【上一轮遗留问题（未解决，本轮最优先处理）】\n$leftovers\n\n")
            }
            append("游戏工作区已有 index.html，最新内容请通过 readfile 获取（上下文中的代码可能已过期）。\n\n")
        }
        // 知名游戏忠实度规则先于指令：对标的理解由 LLM 按指令原话自行完成，
        // 但"它实际怎么玩就做什么"的纪律必须显式注入，防通用清单把它拉偏。
        append(GamePrompt.KNOWN_GAME_FIDELITY_RULE)
        append("\n\n")
        // 修改范围契约（硬性）：修改回合只允许动用户点名的部分，既有逻辑保持原样。
        if (scopeFence) {
            append(GamePrompt.SCOPE_FENCE_RULE)
            append("\n\n")
        }
        append("用户指令：$instruction\n\n")
        append(gameContractBlock(plan))
        append("\n\n")
        append(GamePrompt.toolWorkflowPrompt(firstGeneration))
        append("\n\n")
        append(GamePrompt.playabilityRequirements())
    }

    /**
     * 上一轮失败的遗留问题（校验/冒烟错误 + known-issues 摘要），注入重试轮上下文。
     * lastError 非空 = 上一轮是失败回合（成功回合会清空），是注入的开关。
     */
    private fun leftoverIssues(): String = buildString {
        val s = _session.value
        if (!s.lastError.isNullOrBlank()) {
            append("- ").append(s.lastError.take(300)).append('\n')
            s.knownIssues.take(3).forEach {
                if (it.isNotBlank()) append("- ").append(it.take(160)).append('\n')
            }
        }
    }.trim()

    /** 工具沙箱根目录（编辑区）：草稿会话与已保存游戏各自独立，路径仍严格限制在该目录内。 */
    private fun gameWorkspaceDir(): File =
        editingGameId?.let { editingWorkspaceDir(it) }
            ?: File(appContext.filesDir, "agent/workspace/draft")

    /** 已保存游戏对应的编辑区目录。 */
    private fun editingWorkspaceDir(gameId: Long): File =
        File(appContext.filesDir, "agent/workspace/game-$gameId")

    /** 编辑区当前的入口文件内容（无编辑区或未写过文件时为 null）。 */
    private fun editingWorkspaceHtml(gameId: Long): String? =
        runCatching {
            GameFileWorkspace(editingWorkspaceDir(gameId)).read(GameFileWorkspaceEntryPoint.DEFAULT)
        }.getOrNull()

    /** 把发布版本写回编辑区工作区（内容一致则跳过）。 */
    private suspend fun syncWorkspaceToPublished(html: String) {
        val workspace = GameFileWorkspace(gameWorkspaceDir())
        val current = workspace.read(GameFileWorkspaceEntryPoint.DEFAULT)
        if (current == html) return
        if (current == null) {
            workspace.writeInitial(GameFileWorkspaceEntryPoint.DEFAULT, html)
        } else {
            workspace.writeUpdated(GameFileWorkspaceEntryPoint.DEFAULT, html)
        }
    }

    /**
     * 工作区与当前代码版本对齐：内容哈希一致则跳过，否则重置后写入 v1 种子。
     * 无种子（首次创建）时清掉跨会话残留的旧文件——工作区目录按会话复用，
     * 旧 index.html 若不清，会被新会话误当成"已有游戏"。
     */
    private suspend fun seedWorkspace(workspace: GameFileWorkspace, html: String?) {
        if (html.isNullOrBlank()) {
            workspace.delete(GameFileWorkspaceEntryPoint.DEFAULT)
            // 全新游戏不得继承上一款游戏的回归断言（字段体系完全不同，
            // 旧 scenarios.json 会让新游戏平白背上必失败的断言）。
            workspace.delete(GameScenarios.FILE)
            return
        }
        val current = workspace.read(GameFileWorkspaceEntryPoint.DEFAULT)
        if (current != null && GameFileWorkspace.sha256(current) == GameFileWorkspace.sha256(html)) return
        workspace.delete(GameFileWorkspaceEntryPoint.DEFAULT)
        workspace.writeInitial(GameFileWorkspaceEntryPoint.DEFAULT, html)
    }

    /** 校验失败写入全局错误签名库（只做历史记账，不参与重试限制）。 */
    private fun recordKnownErrors(report: ValidationReport) {
        val normalized = ErrorSignature.normalize(report.errors.joinToString(";") { it.message })
        val known = RetryBookkeeping.record(_session.value.knownErrors, classifyFailure(report), normalized)
        saveGlobalErrorSignatures(known)
        _session.update { s ->
            s.copy(
                knownErrors = known,
                knownIssues = report.warnings.map { "${it.category}:${it.message}" },
                lastError = report.errors.firstOrNull()?.message
            )
        }
    }

    /** 冒烟测试 runner：注入优先（测试/诊断），否则用同 WebView 内核的真实实现。 */
    private val smokeRunner: SmokeTestRunner? by lazy {
        injectedSmokeRunner ?: runCatching { AndroidSmokeTestRunner(appContext) }.getOrNull()
    }

    /**
     * 静态校验通过后的可运行性冒烟（确定性 tick）。无可可用 runner 或基础设施
     * 异常时返回 null 跳过——不能因测试设施问题阻塞交付；只有"游戏代码本身
     * 跑不过 tick"才判定失败并回填修复。
     */
    /** 沙箱设施异常哨兵：结果不可读/执行异常——不交付（沙箱通过才交付），以独立理由退出。 */
    private fun sandboxInfraResult(reason: String): SmokeTestResult = SmokeTestResult(
        passed = false,
        errors = listOf("沙箱执行环境异常"),
        message = "sandbox-infra:$reason"
    )

    /** 均衡/精品档读取工作区功能断言（档位不符/文件缺失/解析失败 → null，沙箱按无断言运行）。 */
    private fun smokeScenariosJson(workspace: GameFileWorkspace, tier: String): String? {
        if (!GameScenarios.enabledForTier(tier)) return null
        val scenarios = GameScenarios.readFromWorkspace(workspace)
        if (scenarios.isEmpty()) return null
        return GameScenarios.toJson(scenarios)
    }

    private suspend fun runSmokeTest(
        html: String,
        scenariosJson: String? = null,
        landscape: Boolean = false
    ): SmokeTestResult? {
        // null = 无可用 runner（JVM 单测）：跳过沙箱。真机上 runner 恒存在，
        // 设施异常一律返回哨兵并以"运行验证环境异常"退出——不借设施问题放行交付。
        val runner = smokeRunner ?: return null
        currentCoroutineContext().ensureActive()
        val stage = if (scenariosJson != null) {
            "冒烟测试中：正在沙箱验证游戏可运行与功能断言"
        } else {
            "冒烟测试中：正在沙箱验证游戏可运行"
        }
        _session.update { it.copy(agentStage = stage) }
        return try {
            // 精品档（deep 模式）：真实调用 restart() 完整重开后复跑半程帧。
            val result = runner.run(
                html,
                deep = QualityTier.normalize(_session.value.qualityTier) == QualityTier.PREMIUM,
                scenariosJson = scenariosJson,
                landscape = landscape
            )
            if (!result.passed && result.errors.isEmpty() && result.message != "smoke-timeout") {
                android.util.Log.w("GameAgent", "沙箱结果不可读：${result.message}")
                sandboxInfraResult("结果不可读(${result.message})")
            } else {
                result
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.w("GameAgent", "沙箱执行异常", e)
            sandboxInfraResult("执行异常(${e.message})")
        }
    }

    /**
     * 顽固错误的升级提示（仅提示，不终止）：3 次换思路、6 次起建议重写模块、
     * 之后周期性重复。bug 修复轮数不设上限（多轮属正常），退出方式为交付成功、
     * 无进展熔断（空转/无产出）、环境/网络异常或用户停止键。
     */
    private fun appendEscalation(
        messages: MutableList<ChatMessage>,
        signatureRepeat: Int,
        kind: String,
        stubbornDetail: String? = null
    ) {
        if (signatureRepeat >= 3) {
            alog("escalation: $kind x$signatureRepeat detail=$stubbornDetail")
            _session.update {
                it.copy(
                    agentStage = "校验中：同类$kind 错误已连续出现 $signatureRepeat 轮，正在要求模型更换实现思路"
                )
            }
        }
        val detail = stubbornDetail?.let { "「$it」" } ?: ""
        when {
            signatureRepeat == 3 -> messages += ChatMessage(
                "user",
                "注意：$kind 错误$detail 已连续出现 3 轮未修复。请更换实现思路（重构该函数 / 换数据结构 / 改变模块划分），而不是重复同样的修改。"
            )
            signatureRepeat == 6 -> messages += ChatMessage(
                "user",
                "$kind 错误$detail 已连续出现 6 轮，局部修补已失效。请停止打补丁：换一种完全不同的实现方式重写包含该问题的整个模块段落（editfile 整段替换或 writefile 均可，与该问题无关的部分保持原样），而不是重复同样的修改。"
            )
            signatureRepeat > 6 && signatureRepeat % 3 == 0 -> messages += ChatMessage(
                "user",
                "$kind 错误$detail 仍未解决（已连续 $signatureRepeat 轮）。请再次更换实现策略；反复失败时对问题模块做整体重写，未涉及的部分仍保持原样。"
            )
        }
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

    private fun classifyFailure(report: ValidationReport): ErrorCategory {
        val messages = report.errors.joinToString(" ") { it.category + ":" + it.message }
        return if (Regex("语法|syntax|解析失败").containsMatchIn(messages)) {
            ErrorCategory.SYNTAX
        } else {
            ErrorCategory.STATIC_RUNTIME
        }
    }

    /** 如实终止回合：[message] 面向用户（零技术细节）；[internalDetail] 只写入决策日志供诊断。 */
    private suspend fun failTurn(message: String, internalDetail: String? = null) {
        currentCoroutineContext().ensureActive()
        alog("FAIL: $message | detail=$internalDetail")
        val failed = updateSession { s ->
            s.copy(
                isGenerating = false,
                error = message + "（本轮耗时 ${formatAgentDuration(turnElapsedMs())}）",
                agentStage = AgentStage.FAILED,
                decisionLog = if (internalDetail.isNullOrBlank()) {
                    s.decisionLog
                } else {
                    s.decisionLog + "失败详情（仅决策日志，不对用户展示）：$internalDetail"
                }
            )
        }
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
            val editingId = editingGameId
            if (editingId != null) {
                // 编辑会话期间运行区（games/<id>，首页保存的游戏）不被未保存的修改覆盖：
                // 只同步会话簿记与 Agent 状态；游戏文件由保存按钮（saveCurrentGame）整体写入。
                repository.updateGameSessionOnly(editingId, s.messages)
                repository.saveGameAgentState(editingId, sessionJson.encodeToString(s))
            } else {
                val html = s.currentHtml ?: return@withContext
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
        append("实现清单：${plan.implementations.joinToString("；") { impl ->
            "${impl.system}(${impl.methods.joinToString("|")};玩家说明:${impl.playerFacing.ifBlank { "略" }};验收:${impl.acceptanceBoundary})"
        }}\n")
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
            AnthropicClient(
                okHttp = okHttp,
                apiKey = s.apiKey,
                baseUrl = s.baseUrl,
                model = s.model,
                maxTokens = preset.maxTokens,
                thinkingEnabled = s.thinkingEnabled
            )
        } else {
            // OpenAI 兼容协议不携带 max_tokens：输出长度由网关按模型上限裁定（不设限）
            OpenAiCompatibleClient(
                okHttp = okHttp,
                apiKey = s.apiKey,
                baseUrl = s.baseUrl,
                model = s.model,
                disableThinking = preset.disableThinking || !s.thinkingEnabled
            )
        }
    }

    private fun isConfigured(): Boolean = settingsRepository.isConfigured()

    private fun buildGenerationMessages(
        instruction: String,
        plan: DesignPlan,
        existingHtml: String?,
        feedback: String,
        /** 修改范围契约：修改回合且未获重做授权时注入提示词。 */
        scopeFence: Boolean = false
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
            append(GamePrompt.KNOWN_GAME_FIDELITY_RULE)
            append("\n\n")
            // 修改范围契约（硬性）：兼容回环的修改回合同样只允许动用户点名的部分。
            if (scopeFence) {
                append(GamePrompt.SCOPE_FENCE_RULE)
                append("\n\n")
            }
            append("用户指令：$instruction\n\n")
            append(gameContractBlock(plan))
            val leftovers = leftoverIssues()
            if (leftovers.isNotBlank()) {
                append("\n【上一轮遗留问题（未解决，本轮最优先处理）】\n$leftovers\n")
            }
            if (feedback.isNotBlank()) {
                append("\n【上一轮校验失败，必须修复】\n$feedback\n")
            }
            append(GamePrompt.codeContract())
            append("\n\n")
            append(GamePrompt.playabilityRequirements())
        }
        msgs += ChatMessage("user", body)
        return msgs
    }

    /** 文本类 LLM 调用（意图/识别/策划）：只收集文本，不携带工具。 */
    private suspend fun collectTextReply(
        llm: LlmClient,
        messages: List<ChatMessage>,
        stageLabel: String? = null
    ): String = callLlm(llm, messages, emptyList(), stageLabel).text

    /**
     * 统一 LLM 调用：流式回调只用于阶段进度回显（绝不复述源代码），最终结果以
     * [LlmResponse] 返回（文本 + 聚合后的工具调用）。[salvageHtmlOnCancel] 仅供
     * 兼容模式（全量重写回环）中断时抢救未收完的 HTML；工具模式正文不含代码，无需抢救。
     */
    /**
     * 统一 LLM 调用（带网络韧性）：对可重试异常（网络/超时/5xx/429）自动重试——
     * 后台运行（前台服务）下的瞬断恢复窗口明显长于前台：射频切换（Wi-Fi↔蜂窝）、
     * Doze 维护窗口间隙常需 30~60 秒才能恢复，真限流 429 的窗口也往往超过半分钟。
     * 因此预算为 5 次尝试、递增退避 5/15/30/60s（累计最多 110s），而不是此前的
     * 3 次 5s/15s（总 20s——后台瞬断经常跨不过这个窗口，直接以"网络连接异常"失败）。
     * 鉴权/请求格式类 4xx 重试无意义，立即失败。
     */
    private suspend fun callLlm(
        llm: LlmClient,
        messages: List<ChatMessage>,
        tools: List<ToolSpec>,
        stageLabel: String? = null,
        salvageHtmlOnCancel: Boolean = false
    ): LlmResponse {
        var attempt = 0
        while (true) {
            attempt++
            try {
                return callLlmOnce(llm, messages, tools, stageLabel, salvageHtmlOnCancel)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 停止键/调整取消 OkHttp 调用会表现为 IO 异常：协程已取消时
                // 不再进入退避重试（实测 ADJUST 后白等 5s 并留下误导性重试日志）。
                currentCoroutineContext().ensureActive()
                val backoffMs = LLM_RETRY_BACKOFF_MS.getOrNull(attempt - 1)
                if (backoffMs == null || !LlmFailureClassifier.isRetryable(e)) throw e
                alog("LLM 调用失败（第 $attempt 次），${backoffMs / 1000}s 后自动重试：" +
                    "${e.javaClass.simpleName}: ${e.message?.take(140)}")
                _session.update { it.copy(agentStage = "网络波动，正在自动重试（第 $attempt/${LLM_RETRY_BACKOFF_MS.size} 次）…") }
                delay(backoffMs)
            }
        }
    }

    private suspend fun callLlmOnce(
        llm: LlmClient,
        messages: List<ChatMessage>,
        tools: List<ToolSpec>,
        stageLabel: String? = null,
        salvageHtmlOnCancel: Boolean = false
    ): LlmResponse {
        val sb = StringBuilder()
        var lastProgressMark = 0
        try {
            return llm.streamChat(
                messages = messages,
                onDelta = { delta ->
                    sb.append(delta)
                    if (stageLabel != null && sb.length - lastProgressMark >= 200) {
                        lastProgressMark = sb.length
                        // onDelta 运行在 OkHttp IO 线程，与 agent 协程/停止键并发写会话，
                        // 必须走原子 update（快照覆盖会丢其它线程刚写入的字段）。
                        _session.update { it.copy(agentStage = "$stageLabel 已接收 ${sb.length} 字符") }
                    }
                },
                onThinking = { /* 思考过程属于内部信号，不回显到前端 */ },
                onDone = {},
                tools = tools
            )
        } catch (e: CancellationException) {
            if (salvageHtmlOnCancel) {
                // 兼容模式的代码生成流被停止键中断时，抢救未收完的 HTML，
                // 让玩家仍可"立即游玩"中间版本。
                HtmlExtractor.extractPartial(sb.toString())?.let { partial ->
                    _session.update { s ->
                        s.copy(
                            currentHtml = partial,
                            lastWarning = "已中断。当前版本可能不完整，可试玩或继续。",
                            agentStage = AgentStage.INTERRUPTED
                        )
                    }
                }
            }
            throw e
        }
    }

    // ---------- 错误签名库（跨会话持久化，只记录错误历史，不参与重试限制） ----------

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

    private fun decodeSession(persisted: String?): GameSession {
        if (persisted.isNullOrBlank()) return GameSession()
        val decoded = runCatching { sessionJson.decodeFromString<GameSession>(persisted) }.getOrDefault(GameSession())
        // 旧会话没有 gameGenerated 字段：已存在游戏代码即视为已生成。
        return if (decoded.gameGenerated || decoded.currentHtml.isNullOrBlank()) {
            decoded
        } else {
            decoded.copy(gameGenerated = true)
        }
    }

}
