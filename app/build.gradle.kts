import java.security.MessageDigest

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "app.healthtrack"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.healthtrack"
        minSdk = 32
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
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
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    androidResources {
        noCompress += "onnx"
    }
}

val verifyEcgFounderModel by tasks.registering {
    group = "verification"
    description = "Fails release builds when any ECG analysis-bundle artifact is absent or changed."
    doLast {
        val assetsRoot = file("src/main/assets").canonicalFile
        val manifestFile = file("src/main/assets/ecg/analysis_bundle.json")
        if (!manifestFile.isFile) throw GradleException("Missing ECG analysis_bundle.json")
        @Suppress("UNCHECKED_CAST")
        val manifest = groovy.json.JsonSlurper().parse(manifestFile) as Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val artifacts = manifest["artifacts"] as? Map<String, Map<String, String>>
            ?: throw GradleException("Invalid ECG analysis bundle artifacts")
        if (artifacts.keys != setOf("model", "labels", "filters", "calibrator", "thresholds")) {
            throw GradleException("ECG analysis bundle must bind model, labels, filters, calibrator, and thresholds")
        }
        artifacts.forEach { (name, entry) ->
            val artifact = file("src/main/assets/${entry["path"]}").canonicalFile
            if (!artifact.path.startsWith(assetsRoot.path) || !artifact.isFile || artifact.length() == 0L) {
                throw GradleException("Missing ECG analysis artifact: $name")
            }
            val digest = MessageDigest.getInstance("SHA-256")
            artifact.inputStream().use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
            }
            val actual = digest.digest().joinToString("") { byte: Byte ->
                (byte.toInt() and 0xff).toString(16).padStart(2, '0')
            }
            if (actual != entry["sha256"]) {
                throw GradleException("ECG analysis artifact hash mismatch: $name")
            }
        }
    }
}

tasks.matching { it.name == "preReleaseBuild" }.configureEach {
    dependsOn(verifyEcgFounderModel)
}

dependencies {
    implementation(project(":protocol"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.fragment)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    implementation(libs.androidx.navigation3.runtime)
    implementation(libs.androidx.navigation3.ui)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.play.services.wearable)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.play.services)
    implementation(libs.onnxruntime.android)
    debugImplementation(libs.androidx.compose.ui.tooling)
    testImplementation(libs.junit)
    testImplementation(libs.truth)
}
