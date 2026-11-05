plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.pdfeditable"
    // androidx.core 1.15 requires compiling against API 35. targetSdk stays at 34.
    compileSdk = 35

    defaultConfig {
        applicationId = "com.pdfeditable"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    signingConfigs {
        // Shared, non-secret debug key (standard "android" passwords) checked into the repo so
        // every CI build has the same signature and new APKs install over old ones.
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
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
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    testOptions {
        // PdfBox-Android touches android.util.Log and friends on the JVM; let the stubs no-op.
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    debugImplementation(libs.androidx.compose.ui.tooling)

    // Writes text and signature images into the PDF when saving.
    implementation(libs.pdfbox.android)

    testImplementation(libs.junit)
    // Reference PDFBox (pure JVM) reads the exported files back in unit tests.
    testImplementation(libs.apache.pdfbox)
}

// PdfBox-Android ships its font metrics as Android assets. On a device they are read through
// PDFBoxResourceLoader; in JVM unit tests it falls back to the classpath, so extract the
// assets from the AAR and put them there.
val pdfboxAar: Configuration by configurations.creating {
    isTransitive = false
}

dependencies {
    val pdfbox = libs.pdfbox.android.get()
    pdfboxAar("${pdfbox.module}:${pdfbox.versionConstraint.requiredVersion}@aar")
}

val pdfboxTestAssetsDir = layout.buildDirectory.dir("pdfbox-test-assets")

val extractPdfboxTestAssets by tasks.registering(Sync::class) {
    from({ zipTree(pdfboxAar.singleFile) }) {
        include("assets/**")
        eachFile { path = path.removePrefix("assets/") }
    }
    includeEmptyDirs = false
    into(pdfboxTestAssetsDir)
}

// Feed the files in as unit-test Java resources; AGP copies those onto the test classpath.
// (Appending to Test.classpath directly does not work: AGP overwrites it.)
android.sourceSets.getByName("test").resources.srcDir(pdfboxTestAssetsDir)

tasks.matching { it.name.endsWith("UnitTestJavaRes") }.configureEach {
    dependsOn(extractPdfboxTestAssets)
}
