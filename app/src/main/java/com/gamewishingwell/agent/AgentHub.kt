package com.gamewishingwell.agent

import android.content.Context
import com.gamewishingwell.data.GameRepository
import com.gamewishingwell.data.LlmSettings
import com.gamewishingwell.data.SettingsRepository
import com.gamewishingwell.llm.LlmClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * 多会话 Agent 注册表（对标 Zai/DSH 的独立会话模型）：每个对话（草稿或某个游戏的
 * 编辑会话）各持一个 [GameAgent] 实例，互不切换身份——进入"继续编辑"不再顶掉正在
 * 运行的其他会话 Agent Loop，多个游戏可同时编辑与生成。
 *
 * GameAgent 本身即"单会话引擎"（会话状态/工作区/回合互斥/生成协程均为实例级），
 * 本类只负责实例的生命周期与跨会话聚合：
 * - 键位："draft"（草稿，全局唯一）与 "game-<id>"（游戏编辑会话）；
 * - 草稿入库后实例身份升级（editingGameId → 新游戏 id），经 [GameAgent.onAdoptedGameId]
 *   回调重挂键位：同一实例续作该游戏，草稿键位让位给下次"创作"的新会话；
 * - [generating] 聚合全部会话的生成状态（前台服务观察它，通知按会话数描述）；
 * - 游戏删除/草稿重置时 [release]/[resetDraft] 驱逐缓存实例并停其生成。
 */
