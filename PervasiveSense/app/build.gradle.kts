plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.iitj.pervasivesense"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.iitj.pervasivesense"
        minSdk = 26
        targetSdk = 34
        versionCode = 2
        versionName = "2.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
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
        viewBinding = true
    }

    androidResources {
        // The model is read into a direct buffer; keep it uncompressed in the APK.
        noCompress += "tflite"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // IN_VEHICLE detection (Google Activity Recognition)
    implementation("com.google.android.gms:play-services-location:21.3.0")

    // TensorFlow Lite runtime. tests/test_tflite_model.py asserts the model loads in
    // THIS version - change both together.
    implementation("org.tensorflow:tensorflow-lite:2.16.1")

    // Pure-JVM tests of the sensing engine: ./gradlew :app:testDebugUnitTest
    testImplementation("junit:junit:4.13.2")
}
