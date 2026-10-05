import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.process.CommandLineArgumentProvider

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    // FF-3 DONE. The plugin IS applied, and the precondition it warned about is met: FF-1 landed a
    // real `app/google-services.json` (project_id `racion-tu-diario-de-mercado`, package_name
    // `com.racion.diariomercado`), so `processDebugGoogleServices` has a file to read.
    //
    // What this plugin actually does, since it is invisible and that is confusing: it does NOT talk
    // to Firebase and it does NOT authenticate anything. It reads `google-services.json` at BUILD
    // time and GENERATES resource values from it, which is the only way the runtime can learn the
    // project id and API key. Without it the file sits in `app/` unread, and `FirebaseApp` has no
    // project to connect to — the "phone with no SIM card" case.
    //
    // Keep this line above the "do not remove" markers: deleting it does not fail the build loudly,
    // it just makes every Firebase call fail at runtime instead.
    alias(libs.plugins.google.services)

    // KSP, not KAPT (DB-1): kapt is deprecated on Kotlin 2.2 and Room 3 requires KSP
    // regardless, so the migration cost of kapt here is paid twice.
    alias(libs.plugins.ksp)
}

// DB-6: `exportSchema = true` only produces something testable if the compiler is told WHERE
// to write it, and that path has to live next to the KSP config that consumes it rather than
// being smeared across the database class. This is the provider Room documents for that job.
//
// `@InputFiles`, NOT Room's documented `@InputDirectory`: KSP creates the schema directory
// itself, and only once there is a database to export. `@InputDirectory` makes Gradle treat
// the path as an input that MUST already exist, so `kspDebugKotlin` fails with "Input file
// does not exist ... directory 'app\schemas' which doesn't exist" before any entity exists.
class RoomSchemaArgProvider(
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    val schemaOutputDir: File,
) : CommandLineArgumentProvider {
    override fun asArguments(): Iterable<String> =
        listOf("room.schemaLocation=${schemaOutputDir.absolutePath}")
}

