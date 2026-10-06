package com.gamewishingwell.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gamewishingwell.agent.AgentHub
import com.gamewishingwell.agent.GameAgent
import com.gamewishingwell.agent.GameBundle
import com.gamewishingwell.agent.GameConfigContract
import com.gamewishingwell.agent.GameSchema
import com.gamewishingwell.agent.GameSession
import com.gamewishingwell.agent.GameSettingOption
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
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

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

    /**
     * 玩家参数覆盖（参数外置契约）：仅运行区（source=game）装载——合法 JSON 才注入
     * （HtmlEnhancer 消费）；预览/草稿不注入（编辑区版本与保存区参数空间不同步）。
     */
    private val _configOverride = MutableStateFlow<String?>(null)
    val configOverride: StateFlow<String?> = _configOverride.asStateFlow()

    /** 当前游戏是否支持参数调整（合并视图定义了 GAME_CONFIG）：参数面板入口显隐依据。 */
    private val _configSupported = MutableStateFlow(false)
    val configSupported: StateFlow<Boolean> = _configSupported.asStateFlow()

    /** 游戏自定义设置项（WW_SETTINGS 声明）：游戏页设置面板渲染，即改即存走参数覆盖管道。 */
    private val _gameSettings = MutableStateFlow<List<GameSettingOption>>(emptyList())
    val gameSettings: StateFlow<List<GameSettingOption>> = _gameSettings.asStateFlow()

    /** 生效值查找缓存（load 时刷新）：override 优先、default 兜底——"检测"自动填当前值。 */
    private var defaultsObj: JsonObject? = null
    private var overrideObj: JsonObject? = null

    private val sessionJson = Json { ignoreUnknownKeys = true }

    /** 当前游戏页的加载源（game=运行区 / preview=编辑区 / draft=草稿）。 */
    private var playSource: String = "draft"

    /** 最近一次 load 的入参：参数覆盖保存后 reload 重读（html 相同、覆盖已变）。 */
    private var loadArgs: Pair<String, Long>? = null

    fun load(source: String, gameId: Long) {
        viewModelScope.launch {
            playSource = source
            loadArgs = source to gameId
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
            // 参数外置契约的运行区装载：覆盖文件原文 → 合法才注入；GAME_CONFIG 探测
            // 与默认表/参数档案提取都在合并视图上做（config.js 已内联，单文件游戏同样成立）。
            if (source == "game" && gameId > 0 && html != null) {
                val raw = repository.readUserConfig(gameId)
                val validOverride = raw?.takeIf { GameConfigContract.isValidOverrideJson(it) }
                _configOverride.value = validOverride
                _configSupported.value = GameConfigContract.hasConfigObject(html)
                val defaults = GameConfigContract.extractDefaults(html)
                defaultsObj = defaults
                overrideObj = validOverride
                    ?.let { runCatching { sessionJson.parseToJsonElement(it) as? JsonObject }.getOrNull() }
                _gameSettings.value = GameConfigContract.extractSettings(html) ?: emptyList()
            } else {
                _configOverride.value = null
                _configSupported.value = false
                _gameSettings.value = emptyList()
                defaultsObj = null
                overrideObj = null
            }
        }
    }

    /** 参数覆盖保存后的生效通道：重读运行区文件与覆盖（html 通常不变、覆盖已变，
     *  GameScreen 检测到 override 变化即整页重载——参数在游戏 init 时读取一次）。 */
    fun reload() {
        loadArgs?.let { (s, g) -> load(s, g) }
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

    /** 保存入口统一裁决：会话已绑定已命名游戏 → 直接覆盖保持原名（onDone）；
     *  未命名草稿 → 首次保存需命名（onNeedName，UI 弹框后经 saveDraft(title) 提交）。 */
    fun saveGame(onNeedName: () -> Unit, onDone: (Boolean) -> Unit) {
        viewModelScope.launch {
            val agent = boundAgent ?: hub.agentFor(null)
            agent.awaitReady()
            if (agent.boundGameId == null) onNeedName() else onDone(agent.saveCurrentGame("") != null)
        }
    }

    // ---------- 参数覆盖与自定义设置（参数外置契约·游戏页） ----------

    /** 设置项/参数的当前生效值：覆盖优先、默认兜底（字符串值免引号展示）。 */
    fun effectiveParamValue(name: String): String? {
        val el = overrideObj?.get(name) ?: defaultsObj?.get(name) ?: return null
        return if (el is JsonPrimitive) el.content else el.toString()
    }

    /**
     * "保存并生效"：把暂存的全部更改（键→输入原文）并入既有覆盖（同键盖顶）后整体落盘，
     * 成功即 reload（覆盖流变化 → 整页重载生效）。输入值经宽松解析（数字/布尔/数组按 JSON，
     * 其余按字符串）——写入前的 JSON 对象校验仍在仓库侧兜底。
     */
    fun saveUserConfigChanges(changes: List<Pair<String, String>>, onDone: (Boolean) -> Unit) {
        val id = _loadedGameId.value
        if (id == null || changes.isEmpty()) {
            onDone(false)
            return
        }
        viewModelScope.launch {
            val merged = LinkedHashMap<String, JsonElement>()
            overrideObj?.forEach { (k, v) -> merged[k] = v }
            changes.forEach { (k, raw) ->
                if (k.isNotBlank()) merged[k.trim()] = GameConfigContract.parseValueOrString(raw)
            }
            val ok = repository.writeUserConfig(id, JsonObject(merged).toString())
            if (ok) reload()
            onDone(ok)
        }
    }
}
