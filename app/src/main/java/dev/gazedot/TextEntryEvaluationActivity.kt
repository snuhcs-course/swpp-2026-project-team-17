package dev.gazedot

import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Local instrumented input field: uses the production accessibility/voice/writing path. */
class TextEntryEvaluationActivity:ComponentActivity() {
    private val modes=listOf("음성입력", "직접 입력 + AI 후보", "저장 문장")
    private val prompts=listOf("도와주세요.","다음에 연락드릴게요.","잠시만 기다려 주세요.","네, 알겠습니다.","감사합니다.")
    private val session=UUID.randomUUID().toString()
    private var index=0
    private var started=0L
    private var active=false
    private var suppress=false
    private var changes=0
    private var removals=0
    private var wrongChanges=0
    private var selectedMode=""
    private var selectedInput=""
    private var events=JSONArray()
    private val trials=JSONArray()
    private lateinit var root:LinearLayout
    private lateinit var editor:EditText
    private lateinit var decoy:EditText
    private lateinit var title:TextView
    private lateinit var status:TextView
    private lateinit var method:Spinner
    private lateinit var input:Spinner
    private lateinit var notes:EditText
    private lateinit var helper:CheckBox
    private lateinit var file:File
    private var exportText=""
    private val export=registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")){uri->
        if(uri!=null) runCatching{contentResolver.openOutputStream(uri)!!.bufferedWriter().use{it.write(exportText)}}
            .onSuccess{status.text="결과 파일을 저장했습니다."}.onFailure{status.text="저장 실패: ${it.message}"}
    }
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        file=File(filesDir,"text-entry-evaluation/$session.json").apply{parentFile!!.mkdirs()}
        root=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(24,24,24,24)}
        root.setOnApplyWindowInsetsListener { _, insets ->
            @Suppress("DEPRECATION")
            root.setPadding(24+insets.systemWindowInsetLeft,24+insets.systemWindowInsetTop,
                24+insets.systemWindowInsetRight,24+insets.systemWindowInsetBottom)
            insets
        }
        setContentView(ScrollView(this).apply{addView(root)})
        fun label(t:String)=TextView(this).apply{text=t;textSize=18f}.also{root.addView(it)}
        label("텍스트 입력 평가 · 외부 전송 없는 연습 입력칸")
        label("① 카메라·보정·접근성 준비 ② 방식 선택 ③ 시작 후 목표 문장 입력 ④ 기록\n음성: 입력칸을 선택하고 양눈을 빠르게 두 번 깜빡이세요.\n작성 패널: 입력칸 선택 → ‘NoTouch로 작성’ → 직접 입력 / 저장 문장.\nAI 후보는 기존 동의 및 서버 연결이 필요합니다.")
        method=Spinner(this).apply{adapter=ArrayAdapter(this@TextEntryEvaluationActivity,android.R.layout.simple_spinner_dropdown_item,modes)};root.addView(method)
        input=Spinner(this).apply{adapter=ArrayAdapter(this@TextEntryEvaluationActivity,android.R.layout.simple_spinner_dropdown_item,listOf("시선으로 조작", "터치로 기능만 확인"))};root.addView(input)
        notes=EditText(this).apply{hint="조건 메모: 거리·조명·안경·참가자 코드 (실명 불필요)"};root.addView(notes)
        title=label("").apply{id=R.id.eval_header}
        editor=EditText(this).apply{id=R.id.eval_editor;hint="목표 입력칸";minLines=2;showSoftInputOnFocus=false;setSingleLine(false)};root.addView(editor)
        decoy=EditText(this).apply{hint="비워 두어야 하는 다른 입력칸";showSoftInputOnFocus=false};root.addView(decoy)
        helper=CheckBox(this).apply{text="이번 시행에서 터치 도움 / 외부 키보드를 사용함"};root.addView(helper)
        button("시행 시작") { begin() }
        button("완료 기록") { finishTrial("completed") }
        button("실패 / 포기 기록") { finishTrial("failed") }
        button("현재 / 최근 결과 JSON 내보내기") {
            if(active)finishTrial("interrupted_export")
            val chosen=if(trials.length()>0)file else File(filesDir,"text-entry-evaluation").listFiles()?.filter{it.extension=="json"}?.maxByOrNull{it.lastModified()}
            if(chosen==null){status.text="아직 기록이 없습니다."}else{exportText=chosen.readText();export.launch("notouch-text-entry-${chosen.name}")}
        }
        button("메인으로 돌아가기") { if(active)finishTrial("interrupted_exit");finish() }
        status=label("실험 전입니다. 자동 테스트 결과를 실제 사용자 결과로 기록하지 않습니다.")
        watch(editor,false);watch(decoy,true)
        showPrompt()
        TextEntryProbe.listener={event-> if(active){events.put(JSONObject().put("event",event).put("elapsed_ms",SystemClock.uptimeMillis()-started));persist()} }
    }
    private fun button(text:String,action:()->Unit){root.addView(Button(this).apply{this.text=text;isFocusable=false;setOnClickListener{action()}},LinearLayout.LayoutParams(-1,-2))}
    private fun showPrompt(){title.text="시행 ${index+1} · 목표: ${prompts[index%prompts.size]}"}
    private fun begin() {
        if(active){status.text="현재 시행을 먼저 기록하세요.";return}
        if(!TrackerState.running || TrackerState.model==null || ControlAccessibilityService.instance==null || !TrackerState.controls){status.text="메인에서 카메라·시선 보정·접근성·조작 재개를 먼저 완료하세요.";return}
        suppress=true;editor.setText("");decoy.setText("");suppress=false
        helper.isChecked=false;changes=0;removals=0;wrongChanges=0;events=JSONArray()
        selectedMode=method.selectedItem.toString();selectedInput=input.selectedItem.toString()
        method.isEnabled=false;input.isEnabled=false;notes.isEnabled=false
        started=SystemClock.uptimeMillis();active=true
        editor.requestFocus();editor.setSelection(0)
        getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(editor.windowToken,0)
        status.text="측정 중 · $selectedMode · $selectedInput\n정해진 방법으로 입력한 뒤 ‘완료 기록’을 누르세요. 자동 전송은 없습니다."
        persist()
    }
    private fun watch(field:EditText,wrong:Boolean) {
        field.addTextChangedListener(object:TextWatcher {
            override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int){}
            override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int){
                if(!active || suppress)return
                if(wrong)wrongChanges++ else {changes++;if(before>0)removals++}
                events.put(JSONObject().put("event",if(wrong)"decoy_changed" else "target_changed").put("elapsed_ms",SystemClock.uptimeMillis()-started).put("removed_units",before).put("added_units",count))
                persist()
            }
            override fun afterTextChanged(s:Editable?){}
        })
    }
    private fun finishTrial(outcome:String) {
        if(!active)return
        val target=prompts[index%prompts.size];val actual=editor.text.toString()
        val exact=TextEntryMetrics.normalized(target)==TextEntryMetrics.normalized(actual)
        val eventNames=(0 until events.length()).map{events.getJSONObject(it).getString("event")}
        val executionObserved=if(selectedMode=="음성입력") "voice_insert_accepted" in eventNames else eventNames.any{it in listOf("writing_result_CONFIRMED","writing_result_TEXT_CONFIRMED")}
        val row=JSONObject().put("trial",index+1).put("method",selectedMode).put("input_mode",selectedInput)
            .put("conditions",notes.text.toString()).put("outcome",outcome).put("target",target).put("actual",actual)
            .put("duration_ms",SystemClock.uptimeMillis()-started).put("exact_match",exact)
            .put("cer",TextEntryMetrics.cer(target,actual)).put("execution_observed",executionObserved).put("success",outcome=="completed" && exact && wrongChanges==0 && executionObserved)
            .put("touch_assistance_reported",helper.isChecked).put("text_change_events",changes)
            .put("replacement_or_deletion_events",removals).put("wrong_field_change_events",wrongChanges).put("events",events)
        trials.put(row);active=false;persist()
        method.isEnabled=true;input.isEnabled=true;notes.isEnabled=true
        status.text="기록 ${index+1}: $outcome · 문장 일치=$exact · CER=%.1f%%\n기기 안에 저장했습니다. 입력 경로 확인=$executionObserved".format(TextEntryMetrics.cer(target,actual)*100)
        index++;showPrompt()
    }
    private fun persist() {
        val data=JSONObject().put("schema",1).put("session",session).put("app_version",BuildConfig.VERSION_NAME)
            .put("device",android.os.Build.MODEL).put("created_or_updated_at",java.time.Instant.now().toString())
            .put("pipeline","iTracker + iris setup + five-point calibration; retargeting branch not merged")
            .put("trials",trials).put("active_trial",if(active)JSONObject().put("trial",index+1).put("method",selectedMode).put("target",prompts[index%prompts.size]).put("actual",editor.text.toString()).put("events",events) else JSONObject.NULL)
        runCatching {val tmp=File(file.parentFile,file.name+".tmp");tmp.writeText(data.toString(2));check(tmp.renameTo(file)){"Cannot save evaluation"}}
            .onFailure{status.text="기록 저장 실패: ${it.message}"}
    }
    override fun onStop(){if(active)finishTrial("interrupted_background");super.onStop()}
    override fun onDestroy(){TextEntryProbe.listener=null;super.onDestroy()}
}
