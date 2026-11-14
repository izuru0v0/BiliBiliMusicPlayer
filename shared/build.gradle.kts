plugins { kotlin("multiplatform"); id("com.android.kotlin.multiplatform.library") }
kotlin {
 androidLibrary { namespace = "dev.bilimusic.shared"; compileSdk = 36; minSdk = 26 }
 jvm()
 js { browser() }
 if (System.getProperty("os.name").startsWith("Mac")) {
  listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
   target.binaries.framework {
    baseName = "BiliMusicCore"
    isStatic = true
   }
  }
 }
 sourceSets { commonTest.dependencies { implementation(kotlin("test")) } }
}
