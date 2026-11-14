plugins { kotlin("jvm"); application }
java {
 sourceCompatibility = JavaVersion.VERSION_17
 targetCompatibility = JavaVersion.VERSION_17
}
tasks.withType<JavaCompile>().configureEach { options.release.set(17) }
kotlin {
 compilerOptions { freeCompilerArgs.add("-Xjdk-release=17"); jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}
dependencies {
 implementation(project(":shared"))
 implementation("org.json:json:20240303")
 implementation("com.google.zxing:core:3.5.0")
}
application {
 mainClass.set("dev.bilimusic.desktop.MainKt")
 applicationDefaultJvmArgs = listOf("-Djava.net.useSystemProxies=true")
}
kotlin.sourceSets.named("main") { kotlin.srcDir("../shared/src/biliMain/kotlin") }
