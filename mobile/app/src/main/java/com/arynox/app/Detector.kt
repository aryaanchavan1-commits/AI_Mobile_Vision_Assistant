package com.arynox.app

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.StatFs

object Detector {
    /** RAM in GB */
    fun ramGb(context: Context): Double {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val info = ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        return info.totalMem / 1e9
    }

    /** Free app-private storage in GB */
    fun freeGb(context: Context): Double {
        return try {
            StatFs(context.filesDir.path).availableBytes / 1e9
        } catch (_: Exception) {
            0.0
        }
    }

    /** arm64-v8a = true otherwise unsupported ABI */
    fun abiSupported(): Boolean =
        Build.SUPPORTED_ABIS.any { it.startsWith("arm64-v8a") }

    /** Pick the biggest model tier this device can hold (RAM first, then storage). */
    fun tier(context: Context): String {
        val ram = ramGb(context)
        var tier = when {
            ram < 4 -> "lite"
            ram < 8 -> "standard"
            ram < 16 -> "pro"
            else -> "max"
        }
        val free = freeGb(context)
        val t = Models.TIERS[tier]!!
        if (free > 0 && free < t.needGb) {
            tier = when {
                free < 3 -> "lite"
                free < 6 -> "lite"
                else -> "standard"
            }
        }
        return tier
    }
}
