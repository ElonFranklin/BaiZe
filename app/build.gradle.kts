plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.protobuf") version "0.9.4"
}

android {
    namespace = "com.baize.ai"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.baize.ai"
        minSdk = 28
        targetSdk = 34
        versionCode = 90
        versionName = "0.9.0-launch"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            ndkVersion = "27.0.12077973"
            abiFilters += listOf("arm64-v8a")
        }

        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++17 -frtti -fexceptions"
                arguments += listOf(
                    "-DANDROID_STL=c++_shared",
                    "-DLLAMA_NATIVE=OFF"
                )
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }


    val keystorePropertiesFile = rootProject.file("keystore.properties")
    fun keystoreProperty(key: String, defaultValue: String): String {
        if (!keystorePropertiesFile.exists()) return System.getenv(key) ?: defaultValue
        val value = keystorePropertiesFile.readText().lineSequence().map { it.trim() }.firstOrNull { it.startsWith("$key=") }?.substringAfter("=") ?: ""
        return if (value.isNotBlank()) value else (System.getenv(key) ?: defaultValue)
    }

    signingConfigs {
        create("release") {
            storeFile = rootProject.file(keystoreProperty("RELEASE_STORE_FILE", "keystores/baize-launch.jks"))
            storePassword = keystoreProperty("RELEASE_STORE_PASSWORD", "")
            keyAlias = keystoreProperty("RELEASE_KEY_ALIAS", "")
            keyPassword = keystoreProperty("RELEASE_KEY_PASSWORD", "")
        }
    }

    buildTypes {
        getByName("release") {
            signingConfig = signingConfigs.getByName("release")
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = true
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
        compose = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    // AndroidX Core
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.activity:activity-compose:1.9.1")
    implementation("androidx.appcompat:appcompat:1.7.0")

    // Compose BOM (compatible with Kotlin 2.0.0)
    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.compose.foundation:foundation")


    // ViewModel + Compose
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.4")

    // SQLite
    implementation("androidx.sqlite:sqlite-ktx:2.4.0")
    implementation("androidx.sqlite:sqlite-framework:2.4.0")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")



    // JSON parsing
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.1")

    // Testing
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation(platform("androidx.compose:compose-bom:2024.06.00"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    implementation("io.coil-kt:coil-compose:2.6.0")
    implementation("androidx.compose.material:material-icons-extended")
    
    // LanCluster gRPC client
    // implementation(project(":lancluster-core"))
}





























