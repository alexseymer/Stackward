import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

/** Debug-only onboarding prefills from root `local.properties` (gitignored). */
fun loadLocalProperties(): Properties {
    val props = Properties()
    val file = rootProject.file("local.properties")
    if (file.exists()) {
        file.inputStream().use { props.load(it) }
    }
    return props
}

fun Properties.dev(key: String, default: String = ""): String {
    val raw = getProperty("stackward.dev.$key", default).orEmpty().trim()
    // Properties does not treat quotes as delimiters — strip accidental wrapping.
    return when {
        raw.length >= 2 &&
            ((raw.startsWith('"') && raw.endsWith('"')) ||
                (raw.startsWith('\'') && raw.endsWith('\''))) ->
            raw.substring(1, raw.length - 1)
        else -> raw
    }
}

fun String.asBuildConfigString(): String =
    "\"${replace("\\", "\\\\").replace("\"", "\\\"")}\""

val localProps = loadLocalProperties()

android {
    namespace = "dev.stackward"
    compileSdk = 37

    defaultConfig {
        applicationId = "dev.stackward"
        minSdk = 28
        targetSdk = 35
        versionCode = 6
        versionName = "0.5.5-dogfood"

        // Empty defaults so release / CI never bake in secrets.
        buildConfigField("boolean", "DEV_PREFILL", "false")
        buildConfigField("String", "DEV_HOST", "\"\"")
        buildConfigField("String", "DEV_PORT", "\"22\"")
        buildConfigField("String", "DEV_USERNAME", "\"\"")
        buildConfigField("String", "DEV_SSH_PASSWORD", "\"\"")
        buildConfigField("String", "DEV_KNOCK_SEQUENCE", "\"\"")
        buildConfigField("boolean", "DEV_USE_JUMP_HOST", "false")
        buildConfigField("String", "DEV_JUMP_HOST", "\"\"")
        buildConfigField("String", "DEV_JUMP_PORT", "\"22\"")
    }

    buildTypes {
        debug {
            val prefill = localProps.dev("prefill", "false").equals("true", ignoreCase = true)
            buildConfigField("boolean", "DEV_PREFILL", prefill.toString())
            buildConfigField("String", "DEV_HOST", localProps.dev("host").asBuildConfigString())
            buildConfigField("String", "DEV_PORT", localProps.dev("port", "22").asBuildConfigString())
            buildConfigField("String", "DEV_USERNAME", localProps.dev("username").asBuildConfigString())
            buildConfigField(
                "String",
                "DEV_SSH_PASSWORD",
                localProps.dev("ssh_password").asBuildConfigString(),
            )
            buildConfigField(
                "String",
                "DEV_KNOCK_SEQUENCE",
                localProps.dev("knock_sequence").asBuildConfigString(),
            )
            buildConfigField(
                "boolean",
                "DEV_USE_JUMP_HOST",
                localProps.dev("use_jump_host", "false").equals("true", ignoreCase = true).toString(),
            )
            buildConfigField(
                "String",
                "DEV_JUMP_HOST",
                localProps.dev("jump_host").asBuildConfigString(),
            )
            buildConfigField(
                "String",
                "DEV_JUMP_PORT",
                localProps.dev("jump_port", "22").asBuildConfigString(),
            )
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/versions/9/OSGI-INF/MANIFEST.MF"
        }
    }

    testOptions {
        unitTests.isIncludeAndroidResources = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")

    implementation("androidx.core:core-ktx:1.19.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.fragment:fragment-ktx:1.8.5")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.11.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0")

    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    implementation("androidx.biometric:biometric:1.1.0")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    implementation("androidx.work:work-runtime-ktx:2.10.0")

    // On-device LLM — Phase 2 (MediaPipe LLM Inference API)
    implementation("com.google.mediapipe:tasks-genai:0.10.35")

    // SSH — Phase 1 (full BC replaces Android's stripped provider for X25519/Ed25519)
    implementation("com.hierynomus:sshj:0.40.0")
    implementation("org.bouncycastle:bcprov-jdk18on:1.78.1")
    implementation("org.bouncycastle:bcpkix-jdk18on:1.78.1")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
