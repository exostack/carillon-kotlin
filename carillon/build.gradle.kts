plugins {
  alias(libs.plugins.android.library)
  alias(libs.plugins.dokka)
  alias(libs.plugins.maven.publish)
}

// The coordinate, and the one the SDK reports at registration. Kept beside
// `Carillon.SDK_VERSION`, which is the value a customer reads in a support
// ticket; the two say the same thing and are bumped together.
group = "dev.carillon"

version = "0.2.0"

android {
  namespace = "dev.carillon.sdk"
  compileSdk = 36

  defaultConfig {
    minSdk = 24
  }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }

  testOptions {
    unitTests {
      isReturnDefaultValues = true
      isIncludeAndroidResources = true
    }
  }
}

dependencies {
  implementation("androidx.core:core:1.17.0")
  implementation("androidx.work:work-runtime-ktx:2.11.2")
  // `api` for the same reason coroutines is: `Carillon.didReceive(RemoteMessage)`
  // puts a Firebase type in the public surface, and every app that keeps its
  // own FirebaseMessagingService and forwards the two calls compiles against
  // it. A dependency the public surface requires is part of that surface.
  api(libs.firebase.messaging)
  // The language's own concurrency vocabulary. See the note in the catalogue.
  // `api` rather than `implementation`: `register()` is a public suspend
  // function, so a customer calling it needs a scope to call it from, and a
  // dependency the public surface requires is part of that surface.
  api(libs.coroutines.android)

  testImplementation("org.robolectric:robolectric:4.16.1")
  testImplementation("androidx.work:work-testing:2.11.2")
  testImplementation(libs.kotlin.test)
  testImplementation(libs.coroutines.test)
}

// The published POM carries the two `api` dependencies above, which is what
// makes an app that only declares this one still compile against
// `RemoteMessage` and against a coroutine scope. Publications are signed when
// a key is supplied, so `publishToMavenLocal` works without one; Maven Central
// rejects an unsigned deployment at validation.
mavenPublishing {
  publishToMavenCentral()
  if (providers.gradleProperty("signingInMemoryKey").isPresent) {
    signAllPublications()
  }

  pom {
    name = "Carillon"
    description = "Carillon push notification SDK for Android."
    inceptionYear = "2026"
    url = "https://github.com/exostack/carillon-kotlin"
    licenses {
      license {
        name = "MIT License"
        url = "https://opensource.org/license/mit"
        distribution = "repo"
      }
    }
    developers {
      developer {
        id = "exostack"
        name = "Exostack"
        email = "hello@carillon.dev"
        organization = "Exostack SARL"
        organizationUrl = "https://carillon.dev"
      }
    }
    scm {
      url = "https://github.com/exostack/carillon-kotlin"
      connection = "scm:git:https://github.com/exostack/carillon-kotlin.git"
      developerConnection = "scm:git:ssh://git@github.com/exostack/carillon-kotlin.git"
    }
  }
}
