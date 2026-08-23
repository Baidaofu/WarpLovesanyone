plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    // 作为 Xposed 模块使用务必添加，其它情况可选
    autowire(libs.plugins.com.google.devtools.ksp)
}

android {
    namespace = "io.github.baidaofu.warp_loves_anyone"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.baidaofu.warp_loves_anyone"
        minSdk = 27
        targetSdk = 35
        versionCode = 4
        versionName = "2.2"

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
    // YukiHookAPI KSP 生成代码依赖 @Keep 注解
    implementation(libs.androidx.annotation)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    // 基础依赖
    implementation(com.highcapable.yukihookapi.api)
    // 作为 Xposed 模块使用务必添加，其它情况可选
    compileOnly(de.robv.android.xposed.api)
    // 作为 Xposed 模块使用务必添加，其它情况可选
    ksp(com.highcapable.yukihookapi.ksp.xposed)
}