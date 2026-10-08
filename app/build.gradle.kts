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
        versionCode = 2
        versionName = "1.1"

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

dependencies {implementation("org.maplibre.gl:android-sdk:13.4.1")
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
            "bootstrap/keio-info.json", "bootstrap/keio-bus-info.json",
            "bootstrap/odakyu.bundle", "bootstrap/odakyu-info.json")
        for (entry in config["files"] as List<*>) {
            val spec = entry as Map<*, *>
            val asset = spec["asset"] as? String ?: continue
            required.add(asset)
            check(root.resolve(asset).length() == (spec["size"] as Number).toLong()) {
                "Offline asset missing/wrong size: $asset. Follow docs/DATA_DISTRIBUTION.md."
            }
        }
        for (asset in required) check(root.resolve(asset).isFile) {
            "Offline asset missing: $asset. Follow docs/DATA_DISTRIBUTION.md."
        }
    }
}
tasks.named("preBuild") { dependsOn(verifyOfflineAssets) }
