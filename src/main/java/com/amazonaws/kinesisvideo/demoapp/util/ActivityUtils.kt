package com.amazonaws.kinesisvideo.demoapp.util

import kotlin.jvm.JvmOverloads
import java.lang.Class
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle

object ActivityUtils {
    private val NO_EXTRAS: Bundle? = null

    @JvmOverloads
    fun startActivity(
        context: Context,
        activityClass: Class<out Activity?>?,
        extras: Bundle? = NO_EXTRAS
    ) {
        val intent = Intent(context, activityClass)
        if (extras != null) {
            intent.putExtras(extras)
        }
        context.startActivity(intent)
    }
}