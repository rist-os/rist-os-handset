import com.google.protobuf.gradle.id
import java.io.File
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.protobuf") version "0.9.4"
}

// rist.backendUrl / rist.pushUrl come from local.properties or env, never source; must be https.
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun deployProp(key: String, env: String): String =
    localProps.getProperty(key) ?: System.getenv(env) ?: ""

android {
    namespace = "watch.rist.assistant"
    compileSdk = 35
    ndkVersion = "26.3.11579264"
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    defaultConfig {
        applicationId = "watch.rist.assistant"
        minSdk = 34
        targetSdk = 35
        versionCode = 471
        versionName = "0.2.0"

        ndk { abiFilters += "arm64-v8a" }
        externalNativeBuild { cmake { } }
    }

    buildFeatures {
    }

    buildTypes {
        debug {
            applicationIdSuffix = ""
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    testOptions {
        unitTests {
            isReturnDefaultValues = true
            isIncludeAndroidResources = true
        }
    }
}

// OtaEngineCallback.kt subclasses @SystemApi UpdateEngineCallback, absent from the public android.jar; Soong-only.
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    exclude("**/OtaEngineCallback.kt")
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.activity:activity-ktx:1.9.2")
    implementation("androidx.localbroadcastmanager:localbroadcastmanager:1.1.0")


    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-session:1.4.1")
    implementation("androidx.media3:media3-common:1.4.1")

    implementation("com.google.protobuf:protobuf-javalite:3.25.5")

    testImplementation("junit:junit:4.13.2")

    // android.jar ships org.json as stubs; tests need the real one.
    testImplementation("org.json:json:20240303")

    testImplementation("org.robolectric:robolectric:4.13")
    testImplementation("androidx.test:core:1.6.1")

    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}

// Written for release too; rist.releaseVariant decides whether it carries endpoints.
val deployBackendUrl = deployProp("rist.backendUrl", "RIST_BACKEND_URL")
val deployPushUrl = deployProp("rist.pushUrl", "RIST_PUSH_URL")
val releaseVariantDecl =
    (localProps.getProperty("rist.releaseVariant") ?: System.getenv("RIST_RELEASE_VARIANT") ?: "")
        .trim().lowercase()

val deployAssetsDebugDir = layout.buildDirectory.dir("generated/deployAssets/debug")
val deployAssetsReleaseDir = layout.buildDirectory.dir("generated/deployAssets/release")
android.sourceSets["debug"].assets.srcDir(deployAssetsDebugDir)
android.sourceSets["release"].assets.srcDir(deployAssetsReleaseDir)

fun writeDeployAsset(dir: File, backend: String, push: String) {
    dir.mkdirs()
    File(dir, "deploy.properties").writeText("backendUrl=$backend\npushUrl=$push\n")
}

val genDeployAssetsDebug = tasks.register("genDeployAssetsDebug") {
    inputs.property("backend", deployBackendUrl)
    inputs.property("push", deployPushUrl)
    outputs.dir(deployAssetsDebugDir)
    doLast {
        writeDeployAsset(deployAssetsDebugDir.get().asFile, deployBackendUrl, deployPushUrl)
    }
}

val genDeployAssetsRelease = tasks.register("genDeployAssetsRelease") {
    inputs.property("backend", deployBackendUrl)
    inputs.property("push", deployPushUrl)
    inputs.property("variant", releaseVariantDecl)
    outputs.dir(deployAssetsReleaseDir)
    doLast {
        val configured = deployBackendUrl.isNotBlank() || deployPushUrl.isNotBlank()
        when (releaseVariantDecl) {
            "provisioned" -> {
                if (!configured) throw GradleException(
                    "rist.releaseVariant=provisioned, but no rist.backendUrl/rist.pushUrl is set.\n" +
                    "The provisioned variant compiles an endpoint in; this build\n" +
                    "would produce an APK with none, which is the public build under another name.\n" +
                    "Set the endpoints in local.properties, or declare rist.releaseVariant=public."
                )
                writeDeployAsset(deployAssetsReleaseDir.get().asFile, deployBackendUrl, deployPushUrl)
            }
            "public" -> {
                writeDeployAsset(deployAssetsReleaseDir.get().asFile, "", "")
            }
            "" -> {
                if (configured) throw GradleException(
                    "A release APK built here would carry assets/deploy.properties with the endpoints\n" +
                    "from local.properties baked into it, and nothing downstream can see inside an APK.\n" +
                    "Say which release you meant, in local.properties or the environment:\n" +
                    "  rist.releaseVariant=public        -> asset written EMPTY (bring-your-own-backend)\n" +
                    "  rist.releaseVariant=provisioned   -> endpoints compiled in\n" +
                    "Debug builds are unaffected and always carry the configured endpoints."
                )
                writeDeployAsset(deployAssetsReleaseDir.get().asFile, "", "")
            }
            else -> throw GradleException(
                "rist.releaseVariant='$releaseVariantDecl' is not a variant. Use 'public' or 'provisioned'."
            )
        }
    }
}

// Per-variant on purpose: the release check must not run for debug builds.
tasks.matching { it.name == "preDebugBuild" }.configureEach { dependsOn(genDeployAssetsDebug) }
tasks.matching { it.name == "preReleaseBuild" }.configureEach { dependsOn(genDeployAssetsRelease) }
tasks.matching { it.name == "mergeDebugAssets" }.configureEach { dependsOn(genDeployAssetsDebug) }
tasks.matching { it.name == "mergeReleaseAssets" }.configureEach { dependsOn(genDeployAssetsRelease) }

protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:3.25.5"
    }
    generateProtoTasks {
        all().forEach { task ->
            task.builtins {
                id("java") {
                    option("lite")
                }
            }
        }
    }
}
