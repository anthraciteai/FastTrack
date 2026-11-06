import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
}

/**
 * Developer-set secrets (API keys, model URL) read from a gitignored `local.properties` at build
 * time, or from environment variables in CI. These are baked into BuildConfig — end users never
 * type them. Any missing value defaults to blank so the app still builds and runs (plumbing stays
 * dormant / falls back).
 */
val secretsProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun secret(key: String): String {
    var value = (secretsProps.getProperty(key) ?: System.getenv(key) ?: "").replace("\"", "\\\"")
    if (key == "GOOGLE_OAUTH_CLIENT_ID") {
        while (value.contains(".apps.googleusercontent.com.apps.googleusercontent.com")) {
            value = value.replace(
                ".apps.googleusercontent.com.apps.googleusercontent.com",
                ".apps.googleusercontent.com"
            )
        }
    }
    return value
}

android {
    namespace = "ai.anthracite.fasttrack"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "ai.anthracite.fasttrack"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // ---- Integration secrets (set in local.properties / CI env, never by end users) ----
        // Garmin Health / Connect IQ.
        buildConfigField("String", "GARMIN_CONSUMER_KEY", "\"${secret("GARMIN_CONSUMER_KEY")}\"")
        buildConfigField("String", "GARMIN_CONSUMER_SECRET", "\"${secret("GARMIN_CONSUMER_SECRET")}\"")
        buildConfigField("String", "GARMIN_ACTIVATION_KEY", "\"${secret("GARMIN_ACTIVATION_KEY")}\"")
        // Garmin Connect Developer "Personal" tier: a single pull-only access token (bearer).
        buildConfigField("String", "GARMIN_PERSONAL_TOKEN", "\"${secret("GARMIN_PERSONAL_TOKEN")}\"")
        // Google OAuth client id for Calendar + Sign-In.
        buildConfigField("String", "GOOGLE_OAUTH_CLIENT_ID", "\"${secret("GOOGLE_OAUTH_CLIENT_ID")}\"")
        // Base URL for the Component-2 web backend sync API.
        buildConfigField(
            "String", "SYNC_BASE_URL",
            "\"${secret("SYNC_BASE_URL").ifBlank { "http://10.0.2.2:5173/" }}\""
        )
        // Optional remote URL for the on-device food-log LLM. Normally the model ships bundled in
        // assets and auto-installs; this is only a fallback if you prefer a hosted download.
        buildConfigField("String", "MEAL_MODEL_URL", "\"${secret("MEAL_MODEL_URL")}\"")
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
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
    // Keep the on-device model weights uncompressed in the APK so they can be memory-mapped and
    // copied efficiently on first launch.
    androidResources {
        noCompress += listOf("task", "bin", "tflite", "litertlm")
    }
}

// With AGP's built-in Kotlin disabled (external KGP 2.3.21 for litertlm 0.18.1), the Kotlin JVM
// target must be aligned with the Java compileOptions (11) explicitly.
kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
    }
}

dependencies {
    // Core + UI (XML / Views)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.coordinatorlayout)
    implementation(libs.androidx.swiperefreshlayout)
    implementation(libs.androidx.recyclerview)
    implementation(libs.androidx.viewpager2)
    implementation(libs.androidx.splashscreen)
    implementation(libs.androidx.preference)

    // Lifecycle + Fragments + Navigation
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.livedata.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.fragment.ktx)
    implementation(libs.androidx.navigation.fragment.ktx)
    implementation(libs.androidx.navigation.ui.ktx)

    // Persistence (Room)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // Async
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.play.services)

    // Reminders / background work
    implementation(libs.androidx.work.runtime.ktx)

    // Web sync plumbing (Component 2)
    implementation(libs.retrofit)
    implementation(libs.retrofit.converter.moshi)
    implementation(libs.okhttp.logging)
    implementation(libs.moshi.kotlin)

    // Integration plumbing
    implementation(libs.androidx.health.connect)
    implementation(libs.play.services.auth)
    // GPS distance/pace tracking during live walks/runs/rides (FusedLocationProvider).
    implementation("com.google.android.gms:play-services-location:21.3.0")
    // Enterprise QR enrollment/sign-in — Google code scanner (ML Kit, no camera UI/permission).
    implementation("com.google.android.gms:play-services-code-scanner:16.1.0")

    // On-device LLM (food-log estimator) — LiteRT-LM Kotlin API
    implementation(libs.litertlm.android)

    testImplementation(libs.junit)
    // org.json is an Android-only stub at runtime; pull the real impl for JVM unit tests.
    testImplementation("org.json:json:20240303")
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
