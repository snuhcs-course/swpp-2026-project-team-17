package dev.gazedot

import android.content.Context
import android.graphics.Point
import android.graphics.Rect
import android.os.Build
import android.view.WindowManager

internal object DisplaySpace {
    @Suppress("DEPRECATION")
    fun bounds(context: Context): Rect {
        val manager = context.getSystemService(WindowManager::class.java)
        if (Build.VERSION.SDK_INT >= 30) return manager.maximumWindowMetrics.bounds
        val size = Point()
        manager.defaultDisplay.getRealSize(size)
        return Rect(0, 0, size.x, size.y)
    }
}
