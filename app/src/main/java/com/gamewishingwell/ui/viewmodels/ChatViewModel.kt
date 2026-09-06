package com.gamewishingwell.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gamewishingwell.agent.AgentHub
import com.gamewishingwell.agent.GameAgent
import com.gamewishingwell.agent.GameSession
import com.gamewishingwell.agent.QualityTier
import com.gamewishingwell.data.GameMeta
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * 单个对话页的 ViewModel（多会话并发架构）：经 [AgentHub] 绑定本对话专属的
 * GameAgent 实例——进入"继续编辑"不再切换/打断其他会话正在运行的 Agent Loop。
 */
class ChatViewModel(
    hub: AgentHub,
    val gameId: Long?,
    private val resumeDraft: Boolean = false
) : ViewModel() {

    private val agent: GameAgent = hub.agentFor(gameId)
    val session: StateFlow<GameSession> = agent.session
    private var lastInstruction: String? = null

    init {
        viewModelScope.launch {
            agent.awaitReady()
            when {
                // 游戏会话：hub 构造时已装载（编辑区优先），此处无事可做。
                gameId != null -> {}
                // 草稿已有内容（含正在运行的生成）则直接续用——底部"创作"入口
                // 不再清掉进行中的草稿对话；仅真正空会话才装载/新建。
                resumeDraft -> if (!agent.hasConversation()) agent.loadDraftSession()
                else -> if (!agent.hasConversation()) agent.newSession(clearDraft = false)
            }
        }
    }

    /** 发送用户消息；确认门待确认期间 [qualityTier] 携带当前质量档位，修正重建卡不丢失用户选择；
     *  [dimension]/[orientation] 为首次对话输入区周围的形态选择（"自动"=null 跟随识别）。 */
    fun send(text: String, qualityTier: String? = null, dimension: String? = null, orientation: String? = null) {
        val trimmed = text.trim()
        if (trimmed.isBlank()) return
        lastInstruction = trimmed
        viewModelScope.launch { agent.awaitReady(); agent.sendUserMessage(trimmed, qualityTier, dimension, orientation) }
    }

    fun regenerate() {
        lastInstruction?.let { instr ->
            viewModelScope.launch { agent.awaitReady(); agent.regenerate(instr) }
        }
    }

    fun stopGeneration() {
        agent.stopGeneration()
    }

    /** 本轮 Agent Loop 已运行时长（停止键计时显示用；确认门等待不计入）。 */
    fun turnElapsedMs(): Long = agent.currentTurnElapsedMs()

    fun fixError(error: String) {
        viewModelScope.launch { agent.awaitReady(); agent.fixWithError(error) }
    }

    /**
     * 撤销（保存的逆操作）：保存区文件覆盖编辑区 + 上下文回滚到保存点。
     * 仅编辑已保存游戏的会话可用（草稿没有保存点）。
     */
    fun undoToSaved(onDone: (Boolean) -> Unit) {
        viewModelScope.launch { onDone(agent.undoToSaved()) }
    }

    /** 确认门入口；[uncheckedModules] 为卡片上取消勾选、玩家不希望实现的系统；
     *  [qualityTier] 为玩家选择的质量档位（fast/light/balanced/premium，默认均衡）；
     *  [orientation]/[dimension] 为卡片上的画面形态选择器（识别错了直接改）。 */
    fun confirmIntent(
        uncheckedModules: Set<String> = emptySet(),
        qualityTier: String = QualityTier.BALANCED,
        orientation: String? = null,
        dimension: String? = null
    ) {
        viewModelScope.launch { agent.awaitReady(); agent.confirmIntent(uncheckedModules, qualityTier, orientation, dimension) }
    }

    fun saveAs(title: String, onDone: (GameMeta?) -> Unit) {
        viewModelScope.launch {
            onDone(agent.saveCurrentGame(title))
        }
    }

    fun startNew() {
        viewModelScope.launch { agent.newSession() }
    }
}
