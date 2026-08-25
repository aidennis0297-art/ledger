// 빌드 스크립트 안에서 `java` 는 Gradle 의 java 확장을 가리킨다.
// java.util.Properties 라고 쓰면 그쪽으로 먹혀 이름을 못 찾는다.
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

/**
 * 서명 비밀번호는 keystore.properties 에서 읽는다. 이 파일은 .gitignore 대상이다.
 * 예전에는 비밀번호가 이 파일에 평문으로 박혀 있어서, 저장소를 어디든 올리면
 * 서명 키(release.keystore)와 그 비밀번호가 한 묶음으로 함께 나갔다.
 */
val keystoreProps = rootProject.file("keystore.properties").takeIf { it.exists() }?.let { f ->
    Properties().apply { f.inputStream().use { s -> load(s) } }
}

android {
    namespace = "com.pushledger"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.pushledger"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1"
    }

    signingConfigs {
        // 비밀번호가 없으면 서명 설정 자체를 만들지 않는다. 빈 비밀번호로 만들어 두면
        // release 빌드가 서명 단계에서야 알 수 없는 이유로 깨진다.
        if (keystoreProps != null) create("release") {
            storeFile = file("${rootDir}/release.keystore")
            storePassword = keystoreProps.getProperty("storePassword")
            keyAlias = keystoreProps.getProperty("keyAlias")
            keyPassword = keystoreProps.getProperty("keyPassword")
            enableV1Signing = true
            enableV2Signing = true
            enableV3Signing = true
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
}

dependencies {
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.1")
    implementation("androidx.activity:activity-compose:1.10.1")

    val composeBom = platform("androidx.compose:compose-bom:2025.06.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("androidx.work:work-runtime-ktx:2.10.2")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("androidx.security:security-crypto:1.0.0")

    testImplementation("junit:junit:4.13.2")
}

// 빌드가 끝나면 APK 를 프로젝트 루트에 놓는다.
// outputs/apk/debug 깊은 경로를 매번 타고 들어가지 않고 폴더를 열면 바로 보이게.
// Copy 태스크를 쓰면 루트가 태스크 출력으로 잡혀 다른 태스크와 충돌한다. 그냥 복사한다.
tasks.matching { it.name == "assembleDebug" }.configureEach {
    doLast {
        val src = layout.buildDirectory.file("outputs/apk/debug/app-debug.apk").get().asFile
        if (src.exists()) {
            src.copyTo(File(rootProject.projectDir, "가계부.apk"), overwrite = true)
        }
    }
}

// 프로젝트 경로에 한글이 들어 있다. 테스트는 별도 JVM 에서 도는데, 그 JVM 이
// 경로를 시스템 로캘로 디코딩하면 클래스패스가 깨져 ClassNotFoundException 만 난다.
// 컴파일은 멀쩡히 되고 테스트만 통째로 안 붙는 증상이라 원인을 짚기 어렵다.
tasks.withType<Test>().configureEach {
    jvmArgs("-Dfile.encoding=UTF-8", "-Dsun.jnu.encoding=UTF-8")
}
