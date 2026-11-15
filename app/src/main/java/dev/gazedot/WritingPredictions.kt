package dev.gazedot

import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.net.URL
import java.util.UUID
import java.util.concurrent.Executors
import javax.net.ssl.HttpsURLConnection

internal data class PredictionPolicy(val id: String, val provider: String, val model: String, val notice: String)

/** Only the NoTouch draft and opaque request metadata leave the device. No default provider/model. */
internal class WritingPredictions(private val changed: () -> Unit) {
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val session = UUID.randomUUID().toString()
    private val gate = PredictionGate()
    @Volatile private var generation = 0L
    @Volatile private var connection: HttpsURLConnection? = null
    var policy: PredictionPolicy? = null; private set
    @Volatile var active = false; private set
    var message = "온라인 예측 설정 전 · 직접 입력을 사용할 수 있어요"; private set
    val candidates get() = gate.displayed
    private val endpoint = BuildConfig.PREDICTION_SERVER_URL.trim().trimEnd('/')
    val configured get() = endpoint.startsWith("https://") && runCatching { URL(endpoint).userInfo == null }.getOrDefault(false)

    private fun request(path: String, body: JSONObject? = null): JSONObject {
        check(configured)
        val c = URL(endpoint + path).openConnection() as HttpsURLConnection
        connection = c
        try {
            c.connectTimeout = 2_000; c.readTimeout = 3_000
            c.instanceFollowRedirects = false
            c.setRequestProperty("X-NoTouch-Session", session)
            if (body != null) {
                c.requestMethod = "POST"; c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                c.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            }
            if (c.responseCode != 200) throw PredictionFailure(c.responseCode)
            val bytes = c.inputStream.use { it.readBytesLimited(16_384) }
            return JSONObject(bytes.toString(Charsets.UTF_8))
        } finally {
            c.disconnect()
            if (connection === c) connection = null
        }
    }

    fun loadPolicy(result: (PredictionPolicy?) -> Unit) {
        stop()
        if (!configured) { message = "AI 연결 주소가 아직 설정되지 않았어요. 직접 입력은 계속할 수 있어요."; changed(); result(null); return }
        val ticket = generation
        worker.execute {
            val policy = runCatching {
                val json = request("/v1/config")
                check(json.getBoolean("enabled"))
                PredictionPolicy(json.getString("policy_id"), json.getString("provider"), json.getString("model"), json.getString("notice"))
                    .also { check(it.id.isNotBlank() && it.provider.isNotBlank() && it.model.isNotBlank()) }
            }.getOrNull()
            main.post {
                if (ticket != generation) return@post
                this.policy = policy
                message = if (policy == null) "AI 설정이 준비되지 않았거나 연결할 수 없어요. 직접 입력을 계속해 주세요." else "온라인 예측 사용 여부를 선택해 주세요"
                changed(); result(policy)
            }
        }
    }

    fun enable(policy: PredictionPolicy) { this.policy = policy; active = true; message = "온라인 예측 사용 중" }
    fun input(input: PredictionInput) {
        generation++; main.removeCallbacksAndMessages(null); connection?.disconnect()
        gate.input(input.version)
        changed()
        if (!active || input.text.isBlank()) return
        if (input.text.length > 2_000) { message = "긴 초안은 예측을 쉬어요. 직접 입력은 계속할 수 있어요."; changed(); return }
        val policy = policy ?: return
        val ticket = generation
        main.postDelayed({
            val timeout = Runnable {
                if (ticket == generation && active) {
                    generation++; connection?.disconnect()
                    message = "예측 시간이 초과됐어요. 직접 입력을 계속할 수 있어요."
                    changed()
                }
            }
            main.postDelayed(timeout, 5_000)
            worker.execute {
                if (ticket != generation || !active) return@execute
                val outcome = runCatching {
                    request("/v1/predictions", JSONObject().put("text", input.text)
                        .put("version", input.version).put("policy_id", policy.id))
                }
                val result = outcome.getOrNull()
                main.post {
                    main.removeCallbacks(timeout)
                    if (ticket != generation || !active) return@post
                    if ((outcome.exceptionOrNull() as? PredictionFailure)?.code == 412) {
                        active = false
                        message = "예측 제공자 설정이 바뀌었어요. 온라인 예측 설정에서 안내를 다시 확인해 주세요."
                    } else if (result == null || result.optLong("version", -1) != input.version) {
                        message = "예측을 가져오지 못했어요. 직접 입력을 계속할 수 있어요."
                    } else {
                        val words = result.optJSONArray("words") ?: JSONArray()
                        gate.offer(input.version, input.candidates((0 until words.length()).map { words.optString(it) }, result.optString("continuation")))
                        message = "후보를 선택하거나 계속 입력해 주세요"
                    }
                    changed()
                }
            }
        }, 350)
    }

    fun hold(held: Boolean) {
        if (gate.held == held) return
        gate.hold(held); changed()
    }
    fun stop() {
        generation++; active = false; main.removeCallbacksAndMessages(null)
        connection?.disconnect(); gate.clear()
        message = "온라인 예측을 사용하지 않고 있어요. 직접 입력은 계속할 수 있어요."
    }
    fun close() { stop(); worker.shutdownNow() }
}

private class PredictionFailure(val code: Int) : Exception()

private fun java.io.InputStream.readBytesLimited(limit: Int): ByteArray {
    val output = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(1_024)
    while (true) {
        val count = read(buffer)
        if (count < 0) break
        check(output.size() + count <= limit)
        output.write(buffer, 0, count)
    }
    return output.toByteArray()
}
