package com.imslept.screenannotator

import android.app.Application
import com.google.android.material.color.DynamicColors

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Prefs.init(this)
        // Material You: colori dinamici dello sfondo, dove disponibili (Pixel con Android 12+)
        DynamicColors.applyToActivitiesIfAvailable(this)
    }
}
