plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.gravarty.htsp.tvinput"
    compileSdk = 37

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":htsp-core"))
    implementation(project(":htsp-provider"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.tvprovider)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.datasource)
    implementation(libs.androidx.media3.extractor)
    implementation(libs.androidx.media3.ui)
    // Software audio decoding like Kodi (ffmpeg), prebuilt by Jellyfin, for devices without e.g. an AC3 decoder
    implementation(libs.jellyfin.media3.ffmpeg.decoder)
    // Dominant logo colour for the home-screen tiles
    implementation(libs.androidx.palette)
    // Setup / settings pages taken from the old htsptvinput project (Leanback)
    implementation(libs.androidx.leanback)
    implementation(libs.androidx.leanback.preference)
    implementation(libs.androidx.preference)
    implementation(libs.kotlinx.coroutines.android)
}
