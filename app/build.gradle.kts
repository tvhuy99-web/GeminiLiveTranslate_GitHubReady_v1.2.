import java.util.Base64

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.oai.geminilivetranslate"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.oai.geminilivetranslate"
        minSdk = 26
        targetSdk = 36
        versionCode = 10202
        versionName = "1.2.2"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    val releaseStoreFile = providers.gradleProperty("releaseStoreFile").orNull
        ?: System.getenv("RELEASE_STORE_FILE")
    val releaseStorePassword = providers.gradleProperty("releaseStorePassword").orNull
        ?: System.getenv("RELEASE_STORE_PASSWORD")
    val releaseKeyAlias = providers.gradleProperty("releaseKeyAlias").orNull
        ?: System.getenv("RELEASE_KEY_ALIAS")
    val releaseKeyPassword = providers.gradleProperty("releaseKeyPassword").orNull
        ?: System.getenv("RELEASE_KEY_PASSWORD")
    val hasReleaseSigning = listOf(
        releaseStoreFile,
        releaseStorePassword,
        releaseKeyAlias,
        releaseKeyPassword,
    ).all { !it.isNullOrBlank() }

    val updateStoreFile = providers.gradleProperty("updateStoreFile").orNull
        ?: System.getenv("UPDATE_STORE_FILE")
    val updateStorePassword = providers.gradleProperty("updateStorePassword").orNull
        ?: System.getenv("UPDATE_STORE_PASSWORD")
    val updateKeyAlias = providers.gradleProperty("updateKeyAlias").orNull
        ?: System.getenv("UPDATE_KEY_ALIAS")
    val updateKeyPassword = providers.gradleProperty("updateKeyPassword").orNull
        ?: System.getenv("UPDATE_KEY_PASSWORD")
    val hasUpdateSigning = listOf(
        updateStoreFile,
        updateStorePassword,
        updateKeyAlias,
        updateKeyPassword,
    ).all { !it.isNullOrBlank() }

    val bundledDebugKey = rootProject.file(".github/signing/stable-debug.keystore.b64")
    val bundledDebugStore = rootProject.file(".gradle/stable-debug.keystore")
    val hasBundledDebugSigning = bundledDebugKey.isFile
    if (hasBundledDebugSigning) {
        bundledDebugStore.parentFile.mkdirs()
        bundledDebugStore.writeBytes(
            Base64.getMimeDecoder().decode(bundledDebugKey.readText().trim()),
        )
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(requireNotNull(releaseStoreFile))
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
                enableV4Signing = true
            }
        }
        if (hasUpdateSigning) {
            create("update") {
                storeFile = file(requireNotNull(updateStoreFile))
                storePassword = updateStorePassword
                keyAlias = updateKeyAlias
                keyPassword = updateKeyPassword
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
                enableV4Signing = true
            }
        }
        if (hasBundledDebugSigning) {
            create("stableDebug") {
                storeFile = bundledDebugStore
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
                enableV4Signing = true
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            if (hasUpdateSigning) {
                signingConfig = signingConfigs.getByName("update")
            } else if (hasBundledDebugSigning) {
                signingConfig = signingConfigs.getByName("stableDebug")
            }
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    lint {
        abortOnError = true
        checkReleaseBuilds = true
        sarifReport = true
        xmlReport = true
        htmlReport = true
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    packaging {
        resources.excludes += setOf("META-INF/AL2.0", "META-INF/LGPL2.1")
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.10.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.11.0")
    implementation("androidx.lifecycle:lifecycle-service:2.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
