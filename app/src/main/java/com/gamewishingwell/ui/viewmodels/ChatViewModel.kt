package com.gamewishingwell.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gamewishingwell.agent.GameAgent
import com.gamewishingwell.agent.GameSession
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

    fun send(text: String) {
        val trimmed = text.trim()
        if (trimmed.isBlank()) return
        lastInstruction = trimmed
        viewModelScope.launch { agent.sendUserMessage(trimmed) }
    }

    fun regenerate() {
        lastInstruction?.let { instr ->
            viewModelScope.launch { agent.regenerate(instr) }
        }
    }

    fun stopGeneration() {
        agent.stopGeneration()
    }

    fun fixError(error: String) {
        viewModelScope.launch { agent.fixWithError(error) }
    }

    /** 确认门入口；[uncheckedModules] 为卡片上取消勾选、玩家不希望实现的系统。 */
    fun confirmIntent(uncheckedModules: Set<String> = emptySet()) {
        viewModelScope.launch { agent.confirmIntent(uncheckedModules) }
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
