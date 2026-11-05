import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// --- Podpis -----------------------------------------------------------------------------------
// Aktualizace přes GitHub fungují jen tehdy, když jsou všechna APK podepsaná STEJNÝM klíčem.
// Lokálně: soubor keystore.properties v kořeni projektu (není v Gitu).
// GitHub Actions: proměnné SIGNING_* ze secrets (viz .github/workflows/release.yml).
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun signing(key: String, env: String): String? = keystoreProps.getProperty(key) ?: System.getenv(env)
val signingStoreFile = signing("storeFile", "SIGNING_STORE_FILE")?.takeIf { it.isNotBlank() }

// --- Verze ------------------------------------------------------------------------------------
// versionCode = 100 + počet commitů (stejně lokálně i na GitHubu), takže každý push je novější verze.
fun gitCommitCount(): Int? = runCatching {
    val process = ProcessBuilder("git", "rev-list", "--count", "HEAD")
        .directory(rootDir)
        .redirectErrorStream(true)
        .start()
    val out = process.inputStream.bufferedReader().readText().trim()
    process.waitFor()
    out.toInt()
}.getOrNull()

val appVersionCode = System.getenv("VERSION_CODE")?.toIntOrNull() ?: gitCommitCount()?.plus(100) ?: 1

android {
    namespace = "cz.fotokiosek"
    compileSdk = 35

    defaultConfig {
        applicationId = "cz.fotokiosek"
        // Automaty mívají starý Android (5–11), proto co nejnižší minimum.
        minSdk = 21
        targetSdk = 34
        versionCode = appVersionCode
        versionName = "1.$appVersionCode"
        buildConfigField("String", "GITHUB_REPO", "\"dominikperlik-png/fotokiosek\"")
    }

    signingConfigs {
        if (signingStoreFile != null) {
            create("release") {
                storeFile = rootProject.file(signingStoreFile)
                storePassword = signing("storePassword", "SIGNING_STORE_PASSWORD")
                keyAlias = signing("keyAlias", "SIGNING_KEY_ALIAS")
                keyPassword = signing("keyPassword", "SIGNING_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        val shared = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        debug {
            // I ladicí sestavení podepisujeme vydávacím klíčem, ať jde přes něj později aktualizovat.
            signingConfig = shared
        }
        release {
            isMinifyEnabled = false
            signingConfig = shared
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
    packaging {
        resources {
            // android-mail a android-activation obsahují stejné licenční soubory
            excludes += setOf("META-INF/LICENSE.md", "META-INF/NOTICE.md", "META-INF/LICENSE", "META-INF/NOTICE")
        }
    }
    lint {
        // Sestavení na GitHubu nesmí spadnout kvůli varováním lintu.
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    val camerax = "1.4.1"
    implementation("androidx.camera:camera-core:$camerax")
    implementation("androidx.camera:camera-camera2:$camerax")
    implementation("androidx.camera:camera-lifecycle:$camerax")

    // Fronta odesílání – e-maily se pošlou, i když zrovna nejde internet
    implementation("androidx.work:work-runtime-ktx:2.9.1")

    // SMTP klient
    implementation("com.sun.mail:android-mail:1.6.7")
    implementation("com.sun.mail:android-activation:1.6.7")

    // Logo ve formátu SVG
    implementation("com.caverock:androidsvg-aar:1.4")
}
