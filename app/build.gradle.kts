plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "io.github.baidaofu.warp_loves_anyone"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.baidaofu.warp_loves_anyone"
        minSdk = 27
        targetSdk = 35
        versionCode = 8
        versionName = "2.6"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // 不配置签名：CI 产出未签名 APK，由使用者自行 apksigner 签名
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
        buildConfig = true
    }
}

dependencies {
    // libxposed API 签名中引用 androidx 注解，需要提供
    implementation(libs.androidx.annotation)
    // libxposed API 102：运行时由 Xposed 框架提供，必须 compileOnly
    // （SweetDependency 依赖直接以 组名.库名 访问，不走 libs catalog）
    compileOnly(io.github.libxposed.api)
    // libxposed Service：模块 app 侧经 XposedService 读写框架远程偏好（Remote Preferences）
    implementation(io.github.libxposed.service)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
