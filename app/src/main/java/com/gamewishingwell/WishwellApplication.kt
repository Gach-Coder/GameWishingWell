package com.gamewishingwell

import android.app.Application
import android.content.Context
import com.gamewishingwell.agent.AgentLog
import com.gamewishingwell.agent.GameAgent
import com.gamewishingwell.agent.GameEngines
import com.gamewishingwell.agent.HtmlEnhancer
import com.gamewishingwell.data.GameRepository
import com.gamewishingwell.data.SettingsRepository
import java.util.concurrent.ConcurrentHashMap

class AppContainer(app: Application) {
    val settingsRepository = SettingsRepository(app)
    val gameRepository = GameRepository(app)
    val gameAgent = GameAgent(app, gameRepository, settingsRepository)

    init {
        // Agent Loop 运行细节日志（仅本地文件调试材料，前端不展示）
        AgentLog.init(app)
        // 内置引擎源码提供器：HtmlEnhancer 注入 ww-engine 声明的引擎（真机/沙箱同管道）。
        // 引擎源码惰性读取并缓存（three.min.js 约 600KB，只在首个 3D 游戏加载时读一次）。
        HtmlEnhancer.engineSourceProvider = assetEngineProvider(app)
    }

    private fun assetEngineProvider(context: Context): (String) -> String? {
        val cache = ConcurrentHashMap<String, String>()
        return { engine ->
            cache.getOrPut(engine) {
                val path = GameEngines.assetPath(engine)
                    ?: return@getOrPut "" // 未内置的引擎名：缓存缺失结果，避免每次渲染重复查询
                runCatching {
                    context.assets.open(path).bufferedReader().use { it.readText() }
                }.getOrDefault("")
            }.ifEmpty { null }
        }
    }
}

class WishwellApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
