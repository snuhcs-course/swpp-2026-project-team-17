package dev.gazedot

import android.graphics.*
import android.os.*
import android.view.*
import android.widget.*
import androidx.activity.ComponentActivity
import kotlin.math.*

class HeadMotionActivity:ComponentActivity() {
    private var measuring:MotionView?=null
    private var started=false
    override fun onCreate(savedInstanceState:Bundle?){super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val model=TrackerState.model
        if(model==null || TrackerState.eyeProfile==null){setContentView(TextView(this).apply{text="얼굴 기준 측정과 9점 보정을 먼저 완료하세요"});return}
        measuring=MotionView(model);setContentView(measuring)
    }
    override fun onStart(){super.onStart();TrackerState.settingsStarted();TrackerState.precisionTraining=true;started=true}
    override fun onStop(){measuring?.cancel();TrackerState.precisionTraining=false;if(started){started=false;TrackerState.settingsStopped()};super.onStop()}
    override fun onRestart(){super.onRestart();result("측정이 중단되었습니다. 기존 시선 보정을 유지합니다.")}
    private fun result(message:String){measuring?.cancel();measuring=null;TrackerState.precisionTraining=false
        getSharedPreferences("personal",MODE_PRIVATE).edit().putString("head_report",message).apply()
        val root=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(28,90,28,28)}
        root.addView(TextView(this).apply{text=message;textSize=19f})
        root.addView(Button(this).apply{text="완료 · 돌아가기";setOnClickListener{finish()}});setContentView(root)
    }
    private inner class MotionView(private val model:GazeModel):View(this@HeadMotionActivity){
        private val prompts=listOf("정면으로 점을 바라보세요","점은 보면서 고개를 한쪽으로 조금 돌리세요","고개를 반대쪽으로 조금 돌리세요",
            "점은 보면서 고개를 조금 끄덕이세요","고개를 반대 방향으로 움직이세요","얼굴 전체를 옆으로 조금 이동하세요",
            "얼굴을 반대쪽으로 조금 이동하세요","고개를 옆으로 살짝 기울이세요","반대쪽으로 살짝 기울이세요",
            "얼굴 전체를 위나 아래로 조금 이동하세요","얼굴을 반대 방향으로 이동하세요",
            "정면에서 조금 가까이 오세요","정면에서 조금 멀리 가세요",
            "검증: 점을 보며 첫 방향으로 고개를 조금 돌리세요","검증: 반대 방향으로 고개를 조금 돌리세요",
            "검증: 점을 보며 첫 방향으로 조금 끄덕이세요","검증: 반대 방향으로 조금 끄덕이세요")
        private val records=mutableListOf<MotionAnchor>();private val samples=mutableListOf<EyeFeatures>()
        private var index=0;private var since=SystemClock.uptimeMillis();private var last=0L;private var closed=false
        private val paint=Paint(Paint.ANTI_ALIAS_FLAG);private var hint="머리를 작게 움직이고 시선은 점에 유지하세요"
        private fun target()=when(index){13->.35 to .35;14->.65 to .65;15->.65 to .35;16->.35 to .65;else->.5 to .5}
        private fun eligible(h:HeadPose):Boolean {
            if(abs(h.yaw)>20 || abs(h.pitch)>18 || abs(h.roll)>10)return false
            if(index==0)return abs(h.yaw)<10 && abs(h.pitch)<10
            val r=records.first().head
            val yaw=h.yaw-r.yaw;val pitch=h.pitch-r.pitch;val x=h.x-r.x;val y=h.y-r.y;val roll=h.roll-r.roll;val z=ln(h.iris/r.iris)
            return when(index){
                1->abs(yaw) in 6.0..14.0 && abs(pitch)<5
                2->abs(yaw) in 6.0..14.0 && yaw*(records[1].head.yaw-r.yaw)<0 && abs(pitch)<5
                3->abs(pitch) in 5.0..11.0 && abs(yaw)<5
                4->abs(pitch) in 5.0..11.0 && pitch*(records[3].head.pitch-r.pitch)<0 && abs(yaw)<5
                5->abs(x) in .035.. .075 && abs(yaw)<5 && abs(pitch)<5
                6->abs(x) in .035.. .075 && x*(records[5].head.x-r.x)<0 && abs(yaw)<5 && abs(pitch)<5
                7->abs(roll) in 5.0..8.0 && abs(yaw)<5 && abs(pitch)<5
                8->abs(roll) in 5.0..8.0 && roll*(records[7].head.roll-r.roll)<0 && abs(yaw)<5 && abs(pitch)<5
                9->abs(y) in .035.. .075 && abs(yaw)<5 && abs(pitch)<5
                10->abs(y) in .035.. .075 && y*(records[9].head.y-r.y)<0 && abs(yaw)<5 && abs(pitch)<5
                11->z in .10.. .18 && abs(yaw)<5 && abs(pitch)<5
                12->z in -.18.. -.10 && abs(yaw)<5 && abs(pitch)<5
                13,14->{val source=records[if(index==13)1 else 2].head.yaw-r.yaw;abs(yaw-source*.8)<2 && abs(pitch)<5}
                else->{val source=records[if(index==15)3 else 4].head.pitch-r.pitch;abs(pitch-source*.8)<2 && abs(yaw)<5}
            }
        }
        private val tick=object:Runnable{override fun run(){if(closed)return
            val now=SystemClock.uptimeMillis()
            if(!TrackerState.running || TrackerState.model!==model){result("추적이 중단되었습니다. 기존 보정을 유지합니다.");return}
            if(now-since>22000){result("자세 표본이 부족해 머리 보정을 적용하지 않았습니다. 기본 9점 보정은 유지됩니다.");return}
            val s=TrackerState.sample;val f=s?.calibrationFeatures(now,last);val h=f?.head
            if(f!=null && h!=null && s.timestampMs>last){last=s.timestampMs
                if(eligible(h) && model.predictBase(f)!=null){samples+=f;hint="자세 유지 · ${samples.size}/18"
                    if(samples.size>=18){
                        val mid=medianFeatures(samples);val mh=mid.head!!
                        if(samples.any{abs(it.head!!.yaw-mh.yaw)>2 || abs(it.head.pitch-mh.pitch)>2 || abs(it.faceX-mid.faceX)>.012 || abs(it.faceY-mid.faceY)>.012 || abs(it.head.roll-mh.roll)>2 || abs(ln(it.head.iris/mh.iris))>.04}){samples.clear();hint="움직임을 멈추고 점을 계속 바라보세요"}
                        else{val raw=model.predictBase(mid)!!;val loc=IntArray(2);getLocationOnScreen(loc);val b=DisplaySpace.bounds(this@HeadMotionActivity);val t=target()
                            records+=MotionAnchor(mh,raw.first,raw.second,(loc[0]+width*t.first)/b.width(),(loc[1]+height*t.second)/b.height())
                            index++;samples.clear();since=now
                            if(index==17){val candidate=HeadCorrection.fit(records.take(13));val accepted=candidate!=null && HeadCorrection.accept(candidate,records.drop(13))
                                if(accepted){TrackerState.cancelActions?.invoke();TrackerState.model=model.withHeadCorrection(candidate);TrackerState.clearCoordinates()}
                                result(if(accepted)"머리 움직임 보정 적용 완료 · 별도 4점에서 오차 감소 확인\n학습 범위를 벗어난 자세에서는 추적을 멈춥니다." else "별도 4점에서 개선이 확인되지 않아 기존 보정을 유지합니다.");return}
                        }
                    }
                }else{samples.clear();hint="안내 방향으로 조금 이동 · 너무 크게 움직이지 마세요"}
            }
            invalidate();postDelayed(this,50)
        }}
        init{post(tick)}
        fun cancel(){closed=true;removeCallbacks(tick)}
        override fun onDetachedFromWindow(){cancel();super.onDetachedFromWindow()}
        override fun onDraw(c:Canvas){c.drawColor(Color.rgb(15,23,42));paint.color=Color.WHITE;paint.textAlign=Paint.Align.CENTER;paint.textSize=width*.046f
            c.drawText("3. 머리 움직임 보정 ${index+1}/17",width/2f,height*.10f,paint);paint.textSize=width*.031f
            c.drawText(prompts.getOrElse(index){"완료"},width/2f,height*.17f,paint);c.drawText(hint,width/2f,height*.22f,paint)
            val t=target();paint.color=Color.CYAN;c.drawCircle((width*t.first).toFloat(),(height*t.second).toFloat(),18f,paint)
            paint.color=Color.LTGRAY;paint.textSize=width*.03f;c.drawText("눈은 점에 고정 · 뒤로 가기: 머리 보정 건너뛰기",width/2f,height*.91f,paint)
        }
    }
}
