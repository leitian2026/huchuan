package com.hulian.transfer

import android.app.Application

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Hub.init(this)
    }
}
