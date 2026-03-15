package io.github.derstrassi.karoofirefly

import android.app.Application
import android.util.Log
import timber.log.Timber

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Timber.plant(Timber.DebugTree())
        Log.i("LightController", "App.onCreate() - Timber initialized")
    }
}
