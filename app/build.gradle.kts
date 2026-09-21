import java.io.FileInputStream
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.jetbrainsKotlinSerialization)
}

// Vision provider credentials for the on-device expiry agent. local.properties
// is gitignored, so the key stays out of the repository - but it is compiled
// into the APK and anyone holding that file can recover it. Rotate accordingly.
//
// Named gemini* since 2026-09-08, when the agent moved from DashScope to
// Gemini's OpenAI-compatible endpoint on measured latency. The protocol is
// OpenAI chat-completions either way, so pointing these three back at DashScope
// - or at any other OpenAI-compatible provider - needs no code change.
val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) FileInputStream(file).use { load(it) }
}

fun localProperty(name: String, fallback: String): String =
    localProperties.getProperty(name)?.takeIf { it.isNotBlank() } ?: fallback

android {
    namespace = "com.expagent"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.expagent"
        minSdk = 24
        targetSdk = 36
        versionCode = 24
        versionName = "0.2.4"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "GEMINI_API_KEY", "\"${localProperty("geminiApiKey", "")}\"")

        // The fallback is the model the agent is actually evaluated against.
        // Probed 2026-09-08: 3.5-flash was both the fastest and the only stable
        // one - 3.6, 3.7, 3.8 and flash-latest returned 503s or 15-37s outliers.
        buildConfigField(
            "String",
            "GEMINI_MODEL",
            "\"${localProperty("geminiModel", "gemini-3.5-flash")}\"",
        )

        // The planner call carries no image and emits about a hundred tokens of
        // closed vocabulary, so it does not need the model that reads the date.
        // Defaults to the same model, so this changes nothing until it is set.
        buildConfigField(
            "String",
            "GEMINI_POLICY_MODEL",
            "\"${localProperty("geminiPolicyModel", localProperty("geminiModel", "gemini-3.5-flash"))}\"",
        )

        buildConfigField(
            "String",
            "GEMINI_BASE_URL",
            "\"${localProperty("geminiBaseUrl", "https://generativelanguage.googleapis.com/v1beta/openai")}\"",
        )
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    lint {
        // This codebase deliberately writes single-statement if/while bodies
        // brace-less and at the same indentation as the condition, so every
        // one of them trips SuspiciousIndentation. All flagged sites were
        // audited and are correct; the check has no signal here.
        disable += "SuspiciousIndentation"
    }
}

dependencies {

coreLibraryDesugaring(libs.desugar.jdk.libs)

implementation(libs.rtk.kotlin.android)

implementation(libs.camera.core)
implementation(libs.camera.lifecycle)
implementation(libs.camera.camera2)
implementation(libs.camera.view)
implementation(libs.ktor.client.core)
implementation(libs.ktor.client.cio)
implementation(libs.kotlinx.coroutines.core)
implementation(libs.kotlinx.serialization.json)
implementation(libs.eventbus)
implementation(libs.navigation.fragment)
implementation(libs.navigation.ui)
implementation(libs.recyclerview)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
