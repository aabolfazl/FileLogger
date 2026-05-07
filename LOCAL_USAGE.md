# Using FileLogger locally in another Android app

This repo is **not published to Maven (local or remote) or JitPack**. The two ways to consume it from another local Android project on the same machine are below. Pick one.

---

## Option A — Gradle composite build (recommended for development)

Best when you want to iterate on FileLogger and the consumer app together: changes in this repo show up in the consumer immediately, no rebuild step in the middle.

In the **consumer app's** `settings.gradle` (or `settings.gradle.kts`), add `includeBuild`:

```gradle
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}
includeBuild('/home/abolfazl/code/FileLogger') {
    dependencySubstitution {
        substitute module('com.github.aabolfazl:filelogger') using project(':filelogger')
        substitute module('com.github.aabolfazl:filelogger-okhttp') using project(':filelogger-okhttp')
    }
}
rootProject.name = "your-consumer-app"
include ':app'
```

In the consumer's `app/build.gradle`:

```gradle
dependencies {
    implementation 'com.github.aabolfazl:filelogger:2.0.0'
    // optional:
    implementation 'com.github.aabolfazl:filelogger-okhttp:2.0.0'
}
```

Sync the consumer project. Gradle resolves the substitutions to the local sources. Edit FileLogger code → rebuild the consumer → changes flow through.

**Caveats:**
- Both projects must use compatible AGP/Kotlin versions. FileLogger is on **AGP 9.2.0 / Kotlin 2.2.10 / Gradle 9.4.1**.
- Consumer's `compileSdk` ≥ 36 (FileLogger's compile SDK).
- Consumer's `minSdk` ≥ 26 (FileLogger uses `java.time` natively — no `coreLibraryDesugaring` needed).
- Consumer's `JavaVersion.targetCompatibility` ≥ 11 (FileLogger emits JVM 11 bytecode).

---

## Option B — Drop the AAR into the consumer's `libs/` folder

Best when you want a frozen snapshot of FileLogger and don't plan to edit it.

### Build the AAR

In this repo:

```bash
./gradlew :filelogger:assembleRelease
./gradlew :filelogger-okhttp:assembleRelease   # optional
```

The artifacts land at:

- `filelogger/build/outputs/aar/filelogger-release.aar`
- `filelogger-okhttp/build/outputs/aar/filelogger-okhttp-release.aar`

### Wire into the consumer

Copy the AAR(s) into `app/libs/` of the consumer project. Then in the consumer's `app/build.gradle`:

```gradle
android {
    // ...
    compileSdk 36
    defaultConfig { minSdk 26 }
    compileOptions {
        sourceCompatibility JavaVersion.VERSION_11
        targetCompatibility JavaVersion.VERSION_11
    }
}

dependencies {
    implementation files('libs/filelogger-release.aar')
    implementation files('libs/filelogger-okhttp-release.aar')   // optional

    // FileLogger's runtime dependencies must be declared here too —
    // AAR consumers don't get transitive dependencies via flat-file deps.
    implementation 'org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1'
    implementation 'androidx.startup:startup-runtime:1.1.1'
    implementation 'androidx.lifecycle:lifecycle-process:2.8.7'
    implementation 'androidx.core:core-ktx:1.13.1'

    // Required if you use :filelogger-okhttp:
    implementation 'com.squareup.okhttp3:okhttp:4.12.0'
}
```

**Caveats:**
- The consumer must declare every runtime transitive dep manually (the AAR-via-`files()` route doesn't carry POM metadata).
- Updates to FileLogger require rebuilding the AAR and re-copying.
- `consumer-rules.pro` from the AAR is honored — your release build's R8/ProGuard will still see the library's keep rules.

---

## Verifying the wire-up

In the consumer's `Application.onCreate` (or anywhere on the main thread):

```kotlin
val config = fileLogger(applicationContext.filesDir.absolutePath) {
    defaultTag = "Consumer"
    minLevel = LogLevel.Debug
}
FileLogger.init(applicationContext, config)
FileLogger.i(message = "FileLogger wired up")
```

Run the app, then on the device:

```bash
adb shell run-as <your.consumer.package> ls files/fileLogs/
adb shell run-as <your.consumer.package> cat files/fileLogs/$(adb shell run-as <your.consumer.package> ls files/fileLogs/ | head -1)
```

You should see a log file with the `FileLogger wired up` line.
