plugins {
    id("com.android.application")
}

android {
    namespace = "dev.voxvargr.aaarp.volumedown"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.voxvargr.aaarp.volumedown"
        minSdk = 23
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":volume-shortcut-common"))
}
