import org.gradle.testing.jacoco.plugins.JacocoTaskExtension
import org.gradle.testing.jacoco.tasks.JacocoCoverageVerification
import org.gradle.testing.jacoco.tasks.JacocoReport

plugins {
    id("com.android.application")
    jacoco
}

android {
    namespace = "com.astor.pulsefitengine"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.astor.pulsefitengine"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        viewBinding = true
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}

jacoco {
    toolVersion = "0.8.14"
}

tasks.withType<Test>().configureEach {
    extensions.configure(JacocoTaskExtension::class) {
        isIncludeNoLocationClasses = true
        excludes = listOf("jdk.internal.*")
    }
}

val protocolCoverageIncludes = listOf(
    "**/HeuristicGarminRealtimeDecoder.class",
    "**/MlrStreamState.class",
    "**/ProtoReader.class",
    "**/GarminMultiLinkController.class",
    "**/GarminMultiLinkController$*.class",
)

val protocolCoverageSourceDirs = files(
    "src/main/java",
)

val protocolCoverageClassDirs = files(
    layout.buildDirectory.dir("tmp/kotlin-classes/debug"),
    layout.buildDirectory.dir("intermediates/javac/debug/compileDebugJavaWithJavac/classes"),
)

tasks.register<JacocoReport>("jacocoProtocolCoverageReport") {
    dependsOn("testDebugUnitTest")

    reports {
        xml.required.set(true)
        html.required.set(true)
    }

    classDirectories.setFrom(
        protocolCoverageClassDirs.asFileTree.matching {
            include(protocolCoverageIncludes)
        },
    )
    sourceDirectories.setFrom(protocolCoverageSourceDirs)
    executionData.setFrom(
        fileTree(layout.buildDirectory) {
            include(
                "jacoco/testDebugUnitTest.exec",
                "outputs/unit_test_code_coverage/debugUnitTest/testDebugUnitTest.exec",
            )
        },
    )
}

tasks.register<JacocoCoverageVerification>("jacocoProtocolCoverageVerification") {
    dependsOn("testDebugUnitTest")

    classDirectories.setFrom(
        protocolCoverageClassDirs.asFileTree.matching {
            include(protocolCoverageIncludes)
        },
    )
    sourceDirectories.setFrom(protocolCoverageSourceDirs)
    executionData.setFrom(
        fileTree(layout.buildDirectory) {
            include(
                "jacoco/testDebugUnitTest.exec",
                "outputs/unit_test_code_coverage/debugUnitTest/testDebugUnitTest.exec",
            )
        },
    )

    violationRules {
        rule {
            limit {
                counter = "LINE"
                value = "COVEREDRATIO"
                minimum = "1.0".toBigDecimal()
            }
        }
    }
}

tasks.named("check").configure {
    dependsOn("jacocoProtocolCoverageVerification")
}
