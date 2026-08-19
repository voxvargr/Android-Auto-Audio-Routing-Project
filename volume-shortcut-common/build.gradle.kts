plugins {
    id("com.android.library")
}

android {
    namespace = "dev.voxvargr.aaarp.shortcut"
    compileSdk = 36

    defaultConfig {
        minSdk = 23
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("androidx.car.app:app:1.7.0")
    implementation("androidx.car.app:app-projected:1.7.0")
    testImplementation("junit:junit:4.13.2")
}
