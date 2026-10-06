package com.gamewishingwell.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gamewishingwell.agent.AgentHub
import com.gamewishingwell.agent.ConfigParam
import com.gamewishingwell.agent.GameBundle
import com.gamewishingwell.agent.GameConfigContract
import com.gamewishingwell.agent.SessionRunState
import com.gamewishingwell.data.GameMeta
import com.gamewishingwell.data.GameRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.io.InputStream
import java.io.OutputStream

class HomeViewModel(
    private val repository: GameRepository,
    private val hub: AgentHub
) : ViewModel() {

    private val _games = MutableStateFlow<List<GameMeta>>(emptyList())
    val games: StateFlow<List<GameMeta>> = _games.asStateFlow()

    private val _hasDraft = MutableStateFlow(false)
    val hasDraft: StateFlow<Boolean> = _hasDraft.asStateFlow()

    /** 磁盘上的"未同步新版本"标记（冷启动：会话未实例化，直接扫 agent_state.json）；
     *  hub 装载会话后由 runState 的 unsaved 维度实时接管。 */
    private val _diskUnsaved = MutableStateFlow<DiskUnsaved>(DiskUnsaved())
    val diskUnsaved: StateFlow<DiskUnsaved> = _diskUnsaved.asStateFlow()

    /** 会话运行视图："继续上次创作"与各游戏卡片的动态运行标识直接观察它
     *  （draftStage / gameStages[id] 非空即该会话 Agent Loop 进行中）。 */
    val runState: StateFlow<SessionRunState> = hub.runState

    data class DiskUnsaved(
        val draft: Boolean = false,
        val games: Set<Long> = emptySet(),
        /** 停止中断（有未交付修改）的游戏：冷启动扫描，hub 装载后由 runState 接管。 */
        val interrupted: Set<Long> = emptySet()
    )

    init { refresh() }

    fun refresh() {
        viewModelScope.launch {
            _games.value = repository.listGames()
            _hasDraft.value = repository.hasDraft()
            _diskUnsaved.value = DiskUnsaved(
                repository.hasDraftUnsaved(),
                repository.listUnsavedGameIds(),
                repository.listInterruptedGameIds()
            )
        }
    }

    fun deleteGame(id: Long) {
        viewModelScope.launch {
            repository.deleteGame(id)
            // 会话实例退役（停其生成）+ 编辑区（工作区）与运行区独立：游戏删除后同步
            // 清理其编辑文件夹，避免孤儿残留（deleteWorkspace 是纯文件操作，经草稿实例执行）。
            hub.release(id)
            hub.agentFor(null).deleteWorkspace(id)
            refresh()
        }
    }

    fun renameGame(id: Long, newTitle: String) {
        viewModelScope.launch {
            repository.renameGame(id, newTitle)
            refresh()
        }
    }

    /**
     * 删除草稿（草稿卡片菜单）：等同"新创作"的清空语义——停掉草稿会话的生成并
     * 删除草稿文件（含草稿日志），完成后刷新首页（草稿卡片消失）。
     */
    fun deleteDraft() {
        viewModelScope.launch {
            hub.resetDraft()
            refresh()
        }
    }

    /** 游戏卡菜单「导出zip」：把 games/<id> 文件夹整体打包写入 SAF 目标 Uri 的输出流。 */
    suspend fun exportGameZip(gameId: Long, out: OutputStream): Boolean =
        repository.exportGameZip(gameId, out)

    /**
     * 首页「导入」按钮：解包用户选择的 zip 为新游戏（与「导出zip」互洽，
     * 新 id = 全新会话身份），成功后刷新列表并返回新游戏元数据，失败返回 null。
     */
    suspend fun importGameZip(input: InputStream): GameMeta? {
        val meta = repository.importGameZip(input)
        if (meta != null) refresh()
        return meta
    }

    // ---------- 参数调整（首页卡片菜单入口，参数外置契约·共享编辑器数据源） ----------

    private val json = Json { ignoreUnknownKeys = true }

    /** 当前打开参数调整的游戏的参数直列（名称/注释/生效值，覆盖优先）。 */
    private val _configParams = MutableStateFlow<List<ConfigParam>>(emptyList())
    val configParams: StateFlow<List<ConfigParam>> = _configParams.asStateFlow()

    /** 卡片菜单「参数调整」：装载该游戏保存区的参数直列（与游戏页同一提取管道：
     *  入口+辅助文件内联合并 → GAME_CONFIG 提取 → 注释/覆盖盖顶）。 */
    fun openConfigEditor(gameId: Long) {
        viewModelScope.launch {
            _configParams.value = withContext(Dispatchers.IO) {
                val html = repository.loadGameHtml(gameId)
                    ?: return@withContext emptyList()
                val files = repository.loadGameTextFiles(gameId)
                val merged = GameBundle.inline(html) { rel -> files[rel] }
                val defaults = GameConfigContract.extractDefaults(merged)
                val docs = GameConfigContract.extractParamDocs(merged)
                val override = repository.readUserConfig(gameId)
                    ?.takeIf { GameConfigContract.isValidOverrideJson(it) }
                    ?.let { runCatching { json.parseToJsonElement(it) as? JsonObject }.getOrNull() }
                defaults?.let { GameConfigContract.buildParamList(it, docs, override) } ?: emptyList()
            }
        }
    }

    /**
     * 首页编辑器「保存并生效」：更改并入既有覆盖（同键盖顶）写入 user_config——
     * 下次进入游戏即注入生效（首页无活动页面，不做整页重载）。
     */
    fun saveUserConfigChanges(gameId: Long, changes: List<Pair<String, String>>, onDone: (Boolean) -> Unit) {
        if (changes.isEmpty()) {
            onDone(false)
            return
        }
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) {
                val merged = LinkedHashMap<String, JsonElement>()
                repository.readUserConfig(gameId)
                    ?.takeIf { GameConfigContract.isValidOverrideJson(it) }
                    ?.let { runCatching { json.parseToJsonElement(it) as? JsonObject }.getOrNull() }
                    ?.forEach { (k, v) -> merged[k] = v }
                changes.forEach { (k, raw) ->
                    if (k.isNotBlank()) merged[k.trim()] = GameConfigContract.parseValueOrString(raw)
                }
                repository.writeUserConfig(gameId, JsonObject(merged).toString())
            }
            if (ok) openConfigEditor(gameId)   // 刷新直列（生效值=新覆盖）
            onDone(ok)
        }
    }
}
