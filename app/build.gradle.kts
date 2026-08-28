import java.io.FileInputStream
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

/**
 * Release signing is read from keystore.properties, which is gitignored and never committed.
 * Absent that file the release build simply stays unsigned, so cloning and building still works
 * for anyone else without handing them a key. See RELEASING.md for how to create one.
 */
val localPropertiesFile = rootProject.file("local.properties")
val localProperties = Properties().apply {
    if (localPropertiesFile.exists()) FileInputStream(localPropertiesFile).use { load(it) }
}

val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) {
        FileInputStream(keystorePropertiesFile).use { load(it) }
    }
}

android {
    namespace = "com.atomic.atomicamp.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.atomic.atomicamp"
        minSdk = 26
        targetSdk = 35
        // Sideloaded builds already went out as 1; Android refuses an update that does
        // not increase this, and the failure looks like a corrupt download.
        versionCode = 2
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Seed values only. These are copied into settings on first run and read from there
        // afterwards, so the app can be pointed at a different key without a rebuild -- and so a
        // build with none of these still works, just with no cloud library configured.
        buildConfigField("String", "B2_KEY_ID", "\"${localProperties.getProperty("b2.keyId", "")}\"")
        buildConfigField("String", "B2_APP_KEY", "\"${localProperties.getProperty("b2.appKey", "")}\"")
        buildConfigField("String", "B2_BUCKET", "\"${localProperties.getProperty("b2.bucket", "")}\"")
        buildConfigField("String", "B2_PREFIX", "\"${localProperties.getProperty("b2.prefix", "")}\"")
    }

    // The exported schemas are what MigrationTestHelper builds old databases from, so the test APK
    // has to carry them.
    sourceSets.getByName("androidTest").assets.srcDir("$projectDir/schemas")

    signingConfigs {
        if (keystorePropertiesFile.exists()) {
            create("release") {
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(project(":engine"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.androidx.navigation.compose)
    implementation(libs.coil.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)

    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.androidx.room.testing)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
}
