import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.kapt")
    id("androidx.room")
    id("com.google.android.gms.oss-licenses-plugin")
}

// Owner-controlled, local-only credential for the explicitly requested Gemini test APK.
// This key is embedded in debug APK bytes, so this file must remain ignored and uncommitted.
val geminiTestProperties = Properties().apply {
    val config = rootProject.file("gemini-test.properties")
    if (config.exists()) config.inputStream().use { load(it) }
}
val geminiDebugApiKey = geminiTestProperties.getProperty("gemini.apiKey", "").trim()
fun String.asBuildConfigString(): String = "\"" + replace("\\", "\\\\").replace("\"", "\\\"") + "\""

// A local Firebase configuration is optional; the application remains usable without it.
if (file("google-services.json").exists()) {
    apply(plugin = "com.google.gms.google-services")
}

android {
    namespace = "ai.memora.app"
    compileSdk = 36
    defaultConfig {
        applicationId = "ai.memora.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 88
        versionName = "1.1.66-gemini-model-selection"
        testInstrumentationRunner = "ai.memora.app.GeminiSyntheticProbe"
    }
    buildFeatures { compose = true; buildConfig = true }
    buildTypes {
        getByName("debug") {
            buildConfigField("String", "GEMINI_API_KEY", geminiDebugApiKey.asBuildConfigString())
        }
        getByName("release") {
            buildConfigField("String", "GEMINI_API_KEY", "\"\"")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    testOptions { unitTests.isIncludeAndroidResources = true }
    bundle { language { enableSplit = false } }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.05.01")
    implementation(composeBom)
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation(platform("com.google.firebase:firebase-bom:35.1.0"))
    implementation("com.google.firebase:firebase-ai")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")
    implementation("com.google.android.gms:play-services-oss-licenses:17.5.1")
    // Match the Firebase AI transport dependency; expose language hints missing from SDK 18.1.0.
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("androidx.room:room-runtime:2.8.5")
    implementation("androidx.room:room-ktx:2.8.5")
    kapt("androidx.room:room-compiler:2.8.5")
    kaptTest("androidx.room:room-compiler:2.8.5")
    kaptAndroidTest("androidx.room:room-compiler:2.8.5")
    implementation("net.zetetic:sqlcipher-android:4.20.0@aar")
    implementation("androidx.sqlite:sqlite:2.7.0")
    debugImplementation("com.google.firebase:firebase-appcheck-debug")
    releaseImplementation("com.google.firebase:firebase-appcheck-playintegrity")
    debugImplementation("androidx.compose.ui:ui-tooling")

    testImplementation(composeBom)
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

room { schemaDirectory("$projectDir/schemas") }

tasks.withType<Test>().configureEach {
    maxHeapSize = "2g"
    systemProperty("robolectric.dependency.repo.url", "https://repo.maven.apache.org/maven2")
    systemProperty("memora.screenshots", layout.buildDirectory.dir("test-screenshots").get().asFile.absolutePath)
}
