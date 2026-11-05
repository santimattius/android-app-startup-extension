package com.santimattius.android.sample.usecases

import android.content.Context
import android.content.Intent
import android.os.Process
import com.santimattius.android.sample.ui.Route
import kotlin.system.exitProcess

/** Relaunches the app in a fresh process, optionally straight into [route]. */
object ProcessRestart {
    const val EXTRA_ROUTE = "route"

    fun restart(context: Context, route: Route? = null) {
        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            ?.apply { route?.let { putExtra(EXTRA_ROUTE, it.name) } }
            ?: return
        context.startActivity(intent)
        Process.killProcess(Process.myPid())
        exitProcess(0)
    }
}
