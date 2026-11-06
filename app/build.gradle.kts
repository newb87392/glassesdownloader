plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "com.open.glassesdownloader"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.open.glassesdownloader"
        minSdk = 26
        targetSdk = 35
        versionCode = 31
        versionName = "0.13.0-rc17"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    testImplementation("junit:junit:4.13.2")
    // Android's org.json is a stub in local JVM tests; the real implementation is needed to
    // exercise MediaIndex without a device.
    testImplementation("org.json:json:20240303")
}
