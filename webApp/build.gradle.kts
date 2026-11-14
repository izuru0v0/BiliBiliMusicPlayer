plugins { kotlin("multiplatform") }
kotlin {
    js {
        browser { commonWebpackConfig { outputFileName = "bilimusic.js" } }
        binaries.executable()
    }
    sourceSets { jsMain.dependencies { implementation(project(":shared")) } }
}
