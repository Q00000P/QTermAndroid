import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

val gdriveClientId = (project.findProperty("qterm.gdriveClientId") as String?)?.trim().orEmpty()

android {
    namespace = "org.qterm.android"
    compileSdk = 36

    defaultConfig {
        applicationId = "org.qterm.android"
        minSdk = 26
        targetSdk = 36
        versionCode = 38
        versionName = "3.8.0"

        // OAuth Google: client id из gradle.properties (qterm.gdriveClientId=…)
        buildConfigField("String", "GDRIVE_CLIENT_ID", "\"$gdriveClientId\"")
        manifestPlaceholders["appAuthRedirectScheme"] =
            if (gdriveClientId.isNotEmpty()) {
                "com.googleusercontent.apps." + gdriveClientId.removeSuffix(".apps.googleusercontent.com")
            } else {
                "org.qterm.oauth.unused"
            }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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
        resources.excludes += setOf(
            "META-INF/DEPENDENCIES",
            "META-INF/LICENSE*",
            "META-INF/NOTICE*",
            "META-INF/INDEX.LIST",
            "META-INF/*.md",
        )
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.06.01")
    implementation(composeBom)
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")

    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")

    // Терминал и SSH — стек ConnectBot (Apache-2.0), заточен под Android
    implementation("org.connectbot:termlib:0.1.0")
    implementation("org.connectbot:sshlib:2.2.48")

    // OAuth для Google Drive
    implementation("net.openid:appauth:0.11.1")

    // Локальный вейлт — этап Argon2id + биометрия
    implementation("com.lambdapioneer.argon2kt:argon2kt:1.6.0")
    implementation("androidx.biometric:biometric:1.1.0")

    testImplementation("junit:junit:4.13.2")
}
