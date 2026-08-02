package dev.stackward

import android.app.Application
import dev.stackward.di.AppContainer

class StackwardApplication : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(applicationContext)
    }
}
