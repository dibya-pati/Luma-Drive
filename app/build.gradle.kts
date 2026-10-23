plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android {
    namespace = "com.castla.projection"
    compileSdk = 35
    defaultConfig { applicationId = "com.castla.projection"; minSdk = 28; targetSdk = 35; versionCode = 21; versionName = "0.4.5" }
    buildFeatures { buildConfig = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    val signingStore = System.getenv("PROJECTION_SIGNING_STORE")
    if (signingStore != null) signingConfigs.create("personalRelease") {
        storeFile = file(signingStore)
        storeType = "PKCS12"
        storePassword = System.getenv("PROJECTION_SIGNING_PASSWORD") ?: error("Signing password is required")
        keyAlias = "projection"
        keyPassword = storePassword
    }
    buildTypes.getByName("release") {
        isMinifyEnabled = true
        isShrinkResources = true
        proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        if (signingStore != null) signingConfig = signingConfigs.getByName("personalRelease")
    }
}
dependencies {
    implementation("org.nanohttpd:nanohttpd:2.3.1")
    implementation("org.nanohttpd:nanohttpd-websocket:2.3.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
    implementation("io.getstream:stream-webrtc-android:1.3.9")
    testImplementation("junit:junit:4.13.2")
}
