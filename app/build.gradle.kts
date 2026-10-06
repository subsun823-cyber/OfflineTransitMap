import groovy.json.JsonSlurper

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.example.offlinetransitmap"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.example.offlinetransitmap"
        minSdk = 26
        targetSdk = 37
        versionCode = 3
        versionName = "1.2"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation("androidx.work:work-runtime-ktx:2.12.0")
    implementation("org.maplibre.gl:android-sdk:13.4.1")
    implementation("androidx.compose.material:material-icons-core")
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}

// Large assets are deliberately kept outside Git. Fail before producing an APK
// that would get stuck at first-run preparation on a clean checkout.
val verifyOfflineAssets = tasks.register("verifyOfflineAssets") {
    val assetRoot = layout.projectDirectory.dir("src/main/assets")
    inputs.dir(assetRoot.dir("bootstrap"))
    doLast {
        val root = assetRoot.asFile
        val config = JsonSlurper().parse(root.resolve("bootstrap/data-files.json")) as Map<*, *>
        val required = mutableListOf("bootstrap/keio.bundle", "bootstrap/keio-bus.bundle",
            "bootstrap/keio-info.json", "bootstrap/keio-bus-info.json")
        val problems = mutableListOf<String>()
        for (entry in config["files"] as List<*>) {
            val spec = entry as Map<*, *>
            val asset = spec["asset"] as? String ?: continue
            val file = root.resolve(asset)
            val expected = (spec["size"] as Number).toLong()
            if (!file.isFile) problems.add("$asset: missing (expected $expected bytes)")
            else if (file.length() != expected) problems.add("$asset: ${file.length()} bytes (expected $expected)")
        }
        for (asset in required) if (!root.resolve(asset).isFile) problems.add("$asset: missing")
        check(problems.isEmpty()) {
            "Offline build data is not ready:\n" + problems.joinToString("\n") +
                "\nThese large files are not stored in Git. On Windows, run tools/prepare_offline_data.cmd" +
                " (or the data-setup ZIP), select the original DB/map, then rebuild." +
                "\nSee docs/DATA_DISTRIBUTION.md. This prepares the build PC once; phones need no manual file copy."
        }
    }
}
tasks.named("preBuild") { dependsOn(verifyOfflineAssets) }
