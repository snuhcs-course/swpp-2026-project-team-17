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
        if(model==null){setContentView(TextView(this).apply{text="9점 화면 보정을 먼저 완료하세요"});return}
        measuring=MotionView(model);setContentView(measuring)
    }
    override fun onStart(){super.onStart();TrackerState.settingsStarted();TrackerState.precisionTraining=true;started=true}
    override fun onStop(){measuring?.cancel();TrackerState.precisionTraining=false;if(started){started=false;TrackerState.settingsStopped()};super.onStop()}
    override fun onRestart(){super.onRestart();result("측정이 중단되었습니다. 기존 시선 보정을 유지합니다.")}
    private fun result(message:String){measuring?.cancel();measuring=null;TrackerState.precisionTraining=false
        getSharedPreferences("personal",MODE_PRIVATE).edit().putString("head_report",message).apply()
        val root=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(28,90,28,28)}
        root.addView(TextView(this).apply{text=message;textSize=19f})
        root.addView(Button(this).apply{text="머리 보정 다시 시도";setOnClickListener{val m=TrackerState.model;if(m!=null){TrackerState.precisionTraining=true;measuring=MotionView(m);setContentView(measuring)}}})
        root.addView(Button(this).apply{text="완료 · 돌아가기";setOnClickListener{finish()}});setContentView(root)
    }
    private inner class MotionView(private val model:GazeModel):View(this@HeadMotionActivity){
        private val prompts=listOf("정면으로 점을 바라보세요","점은 보면서 고개를 한쪽으로 조금 돌리세요","고개를 반대쪽으로 조금 돌리세요",
            "점은 보면서 고개를 조금 끄덕이세요","고개를 반대 방향으로 움직이세요","얼굴 전체를 옆으로 조금 이동하세요",
            "얼굴을 반대쪽으로 조금 이동하세요","고개를 옆으로 살짝 기울이세요","반대쪽으로 살짝 기울이세요",
            "얼굴 전체를 위나 아래로 조금 이동하세요","얼굴을 반대 방향으로 이동하세요",
            "정면에서 조금 가까이 오세요","정면에서 조금 멀리 가세요",
            "검증: 점을 보며 첫 방향으로 고개를 조금 돌리세요","검증: 반대 방향으로 고개를 조금 돌리세요",
            "검증: 점을 보며 첫 방향으로 조금 끄덕이세요","검증: 반대 방향으로 조금 끄덕이세요",
            "검증: 처음 자세로 돌아와 가운데 점을 보세요")
        private val records=mutableListOf<MotionAnchor>();private val samples=mutableListOf<EyeFeatures>()
        private var index=0;private var since=SystemClock.uptimeMillis();private var last=0L;private var closed=false
        private var diagnostic="얼굴 자세 데이터 대기"
        private val paint=Paint(Paint.ANTI_ALIAS_FLAG);private var hint="머리를 작게 움직이고 시선은 점에 유지하세요"
        private fun target()=when(index){13->.35 to .35;14->.65 to .65;15->.65 to .35;16->.35 to .65;else->.5 to .5}
        private fun eligible(h:HeadPose):Boolean {
            if(!h.vector().all{it.isFinite()})return false
            // Neutral is the user's actual phone/head pose, not the matrix's world zero.
            if(index==0)return true
            val r=records.first().head
            val yaw=CalibrationPose.angle(h.yaw,r.yaw);val pitch=CalibrationPose.angle(h.pitch,r.pitch);val x=h.x-r.x;val y=h.y-r.y;val roll=CalibrationPose.angle(h.roll,r.roll);val z=ln(h.iris/r.iris)
            if(abs(yaw)>22 || abs(pitch)>20 || abs(roll)>16)return false
            return when(index){
                1->abs(yaw) in 6.0..18.0 && abs(pitch)<9
                2->abs(yaw) in 6.0..18.0 && yaw*(CalibrationPose.angle(records[1].head.yaw,r.yaw))<0 && abs(pitch)<9
                3->abs(pitch) in 5.0..15.0 && abs(yaw)<9
                4->abs(pitch) in 5.0..15.0 && pitch*(CalibrationPose.angle(records[3].head.pitch,r.pitch))<0 && abs(yaw)<9
                5->abs(x) in .035.. .075 && abs(yaw)<9 && abs(pitch)<9
                6->abs(x) in .035.. .075 && x*(records[5].head.x-r.x)<0 && abs(yaw)<9 && abs(pitch)<9
                7->abs(roll) in 5.0..12.0 && abs(yaw)<9 && abs(pitch)<9
                8->abs(roll) in 5.0..12.0 && roll*(CalibrationPose.angle(records[7].head.roll,r.roll))<0 && abs(yaw)<9 && abs(pitch)<9
                9->abs(y) in .035.. .075 && abs(yaw)<9 && abs(pitch)<9
                10->abs(y) in .035.. .075 && y*(records[9].head.y-r.y)<0 && abs(yaw)<9 && abs(pitch)<9
                11->z in .10.. .18 && abs(yaw)<9 && abs(pitch)<9
                12->z in -.18.. -.10 && abs(yaw)<9 && abs(pitch)<9
                13,14->{val source=CalibrationPose.angle(records[if(index==13)1 else 2].head.yaw,r.yaw);abs(yaw-source*.8)<2 && abs(pitch)<9}
                15,16->{val source=CalibrationPose.angle(records[if(index==15)3 else 4].head.pitch,r.pitch);abs(pitch-source*.8)<3 && abs(yaw)<9}
                else->CalibrationPose.stable(h,r)
            }
        }
        private val tick=object:Runnable{override fun run(){if(closed)return
            val now=SystemClock.uptimeMillis()
            if(!TrackerState.running || TrackerState.model!==model){result("추적이 중단되었습니다. 기존 보정을 유지합니다.");return}
            if(now-since>60000){result("${index+1}단계 측정 시간 초과 · 기본 9점 보정 유지\n$hint\n$diagnostic");return}
            val s=TrackerState.sample;val f=s?.calibrationFeatures(now,last);val h=f?.head
            val live=TrackerState.faceFrame?.takeIf{now-it.at in 0..250}?.head
            diagnostic=if(live==null) "얼굴 자세 데이터 없음 · 조명/카메라 확인" else {
                val r=records.firstOrNull()?.head ?: live.copy(yaw=0.0,pitch=0.0,roll=0.0)
                "회전 %.1f° / 끄덕임 %.1f° / 기울임 %.1f°".format(
                    CalibrationPose.angle(live.yaw,r.yaw),CalibrationPose.angle(live.pitch,r.pitch),CalibrationPose.angle(live.roll,r.roll))
            }
            if(f==null && (s==null || now-s.timestampMs !in 0..250 || s.quality !in listOf(TrackingQuality.TRACKING,TrackingQuality.OUT_OF_RANGE))){
                samples.clear();hint=if(live==null) "얼굴 자세 검출 대기" else "양쪽 눈 검출 대기 · 눈은 점을 바라보세요"
            }
            if(f!=null && h!=null && s.timestampMs>last){last=s.timestampMs
                if(now-since<1000){samples.clear();hint="점으로 시선을 옮긴 뒤 자세를 유지하세요"}
                else if(eligible(h) && model.predictBase(f)!=null){samples+=f;hint="자세 유지 · ${samples.size}/18"
                    if(samples.size>=18){
                        val mid=medianFeatures(samples);val mh=mid.head!!
                        if(samples.any{abs(CalibrationPose.angle(it.head!!.yaw,mh.yaw))>2 || abs(CalibrationPose.angle(it.head.pitch,mh.pitch))>2 || abs(it.faceX-mid.faceX)>.012 || abs(it.faceY-mid.faceY)>.012 || abs(CalibrationPose.angle(it.head.roll,mh.roll))>2 || abs(ln(it.head.iris/mh.iris))>.04}){samples.clear();hint="움직임을 멈추고 점을 계속 바라보세요"}
                        else{val raw=model.predictBase(mid)!!;val loc=IntArray(2);getLocationOnScreen(loc);val b=DisplaySpace.bounds(this@HeadMotionActivity);val t=target()
                            records+=MotionAnchor(mh,raw.first,raw.second,(loc[0]+width*t.first)/b.width(),(loc[1]+height*t.second)/b.height())
                            index++;samples.clear();since=now
                            if(index==18){val candidate=HeadCorrection.fit(records.take(13));val accepted=candidate!=null && HeadCorrection.accept(candidate,records.drop(13))
                                if(accepted){TrackerState.cancelActions?.invoke();TrackerState.model=model.withHeadCorrection(candidate);TrackerState.clearCoordinates()}
                                result(if(accepted)"머리 움직임 보정 적용 완료 · 별도 5점에서 오차 감소 확인\n학습 범위를 벗어난 자세에서는 추적을 멈춥니다." else "별도 5점에서 개선이 확인되지 않아 기존 보정을 유지합니다.");return}
                        }
                    }
                }else{samples.clear();hint=if(model.predictBase(f)==null) "얼굴 위치/거리가 기본 보정 범위를 벗어났어요" else when(index){
                    1,2->"좌우 회전 6~18° · 반대 방향 단계인지 확인"
                    3,4->"끄덕임 5~15° · 반대 방향 단계인지 확인"
                    7,8->"좌우 기울임 5~12° · 시선은 점에 고정"
                    5,6,9,10->"고개 회전 없이 얼굴 전체를 조금 이동"
                    11,12->"처음 위치에서 거리만 약 10~16% 변경"
                    else->"학습 때보다 조금 작게 움직이고 점을 보세요"
                }}
            }
            invalidate();postDelayed(this,50)
        }}
        init{post(tick)}
        fun cancel(){closed=true;removeCallbacks(tick)}
        override fun onDetachedFromWindow(){cancel();super.onDetachedFromWindow()}
        override fun onDraw(c:Canvas){c.drawColor(Color.rgb(15,23,42));paint.color=Color.WHITE;paint.textAlign=Paint.Align.CENTER;paint.textSize=width*.046f
            c.drawText("3. 머리 움직임 보정 ${index+1}/18",width/2f,height*.10f,paint);paint.textSize=width*.031f
            c.drawText(prompts.getOrElse(index){"완료"},width/2f,height*.17f,paint);c.drawText(hint,width/2f,height*.22f,paint)
            c.drawText(diagnostic,width/2f,height*.28f,paint)
            val live=TrackerState.faceFrame?.head;val ref=records.firstOrNull()?.head
            if(live!=null && ref!=null)c.drawText("가로 %.1f%% / 세로 %.1f%% / 크기 %+.1f%%".format((live.x-ref.x)*100,(live.y-ref.y)*100,(live.iris/ref.iris-1)*100),width/2f,height*.33f,paint)
            val t=target();paint.color=Color.CYAN;c.drawCircle((width*t.first).toFloat(),(height*t.second).toFloat(),18f,paint)
            paint.color=Color.LTGRAY;paint.textSize=width*.03f;c.drawText("눈은 점에 고정 · 뒤로 가기: 머리 보정 건너뛰기",width/2f,height*.91f,paint)
        }
    }
}
