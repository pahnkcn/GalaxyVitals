import java.io.File
import java.security.MessageDigest

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "app.galaxyvitals"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.galaxyvitals"
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
        noCompress += "tflite"
    }
}

val verifyEcgNao3Bundle by tasks.registering {
    group = "verification"
    description = "Fails release builds when the hash-bound NAO3 bundle is absent or changed."
    doLast {
        val assetsRoot = file("src/main/assets").canonicalFile
        val manifestFile = file("src/main/assets/ecg/ecg_nao3_bundle.json").canonicalFile
        if (!manifestFile.isFile) throw GradleException("Missing ECG ecg_nao3_bundle.json")
        val manifest = groovy.json.JsonSlurper().parse(manifestFile) as? Map<*, *>
            ?: throw GradleException("Invalid ECG NAO3 bundle")
        if (manifest["schema"] != "app.galaxyvitals.ecg.nao3.bundle" ||
            manifest["version"] != 2 ||
            manifest["compatibility_id"] != "ecg-nao3-student-256hz-v2"
        ) {
            throw GradleException("Unexpected ECG NAO3 bundle contract")
        }
        val artifacts = manifest["artifacts"] as? Map<*, *>
            ?: throw GradleException("Invalid ECG analysis bundle artifacts")
        if (artifacts.keys != setOf("model", "filters", "policy", "split")) {
            throw GradleException("ECG NAO3 bundle must bind model, filters, policy, and split")
        }
        artifacts.forEach { (rawName, rawEntry) ->
            val name = rawName as? String
                ?: throw GradleException("Invalid ECG analysis artifact name")
            val entry = rawEntry as? Map<*, *>
                ?: throw GradleException("Invalid ECG analysis artifact: $name")
            if (entry.keys != setOf("path", "sha256")) {
                throw GradleException("Invalid ECG analysis artifact contract: $name")
            }
            val relativePath = entry["path"] as? String
                ?: throw GradleException("Missing ECG analysis artifact path: $name")
            val expectedSha256 = entry["sha256"] as? String
                ?: throw GradleException("Missing ECG analysis artifact hash: $name")
            if (!Regex("[0-9a-f]{64}").matches(expectedSha256)) {
                throw GradleException("Invalid ECG analysis artifact hash: $name")
            }
            val artifact = File(assetsRoot, relativePath).canonicalFile
            if (!artifact.toPath().startsWith(assetsRoot.toPath()) ||
                artifact == assetsRoot ||
                !artifact.isFile ||
                artifact.length() == 0L
            ) {
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
            if (actual != expectedSha256) {
                throw GradleException("ECG analysis artifact hash mismatch: $name")
            }
        }

        val filtersFile = file("src/main/assets/ecg/ecg_nao3_filters_256hz.json").canonicalFile
        if (!filtersFile.isFile) {
            throw GradleException("Missing ECG ecg_nao3_filters_256hz.json")
        }
        val filters = groovy.json.JsonSlurper().parse(filtersFile) as? Map<*, *>
            ?: throw GradleException("Invalid ECG NAO3 filters JSON")
        val sos = filters["sos"] as? List<*>
            ?: throw GradleException("ECG NAO3 filters missing sos array")
        if (sos.size != 5) {
            throw GradleException("ECG NAO3 filters sos must have exactly 5 rows, found ${sos.size}")
        }
        sos.forEachIndexed { index, rawRow ->
            val row = rawRow as? List<*>
                ?: throw GradleException("ECG NAO3 filters sos row $index is not an array")
            if (row.size != 6) {
                throw GradleException(
                    "ECG NAO3 filters sos row $index must have exactly 6 coefficients, found ${row.size}",
                )
            }
            row.forEachIndexed { coeffIndex, rawCoeff ->
                val number = rawCoeff as? Number
                    ?: throw GradleException(
                        "ECG NAO3 filters sos[$index][$coeffIndex] is not a number",
                    )
                val value = number.toDouble()
                if (!value.isFinite()) {
                    throw GradleException(
                        "ECG NAO3 filters sos[$index][$coeffIndex] is not finite: $value",
                    )
                }
            }
        }

        val policyFile = file("src/main/assets/ecg/ecg_nao3_decision_policy.json").canonicalFile
        val splitFile = file("src/main/assets/ecg/ecg_nao3_cinc2017_split.json").canonicalFile
        if (!policyFile.isFile) throw GradleException("Missing ECG ecg_nao3_decision_policy.json")
        if (!splitFile.isFile) throw GradleException("Missing ECG ecg_nao3_cinc2017_split.json")
        val policy = groovy.json.JsonSlurper().parse(policyFile) as? Map<*, *>
            ?: throw GradleException("Invalid ECG NAO3 decision policy JSON")
        val split = groovy.json.JsonSlurper().parse(splitFile) as? Map<*, *>
            ?: throw GradleException("Invalid ECG NAO3 CinC 2017 split JSON")
        if (policy["schema"] != "app.galaxyvitals.ecg.nao3.decision_policy") {
            throw GradleException("Unexpected ECG NAO3 decision policy schema")
        }
        if (policy["compatibility_id"] != "ecg-nao3-student-256hz-v2") {
            throw GradleException("ECG NAO3 decision policy compatibility id mismatch")
        }
        val precisionTarget = (policy["precision_target"] as? Number)?.toDouble()
            ?: throw GradleException("ECG NAO3 decision policy missing precision_target")
        if (precisionTarget != 0.9) {
            throw GradleException("ECG NAO3 decision policy precision_target must be 0.9")
        }
        val minMargin = (policy["min_margin"] as? Number)?.toDouble()
            ?: throw GradleException("ECG NAO3 decision policy missing min_margin")
        if (!minMargin.isFinite() || minMargin < 0.0) {
            throw GradleException("ECG NAO3 decision policy min_margin must be finite and non-negative")
        }
        val classes = policy["classes"] as? Map<*, *>
            ?: throw GradleException("ECG NAO3 decision policy missing classes")
        if (classes.keys != setOf("N", "A", "O")) {
            throw GradleException("ECG NAO3 decision policy must define classes N, A, and O")
        }
        if (split["schema"] != "app.galaxyvitals.ecg.nao3.cinc2017.split") {
            throw GradleException("Unexpected ECG NAO3 CinC 2017 split schema")
        }
        if (split["split_rule"] != "record-disjoint") {
            throw GradleException("ECG NAO3 CinC 2017 split must be record-disjoint")
        }
        val splitStatus = split["status"] as? String
            ?: throw GradleException("ECG NAO3 CinC 2017 split missing status")
        val calibrationRecords = split["calibration_records"] as? List<*>
            ?: throw GradleException("ECG NAO3 CinC 2017 split missing calibration_records")
        val evaluationRecords = split["evaluation_records"] as? List<*>
            ?: throw GradleException("ECG NAO3 CinC 2017 split missing evaluation_records")
        val calibrationAbsent = splitStatus == "calibration_data_absent" || calibrationRecords.isEmpty()
        classes.forEach { (rawName, rawClass) ->
            val classPolicy = rawClass as? Map<*, *>
                ?: throw GradleException("Invalid ECG NAO3 class policy: $rawName")
            val alwaysAbstain = classPolicy["always_abstain"] as? Boolean
                ?: throw GradleException("ECG NAO3 class $rawName missing always_abstain")
            val demonstrated = (classPolicy["demonstrated_precision"] as? Number)?.toDouble()
            if (!alwaysAbstain) {
                if (demonstrated == null || !demonstrated.isFinite() || demonstrated < 0.9) {
                    throw GradleException(
                        "ECG NAO3 class $rawName cannot be enabled without demonstrated precision >= 0.9",
                    )
                }
            }
            if (calibrationAbsent && !alwaysAbstain) {
                throw GradleException(
                    "ECG NAO3 class $rawName cannot be enabled while CinC 2017 calibration data is absent",
                )
            }
        }
        if (calibrationAbsent && evaluationRecords.isNotEmpty()) {
            throw GradleException("Absent CinC 2017 calibration cannot claim evaluation records")
        }
    }
}

tasks.matching { it.name == "preBuild" }.configureEach {
    dependsOn(verifyEcgNao3Bundle)
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
    implementation(libs.litert)
    debugImplementation(libs.androidx.compose.ui.tooling)
    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.sqlite.jdbc)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.truth)
}
