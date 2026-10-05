import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// Firma de release: crea `keystore.properties` en la raíz (ver PLAY_STORE.md). Nunca lo subas a git.
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "com.shortsmaker.viral"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.shortsmaker.viral"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // AdMob: por defecto (debug) se usan los IDs DE PRUEBA de Google, para no generar tráfico inválido con tus IDs reales.
        // Los IDs reales van sólo en el build de release (ver `buildTypes.release`).
        manifestPlaceholders["admobAppId"] = "ca-app-pub-3940256099942544~3347511713"
        buildConfigField("String", "ADMOB_BANNER_ID", "\"ca-app-pub-3940256099942544/6300978111\"")
    }

    androidResources {
        localeFilters += listOf("es", "en", "fr", "de", "pt", "zh", "ja", "ru", "hi")
        noCompress += "tflite" // MediaPipe mapea el modelo directamente desde assets
    }

    signingConfigs {
        if (keystoreProps.containsKey("storeFile")) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // AdMob: IDs REALES (sólo release). Nunca hagas clic en tus propios anuncios; prueba con los builds debug.
            manifestPlaceholders["admobAppId"] = "ca-app-pub-5065513980633271~1960158272"
            buildConfigField("String", "ADMOB_BANNER_ID", "\"ca-app-pub-5065513980633271/1037783834\"")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "META-INF/DEPENDENCIES")
        // Las librerías nativas (Vosk/JNA/MediaPipe) se empaquetan sin comprimir y alineadas a 16 KB.
        jniLibs.useLegacyPackaging = false
    }

    lint {
        // Media3 marca casi toda su API de efectos/transformer como @UnstableApi.
        disable += "UnsafeOptInUsageError"
        // Ejecuta `./gradlew lint` antes de publicar y revisa el informe.
        abortOnError = false
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.media3.common)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.ui)
    implementation(libs.media3.transformer)
    implementation(libs.media3.effect)

    implementation(libs.androidx.work.runtime) // análisis en segundo plano (Foreground Service)

    implementation(libs.okhttp)

    // Anuncios (AdMob) + formulario de consentimiento UMP (GDPR/EEE/Reino Unido)
    implementation(libs.play.services.ads)
    implementation(libs.user.messaging.platform)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    // IA 100 % on-device (sin servidores propios)
    implementation(libs.vosk.android)            // transcripción offline (Apache 2.0)
    implementation(libs.mediapipe.tasks.vision)  // detección de rostros (Apache 2.0)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.core)
}
