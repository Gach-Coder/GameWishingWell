package com.gamewishingwell.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gamewishingwell.agent.AgentHub
import com.gamewishingwell.agent.GameAgent
import com.gamewishingwell.agent.GameBundle
import com.gamewishingwell.agent.GameSchema
import com.gamewishingwell.agent.GameSession
import com.gamewishingwell.data.GameRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

@OptIn(ExperimentalCoroutinesApi::class)
class GameViewModel(
    private val repository: GameRepository,
    private val hub: AgentHub
) : ViewModel() {

    /** 本页绑定的会话实例（预览=对应游戏/草稿的编辑会话；运行区按 loadedGameId
     *  在报障修复时解析）。多会话并发：不再读写全局单会话。 */
    private var boundAgent: GameAgent? = null

    private val _agentSessionFlow = MutableStateFlow<StateFlow<GameSession>?>(null)
    val agentSession: StateFlow<GameSession?> = _agentSessionFlow
        .flatMapLatest { it ?: flowOf(null) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

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
            // 预览绑定对应会话实例（草稿预览=草稿会话；游戏预览=该游戏的编辑会话，
            // 冷启动时 hub 装载其持久化会话）；生成状态与保存栏位均来自该会话。
            val agent = hub.agentFor(gameId.takeIf { source == "preview" && it > 0 })
            boundAgent = agent
            _agentSessionFlow.value = agent.session
            agent.awaitReady()
            val (html, id, landscape) = withContext(Dispatchers.IO) {
                when {
                    // 预览：直接播放当前编辑会话（编辑区）的最新版本。编辑区与运行区
                    // 相互独立后，未按保存按钮写入的修改只存在于编辑区——立即游玩
                    // 必须走会话内存版本，而不是运行区（games/<id>）的入库版本。
                    // 多文件游戏经 GameBundle 把工作区辅助文件（js/css）内联进入口
                    // html，GameScreen 拿到的仍是自包含单页面（与单文件游戏同构）。
                    source == "preview" -> {
                        val s = agent.session.value
                        Triple(
                            agent.runnablePreviewHtml(),
                            gameId.takeIf { it > 0 },
                            s.gameSchema?.screenOrientation == GameSchema.ORIENTATION_LANDSCAPE
                        )
                    }
                    source == "game" && gameId > 0 -> {
                        val h = repository.loadGameHtml(gameId)
                        if (h != null) repository.touchPlay(gameId)
                        // 运行区多文件：入口 + 保存区文本文件（js/css 等）内联合并
                        val files = if (h != null) repository.loadGameTextFiles(gameId) else emptyMap()
                        val runnable = h?.let { GameBundle.inline(it) { rel -> files[rel] } }
                        Triple(runnable, gameId, sessionLandscape(repository.loadGameAgentState(gameId)))
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
     *  当前编辑会话的版本，直接在会话上修复，避免重载覆盖未保存的编辑状态。
     *  @return false 表示当前正在生成、报障未被受理（调用方应提示稍后再试，
     *  错误提示保留不消失——此前静默早退会让用户误以为修复已提交）。 */
    fun fixErrorAndGo(): Boolean {
        val err = _jsError.value ?: return false
        // 报障修复绑定到报错游戏的专属会话（hub 按需装载），不再切换全局会话——
        // 其他会话正在运行的 Agent Loop 不受影响。按加载源解析：预览=已绑定的
        // 编辑会话；运行区=报错游戏（loadedGameId）的会话；草稿播放=草稿会话。
        val agent = when {
            playSource == "preview" && boundAgent != null -> boundAgent!!
            playSource == "game" && _loadedGameId.value != null -> hub.agentFor(_loadedGameId.value)
            else -> hub.agentFor(null)
        }
        if (agent.session.value.isGenerating) return false
        _jsError.value = null
        viewModelScope.launch {
            agent.awaitReady()
            agent.fixWithError(err)
        }
        return true
    }

    fun saveDraft(title: String, onDone: (Boolean) -> Unit) {
        viewModelScope.launch {
            val agent = boundAgent ?: hub.agentFor(null)
            agent.awaitReady()
            val meta = agent.saveCurrentGame(title)
            onDone(meta != null)
        }
    }
}
