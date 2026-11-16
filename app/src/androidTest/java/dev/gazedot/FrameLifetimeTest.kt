package dev.gazedot

import android.graphics.Bitmap
import android.app.Instrumentation
import android.app.Activity
import android.os.Bundle
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark

/** Device regression: uses Android Bitmap, the shipped MediaPipe wrapper and real iTracker. */
class FrameLifetimeInstrumentation: Instrumentation() {
    override fun onCreate(arguments:Bundle?) {super.onCreate(arguments);start()}
    override fun onStart() {
        super.onStart()
        val report=Bundle()
        try {
            testEarlyMediaPipeCloseReproducesReleasedBitmap()
            testBitmapSurvivesUntilBothConsumersFinish()
            report.putString("stream","Frame lifetime: 2 checks passed")
            finish(Activity.RESULT_OK,report)
        } catch(t:Throwable) {
            report.putString("stream",t.stackTraceToString())
            finish(Activity.RESULT_CANCELED,report)
        }
    }
    fun testBitmapSurvivesUntilBothConsumersFinish() {
        val bitmap=Bitmap.createBitmap(480,640,Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(android.graphics.Color.GRAY)
        val wrapper=BitmapImageBuilder(bitmap).build()
        val points=MutableList(478){NormalizedLandmark.create(.5f,.5f,0f)}
        fun point(i:Int,x:Float,y:Float){points[i]=NormalizedLandmark.create(x,y,0f)}
        point(10,.5f,.2f);point(152,.5f,.8f);point(234,.2f,.5f);point(454,.8f,.5f)
        point(33,.28f,.4f);point(133,.43f,.4f);point(362,.57f,.4f);point(263,.72f,.4f)
        val engine=ITrackerEngine(targetContext)
        try {
            check(!bitmap.isRecycled)
            val output=engine.predict(bitmap,points)
            checkNotNull(output)
            check(output.first.isFinite() && output.second.isFinite())
            check(!bitmap.isRecycled)
        } finally {wrapper.close();engine.close()}
        check(bitmap.isRecycled)
    }
    fun testEarlyMediaPipeCloseReproducesReleasedBitmap() {
        val bitmap=Bitmap.createBitmap(16,16,Bitmap.Config.ARGB_8888)
        val wrapper=BitmapImageBuilder(bitmap).build()
        wrapper.close()
        check(bitmap.isRecycled)
    }
}
