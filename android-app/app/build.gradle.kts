import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

val releaseSigningFile = providers.environmentVariable("ANDROID_SIGNING_PROPERTIES")
    .orNull?.takeIf { it.isNotBlank() }?.let { file(it) }
val releaseSigning = Properties().apply {
    releaseSigningFile?.let {
        if (!it.isFile) throw GradleException("ANDROID_SIGNING_PROPERTIES 指向的签名配置文件不存在。")
        it.inputStream().use { stream -> load(stream) }
    }
    if (releaseSigningFile == null) {
        mapOf("storeFile" to "ANDROID_KEYSTORE_FILE", "storeType" to "ANDROID_KEYSTORE_TYPE",
            "storePassword" to "ANDROID_KEYSTORE_PASSWORD", "keyAlias" to "ANDROID_KEY_ALIAS",
            "keyPassword" to "ANDROID_KEY_PASSWORD").forEach { (property, variable) ->
            providers.environmentVariable(variable).orNull?.let { setProperty(property, it) }
        }
    }
}
val hasReleaseSigning =
    listOf("storeFile", "storePassword", "keyAlias", "keyPassword")
        .all { !releaseSigning.getProperty(it).isNullOrBlank() }

android {
    namespace = "com.windy.phonemouse.app"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.windy.phonemouse.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 4
        versionName = "1.1.0"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = releaseSigningFile?.parentFile?.resolve(releaseSigning.getProperty("storeFile"))
                    ?: rootProject.file(releaseSigning.getProperty("storeFile"))
                storeType = releaseSigning.getProperty("storeType", "PKCS12")
                storePassword = releaseSigning.getProperty("storePassword")
                keyAlias = releaseSigning.getProperty("keyAlias")
                keyPassword = releaseSigning.getProperty("keyPassword")
            }
        }
    }
    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
        }
        release {
            isMinifyEnabled = false
            if (hasReleaseSigning) signingConfig = signingConfigs.getByName("release")
        }
    }
}

val validateReleaseSigning = tasks.register("validateReleaseSigning") {
    doLast {
        if (!hasReleaseSigning) {
            throw GradleException("Release 构建需要 ANDROID_SIGNING_PROPERTIES 配置文件或完整的 ANDROID_KEYSTORE_* / ANDROID_KEY_* 环境变量；开发测试请使用 assembleDebug。")
        }
    }
}
tasks.matching { it.name == "preReleaseBuild" }.configureEach {
    dependsOn(validateReleaseSigning)
}

// Keep the first build reproducible with the Android/Gradle artifacts already
// available on this machine. These versions are API-compatible with the code.
configurations.configureEach {
    resolutionStrategy.force(
        "org.jetbrains.kotlin:kotlin-stdlib:2.2.10",
        "org.jetbrains.kotlin:kotlin-stdlib-jdk7:2.2.10",
        "org.jetbrains.kotlin:kotlin-stdlib-jdk8:2.2.10",
        "org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:1.10.2",
        "org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2",
        "org.jetbrains.kotlinx:kotlinx-serialization-core-jvm:1.9.0",
        "org.jetbrains.kotlinx:kotlinx-serialization-json-jvm:1.9.0",
    )
}

dependencies {
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.compose.ui:ui:1.11.3")
    implementation("androidx.compose.foundation:foundation:1.11.3")
    implementation("androidx.compose.material3:material3:1.4.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.10.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    implementation("androidx.datastore:datastore-preferences:1.2.1")
    implementation("com.squareup.okhttp3:okhttp-android:5.1.0")
    implementation("org.jetbrains:annotations:23.0.0")
    add("kotlinCompilerClasspath", "org.jetbrains:annotations:23.0.0")
    add("kotlinCompilerClasspath", "org.jetbrains.kotlin:kotlin-compiler-embeddable:2.3.10")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
