import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Base64

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// Локальные настройки — в ~/.gradle/gradle.properties (в гит не попадают), в CI — переменные окружения.
fun prop(name: String): String? = (project.findProperty(name) as String?)?.trim()?.takeIf { it.isNotEmpty() }
fun env(name: String): String? = System.getenv(name)?.trim()?.takeIf { it.isNotEmpty() }

// Google OAuth: Android-клиент привязан к SHA-1 подписи → у debug и release разные client id
// (README-GDRIVE.md). Release берёт qterm.gdriveClientIdRelease, без него — общий qterm.gdriveClientId.
val gdriveClientId = prop("qterm.gdriveClientId").orEmpty()
val gdriveClientIdRelease = prop("qterm.gdriveClientIdRelease") ?: gdriveClientId
fun redirectScheme(id: String) =
    if (id.isNotEmpty()) "com.googleusercontent.apps." + id.removeSuffix(".apps.googleusercontent.com")
    else "org.qterm.oauth.unused"

// Токен обновлений (GitHub, только чтение Q00000P/QTerm-releases): вшивается в BuildConfig.UPDATE_TOKEN.
// Пустой — проверка обновлений в приложении отключена с понятным сообщением.
val updateToken = (prop("qterm.updateToken") ?: env("QTERM_UPDATE_TOKEN")).orEmpty()
    .filter { it.isLetterOrDigit() || it == '_' }

// Release-подпись: ~/.gradle/gradle.properties (qterm.release.*) или CI-переменные (QTERM_KEYSTORE_B64 …).
// Нет ни того ни другого — release собирается неподписанным (app-release-unsigned.apk), debug не затрагивается.
data class ReleaseKey(val store: File, val storePassword: String, val alias: String, val keyPassword: String)
val releaseKey: ReleaseKey? = run {
    val fromProps = prop("qterm.release.storeFile")?.let { path ->
        val f = file(path.replaceFirst(Regex("^~(?=/|$)"), System.getProperty("user.home")))
        val sp = prop("qterm.release.storePassword")
        if (f.isFile && sp != null) {
            ReleaseKey(f, sp, prop("qterm.release.keyAlias") ?: "qterm", prop("qterm.release.keyPassword") ?: sp)
        } else {
            logger.warn("qterm.release.storeFile=$path: файла нет или не задан qterm.release.storePassword — release будет неподписанным")
            null
        }
    }
    fromProps ?: env("QTERM_KEYSTORE_B64")?.let { b64 ->
        val sp = env("QTERM_KEYSTORE_PASSWORD") ?: return@let null
        val f = layout.buildDirectory.file("qterm-release.jks").get().asFile
        f.parentFile.mkdirs()
        f.writeBytes(Base64.getMimeDecoder().decode(b64))
        ReleaseKey(f, sp, env("QTERM_KEY_ALIAS") ?: "qterm", env("QTERM_KEY_PASSWORD") ?: sp)
    }
}

android {
    namespace = "org.qterm.android"
    compileSdk = 36

    defaultConfig {
        applicationId = "org.qterm.android"
        minSdk = 26
        targetSdk = 36
        versionCode = 322
        versionName = "3.22.0"

        buildConfigField("String", "UPDATE_TOKEN", "\"$updateToken\"")
    }

    signingConfigs {
        if (releaseKey != null) {
            create("release") {
                storeFile = releaseKey.store
                storePassword = releaseKey.storePassword
                keyAlias = releaseKey.alias
                keyPassword = releaseKey.keyPassword
            }
        }
    }

    buildTypes {
        // OAuth Google: client id из gradle.properties (qterm.gdriveClientId / qterm.gdriveClientIdRelease)
        getByName("debug") {
            buildConfigField("String", "GDRIVE_CLIENT_ID", "\"$gdriveClientId\"")
            manifestPlaceholders["appAuthRedirectScheme"] = redirectScheme(gdriveClientId)
        }
        release {
            buildConfigField("String", "GDRIVE_CLIENT_ID", "\"$gdriveClientIdRelease\"")
            manifestPlaceholders["appAuthRedirectScheme"] = redirectScheme(gdriveClientIdRelease)
            if (releaseKey != null) signingConfig = signingConfigs.getByName("release")
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

    // «Ноды 3x-ui» / AWG: HTTP к панелям (свой TLS «не проверять», cookie-сессии) и QR-коды
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.google.zxing:core:3.5.3")

    // OAuth для Google Drive
    implementation("net.openid:appauth:0.11.1")

    // Локальный вейлт — этап Argon2id + биометрия
    implementation("com.lambdapioneer.argon2kt:argon2kt:1.6.0")
    implementation("androidx.biometric:biometric:1.1.0")
    // BiometricPrompt живёт на FragmentActivity (QDash: калитка и ключ подтверждения)
    implementation("androidx.fragment:fragment-ktx:1.8.6")

    // QDash: ed25519 (ключ устройства), X25519 + HKDF + ChaCha20-Poly1305 (конверт) — лёгкий API без JCA-провайдера
    implementation("org.bouncycastle:bcprov-jdk18on:1.80")

    testImplementation("junit:junit:4.13.2")
}
