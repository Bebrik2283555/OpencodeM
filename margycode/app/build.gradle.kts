plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.opencode"
    compileSdk = 35
    buildToolsVersion = "35.0.0"
    ndkVersion = "28.2.13676358"
    externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt"); version = "3.22.1" } }

    defaultConfig {
        applicationId = "com.opencode"
        minSdk = 28
        targetSdk = 35
        versionCode = 31
        versionName = "1.0"
        ndk { abiFilters += "arm64-v8a" }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // signed afterwards by build.sh with keys/OpencodeM.jks
            signingConfig = null
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        // opencode is an executable in jniLibs: it has to be unpacked onto disk to be run
        jniLibs.useLegacyPackaging = true
        // stripping would cut off the program Bun keeps appended to its own file
        jniLibs.keepDebugSymbols += setOf("**/libopencode.so", "**/libldmusl.so", "**/libclaude.so", "**/liblinux.so", "**/libproot.so", "**/libprootloader.so", "**/libllamaserver.so", "**/libllamaserveri8mm.so")
        jniLibs.keepDebugSymbols += setOf("**/libmcp-runtime.so", "**/libshell-runtime.so", "**/liblinuxchroot.so")
        resources.excludes += setOf("META-INF/*.kotlin_module", "META-INF/AL2.0", "META-INF/LGPL2.1")
    }
    sourceSets.getByName("main").jniLibs.setSrcDirs(listOf(layout.buildDirectory.dir("runtime-libs")))
}

val runtimeWrappers by tasks.registering(Copy::class) {
    from("src/main/jniLibs") { exclude("**/liblinux.so") }
    from(rootProject.file("engine/mcp-runtime.sh")) { into("arm64-v8a"); rename { "libmcp-runtime.so" } }
    from(rootProject.file("engine/shell-runtime.sh")) { into("arm64-v8a"); rename { "libshell-runtime.so" } }
    from(rootProject.file("engine/linux.sh")) { into("arm64-v8a"); rename { "liblinux.so" } }
    from(rootProject.file("engine/chroot.sh")) { into("arm64-v8a"); rename { "liblinuxchroot.so" } }
    into(layout.buildDirectory.dir("runtime-libs"))
}
tasks.named("preBuild") { dependsOn(runtimeWrappers) }

dependencies {
    val compose = platform("androidx.compose:compose-bom:2025.02.00")
    implementation(compose)
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.activity:activity-compose:1.10.0")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
