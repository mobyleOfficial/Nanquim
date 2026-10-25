import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.mobyle.nanquim.engine"
    compileSdk =
        libs.versions.compileSdk
            .get()
            .toInt()
    ndkVersion = libs.versions.ndk.get()

    defaultConfig {
        minSdk =
            libs.versions.minSdk
                .get()
                .toInt()
        consumerProguardFiles("consumer-rules.pro")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
        externalNativeBuild {
            cmake {
                arguments += listOf("-DANDROID_STL=c++_static")
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = libs.versions.cmake.get()
        }
    }

    sourceSets["androidTest"].assets.srcDir("../goldens")
    sourceSets["androidTest"].assets.srcDir("../assets-src")

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    api(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)

    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(project(":formats"))
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}

val hostCmake = providers.gradleProperty("nanquim.hostCmake").orElse("cmake")
val hostCtest = providers.gradleProperty("nanquim.hostCtest").orElse("ctest")
val hostTestBuildDirectory = layout.buildDirectory.dir("hostTest")

val configureHostTests by tasks.registering(Exec::class) {
    group = "verification"
    description = "Configures the host C++ test build."
    inputs.dir("src/test/cpp")
    commandLine(
        hostCmake.get(),
        "-S",
        file("src/test/cpp").absolutePath,
        "-B",
        hostTestBuildDirectory.get().asFile.absolutePath,
        "-G",
        "Ninja",
        "-DCMAKE_BUILD_TYPE=Debug",
    )
}

val buildHostTests by tasks.registering(Exec::class) {
    group = "verification"
    description = "Builds the host C++ tests."
    dependsOn(configureHostTests)
    commandLine(hostCmake.get(), "--build", hostTestBuildDirectory.get().asFile.absolutePath)
}

tasks.register<Exec>("hostTest") {
    group = "verification"
    description = "Runs the C++ unit tests (GoogleTest) on the host."
    dependsOn(buildHostTests)
    workingDir(hostTestBuildDirectory)
    commandLine(hostCtest.get(), "--output-on-failure")
}
