plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.whisperboard"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.whisperboard"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"

        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
    }

    signingConfigs {
        create("release") {
            val props = project.rootProject.file("local.properties")
                .takeIf { it.exists() }
                ?.let { java.util.Properties().apply { load(it.inputStream()) } }

            storeFile = file(System.getenv("KEYSTORE_FILE") ?: props?.getProperty("signing.storeFile") ?: "keystore.jks")
            storePassword = System.getenv("KEYSTORE_PASSWORD") ?: props?.getProperty("signing.storePassword") ?: ""
            keyAlias = System.getenv("KEY_ALIAS") ?: props?.getProperty("signing.keyAlias") ?: ""
            keyPassword = System.getenv("KEY_PASSWORD") ?: props?.getProperty("signing.keyPassword") ?: ""
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation(project(":whisper"))

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)

    debugImplementation(libs.androidx.compose.ui.tooling)
}
