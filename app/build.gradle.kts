import com.google.firebase.crashlytics.buildtools.gradle.CrashlyticsExtension
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

val firebaseConfigured = file("google-services.json").exists()
if (firebaseConfigured) {
    apply(
        plugin =
            libs.plugins.google.services
                .get()
                .pluginId,
    )
    apply(
        plugin =
            libs.plugins.firebase.crashlytics
                .get()
                .pluginId,
    )
}

val keystoreProperties =
    Properties().apply {
        val keystorePropertiesFile = rootProject.file("keystore.properties")
        if (keystorePropertiesFile.exists()) keystorePropertiesFile.inputStream().use(::load)
    }
val releaseKeystore = keystoreProperties.getProperty("storeFile")?.let(rootProject::file)?.takeIf { it.exists() }

// AdMob settings live in local.properties (ignored by git; the repository is public):
//   admob.appId=ca-app-pub-XXXXXXXXXXXXXXXX~XXXXXXXXXX            the app, from the AdMob console
//   admob.galleryBannerId=ca-app-pub-XXXXXXXXXXXXXXXX/XXXXXXXXXX   the gallery's banner unit
//   admob.testDeviceIds=<hashed id from Logcat>,<another>          debug only: devices the consent form treats as test devices
//   admob.debugGeography=EEA                                       debug only: EEA, REGULATED_US_STATE or OTHER
// Debug builds always use Google's test IDs; a release build without both IDs shows no ads at all. With
// admob.debugGeography set, debug builds forget the consent on every launch so the form can be tested again and again.
val localProperties =
    Properties().apply {
        val localPropertiesFile = rootProject.file("local.properties")
        if (localPropertiesFile.exists()) localPropertiesFile.inputStream().use(::load)
    }

fun localProperty(name: String): String? = localProperties.getProperty(name)?.trim()?.takeIf { it.isNotEmpty() }

val admobAppId: String? = localProperty("admob.appId")
val galleryBannerId: String? = localProperty("admob.galleryBannerId")
val adsTestDeviceIds: String = localProperty("admob.testDeviceIds").orEmpty()
val adsDebugGeography: String = localProperty("admob.debugGeography").orEmpty().uppercase()

// A malformed id still builds and ships, but every ad request fails and the gallery shows an empty banner slot.
require(admobAppId == null || admobAppId.matches(Regex("""ca-app-pub-\d+~\d+"""))) {
    "admob.appId must look like ca-app-pub-0000000000000000~0000000000, not $admobAppId"
}
require(galleryBannerId == null || galleryBannerId.matches(Regex("""ca-app-pub-\d+/\d+"""))) {
    "admob.galleryBannerId must look like ca-app-pub-0000000000000000/0000000000, not $galleryBannerId"
}
require(adsDebugGeography in setOf("", "EEA", "REGULATED_US_STATE", "OTHER")) {
    "admob.debugGeography must be EEA, REGULATED_US_STATE or OTHER, not $adsDebugGeography"
}
val testAdmobAppId = "ca-app-pub-3940256099942544~3347511713"
val testBannerId = "ca-app-pub-3940256099942544/9214589741"

android {
    namespace = "com.mobyle.nanquim"
    compileSdk =
        libs.versions.compileSdk
            .get()
            .toInt()

    defaultConfig {
        applicationId = "com.mobyle.nanquim"
        minSdk =
            libs.versions.minSdk
                .get()
                .toInt()
        targetSdk =
            libs.versions.targetSdk
                .get()
                .toInt()
        // Bumped by one for every Play upload. The deploy workflows pass -PversionCode with the next number after the
        // latest Firebase App Distribution release (never below this one).
        versionCode = providers.gradleProperty("versionCode").orNull?.toInt() ?: 4
        versionName = "1.2.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // Consent testing is for debug builds only; see the AdMob settings above.
        buildConfigField("String", "ADS_TEST_DEVICE_IDS", "\"\"")
        buildConfigField("String", "ADS_DEBUG_GEOGRAPHY", "\"\"")
    }

    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = releaseKeystore
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            buildConfigField("boolean", "DEVELOPER_TOOLS", "true")
            manifestPlaceholders["admobAppId"] = testAdmobAppId
            buildConfigField("boolean", "ADS_ENABLED", "true")
            buildConfigField("String", "ADS_GALLERY_BANNER_ID", "\"$testBannerId\"")
            buildConfigField("String", "ADS_TEST_DEVICE_IDS", "\"$adsTestDeviceIds\"")
            buildConfigField("String", "ADS_DEBUG_GEOGRAPHY", "\"$adsDebugGeography\"")
        }
        release {
            signingConfig = signingConfigs.findByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            buildConfigField("boolean", "DEVELOPER_TOOLS", "false")
            val adsConfigured = admobAppId != null && galleryBannerId != null
            manifestPlaceholders["admobAppId"] = if (adsConfigured) admobAppId!! else testAdmobAppId
            buildConfigField("boolean", "ADS_ENABLED", adsConfigured.toString())
            buildConfigField("String", "ADS_GALLERY_BANNER_ID", "\"${if (adsConfigured) galleryBannerId else ""}\"")
            if (firebaseConfigured) {
                configure<CrashlyticsExtension> { nativeSymbolUploadEnabled = true }
            }
        }
        create("benchmark") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
            isDebuggable = false
            buildConfigField("boolean", "DEVELOPER_TOOLS", "true")
            buildConfigField("boolean", "ADS_ENABLED", "false")
        }
    }

    flavorDimensions += "environment"
    productFlavors {
        create("dev") {
            dimension = "environment"
            applicationIdSuffix = ".dev"
            versionNameSuffix = "-dev"
        }
        create("prod") {
            dimension = "environment"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    sourceSets["main"].assets.srcDir("../assets-src")
    sourceSets["androidTest"].assets.srcDir("../goldens/palettes")
    sourceSets["main"].assets.srcDir(layout.buildDirectory.dir("generated/licenseAssets"))
}

val copyLicenseAssets by tasks.registering(Copy::class) {
    from(rootProject.file("CREDITS.md"))
    into(layout.buildDirectory.dir("generated/licenseAssets/about"))
}

tasks.named("preBuild") { dependsOn(copyLicenseAssets) }

androidComponents {
    beforeVariants(selector().withFlavor("environment" to "dev").withBuildType("benchmark")) { variant ->
        variant.enable = false
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":document"))
    implementation(project(":formats"))
    implementation(project(":engine"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.input.motionprediction)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.glance.appwidget)
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.analytics)
    implementation(libs.firebase.crashlytics.ndk)
    implementation(libs.play.review.ktx)
    implementation(libs.play.services.ads)
    implementation(libs.user.messaging.platform)
    implementation(platform(libs.koin.bom))
    implementation(libs.koin.android)
    implementation(libs.koin.core)
    implementation(libs.koin.compose.viewmodel)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.navigation3.runtime)
    implementation(libs.androidx.navigation3.ui)
    implementation(libs.androidx.lifecycle.viewmodel.navigation3)
    ksp(libs.androidx.room.compiler)
    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit.jupiter)
    testImplementation(platform(libs.koin.bom))
    testImplementation(libs.koin.test)
    testRuntimeOnly(libs.junit.platform.launcher)

    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
