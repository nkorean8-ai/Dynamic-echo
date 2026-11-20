plugins {
  id("com.android.library")
  id("com.google.devtools.ksp")
  id("com.google.dagger.hilt.android")
  alias(libs.plugins.kotlin.serialization)
  alias(libs.plugins.compose.compiler)
}

android {
  namespace = "echo.music.island"
  compileSdk = 37

  defaultConfig {
    minSdk = 26
  }

  buildFeatures { compose = true }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
  }
}

kotlin {
  jvmToolchain(21)
}

dependencies {
  implementation(libs.activity)
  implementation(libs.appcompat)
  implementation(libs.compose.runtime)
  implementation(libs.compose.foundation)
  implementation(libs.compose.ui)
  implementation(libs.compose.animation)
  implementation(libs.material3)
  implementation(libs.lifecycle.process)
  implementation(libs.lifecycle.runtime)
  implementation(libs.viewmodel)
  implementation(libs.hilt)
  ksp(libs.hilt.compiler)
  implementation(libs.timber)
  implementation(libs.coil)
  implementation(libs.coil.network.okhttp)
}
