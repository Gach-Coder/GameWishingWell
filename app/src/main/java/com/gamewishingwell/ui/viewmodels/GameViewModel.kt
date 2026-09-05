package com.gamewishingwell.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gamewishingwell.agent.GameAgent
import com.gamewishingwell.agent.GameSchema
import com.gamewishingwell.agent.GameSession
import com.gamewishingwell.data.GameRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

class GameViewModel(
    private val repository: GameRepository,
    private val agent: GameAgent
) : ViewModel() {

    private val _html = MutableStateFlow<String?>(null)
    val html: StateFlow<String?> = _html.asStateFlow()

    private val _jsError = MutableStateFlow<String?>(null)
    val jsError: StateFlow<String?> = _jsError.asStateFlow()

    /** 当前加载的是否为已保存游戏及其 id（null = 草稿）。 */
    private val _loadedGameId = MutableStateFlow<Long?>(null)
    val loadedGameId: StateFlow<Long?> = _loadedGameId.asStateFlow()

    /** 游戏 Schema 的画面方向（true = 横板）：游戏页据此请求横屏呈现。 */
    private val _landscape = MutableStateFlow(false)
    val landscape: StateFlow<Boolean> = _landscape.asStateFlow()

    private val sessionJson = Json { ignoreUnknownKeys = true }

    /** 当前游戏页的加载源（game=运行区 / preview=编辑区 / draft=草稿）。 */
    private var playSource: String = "draft"

    fun load(source: String, gameId: Long) {
        viewModelScope.launch {
            playSource = source
            val (html, id, landscape) = withContext(Dispatchers.IO) {
                when {
                    // 预览：直接播放当前编辑会话（编辑区）的最新版本。编辑区与运行区
                    // 相互独立后，未按保存按钮写入的修改只存在于编辑区——立即游玩
                    // 必须走会话内存版本，而不是运行区（games/<id>）的入库版本。
                    source == "preview" -> {
                        val s = agent.session.value
                        Triple(
                            s.currentHtml,
                            gameId.takeIf { it > 0 },
                            s.gameSchema?.screenOrientation == GameSchema.ORIENTATION_LANDSCAPE
                        )
                    }
                    source == "game" && gameId > 0 -> {
                        val h = repository.loadGameHtml(gameId)
                        if (h != null) repository.touchPlay(gameId)
                        Triple(h, gameId, sessionLandscape(repository.loadGameAgentState(gameId)))
                    }
                    else -> {
                        Triple(repository.loadDraftHtml(), null, sessionLandscape(repository.loadDraftAgentState()))
                    }
                }
            }
            _html.value = html
            _loadedGameId.value = id
            _landscape.value = landscape
            _jsError.value = null
        }
    }

    /** 从持久化的 agent_state.json 读画面方向；缺失/解析失败按竖版处理（不强制旋转）。 */
    private fun sessionLandscape(raw: String?): Boolean = runCatching {
        raw?.let {
            sessionJson.decodeFromString<GameSession>(it).gameSchema?.screenOrientation ==
                GameSchema.ORIENTATION_LANDSCAPE
        } ?: false
    }.getOrDefault(false)

    fun onJsError(message: String) {
        // 同一页面反复报同一个错时保留第一条，避免覆盖层闪烁；
        // 注入的错误（含行号，[游戏错误] 前缀）优先于浏览器原生 Uncaught 提示
        val current = _jsError.value
        if (current == null) {
            _jsError.value = message
        } else if (!current.startsWith("[游戏错误]") && message.startsWith("[游戏错误]")) {
            _jsError.value = message
        }
    }

    fun dismissError() {
        _jsError.value = null
    }

    /** 让 AI 修复运行时报错：先确保 agent 会话与当前游戏一致，再发起修复。
     *  运行区（game）游玩页的报障重载会话后修复；预览页（preview）本身就是
     *  当前编辑会话的版本，直接在会话上修复，避免重载覆盖未保存的编辑状态。 */
    fun fixErrorAndGo() {
        val err = _jsError.value ?: return
        _jsError.value = null
        viewModelScope.launch {
            val id = _loadedGameId.value
            if (playSource == "game" && id != null) agent.loadGameSession(id)
            agent.fixWithError(err)
        }
    }

    fun saveDraft(title: String, onDone: (Boolean) -> Unit) {
        viewModelScope.launch {
            val meta = agent.saveCurrentGame(title)
            onDone(meta != null)
        }
    }
}
