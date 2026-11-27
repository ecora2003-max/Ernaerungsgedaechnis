pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        val verifiedRobolectric = providers.gradleProperty("robolectricSdkDir").orNull
            ?.let { file(it).resolve("maven") }?.takeIf { it.isDirectory }
        if (verifiedRobolectric != null) {
            exclusiveContent {
                forRepository { maven { url = uri(verifiedRobolectric) } }
                filter { includeVersion("org.robolectric", "nativeruntime-dist-compat", "1.0.16") }
            }
        }
        google()
        mavenCentral()
    }
}
rootProject.name="ErnaehrungsgedaechtnisDemo"
include(":app")
include(":nano-runtime")
