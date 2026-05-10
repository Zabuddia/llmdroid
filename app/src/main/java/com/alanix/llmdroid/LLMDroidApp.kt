package com.alanix.llmdroid

import android.app.Application
import com.alanix.llmdroid.data.SettingsStore

class LLMDroidApp : Application() {
    lateinit var settingsStore: SettingsStore
        private set

    override fun onCreate() {
        super.onCreate()
        settingsStore = SettingsStore(this)
    }
}
