package com.gamewishingwell

import android.app.Application
import com.gamewishingwell.agent.AgentLog
import com.gamewishingwell.agent.GameAgent
import com.gamewishingwell.data.GameRepository
import com.gamewishingwell.data.SettingsRepository

class AppContainer(app: Application) {
    val settingsRepository = SettingsRepository(app)
    val gameRepository = GameRepository(app)
    val gameAgent = GameAgent(app, gameRepository, settingsRepository)

    init {
        // Agent Loop 运行细节日志（仅本地文件调试材料，前端不展示）
        AgentLog.init(app)
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
