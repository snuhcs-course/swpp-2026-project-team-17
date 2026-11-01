import java.security.MessageDigest

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Build provenance recorded by gaze evaluation sessions. Values that cannot be determined stay "unknown".
fun gitOutput(vararg args: String): String? = try {
    val out = providers.exec { commandLine(listOf("git") + args); isIgnoreExitValue = true }
    if (out.result.get().exitValue == 0) out.standardOutput.asText.get().trim() else null
} catch (e: Exception) { null }
fun sha256Hex(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

val gitCommit = gitOutput("rev-parse", "HEAD") ?: "unknown"
val gitDirty = gitOutput("status", "--porcelain")?.let { it.isNotEmpty().toString() } ?: "unknown"
val gitBaseMain = gitOutput("merge-base", "HEAD", "refs/remotes/origin/main") ?: "unknown"
val evaluationProtocol = rootProject.file("docs/evaluation/iteration3/protocol.json")
// Same rule as tools/gaze_eval: SHA-256 of the manifest bytes with CRLF converted to LF.
val evaluationProtocolSha256 = sha256Hex(evaluationProtocol.readText(Charsets.UTF_8).replace("\r\n", "\n").toByteArray(Charsets.UTF_8))
val faceLandmarkerSha256 = sha256Hex(file("src/main/assets/face_landmarker.task").readBytes())
val mediapipeVersion = "0.10.26"
val cameraxVersion = "1.4.2"

android {
    namespace = "dev.gazedot"
    compileSdk = 35
    defaultConfig {
        applicationId = "dev.gazedot"
        minSdk = 26
        targetSdk = 35
        versionCode = 7
        versionName = "0.7.0"
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64") }
        buildConfigField("String", "GIT_COMMIT", "\"$gitCommit\"")
        buildConfigField("String", "GIT_DIRTY", "\"$gitDirty\"")
        buildConfigField("String", "GIT_BASE_MAIN", "\"$gitBaseMain\"")
        buildConfigField("String", "EVAL_PROTOCOL_SHA256", "\"$evaluationProtocolSha256\"")
        buildConfigField("String", "FACE_LANDMARKER_SHA256", "\"$faceLandmarkerSha256\"")
        buildConfigField("String", "MEDIAPIPE_VERSION", "\"$mediapipeVersion\"")
        buildConfigField("String", "CAMERAX_VERSION", "\"$cameraxVersion\"")
    }
    buildFeatures { buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    androidResources { noCompress += "task" }
    testOptions {
        unitTests.all { test ->
            // Evaluation tests read the protocol manifest and the cross-language golden session.
            test.inputs.file(evaluationProtocol)
            test.inputs.dir(rootProject.file("tools/gaze_eval/testdata"))
            // Optional offline replay of recorded sessions: -PgazeReplaySession=<dir> [-PgazeReplayOut=<dir>],
            // paths relative to the repository root.
            providers.gradleProperty("gazeReplaySession").orNull?.let {
                test.systemProperty("gaze.replay.session", rootProject.file(it).absolutePath)
                test.outputs.upToDateWhen { false }
            }
            providers.gradleProperty("gazeReplayOut").orNull?.let { test.systemProperty("gaze.replay.out", rootProject.file(it).absolutePath) }
        }
    }
}
dependencies {
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.20.0")
    implementation("androidx.activity:activity-ktx:1.10.1")
    implementation("androidx.lifecycle:lifecycle-service:2.8.7")
    implementation("androidx.camera:camera-camera2:$cameraxVersion")
    implementation("androidx.camera:camera-lifecycle:$cameraxVersion")
    implementation("com.google.mediapipe:tasks-vision:$mediapipeVersion")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
