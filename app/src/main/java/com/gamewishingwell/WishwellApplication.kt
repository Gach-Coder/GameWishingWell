package com.gamewishingwell

import android.app.Application
import com.gamewishingwell.agent.GameAgent
import com.gamewishingwell.data.GameRepository
import com.gamewishingwell.data.SettingsRepository

class AppContainer(app: Application) {
    val settingsRepository = SettingsRepository(app)
    val gameRepository = GameRepository(app)
    val gameAgent = GameAgent(app, gameRepository, settingsRepository)
}

class WishwellApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
