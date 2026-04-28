import org.gradle.testing.jacoco.tasks.JacocoReport
import org.gradle.testing.jacoco.plugins.JacocoTaskExtension

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.compose.compiler)
  jacoco
}

android {
    namespace = "org.bayton.tools.managedconfig"
    compileSdk = 37
    defaultConfig {
        applicationId = "org.bayton.tools.managedconfig"
        minSdk = 28
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        debug {
            enableUnitTestCoverage = true
        }
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
      compose = true
      aidl = false
      buildConfig = false
      shaders = false
    }

    packaging {
      resources {
        excludes += "/META-INF/{AL2.0,LGPL2.1}"
      }
    }

    testOptions {
      unitTests {
        isIncludeAndroidResources = true
      }
    }
}

kotlin {
    jvmToolchain(17)
}

jacoco {
  toolVersion = "0.8.13"
}

val debugCoverageExclusions =
  listOf(
    "**/R.class",
    "**/R$*.class",
    "**/BuildConfig.*",
    "**/Manifest*.*",
    "**/*Test*.*",
    "**/*\$Preview*.*",
    "**/*ComposableSingletons*.*",
  )

tasks.register<JacocoReport>("jacocoDebugUnitTestReport") {
  dependsOn("testDebugUnitTest")

  reports {
    xml.required.set(true)
    html.required.set(true)
    csv.required.set(false)
  }

  val kotlinTree = fileTree("${layout.buildDirectory.get()}/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes") {
    exclude(debugCoverageExclusions)
  }
  val javaTree = fileTree("${layout.buildDirectory.get()}/intermediates/javac/debug/compileDebugJavaWithJavac/classes") {
    exclude(debugCoverageExclusions)
  }

  classDirectories.setFrom(files(kotlinTree, javaTree))
  sourceDirectories.setFrom(
    files(
      "${project.projectDir}/src/main/java",
      "${project.projectDir}/src/main/kotlin",
    ),
  )
  executionData.setFrom(
    files(
      "${layout.buildDirectory.get()}/outputs/unit_test_code_coverage/debugUnitTest/testDebugUnitTest.exec",
      "${layout.buildDirectory.get()}/jacoco/testDebugUnitTest.exec",
    ),
  )
}

tasks.withType<Test>().configureEach {
  extensions.configure(JacocoTaskExtension::class.java) {
    isIncludeNoLocationClasses = true
    excludes = listOf("jdk.internal.*")
  }
}

dependencies {
  val composeBom = platform(libs.androidx.compose.bom)
  implementation(composeBom)
  androidTestImplementation(composeBom)

  // Core Android dependencies
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.core.splashscreen)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.activity.compose)
  implementation(libs.androidx.enterprise.feedback)

  // Arch Components
  // Compose
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.compose.material3)
  // Tooling
  debugImplementation(libs.androidx.compose.ui.tooling)
  // Instrumented tests
  androidTestImplementation(libs.androidx.compose.ui.test.junit4)
  debugImplementation(libs.androidx.compose.ui.test.manifest)

  // Local tests: jUnit, coroutines, Android runner
  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)
  testImplementation(libs.robolectric)

  // Instrumented tests: jUnit rules and runners
  androidTestImplementation(libs.androidx.test.core)
  androidTestImplementation(libs.androidx.test.ext.junit)
  androidTestImplementation(libs.androidx.test.runner)
  androidTestImplementation(libs.androidx.test.espresso.core)
  androidTestImplementation(libs.androidx.test.uiautomator)

}
