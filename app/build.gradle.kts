import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
// OpenAI provider/model/key settings stay in server/.env. Only a proxy URL belongs in the APK.
val predictionProperties = Properties().apply {
    val config = rootProject.file("online-prediction.properties")
    if (config.isFile) config.inputStream().use { load(it) }
}
val predictionUrl = predictionProperties.getProperty("PREDICTION_SERVER_URL", "https://notouch-server.vercel.app")
    .replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "").replace("\r", "")
android {
    namespace = "dev.gazedot"
    compileSdk = 35
    defaultConfig {
        applicationId = "dev.gazedot"
        minSdk = 26
        targetSdk = 35
        versionCode = 31
        versionName = "0.1.31"
        buildConfigField("String", "PREDICTION_SERVER_URL", "\"$predictionUrl\"")
        ndk { abiFilters += (providers.gradleProperty("targetAbi").orNull?.let { listOf(it) } ?: listOf("arm64-v8a", "armeabi-v7a", "x86_64")) }
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
    testImplementation("junit:junit:4.13.2")
}
