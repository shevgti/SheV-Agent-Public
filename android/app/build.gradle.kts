plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}
android {
    namespace = "com.shev.agent"
    compileSdk = 37
    defaultConfig {
        applicationId = "com.shev.agent"
        minSdk = 26
        targetSdk = 35
        versionCode = 17
        versionName = "0.2.6"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        val githubClientId = providers.gradleProperty("GITHUB_CLIENT_ID")
            .orElse(providers.environmentVariable("GITHUB_CLIENT_ID"))
            .getOrElse("")
        require(githubClientId.isEmpty() || githubClientId.matches(Regex("[A-Za-z0-9._-]{8,100}"))) {
            "GITHUB_CLIENT_ID must be a valid GitHub OAuth App / GitHub App client ID."
        }
        buildConfigField("String", "GITHUB_CLIENT_ID", "\"$githubClientId\"")
    }
    signingConfigs {
        create("release") {
            val storePath = System.getenv("SHEV_UPLOAD_STORE_FILE")
            if (!storePath.isNullOrBlank()) {
                storeFile = file(storePath)
                storePassword = System.getenv("SHEV_UPLOAD_STORE_PASSWORD")
                keyAlias = System.getenv("SHEV_UPLOAD_KEY_ALIAS")
                keyPassword = System.getenv("SHEV_UPLOAD_KEY_PASSWORD")
            }
        }
    }
    buildTypes {
        getByName("release") {
            signingConfig = signingConfigs.getByName("release")
        }
    }
    buildFeatures { compose = true; buildConfig = true }
    packaging {
        resources {
            excludes += "META-INF/INDEX.LIST"
            excludes += "META-INF/DEPENDENCIES"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}
dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.activity:activity-compose:1.10.0")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    implementation("androidx.room:room-runtime:2.8.5")
    implementation("androidx.room:room-ktx:2.8.5")
    implementation("com.google.code.gson:gson:2.11.0")

    // Optional offline Russian neural TTS runtime; model weights are downloaded on demand.
    implementation("com.k2fsa:sherpa-onnx:v2.0.1@aar")
    implementation("org.apache.commons:commons-compress:1.27.1")

    // Google ADK Kotlin + on-device LiteRT-LM inference.
    implementation("com.google.adk:google-adk-kotlin-core:1.5.0")
    implementation("com.google.adk:google-adk-kotlin-litertlm:1.5.0")
    ksp("com.google.adk:google-adk-kotlin-processor:1.5.0")
    implementation("com.google.ai.edge.litertlm:litertlm-android:0.16.1")
    ksp("androidx.room:room-compiler:2.8.5")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:core:1.6.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
