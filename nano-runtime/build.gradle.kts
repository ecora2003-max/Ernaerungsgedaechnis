plugins { id("com.android.library") }
android {
    namespace = "de.ernaehrung.nano"
    compileSdk = 36
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
dependencies {
    // Official public Java API; SDK Kotlin metadata is not exported to the app.
    implementation("com.google.mlkit:genai-prompt:1.0.0-beta4")
}
