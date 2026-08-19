plugins {
    id("com.android.application")
}

android {
    namespace = "dev.voxvargr.aaarp.aatrusthook"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.voxvargr.aaarp.aatrusthook"
        minSdk = 30
        targetSdk = 36
        versionCode = 5
        versionName = "0.5.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources {
            merges += "META-INF/xposed/*"
            excludes += "**"
        }
    }

    lint {
        abortOnError = true
    }
}

dependencies {
    compileOnly("io.github.libxposed:api:102.0.0")
    testImplementation("junit:junit:4.13.2")
}

// AGP 9 adds Kotlin stdlib to every app by default. This module is Java-only and should carry no
// runtime library code into the hooked process.
configurations.matching { it.name.endsWith("RuntimeClasspath") }.configureEach {
    exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib")
}
