plugins { id("com.android.application"); id("org.jetbrains.kotlin.plugin.compose") }
android {
 namespace = "dev.bilimusic.app"
 compileSdk = 36
 defaultConfig { applicationId = "dev.bilimusic.app"; minSdk = 26; targetSdk = 36; versionCode = 1; versionName = "0.1.0" }
 buildFeatures { compose = true }
 sourceSets.named("main") { kotlin.srcDir("../shared/src/biliMain/kotlin") }
 compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
dependencies {
 implementation(project(":shared"))
 implementation("com.google.zxing:core:3.5.0")
 implementation(platform("androidx.compose:compose-bom:2025.08.00"))
 implementation("androidx.activity:activity-compose:1.10.1")
 implementation("androidx.compose.material3:material3")
 implementation("androidx.compose.ui:ui")
 implementation("androidx.compose.foundation:foundation")
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
