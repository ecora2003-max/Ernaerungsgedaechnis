plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}
android {
    namespace="de.ernaehrung.demo"
    compileSdk=35
    defaultConfig {
        applicationId="de.ernaehrung.demo"
        minSdk=26
        targetSdk=35
        versionCode=1
        versionName="0.1.0-demo"
    }
    compileOptions { sourceCompatibility=JavaVersion.VERSION_17;targetCompatibility=JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget="17" }
    buildFeatures { compose=true }
    testOptions { unitTests.isIncludeAndroidResources=true }
    packaging { resources.excludes+="/META-INF/{AL2.0,LGPL2.1}" }
}
dependencies {
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
}
tasks.withType<Test>().configureEach {
    maxHeapSize="2g"
    // Optional build-host proxy, never embedded into the application.
    listOf("https.proxyHost","https.proxyPort","http.proxyHost","http.proxyPort","javax.net.ssl.trustStore").forEach { key->
        System.getProperty(key)?.let {systemProperty(key,it)}
    }
    systemProperty("robolectric.dependency.repo.url","https://repo.maven.apache.org/maven2")
}