class AgentHub(
    private val appContext: Context,
    private val repository: GameRepository,
    private val settingsRepository: SettingsRepository,
    /** 测试注入点；为 null 时按 LlmSettings 创建真实厂商客户端（透传给 GameAgent）。 */
    private val injectedClientFactory: ((LlmSettings) -> LlmClient?)? = null,
    /** 冒烟测试器注入点（透传给 GameAgent）。 */
    private val injectedSmokeRunner: SmokeTestRunner? = null
) {
    private val agents = ConcurrentHashMap<String, GameAgent>()
    private val watchers = ConcurrentHashMap<GameAgent, Job>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _generating = MutableStateFlow(GenerationSummary(0, null))
    val generating: StateFlow<GenerationSummary> = _generating.asStateFlow()

    private val _runState = MutableStateFlow(SessionRunState())
    /** 按键位的会话运行视图（首页卡片动态标识观察）：draftStage 非空 = 草稿 Agent Loop
     *  进行中；gameStages 为正在运行的游戏会话（id → 阶段文案，如"代码生成中"）。
     *  与 [generating] 同源同时重算——会话翻转 isGenerating 或阶段推进即刷新。 */
    val runState: StateFlow<SessionRunState> = _runState.asStateFlow()

    /** 取（必要时创建）会话实例。[gameId] null = 草稿；游戏会话首次创建即在自身
     *  协程内装载持久化会话（编辑区优先），调用方经 GameAgent.awaitReady 等待完成。 */
    fun agentFor(gameId: Long?): GameAgent {
        val key = keyOf(gameId)
        return agents.computeIfAbsent(key) {
            val agent = GameAgent(
                appContext, repository, settingsRepository,
                injectedClientFactory, injectedSmokeRunner,
                pinnedGameId = gameId
            )
            if (gameId == null) {
                // 草稿入库时身份升级 → 重挂键位（见 rekey）。
                agent.onAdoptedGameId = { newId -> rekeyDraftTo(agent, newId) }
            }
            watch(agent)
            agent
        }
    }

    fun peek(gameId: Long?): GameAgent? = agents[keyOf(gameId)]

    /** 游戏删除：驱逐缓存实例（停其生成、释放协程）。工作区文件清理由调用方经
     *  草稿实例的 deleteWorkspace 完成（文件操作与实例身份无关）。 */
    fun release(gameId: Long) {
        agents.remove(keyOf(gameId))?.let { retire(it) }
    }

    /** 草稿重置（首页"新创作"入口）：驱逐草稿实例（停其生成）并以 clearDraft 语义
     *  新建——草稿文件同步删除。语义：点创作即弃旧草稿、打开新的空对话，单一
     *  草稿槽位由新对话持有，首条消息发送后覆盖落盘。挂起直到文件删除与会话
     *  重建完成才放行导航（历史缺陷：clearDraft=false 旧文件残留，ChatViewModel
     *  在空会话时装载旧草稿，"新的空对话"实际回显旧内容）。 */
    suspend fun resetDraft() {
        agents.remove(KEY_DRAFT)?.let { retire(it) }
        val fresh = agentFor(null)
        fresh.awaitReady()
        fresh.newSession(clearDraft = true)
    }

    /** 停止全部会话的生成（前台服务通知的"停止"动作，多会话并发生成时一网打尽）。 */
    fun stopAll() {
        agents.values.forEach { it.stopGeneration() }
    }

    fun shutdown() {
        scope.cancel()
        agents.values.forEach { it.shutdown() }
        agents.clear()
        watchers.clear()
    }

    /** 草稿实例入库升级：同一实例从 "draft" 挂到 "game-<id>"，会话续作不中断；
     *  新 id 不可能有旧实例，putIfAbsent 仅作防御。 */
    private fun rekeyDraftTo(agent: GameAgent, newId: Long) {
        agents.remove(KEY_DRAFT, agent)
        agents.putIfAbsent(keyOf(newId), agent)
        recompute()
    }

    private fun retire(agent: GameAgent) {
        watchers.remove(agent)?.cancel()
        agent.shutdown()
        recompute()
    }

    /** 会话状态聚合：任一实例的 session 变化即重算生成计数与代表阶段（前台服务观察）。 */
    private fun watch(agent: GameAgent) {
        watchers[agent] = scope.launch {
            agent.session.collect { recompute() }
        }
        recompute()
    }

    private fun recompute() {
        val active = agents.entries.filter { it.value.session.value.isGenerating }
        _generating.value = GenerationSummary(
            count = active.size,
            stage = active.firstOrNull()?.value?.session?.value?.agentStage
        )
        // "draft" 键 removePrefix 后不是数字，toLongOrNull 为 null 被过滤——两条键位一趟分拣。
        _runState.value = SessionRunState(
            draftStage = agents[KEY_DRAFT]
                ?.takeIf { it.session.value.isGenerating }?.session?.value?.agentStage,
            gameStages = active.mapNotNull { (key, agent) ->
                key.removePrefix("game-").toLongOrNull()?.let { it to agent.session.value.agentStage }
            }.toMap(),
            draftUnsaved = agents[KEY_DRAFT]?.session?.value?.unsavedVersion == true,
            gameUnsaved = agents.entries.mapNotNull { (key, agent) ->
                if (!agent.session.value.unsavedVersion) return@mapNotNull null
                key.removePrefix("game-").toLongOrNull()
            }.toSet(),
            // 中断续跑态（停止时工作区有未交付修改）：卡片「第 N+ 版」角标的实时来源
            gameInterrupted = agents.entries.mapNotNull { (key, agent) ->
                if (agent.session.value.resumableTurn == null) return@mapNotNull null
                key.removePrefix("game-").toLongOrNull()
            }.toSet()
        )
    }

    private fun keyOf(gameId: Long?): String =
        gameId?.let { "game-$it" } ?: KEY_DRAFT

    private companion object {
        const val KEY_DRAFT = "draft"
    }
}

/** 跨会话生成聚合：count=正在生成的会话数；stage 取其中一个会话的阶段文案（通知用）。 */
data class GenerationSummary(val count: Int, val stage: String?) {
    /** 通知文案：单会话直接用其阶段；多会话并发时前缀计数（零技术细节纪律不变）。 */
    fun displayStage(): String = when {
        count <= 1 -> stage ?: "正在生成"
        else -> "正在生成 $count 个游戏 · ${stage ?: ""}".trimEnd(' ', '·', ' ')
    }
}

/** 按键位的会话运行视图（首页卡片动态标识用）：draftStage=草稿阶段（null=未运行），
 *  gameStages=正在运行的游戏会话（gameId → 阶段文案）；unsaved 维度=Agent Loop
 *  已交付但未保存到运行区的新版本（保存/撤销后消失，随会话持久化）。 */
data class SessionRunState(
    val draftStage: String? = null,
    val gameStages: Map<Long, String> = emptyMap(),
    val draftUnsaved: Boolean = false,
    val gameUnsaved: Set<Long> = emptySet(),
    /** 存在停止中断的未交付修改（resumableTurn 非 null）的游戏：卡片「第 N+ 版」角标。 */
    val gameInterrupted: Set<Long> = emptySet()
)
