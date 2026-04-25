package com.echoshield.echonode

import android.app.Application
import android.util.Log

class EchoShieldApp : Application() {

    companion object {
        private const val TAG = "EchoShieldApp"
        
        @Volatile
        lateinit var instance: EchoShieldApp
            private set
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        Log.d(TAG, "EchoShield Application initialized")
    }
}
