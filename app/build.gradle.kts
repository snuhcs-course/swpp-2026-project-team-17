import java.util.Properties
import java.security.MessageDigest

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
// TODO: AI provider/model/API key are unset in server/.env. Only a proxy URL belongs in the APK.
val predictionProperties = Properties().apply {
    val config = rootProject.file("online-prediction.properties")
    if (config.isFile) config.inputStream().use { load(it) }
}
val predictionUrl = predictionProperties.getProperty("PREDICTION_SERVER_URL", "")
    .replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "").replace("\r", "")
val predictionToken = predictionProperties.getProperty("PREDICTION_CLIENT_TOKEN", "").trim()
require(predictionToken.isEmpty() || predictionToken.matches(Regex("[A-Za-z0-9_-]{43}")))
android {
    namespace = "dev.gazedot"
    compileSdk = 35
    defaultConfig {
        applicationId = "dev.gazedot"
        minSdk = 26
        targetSdk = 35
        versionCode = 17
        versionName = "0.11.0-itracker-research"
        buildConfigField("String", "PREDICTION_SERVER_URL", "\"$predictionUrl\"")
        buildConfigField("String", "PREDICTION_CLIENT_TOKEN", "\"$predictionToken\"")
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64") }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    androidResources { noCompress += "task" }
    buildFeatures { buildConfig = true }
}
dependencies {
    implementation("androidx.activity:activity-ktx:1.10.1")
    implementation("androidx.lifecycle:lifecycle-service:2.8.7")
    implementation("androidx.camera:camera-camera2:1.4.2")
    implementation("androidx.camera:camera-lifecycle:1.4.2")
    implementation("com.google.mediapipe:tasks-vision:0.10.26")
    implementation("org.opencv:opencv:4.12.0")
    testImplementation("junit:junit:4.13.2")
}

val verifyITrackerAssets by tasks.registering {
    val assetDir = layout.projectDirectory.dir("src/main/assets/itracker")
    inputs.dir(assetDir)
    doLast {
        val weights = assetDir.file("weights.caffemodel").asFile
        check(weights.isFile) { "Run python tools/itracker/prepare.py before building this research APK" }
        val hash = MessageDigest.getInstance("SHA-256").digest(weights.readBytes())
            .joinToString("") { "%02x".format(it) }
        check(hash == "d748042ebd402a4b6418cdff44a6e64f9bca1256fd3761f7ddc68fd5519ef98a") {
            "Wrong or incomplete official iTracker checkpoint"
        }
        for (name in listOf("left", "right", "face"))
            check(assetDir.file("mean_$name.f32").asFile.length() == 602112L)
        check(assetDir.file("deploy.prototxt").asFile.isFile)
        check(assetDir.file("LICENSE.md").asFile.isFile)
    }
}
tasks.named("preBuild").configure { dependsOn(verifyITrackerAssets) }
