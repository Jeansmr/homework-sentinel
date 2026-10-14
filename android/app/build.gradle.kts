plugins {
    id("com.android.application")
}

android {
    namespace = "com.homesentinel.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.homesentinel.app"
        minSdk = 24
        targetSdk = 34
        versionCode = 2
        versionName = "1.0.1"
        resourceConfigurations += listOf("zh", "en")
    }

    signingConfigs {
        create("release") {
            // 本地自签名，方便直接装到手机上。
            // 想换成自己的签名：改这里的路径，或删掉这段改用 build.gradle 默认签名。
            val ks = rootProject.file("keystore/hw-sentinel.jks")
            if (ks.exists()) {
                storeFile = ks
                storePassword = "hwsentinel"
                keyAlias = "hwsentinel"
                keyPassword = "hwsentinel"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            if (rootProject.file("keystore/hw-sentinel.jks").exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        debug {
            applicationIdSuffix = ".debug"
        }
    }

    // 同时启用新旧两套签名方案，兼容面更广
    signingConfigs.forEach { cfg ->
        cfg.enableV1Signing = true
        cfg.enableV2Signing = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources.excludes += setOf("META-INF/*.kotlin_module")
    }

    // 网页资源不参与压缩，否则 WebView 读取可能出问题
    androidResources {
        noCompress += listOf("html", "js", "css", "webmanifest", "svg", "png")
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    // 刻意不引入任何第三方库：
    // 1) 构建快、体积小；2) 没有网络依赖，离线也能编译。
}
