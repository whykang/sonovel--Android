import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// 发布签名：读取项目根目录 keystore.properties（不存在时 release 使用 debug 签名）
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

// 本机私有配置：读取项目根目录 local.properties（该文件不提交到仓库），未配置的项对应功能不启用
//   update.url     更新检测地址
//   umeng.appkey   友盟统计 AppKey
//   umeng.channel  友盟渠道名（默认 official）
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val updateUrl: String = localProps.getProperty("update.url", "").trim()
val umengAppKey: String = localProps.getProperty("umeng.appkey", "").trim()
val umengChannel: String = localProps.getProperty("umeng.channel", "official").trim().ifEmpty { "official" }

android {
    namespace = "com.wang.sonovel"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.wang.sonovel"
        // Android 6.0+，覆盖绝大多数设备
        minSdk = 23
        targetSdk = 35
        versionCode = 3
        versionName = "3.0"

        buildConfigField("String", "UPDATE_URL", "\"$updateUrl\"")
        buildConfigField("String", "UMENG_APPKEY", "\"$umengAppKey\"")
        buildConfigField("String", "UMENG_CHANNEL", "\"$umengChannel\"")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables { useSupportLibrary = true }
    }

    signingConfigs {
        if (keystoreProps.getProperty("storeFile") != null) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    coreLibraryDesugaring(libs.desugar.jdk.libs)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.okhttp)
    implementation(libs.jsoup)
    implementation(libs.gson)
    implementation(libs.coil.compose)
    implementation(libs.quickjs.android)
    implementation(libs.material.kolor)
    implementation(libs.umeng.common)
    implementation(libs.umeng.asms)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.runner)
}
