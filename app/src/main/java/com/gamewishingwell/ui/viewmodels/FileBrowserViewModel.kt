package com.gamewishingwell.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gamewishingwell.data.GameFileContent
import com.gamewishingwell.data.GameFileEntry
import com.gamewishingwell.data.GameRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** 只读文件查看器的状态：打开中 / 就绪 / 打不开（文件消失、路径非法等）。 */
sealed interface FileViewerState {
    data object Loading : FileViewerState
    data class Ready(val content: GameFileContent) : FileViewerState
    data object Failed : FileViewerState
}

/**
 * 文件可视系统：游戏存储文件夹的逐级浏览（点文件夹进入、返回键/返回箭头逐级退回）
 * 与只读打开文件（点文件加载内容，可选中复制、不可编辑）。
 */
class FileBrowserViewModel(private val repository: GameRepository) : ViewModel() {

    private val _entries = MutableStateFlow<List<GameFileEntry>?>(null)
    val entries: StateFlow<List<GameFileEntry>?> = _entries.asStateFlow()

    /** 当前子目录相对游戏根目录的路径（"" = 根目录，分隔符固定 "/"）。 */
    private val _currentPath = MutableStateFlow("")
    val currentPath: StateFlow<String> = _currentPath.asStateFlow()

    /** 非 null 时页面整体切换为文件查看器。 */
    private val _viewer = MutableStateFlow<FileViewerState?>(null)
    val viewer: StateFlow<FileViewerState?> = _viewer.asStateFlow()

    private var gameId: Long = -1L
    private var listJob: Job? = null
    private var viewerJob: Job? = null

    fun load(id: Long) {
        gameId = id
        _currentPath.value = ""
        _viewer.value = null
        refresh()
    }

    fun openFolder(name: String) {
        _currentPath.value = joinPath(_currentPath.value, name)
        refresh()
    }

    fun navigateUp() {
        val path = _currentPath.value
        if (path.isEmpty()) return
        _currentPath.value = path.substringBeforeLast('/', "")
        refresh()
    }

    fun openFile(name: String) {
        val relativePath = joinPath(_currentPath.value, name)
        viewerJob?.cancel()
        _viewer.value = FileViewerState.Loading
        viewerJob = viewModelScope.launch {
            _viewer.value = repository.readGameFile(gameId, relativePath)
                ?.let(FileViewerState::Ready)
                ?: FileViewerState.Failed
        }
    }

    fun closeFile() {
        viewerJob?.cancel()
        _viewer.value = null
    }

    private fun refresh() {
        listJob?.cancel()
        _entries.value = null
        listJob = viewModelScope.launch {
            _entries.value = repository.listGameFiles(gameId, _currentPath.value)
        }
    }

    private fun joinPath(base: String, name: String): String =
        if (base.isEmpty()) name else "$base/$name"
}
