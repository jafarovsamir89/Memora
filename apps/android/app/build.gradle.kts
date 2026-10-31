plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

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
        versionCode = 5
        versionName = "0.5.0-az-memory"
        testInstrumentationRunner = "ai.memora.app.GeminiSyntheticProbe"
    }
    buildFeatures { compose = true }
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
    // Match the Firebase AI transport dependency; expose language hints missing from SDK 18.1.0.
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    debugImplementation("com.google.firebase:firebase-appcheck-debug")
    releaseImplementation("com.google.firebase:firebase-appcheck-playintegrity")
    debugImplementation("androidx.compose.ui:ui-tooling")

    testImplementation(composeBom)
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

tasks.withType<Test>().configureEach {
    maxHeapSize = "2g"
    systemProperty("robolectric.dependency.repo.url", "https://repo.maven.apache.org/maven2")
    systemProperty("memora.screenshots", layout.buildDirectory.dir("test-screenshots").get().asFile.absolutePath)
}
