package com.gamewishingwell.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gamewishingwell.agent.GameAgent
import com.gamewishingwell.agent.GameSession
import com.gamewishingwell.agent.QualityTier
import com.gamewishingwell.data.GameMeta
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class ChatViewModel(
    private val agent: GameAgent,
    val gameId: Long?,
    private val resumeDraft: Boolean = false
) : ViewModel() {

    val session: StateFlow<GameSession> = agent.session
    private var lastInstruction: String? = null

    init {
        viewModelScope.launch {
            when {
                gameId != null -> agent.loadGameSession(gameId)
                resumeDraft -> agent.loadDraftSession()
                // 底部"创作"入口：永远从空会话开始，但暂不删除草稿文件
                else -> agent.newSession(clearDraft = false)
            }
        }
    }

    /** 发送用户消息；确认门待确认期间 [qualityTier] 携带当前质量档位，修正重建卡不丢失用户选择；
     *  [dimension]/[orientation] 为首次对话输入区周围的形态选择（"自动"=null 跟随识别）。 */
    fun send(text: String, qualityTier: String? = null, dimension: String? = null, orientation: String? = null) {
        val trimmed = text.trim()
        if (trimmed.isBlank()) return
        lastInstruction = trimmed
        viewModelScope.launch { agent.sendUserMessage(trimmed, qualityTier, dimension, orientation) }
    }

    fun regenerate() {
        lastInstruction?.let { instr ->
            viewModelScope.launch { agent.regenerate(instr) }
        }
    }

    fun stopGeneration() {
        agent.stopGeneration()
    }

    /** 本轮 Agent Loop 已运行时长（停止键计时显示用；确认门等待不计入）。 */
    fun turnElapsedMs(): Long = agent.currentTurnElapsedMs()

    fun fixError(error: String) {
        viewModelScope.launch { agent.fixWithError(error) }
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
        viewModelScope.launch { agent.confirmIntent(uncheckedModules, qualityTier, orientation, dimension) }
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
