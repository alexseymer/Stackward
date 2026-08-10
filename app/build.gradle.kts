import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

val keystoreProperties = Properties().apply {
    val propsFile = rootProject.file("keystore.properties")
    if (propsFile.exists()) {
        load(propsFile.inputStream())
    }
}

fun signingProp(name: String, envVar: String, default: String): String =
    keystoreProperties.getProperty(name) ?: System.getenv(envVar) ?: default

android {
    namespace = "dev.stackward"
    compileSdk = 37

    defaultConfig {
        applicationId = "dev.stackward"
        minSdk = 28
        targetSdk = 35
        versionCode = 10
        versionName = "0.5.9-dogfood"
    }

    signingConfigs {
        create("dogfood") {
            storeFile = file(signingProp("dogfood.storeFile", "DOGFOOD_STORE_FILE", "dogfood.keystore"))
            storePassword = signingProp("dogfood.storePassword", "DOGFOOD_STORE_PASSWORD", "dogfood")
            keyAlias = signingProp("dogfood.keyAlias", "DOGFOOD_KEY_ALIAS", "dogfood")
            keyPassword = signingProp("dogfood.keyPassword", "DOGFOOD_KEY_PASSWORD", "dogfood")
            enableV1Signing = true
            enableV2Signing = true
            enableV3Signing = false
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("dogfood")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("dogfood")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        create("dogfood") {
            initWith(getByName("release"))
            // Separate package ID avoids signature conflicts with older installs.
            applicationIdSuffix = ".dogfood"
            isDebuggable = false
            ndk {
                abiFilters.clear()
                abiFilters += "arm64-v8a"
            }
            matchingFallbacks += listOf("release")
            signingConfig = signingConfigs.getByName("dogfood")
        }
        // Tiny install-probe APK (no native libs) to diagnose sideload failures.
        create("smoke") {
            initWith(getByName("release"))
            applicationIdSuffix = ".smoke"
            versionNameSuffix = "-smoke"
            isDebuggable = false
            matchingFallbacks += listOf("release")
            signingConfig = signingConfigs.getByName("dogfood")
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
        jniLibs {
            useLegacyPackaging = false
        }
        resources {
            excludes += "/META-INF/versions/9/OSGI-INF/MANIFEST.MF"
        }
    }

    testOptions {
        unitTests.isIncludeAndroidResources = false
    }
}

androidComponents {
    onVariants(selector().withBuildType("smoke")) { variant ->
        // Strip every .so so the probe APK is small and has no 16 KB / ELF concerns.
        variant.packaging.jniLibs.excludes.add("**/*.so")
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.06.01")

    implementation("androidx.core:core-ktx:1.19.0")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.fragment:fragment-ktx:1.8.5")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.11.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0")

    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    implementation("androidx.biometric:biometric:1.1.0")
    implementation("androidx.security:security-crypto:1.1.0")

    implementation("androidx.work:work-runtime-ktx:2.10.0")

    // On-device LLM — Phase 2 (MediaPipe LLM Inference API)
    implementation("com.google.mediapipe:tasks-genai:0.10.35")

    // SSH — Phase 1 (full BC replaces Android's stripped provider for X25519/Ed25519)
    implementation("com.hierynomus:sshj:0.40.0")
    implementation("org.bouncycastle:bcprov-jdk18on:1.85")
    implementation("org.bouncycastle:bcpkix-jdk18on:1.85")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20260719")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
