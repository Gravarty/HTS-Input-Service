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
    // Setup / settings pages taken from the old htsptvinput project (Leanback)
    implementation(libs.androidx.leanback)
    implementation(libs.androidx.leanback.preference)
    implementation(libs.androidx.preference)
    implementation(libs.kotlinx.coroutines.android)
}