android {
    namespace = "com.racion.diariomercado"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.racion.diariomercado"
        minSdk = 30
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        debug {
            // MANDATORY (OFF-1): Open Food Facts blocks generic/absent User-Agent headers.
            // Format must be: AppName/Version (contact)
            //
            // TODO(OFF-1): `contact@example.com` is a PLACEHOLDER. The contact is how Open Food
            // Facts identifies your app and is what they use to unblock you if the API starts
            // rejecting you. Replace it with a real mailbox BEFORE the first real request, or
            // risk an IP ban you have no way to trace back. Better: read it from a gitignored
            // `local.properties` entry so the address never lands in version control.
            buildConfigField("String", "OPEN_FOOD_FACTS_BASE_URL", "\"https://world.openfoodfacts.org/\"")
            buildConfigField("String", "OPEN_FOOD_FACTS_USER_AGENT", "\"RacionTuDiarioDeMercado/${defaultConfig.versionName} (jucarvajal2000@gmail.com)\"")
        }
        release {
            // MANDATORY (OFF-1): same two fields, release flavour. Same placeholder caveat.
            buildConfigField("String", "OPEN_FOOD_FACTS_BASE_URL", "\"https://world.openfoodfacts.org/\"")
            buildConfigField("String", "OPEN_FOOD_FACTS_USER_AGENT", "\"RacionTuDiarioDeMercado/${defaultConfig.versionName} (jucarvajal2000@gmail.com)\"")
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    testOptions {
        unitTests {
            // MANDATORY for Robolectric (DB-1): it reads the merged manifest, resources and
            // assets at RUNTIME. Without this flag it dies on the first test that touches an
            // Android class, and the stack trace blames Robolectric instead of this setting.
            isIncludeAndroidResources = true
        }
    }
    // DB-7: the exported schemas have to be reachable as ASSETS, and the route to that is not the
    // one Room's documentation shows. Room's snippet is
    // `sourceSets { androidTest.assets.srcDirs += files("$projectDir/schemas") }`, which covers an
    // instrumentation test and not a Robolectric JVM test in `src/test`.
    //
    // Room 2.8.5's `MigrationTestHelper` loads the schema for a version through
    // `context.assets.open("$assetsFolder/$version.json")` in ALL FOUR of its constructors — the
    // `File` argument on the `SQLiteDriver` one is the database file, not the schema directory, so
    // there is no constructor that takes a schema path on disk. Robolectric's `@Config` has no
    // `assets` option either, and `org.robolectric.internal.DefaultManifestFactory` derives the
    // asset directory solely from AGP's `android_merged_assets` property in
    // `com/android/tools/test_config.properties`.
    //
    // That property is the deciding detail, and it is why `sourceSets { test { ... } }` cannot work:
    // AGP always writes `android_merged_assets=build/intermediates/assets/debug/mergeDebugAssets` —
    // the **variant** merge, assembled from the `main`/`debug` asset source sets. A `test` source
    // set compiles fine and changes nothing. Measured, not assumed: with the schema added to `test`,
    // a forced `:app:mergeDebugAssets --rerun` left `mergeDebugAssets` containing only the MLKit
    // models, and the generated `test_config.properties` was byte-identical. With the schema added
    // to `debug`, the same task produced
    // `mergeDebugAssets/com.racion.diariomercado.data.local.RacionDatabase/1.json`.
    //
    // `debug` and not `main`, so two JSON files do not ship in the release APK. The cost is that
    // they DO ship in the debug APK (~30 KB), which is the honest price of Room doing its own
    // schema validation against the real v1 — which is the whole point of design D3. A hand-rolled
    // loader that reads `1.json` off disk would cost nothing and would validate nothing, and a
    // migration test that silently validates nothing is worse than a red one.
    sourceSets {
        getByName("debug") {
            assets.directories += File(projectDir, "schemas").absolutePath
        }
    }
}

ksp {
    arg(RoomSchemaArgProvider(File(projectDir, "schemas")))
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)

    // AndroidX foundation extras
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)

    // Accompanist permissions for runtime permission handling (BC-1)
    implementation(libs.accompanist.permissions)

    // CameraX for barcode scanning (BC-1)
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)

    // Networking / serialization (OFF-1..OFF-4)
    implementation(libs.squareup.retrofit)
    implementation(libs.squareup.retrofit.converter.moshi)
    implementation(libs.squareup.moshi)
    // Reflection-based Kotlin adapters: what actually deserializes the OFF DTOs today.
    implementation(libs.squareup.moshi.kotlin)
    ksp(libs.squareup.moshi.kotlin.codegen)
    implementation(libs.squareup.okhttp.logging.interceptor)
    implementation(libs.kotlinx.coroutines.android)

    // Images (product photos from Open Food Facts)
    implementation(libs.io.coil.kt.coil3)
    implementation(libs.io.coil.kt.coil3.compose)

    // Firebase (FF-1..FF-5) - libraries only, the plugin is NOT applied yet.
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.auth)
    implementation(libs.firebase.firestore)
    implementation(libs.firebase.analytics)
    // Google Sign-In - DECLARED BUT UNUSED, on purpose. The ratified strategy is
    // anonymous-first + Email/Password, so these exist only so the next lane stops
    // hardcoding coordinates and can import the catalog instead.
    implementation(libs.androidx.credentials)
    implementation(libs.androidx.credentials.play.services.auth)
    implementation(libs.googleid)

    // Barcode scanning (BC-1)
    implementation(libs.play.services.mlkit.barcode.scanning)

    // Room (DB-1..DB-6) - the local source of truth. The compiler runs through `ksp`, not
    // `implementation`: it generates code at build time and must never reach the APK.
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
    // In-memory DAO tests. room-testing carries the MigrationTestHelper and the in-memory
    // builder assertions; robolectric + test:core are what run the SQLite/Android layer.
    testImplementation(libs.androidx.room.testing)
    testImplementation(libs.org.robolectric.robolectric)
    testImplementation(libs.androidx.test.core)
    // B6/Q-4 (Brayan's lane): the recorded test for the OFF `User-Agent` header and for
    // `status = 0` -> NotFound. Declared in the catalog (single-owner file, Sprint-1 §1.1) and
    // wired HERE so the lane is actually unblocked — a catalog entry with no
    // `testImplementation` still fails to resolve in a teammate's test source.
    testImplementation(libs.squareup.okhttp.mockwebserver)

    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}

// MANDATORY for the Robolectric DAO tests (DB-1/Q-3).
//
// Robolectric 4.17 installs a FileDescriptorInterceptor that reflects into
// `jdk.internal.access.SharedSecrets`, a JDK-internal package that JDK 9+ no longer exports.
// On this machine's JBR (JDK 25) that makes EVERY Robolectric test die in `beforeTest`, before
// a single assertion runs:
//
//   java.lang.IllegalAccessException: ... cannot access class jdk.internal.access.SharedSecrets
//   (in module java.base) because module java.base does not export jdk.internal.access
//
// These flags have to live on the Test task because module access is resolved at JVM LAUNCH
// time — there is no way to grant it from inside the test, via `@Config`, or from a rule.
// Only the unit-test JVM needs them, which is why this is scoped to `Test` and not `allprojects`.
//
// The symptom if these are ever dropped: every Robolectric-backed DAO test fails with
// "Failed to interact with raw FileDescriptor internals; perhaps JRE has changed?", which reads
// like a Robolectric bug rather than a missing JVM flag. That is the whole 58-test Room suite
// (DiaryDaoTest + CatalogDaoTest), while the non-Robolectric suites stay green and hide the cause.
// Note that `--add-exports` is the flag that maps onto the exception above; the three
// `--add-opens` are precautionary because Robolectric reflectively rewrites shadows, and have
// not been individually proven load-bearing. Trim them only with a re-run, never by assumption.
tasks.withType<Test>().configureEach {
    jvmArgs(
        "--add-exports=java.base/jdk.internal.access=ALL-UNNAMED",
        "--add-opens=java.base/java.lang=ALL-UNNAMED",
        "--add-opens=java.base/java.util=ALL-UNNAMED",
        "--add-opens=java.base/java.io=ALL-UNNAMED"
    )
}
