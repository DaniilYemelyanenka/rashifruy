plugins {
    id("com.android.application")
}

android {
    namespace = "ru.meevo.rasshifruy"
    compileSdk = 37

    defaultConfig {
        applicationId = "ru.meevo.rasshifruy"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            // R8/ProGuard полностью отключены — защита нативного JNI-слоя sherpa-onnx от искажений оптимизатором.
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
        viewBinding = true
    }

    androidResources {
        // ONNX-файлы моделей не должны сжиматься в APK, иначе ORT не примет их из assets.
        noCompress += "onnx"
    }
}

dependencies {
    // sherpa-onnx (v2.0.1): единственный движок распознавания и диаризации (офлайн, JNI).
    implementation(files("libs/sherpa-onnx.aar"))

    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.10.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.activity:activity:1.8.0")
    implementation("androidx.core:core:1.10.1")

    testImplementation("junit:junit:4.13.2")

    androidTestImplementation("androidx.test:core:1.5.0")
    androidTestImplementation("androidx.test:runner:1.5.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
}
