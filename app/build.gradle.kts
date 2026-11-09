plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}
android {
    namespace="de.ernaehrung.demo"
    compileSdk=36
    defaultConfig {
        applicationId="de.ernaehrung.demo"
        minSdk=26
        targetSdk=35
        versionCode=10
        versionName="0.10.0-fitness-duration"
        manifestPlaceholders["appLabel"] = "Ernährung · Demo"
        testInstrumentationRunner="de.ernaehrung.demo.SyntheticPersistenceInstrumentation"
    }
    compileOptions { sourceCompatibility=JavaVersion.VERSION_17;targetCompatibility=JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget="17" }
    buildFeatures { compose=true }
    buildTypes {
        getByName("debug") {
            // Explicit isolated hardware-test build; never replaces the user's
            // installed sandbox or its signing identity/data. Default unchanged.
            if (providers.gradleProperty("egWatchSpike").orNull == "true") {
                applicationIdSuffix = ".watchspike"
                manifestPlaceholders["appLabel"] = "EG · Watch-Test"
            }
        }
    }
    testOptions { unitTests.isIncludeAndroidResources=true }
    packaging { resources.excludes+="/META-INF/{AL2.0,LGPL2.1}" }
    sourceSets["test"].resources.srcDir("schemas")
    // One synthetic migration fixture runs against both host and platform SQLite.
    // Published schema assets are packaged only in the instrumentation APK.
    sourceSets["test"].java.srcDir("src/sharedTest/java")
    sourceSets["androidTest"].java.srcDir("src/sharedTest/java")
    sourceSets["androidTest"].assets.srcDir("schemas")
}
ksp { arg("room.schemaLocation", "$projectDir/schemas") }
dependencies {
    implementation(project(":nano-runtime"))
    implementation("androidx.health.connect:connect-client:1.1.0")
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
    implementation("com.google.zxing:core:3.5.3")
    // Bundled Latin OCR model: photo processing works offline; no cloud OCR/API key.
    implementation("com.google.mlkit:text-recognition:16.0.1")
    implementation("androidx.exifinterface:exifinterface:1.3.7")
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
    androidTestImplementation(bom)
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.3.0")
}
// ML Kit beta4 needs the newer stdlib at RUNTIME. The SDK lives behind the
// Java-only nano-runtime API so the existing Kotlin/Compose/KSP 2.0 compiler
// retains its matching compile ABI, without skipping metadata checks or
// downgrading the SDK runtime. Verified in an isolated compile/package probe;
// the complete application regression must also pass with the new runtime.
configurations.matching { it.name.endsWith("CompileClasspath") }.configureEach {
    resolutionStrategy.force("org.jetbrains.kotlin:kotlin-stdlib:2.0.21",
        "org.jetbrains.kotlin:kotlin-stdlib-common:2.0.21",
        "org.jetbrains.kotlin:kotlin-stdlib-jdk7:2.0.21", "org.jetbrains.kotlin:kotlin-stdlib-jdk8:2.0.21")
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
