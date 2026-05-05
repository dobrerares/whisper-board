import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "com.whisperboard"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.whisperboard"
        minSdk = 26
        targetSdk = 35
        versionCode = 6
        versionName = "1.0.0"

        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
    }

    signingConfigs {
        create("release") {
            storeFile = file(System.getenv("KEYSTORE_FILE") ?: localProps.getProperty("signing.storeFile", "keystore.jks"))
            storePassword = System.getenv("KEYSTORE_PASSWORD") ?: localProps.getProperty("signing.storePassword", "")
            keyAlias = System.getenv("KEY_ALIAS") ?: localProps.getProperty("signing.keyAlias", "")
            keyPassword = System.getenv("KEY_PASSWORD") ?: localProps.getProperty("signing.keyPassword", "")
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

    testOptions {
        unitTests {
            // android.util.Log and other stubs return zero/null instead of
            // throwing — lets our Kotlin code under test call Log.d/Log.w
            // freely without having to mock the framework.
            isReturnDefaultValues = true
        }
    }

    // Both :whisper and :llm produce a copy of libggml*.so because each
    // module builds ggml as part of its own CMake graph. The `.so`s are
    // ABI-compatible (both come from the same ggml inside whisper.cpp /
    // llama.cpp), so packaging picks the first one it sees rather than
    // failing the merge. The two JNI shims both load via System.loadLibrary
    // and depend transitively on the same ggml ABI surface — `pickFirst`
    // is the appropriate disposition.
    packaging {
        jniLibs {
            pickFirsts += listOf(
                "lib/*/libggml.so",
                "lib/*/libggml-base.so",
                "lib/*/libggml-cpu.so",
            )
        }
    }
}

dependencies {
    implementation(project(":whisper"))
    implementation(project(":llm"))

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.okhttp)
    implementation(libs.androidx.security.crypto)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.org.json)
    testImplementation(libs.androidx.room.testing)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
}
