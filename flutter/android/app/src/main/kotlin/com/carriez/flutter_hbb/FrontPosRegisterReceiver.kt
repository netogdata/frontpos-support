package com.carriez.flutter_hbb

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * FrontPOS Support: tilknytning fra forhandler-scriptet uden tryk på enheden:
 *   adb shell am broadcast -a dk.frontpos.support.REGISTER --es code ABCD2345 -n dk.frontpos.support/com.carriez.flutter_hbb.FrontPosRegisterReceiver
 *
 * Info : Beskyttet af android.permission.DUMP i manifestet, som kun adb (shell) og systemet har.
 *        Resultatet står i loggen (tag FrontPosSupport) og i broadcast-resultatet.
 */
class FrontPosRegisterReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val code = intent.getStringExtra("code")?.trim().orEmpty()
        if (intent.action != ACTION || code.isEmpty()) {
            return
        }
        val pending = goAsync()
        FrontPosSupport.register(context, code) { ok, message ->
            Log.i("FrontPosSupport", "register from adb: ok=$ok $message")
            pending.resultCode = if (ok) 1 else 0
            pending.resultData = message
            pending.finish()
        }
    }

    companion object {
        const val ACTION = "dk.frontpos.support.REGISTER"
    }
}
