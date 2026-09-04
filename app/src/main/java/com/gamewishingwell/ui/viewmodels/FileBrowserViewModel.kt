package com.gamewishingwell.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gamewishingwell.data.GameFileEntry
import com.gamewishingwell.data.GameRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** 文件可视系统：加载一个游戏存储文件夹的一级条目（null = 加载中）。 */
class FileBrowserViewModel(private val repository: GameRepository) : ViewModel() {

    private val _entries = MutableStateFlow<List<GameFileEntry>?>(null)
    val entries: StateFlow<List<GameFileEntry>?> = _entries.asStateFlow()

    fun load(gameId: Long) {
        viewModelScope.launch {
            _entries.value = repository.listGameFiles(gameId)
        }
    }
}
