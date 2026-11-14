pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement { repositories { google(); mavenCentral() } }
rootProject.name = "BiliMusic"
include(":shared", ":androidApp", ":desktopApp")
include(":webApp")
