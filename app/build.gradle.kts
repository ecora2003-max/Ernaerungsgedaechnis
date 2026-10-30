plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}
android {
    namespace="de.ernaehrung.demo"
    compileSdk=35
    defaultConfig {
        applicationId="de.ernaehrung.demo"
        minSdk=26
        targetSdk=35
        versionCode=6
        versionName="0.6.0-execution"
    }
    compileOptions { sourceCompatibility=JavaVersion.VERSION_17;targetCompatibility=JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget="17" }
    buildFeatures { compose=true }
    testOptions { unitTests.isIncludeAndroidResources=true }
    packaging { resources.excludes+="/META-INF/{AL2.0,LGPL2.1}" }
    sourceSets["test"].resources.srcDir("schemas")
}
ksp { arg("room.schemaLocation", "$projectDir/schemas") }
dependencies {
    implementation("androidx.room:room-runtime:2.7.2")
    implementation("androidx.room:room-ktx:2.7.2")
    ksp("androidx.room:room-compiler:2.7.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    val bom=platform("androidx.compose:compose-bom:2024.12.01")
    implementation(bom)
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.navigation:navigation-compose:2.8.5")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    testImplementation(bom)
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.compose.ui:ui-test-junit4")
    testImplementation("androidx.test:core:1.6.1")
    testImplementation("androidx.test.ext:junit:1.2.1")
    testImplementation("androidx.room:room-testing:2.7.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}
tasks.withType<Test>().configureEach {
    maxHeapSize="2g"
    // Optional writable JVM home for sandboxed build hosts; normal Android Studio/CI is unchanged.
    providers.gradleProperty("testHome").orNull?.let { path ->
        val directory = file(path)
        check(directory.isDirectory || directory.mkdirs()) { "Cannot create testHome: $directory" }
        systemProperty("user.home", directory.absolutePath)
    }
    // Optional build-host proxy, never embedded into the application.
    listOf("https.proxyHost","https.proxyPort","http.proxyHost","http.proxyPort","javax.net.ssl.trustStore").forEach { key->
        System.getProperty(key)?.let {systemProperty(key,it)}
    }
    systemProperty("robolectric.dependency.repo.url","https://repo.maven.apache.org/maven2")
}
