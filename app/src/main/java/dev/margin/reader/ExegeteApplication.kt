package dev.margin.reader

import android.app.Application

class ExegeteApplication : Application() {
    lateinit var readium: ReadiumComponents
        private set

    override fun onCreate() {
        super.onCreate()
        readium = ReadiumComponents(this)
    }
}
