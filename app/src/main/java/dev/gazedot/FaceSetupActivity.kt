package dev.gazedot

import android.app.Activity
import android.graphics.*
import android.os.*
import android.view.*
import androidx.activity.ComponentActivity
import kotlin.math.min

/** Live landmark silhouette: same aspect ratio for target and detected outline, mirrored once for user guidance. */
class FaceSetupActivity:ComponentActivity() {
    private lateinit var guide:Guide
    private var started=false
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        guide=Guide();setContentView(guide)
    }
    override fun onStart(){super.onStart();TrackerState.settingsStarted();TrackerState.precisionTraining=true;started=true;guide.resume()}
    override fun onStop(){guide.pause();TrackerState.precisionTraining=false;if(started){started=false;TrackerState.settingsStopped()};super.onStop()}
    private inner class Guide:View(this@FaceSetupActivity) {
        private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
        private val frames=mutableListOf<FaceFrame>()
        private var last=0L;private var began=0L;private var active=false
        private var hint="눈을 편하게 뜨고 얼굴을 윤곽 안에 맞춰 주세요"
        private var result:EyeProfile?=null
        private val tick=object:Runnable {
            override fun run(){if(!active)return
                val now=SystemClock.uptimeMillis();val f=TrackerState.faceFrame
                if(!TrackerState.running){hint="카메라가 중지되었습니다. 뒤로 가서 다시 시작하세요";frames.clear()}
                else if(f==null || now-f.at !in 0..250){hint="얼굴 검출 대기 · 카메라를 바라보세요";frames.clear()}
                else if(f.at>last){last=f.at
                    if(f.aligned()){
                        frames+=f
                        if(frames.size>45)frames.removeAt(0)
                        hint="자연스럽게 눈을 뜬 채 유지 · ${frames.size.coerceAtMost(30)}/30"
                        val profile=EyeProfile.fit(frames)
                        if(profile!=null){result=profile;TrackerState.eyeProfile=profile
                            getSharedPreferences("personal",MODE_PRIVATE).edit().putString("eye_profile_report",
                                "왼쪽 %.1f px / 오른쪽 %.1f px · 눈 너비 대비 %.2f / %.2f".format(profile.leftDiameter,profile.rightDiameter,profile.leftRatio,profile.rightRatio)).apply()
                            setResult(Activity.RESULT_OK);finish();return}
                    }else{frames.clear();hint=when{
                        f.leftBlink>=.25 || f.rightBlink>=.25 -> "양쪽 눈을 자연스럽게 떠 주세요"
                        kotlin.math.abs(f.head.yaw)>12 || kotlin.math.abs(f.head.pitch)>12 -> "얼굴을 정면으로 향해 주세요"
                        f.faceWidth<.30 || f.faceHeight<.42 -> "얼굴을 조금 더 가까이 가져와 주세요"
                        f.faceWidth>.68 || f.faceHeight>.88 -> "얼굴을 조금 더 멀리 두세요"
                        else -> "얼굴 윤곽을 가운데 가이드에 맞춰 주세요"}}
                }
                if(now-began>30000 && result==null)hint="측정 대기 · 조명과 눈 가림을 확인하세요 (뒤로 가기: 취소)"
                invalidate();postDelayed(this,50)
            }
        }
        fun resume(){active=true;began=SystemClock.uptimeMillis();frames.clear();last=0;removeCallbacks(tick);post(tick)}
        fun pause(){active=false;removeCallbacks(tick);frames.clear()}
        override fun onDraw(c:Canvas){
            c.drawColor(Color.rgb(15,23,42));val f=TrackerState.faceFrame
            val aspect=if(f!=null)f.width.toFloat()/f.height else .75f
            val h=min(height*.62f,width*.88f/aspect);val w=h*aspect;val left=(width-w)/2;val top=height*.22f
            fun sx(x:Double)=left+(1-x).toFloat()*w
            fun sy(y:Double)=top+y.toFloat()*h
            paint.style=Paint.Style.STROKE;paint.strokeWidth=5f;paint.color=Color.LTGRAY
            val path=Path().apply{moveTo(sx(.5),sy(.12));cubicTo(sx(.15),sy(.12),sx(.19),sy(.72),sx(.5),sy(.9));cubicTo(sx(.81),sy(.72),sx(.85),sy(.12),sx(.5),sy(.12))}
            c.drawPath(path,paint)
            // Eye and nose landmarks in the target silhouette.
            c.drawOval(sx(.40),sy(.40),sx(.27),sy(.45),paint);c.drawOval(sx(.73),sy(.40),sx(.60),sy(.45),paint)
            c.drawLine(sx(.5),sy(.45),sx(.5),sy(.60),paint)
            if(f!=null && SystemClock.uptimeMillis()-f.at in 0..250){paint.color=if(f.aligned())Color.GREEN else Color.YELLOW
                val live=Path();f.outline.forEachIndexed{i,p->if(i==0)live.moveTo(sx(p.first),sy(p.second))else live.lineTo(sx(p.first),sy(p.second))};live.close();c.drawPath(live,paint)}
            paint.style=Paint.Style.FILL;paint.color=Color.WHITE;paint.textAlign=Paint.Align.CENTER;paint.textSize=width*.055f
            c.drawText("1. 얼굴·홍채 기준 측정",width/2f,height*.10f,paint)
            paint.textSize=width*.034f;c.drawText(hint,width/2f,height*.17f,paint)
            if(f!=null) c.drawText("홍채 왼쪽 %.1f px · 오른쪽 %.1f px".format(f.leftDiameter,f.rightDiameter),width/2f,height*.83f,paint)
            c.drawText("눈을 크게 치켜뜨지 말고 편하게 유지하세요",width/2f,height*.88f,paint)
            c.drawText("영상 속 크기 측정 · 실제 홍채 지름(mm)은 아님",width/2f,height*.93f,paint)
        }
    }
}
