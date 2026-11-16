package dev.gazedot

import android.content.Context
import android.graphics.Bitmap
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.*
import org.opencv.dnn.Dnn
import org.opencv.dnn.Net
import org.opencv.imgproc.Imgproc
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.*

/** Frozen official 25x iTracker Caffe checkpoint; CPU inference, no remote gaze service. */
class ITrackerEngine(context:Context):AutoCloseable {
    private var net:Net?=null
    private val means=mutableMapOf<String,Mat>()
    init {
        check(OpenCVLoader.initLocal()) { "OpenCV native runtime unavailable" }
        Core.setNumThreads(2)
        try {
            val dir=File(context.codeCacheDir,"itracker-v17").apply{mkdirs()}
            fun asset(name:String):String {
                val file=File(dir,name)
                context.assets.open("itracker/$name").use{input->file.outputStream().use{input.copyTo(it)}}
                return file.absolutePath
            }
            net=Dnn.readNetFromCaffe(asset("deploy.prototxt"),asset("weights.caffemodel"))
            check(!net!!.empty()) { "iTracker weights could not be loaded" }
            net!!.setPreferableBackend(Dnn.DNN_BACKEND_OPENCV)
            net!!.setPreferableTarget(Dnn.DNN_TARGET_CPU)
            for(name in listOf("left","right","face")) {
                val bytes=context.assets.open("itracker/mean_$name.f32").use{it.readBytes()}
                check(bytes.size==3*224*224*4)
                val values=FloatArray(3*224*224)
                ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(values)
                means[name]=Mat(intArrayOf(1,3,224,224),CvType.CV_32F).apply{put(intArrayOf(0,0,0,0),values)}
            }
            // Warm up before accepting camera frames; missing/unsupported operators fail visibly.
            val zero=Mat.zeros(intArrayOf(1,3,224,224),CvType.CV_32F)
            val grid=Mat.zeros(intArrayOf(1,625,1,1),CvType.CV_32F)
            try {
                for(name in means.keys) net!!.setInput(zero,"image_$name")
                net!!.setInput(grid,"facegrid")
                val result=net!!.forward("fc3")
                try {
                    check(result.total()==2L)
                    val values=FloatArray(2);result.get(0,0,values)
                    check(abs(values[0]-1.270289f)<.01f && abs(values[1]+4.482550f)<.01f) {
                        "iTracker checkpoint self-test failed"
                    }
                }
                finally { result.release() }
            } finally { zero.release();grid.release() }
        } catch(t:Exception) { close();throw t }
    }

    fun predict(image:Bitmap,p:List<NormalizedLandmark>):Pair<Double,Double>? {
        check(!image.isRecycled) { "iTracker received a released camera bitmap" }
        if(p.size<478 || p.take(478).any{!it.x().isFinite() || !it.y().isFinite()}) return null
        val w=image.width;val h=image.height
        fun x(i:Int)=p[i].x().toDouble()*w
        fun y(i:Int)=p[i].y().toDouble()*h
        val facePoints=p.take(468)
        val minX=facePoints.minOf{it.x().toDouble()*w};val maxX=facePoints.maxOf{it.x().toDouble()*w}
        val minY=facePoints.minOf{it.y().toDouble()*h};val maxY=facePoints.maxOf{it.y().toDouble()*h}
        val face=ITrackerGeometry.square((minX+maxX)/2,(minY+maxY)/2,max(maxX-minX,maxY-minY)*1.05)
        fun eye(a:Int,b:Int)=ITrackerGeometry.square((x(a)+x(b))/2,(y(a)+y(b))/2,hypot(x(b)-x(a),y(b)-y(a))*1.7)
        val crops=mapOf("left" to eye(362,263),"right" to eye(33,133),"face" to face)
        if(crops.values.any{!ITrackerGeometry.usable(it,w,h)})return null
        val rgba=Mat();val bgr=Mat();val inputs=mutableListOf<Mat>()
        try {
            Utils.bitmapToMat(image,rgba);Imgproc.cvtColor(rgba,bgr,Imgproc.COLOR_RGBA2BGR)
            for((name,box) in crops) {
                val affine=Mat(2,3,CvType.CV_64F)
                val crop=Mat()
                try {
                    // Pixel-centre mapping matches resize; out-of-frame edges repeat, never black-pad.
                    affine.put(0,0,box.width/224,0.0,box.x+box.width/448-.5,
                        0.0,box.height/224,box.y+box.height/448-.5)
                    Imgproc.warpAffine(bgr,crop,affine,Size(224.0,224.0),
                        Imgproc.INTER_LINEAR or Imgproc.WARP_INVERSE_MAP,Core.BORDER_REPLICATE,Scalar(0.0))
                    val blob=Dnn.blobFromImage(crop,1.0,Size(224.0,224.0),Scalar(0.0),false,false)
                    inputs+=blob
                    // Caffe trained on 0..255 BGR with per-pixel means, NOT RGB /255.
                    Core.subtract(blob,means.getValue(name),blob)
                    net!!.setInput(blob,"image_$name")
                } finally {affine.release();crop.release()}
            }
            val grid=Mat(intArrayOf(1,625,1,1),CvType.CV_32F)
            inputs+=grid;grid.put(intArrayOf(0,0,0,0),ITrackerGeometry.grid(face,w,h))
            net!!.setInput(grid,"facegrid")
            val output=net!!.forward("fc3")
            try {
                if(output.total()!=2L)return null
                val values=FloatArray(2);output.get(0,0,values)
                if(values.any{!it.isFinite() || abs(it)>50f})return null
                return values[0].toDouble() to values[1].toDouble()
            } finally {output.release()}
        } finally { inputs.forEach{it.release()};rgba.release();bgr.release() }
    }
    override fun close(){means.values.forEach{it.release()};means.clear();net=null}
}
