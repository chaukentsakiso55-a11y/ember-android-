plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.ember.assistant"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.ember.assistant"
        minSdk = 26
        targetSdk = 35
        versionCode = 12
        versionName = "12.0.0"
    }
}
