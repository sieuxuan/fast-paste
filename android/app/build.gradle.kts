import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.fastpaste.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.fastpaste.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 226
        versionName = "2.2.6"
    }

    signingConfigs {
        if (releaseSigningReady) {
            create("githubRelease") {
                storeFile = signingStoreFile
                storePassword = signingStorePassword
                keyAlias = signingKeyAlias
                keyPassword = signingKeyPassword
            }
        }
    }

    buildTypes {
        debug {
            if (releaseSigningReady) signingConfig = signingConfigs.getByName("githubRelease")
        }

        release {
            isMinifyEnabled = true
            if (releaseSigningReady) signingConfig = signingConfigs.getByName("githubRelease")
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

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    lint {
        // AGP 8.5's Compose detector cannot read Kotlin metadata 2.1 from a
        // transitive library and crashes before reporting the other checks.
        disable += "CoroutineCreationDuringComposition"
    }
}

tasks.matching { it.name == "packageRelease" }.configureEach {
    doFirst {
        check(releaseSigningReady) {
            "Thiếu Android signing secrets; không tạo APK release unsigned."
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.sqlcipher)
    implementation(libs.androidx.sqlite)

    implementation(libs.okhttp)
    implementation(libs.play.services.auth)
    implementation(libs.play.services.code.scanner)

    debugImplementation(libs.compose.ui.tooling)
    testImplementation(libs.junit)
    testImplementation("org.json:json:20240303")
}

val localSigning = Properties().apply {
    rootProject.file("keystore.properties").takeIf { it.exists() }?.inputStream()?.use(::load)
}

fun signingSecret(name: String): String? =
    providers.environmentVariable(name).orNull
        ?: localSigning.getProperty(name)?.takeIf(String::isNotBlank)

val signingStoreFile = signingSecret("FASTPASTE_ANDROID_KEYSTORE_PATH")
    ?.let(rootProject::file)
    ?: file("fastpaste-release.keystore")
val signingStorePassword = signingSecret("FASTPASTE_ANDROID_STORE_PASSWORD")
val signingKeyAlias = signingSecret("FASTPASTE_ANDROID_KEY_ALIAS")
val signingKeyPassword = signingSecret("FASTPASTE_ANDROID_KEY_PASSWORD")
val releaseSigningReady = signingStoreFile.exists() &&
    signingStorePassword != null && signingKeyAlias != null && signingKeyPassword != null
