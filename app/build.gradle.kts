plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "ch.luca.tuff.urlocalai"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "ch.luca.tuff.urlocalai"
        minSdk = 23
        targetSdk = 37
        versionCode = 1
        versionName = "1.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk {
            // Force la compilation uniquement pour ton vrai téléphone et l'émulateur 32-bit
            abiFilters.addAll(setOf("armeabi-v7a", "x86"))
        }

        externalNativeBuild {
            cmake {
                // Force l'accélération NEON et désactive OpenMP (qui détruirait tes 1 Go de RAM)
                arguments("-DANDROID_ARM_NEON=TRUE", "-DGGML_OPENMP=OFF")
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}