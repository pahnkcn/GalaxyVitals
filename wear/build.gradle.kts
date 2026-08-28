import java.security.MessageDigest

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

val samsungAar = file("libs/samsung-health-sensor-api.aar")
val samsungAarSha256 = if (samsungAar.isFile) {
    val digest = MessageDigest.getInstance("SHA-256")
    samsungAar.inputStream().use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read <= 0) break
            digest.update(buffer, 0, read)
        }
    }
    digest.digest().joinToString("") { byte -> "%02X".format(byte) }
} else {
    "MISSING"
}

android {
    namespace = "app.galaxyvitals.wear"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.galaxyvitals"
        minSdk = 33
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        buildConfigField("String", "SAMSUNG_HEALTH_SENSOR_SDK_VERSION", "\"1.4.1\"")
        buildConfigField("String", "SAMSUNG_HEALTH_SENSOR_AAR_SHA256", "\"$samsungAarSha256\"")
    }

    buildTypes {
        release {
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
        buildConfig = true
    }
}

dependencies {
    implementation(project(":protocol"))
    if (samsungAar.exists()) {
        implementation(files(samsungAar))
    } else {
        implementation(project(":samsung-health-api"))
    }
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.fragment)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.viewmodel.navigation3)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material.icons)
    implementation(libs.androidx.wear.compose.foundation)
    implementation(libs.androidx.wear.compose.material3)
    implementation(libs.androidx.wear.compose.navigation3)
    implementation(libs.androidx.navigation3.runtime)
    implementation(libs.androidx.navigation3.ui)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.play.services.wearable)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.play.services)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.wear.compose.ui.tooling)
    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(project(":samsung-health-api"))
}

private fun excludeSamsungAar(files: Iterable<java.io.File>): List<java.io.File> =
    files.filter { !it.name.contains("samsung-health-sensor-api") }

tasks.withType<Test>().configureEach {
    doFirst {
        classpath = files(excludeSamsungAar(classpath))
    }
}
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    if (name.contains("UnitTest", ignoreCase = true)) {
        doFirst {
            libraries.setFrom(excludeSamsungAar(libraries))
        }
    }
}
