// app/build.gradle.kts
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.parcelize")
    id("org.jetbrains.kotlin.kapt")
    id("org.jetbrains.kotlin.plugin.serialization") // ЛР11: notes.json
}

android {
    namespace = "com.phonoteka"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.phonoteka"
        minSdk = 24
        targetSdk = 34
        versionCode = 2
        versionName = "1.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // Defaults for every variant; each flavor overrides them explicitly.
        manifestPlaceholders["allowBackup"] = "false"
        manifestPlaceholders["allowCleartext"] = "false"
    }

    // Две сборки одной кодовой базы (объединённая Фонотека).
    // Различия ТОЛЬКО в поведении защитных механизмов: экраны, навигация, фикстуры — общие.
    flavorDimensions += "security"
    productFlavors {
        create("vulnerable") {
            dimension = "security"
            applicationIdSuffix = ".vulnerable"
            buildConfigField("String", "SECURITY_MODE", "\"VULNERABLE\"")
            buildConfigField("boolean", "ENFORCE_CERT_PINNING", "false")
            buildConfigField("boolean", "ENFORCE_STEP_UP", "false")
            buildConfigField("boolean", "ENFORCE_OTP_SINGLE_USE", "false")
            buildConfigField("boolean", "ENFORCE_QR_TICKET_SINGLE_USE", "false")
            buildConfigField("boolean", "REQUIRE_FRESH_AUTH_FOR_LIBRARY", "false")
            buildConfigField("boolean", "ALLOW_USER_CA", "true")
            buildConfigField("boolean", "ALLOW_CLEARTEXT", "true")
            buildConfigField("boolean", "ALLOW_BACKUP", "true")
            manifestPlaceholders["allowBackup"] = "true"
            manifestPlaceholders["allowCleartext"] = "true"
        }
        create("hardened") {
            dimension = "security"
            applicationIdSuffix = ".hardened"
            buildConfigField("String", "SECURITY_MODE", "\"HARDENED\"")
            buildConfigField("boolean", "ENFORCE_CERT_PINNING", "true")
            buildConfigField("boolean", "ENFORCE_STEP_UP", "true")
            buildConfigField("boolean", "ENFORCE_OTP_SINGLE_USE", "true")
            buildConfigField("boolean", "ENFORCE_QR_TICKET_SINGLE_USE", "true")
            buildConfigField("boolean", "REQUIRE_FRESH_AUTH_FOR_LIBRARY", "true")
            buildConfigField("boolean", "ALLOW_USER_CA", "false")
            buildConfigField("boolean", "ALLOW_CLEARTEXT", "false")
            buildConfigField("boolean", "ALLOW_BACKUP", "false")
            manifestPlaceholders["allowBackup"] = "false"
            manifestPlaceholders["allowCleartext"] = "false"
        }
    }

    buildTypes {
        debug {
            // Public owned-fixture key only, never a real service secret. Empty => WSS demo disabled.
            val localFixtureKey = System.getenv("PHONOTEKA_MOCK_HMAC_KEY") ?: "phonoteka-owned-lab15-demo-key-v1"
            require(localFixtureKey.isEmpty() || localFixtureKey.matches(Regex("[A-Za-z0-9._-]{1,128}")))
            buildConfigField("String", "LOCAL_FIXTURE_KEY", "\"$localFixtureKey\"")
            // Отдельный applicationId, чтобы debug-сборка вставала рядом с release-вариантом.
            applicationIdSuffix = ".debug"
        }
        release {
            buildConfigField("String", "LOCAL_FIXTURE_KEY", "\"\"")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    if (providers.gradleProperty("withBenchmarks").orNull == "true") {
        buildTypes.create("benchmark") {
            initWith(buildTypes.getByName("release"))
            applicationIdSuffix = ".labcheck"
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
            isDebuggable = false
        }
        sourceSets.getByName("benchmark").java.srcDir("src/release/java")
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.15"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true // java.util.Base64 на API 24-25
    }

    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf(
            "-P", "plugin:androidx.compose.compiler.plugins.kotlin:reportsDestination=${buildDir.absolutePath}/compose_reports",
            "-P", "plugin:androidx.compose.compiler.plugins.kotlin:metricsDestination=${buildDir.absolutePath}/compose_metrics"
        )
    }

    testOptions {
        unitTests {
            isReturnDefaultValues = true
        }
    }
}

dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")
    // Compose BOM
    val composeBom = platform("androidx.compose:compose-bom:2024.09.03")
    implementation(composeBom)

    // Compose
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.foundation:foundation")

    // Activity + Lifecycle
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.biometric:biometric:1.1.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")

    // Navigation
    implementation("androidx.navigation:navigation-compose:2.8.0")

    // Room
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    kapt("androidx.room:room-compiler:2.6.1")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // Owned local QR invitations: camera requested only by scanner, no network decoder.
    implementation("androidx.camera:camera-core:1.3.4")
    implementation("androidx.camera:camera-camera2:1.3.4")
    implementation("androidx.camera:camera-lifecycle:1.3.4")
    implementation("androidx.camera:camera-view:1.3.4")
    implementation("com.google.zxing:core:3.5.3")

    // Media3 (audio player)
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-ui:1.4.1")
    implementation("androidx.media3:media3-session:1.4.1")

    // DataStore (settings)
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    // Network + downloader
    implementation("com.github.TeamNewPipe:NewPipeExtractor:v0.26.3")
    // Override the extractor's transitive parser: fixed versions for
    // GHSA-65r4-943x-97jj and GHSA-pmhh-3w7g-xqp8. Keep resolution fail-closed.
    implementation("org.jsoup:jsoup:1.23.2") {
        version { strictly("1.23.2") }
    }
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:okhttp-tls:4.12.0") // Ephemeral certificate for the owned phone identity host.
    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.retrofit2:converter-gson:2.11.0")
    implementation("com.google.code.gson:gson:2.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3") // ЛР11: FreeNotesCodec
    implementation("io.coil-kt:coil-compose:2.6.0")

    // Debug
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("com.squareup.okhttp3:logging-interceptor:4.12.0")
    debugImplementation("com.squareup.okhttp3:mockwebserver:4.12.0") // Owned WSS fixture hosted by the second phone.

    // Testing
    testImplementation("junit:junit:4.13.2")
    // ЛР13 доп.: настоящая реализация org.json для JVM-тестов (android.jar содержит только заглушки)
    testImplementation("org.json:json:20240303")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("com.squareup.okhttp3:okhttp-tls:4.12.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
    androidTestImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
    testImplementation("androidx.room:room-testing:2.6.1")
    testImplementation("org.mockito:mockito-core:5.12.0")
    testImplementation("org.mockito.kotlin:mockito-kotlin:5.4.0")

    androidTestImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    androidTestImplementation("com.squareup.okhttp3:okhttp-tls:4.12.0")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation(platform(composeBom))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

// Current Room schemas are generated by a real build, not hand-authored historical evidence.
kapt { arguments { arg("room.schemaLocation", "$projectDir/schemas") } }
