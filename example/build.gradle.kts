plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.google.services) apply false
}

// google-services.json is deliberately not committed — it names a personal
// Firebase project, and the bench must not assume anyone's. The plugin refuses
// to build without the file, so it applies only when one is present: a fresh
// clone builds and tests, and push delivery lights up the moment the file is
// dropped in (Firebase console > add an Android app with this package).
if (file("google-services.json").exists()) {
  apply(plugin = libs.plugins.google.services.get().pluginId)
}

android {
  namespace = "dev.carillon.example"
  compileSdk = 36

  defaultConfig {
    applicationId = "dev.carillon.example"
    minSdk = 24
    targetSdk = 36
    versionCode = 1
    versionName = "0.1.0"
  }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
}

dependencies {
  implementation(project(":carillon"))
}
