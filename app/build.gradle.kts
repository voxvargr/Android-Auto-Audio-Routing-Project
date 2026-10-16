plugins {
    id("com.android.application")
}

android {
    namespace = "dev.voxvargr.aaarp"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.voxvargr.aaarp"
        minSdk = 23
        targetSdk = 36
        versionCode = 4
        versionName = "0.2.2"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
