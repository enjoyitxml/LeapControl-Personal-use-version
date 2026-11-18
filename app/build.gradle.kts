import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

/**
 * 仓库已转为**私有**，检测更新与下载安装包必须带一个只读令牌，
 * 否则 `api.github.com/repos/.../releases/latest` 与附件接口都会 404。
 *
 * ## 令牌怎么进包
 * 一律以**密文**进入 APK（`BuildConfig.GITHUB_TOKEN_ENCRYPTED`），运行时由
 * `ObfuscatedSecrets.deobfuscate` 还原 —— 与高德 / 蒲公英
 * 那两个 key 同一套机制。这样：
 *  - 源码与 git 历史里没有明文（原来那句「APK 里不许有明文凭据」仍然成立）；
 *  - `strings` / 反编译 BuildConfig 只能看到十六进制串。
 *
 * 优先级：`local.properties` 的 `leapcontrol.gh.token` → 环境变量
 * `LEAPCONTROL_GH_TOKEN` → `gradle.properties` 里的内置密文。前两者是本机明文，方便换 token
 * 后不改编代码；都没有的时候用内置密文兜底，保证**任何一台机器构建出来的包**都能正常检查更新
 * （否则换台电脑发版，更新检查会静默失效，很难察觉）。
 * 公开版构建时一律为空（见下方 `ghTokenEncrypted`），公开包不携带任何凭据。
 */
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

/** 与 `ObfuscatedSecrets.deobfuscate` 严格对称：逐字节 XOR 变长盐，再转十六进制。 */
val encodeSecret: (String) -> String = { plain ->
    val bytes = plain.toByteArray(Charsets.UTF_8)
    val sb = StringBuilder(bytes.size * 2)
    for (i in bytes.indices) {
        val salt = (0x7B + i * 37 + (i shr 2)) and 0xFF
        sb.append("%02x".format((bytes[i].toInt() xor salt) and 0xFF))
    }
    sb.toString()
}

/**
 * 内置的只读令牌（密文）。仅具备 releases 读取权限，用于版本检测与安装包下载。
 *
 * 2026-11-15 从源码常量挪到 `gradle.properties` 的 `leapcontrol.gh.token.encrypted`：
 * 仓库现在要分叉出一个**对外发布**的公开版，密文留在源码里就等于随源码一起公开
 * （密文用的是一眼能反推的 XOR 盐，公开即等于泄露读取权限）。放在构建属性里，
 * 公开仓库的那份 properties 不带这个键，构建出来的包自然没有令牌、走匿名接口。
 */
val BUILTIN_GH_TOKEN_ENCRYPTED =
    (project.findProperty("leapcontrol.gh.token.encrypted") as String?)?.trim().orEmpty()

/**
 * 版本分叉开关：`full`（默认，完整版）与 `personal`（公开版）。
 *
 * 两个仓库共用同一份源码，差异全部由这个属性驱动 —— 公开仓库在自己的
 * `gradle.properties` 里写 `leapcontrol.edition=personal`，于是同一个提交
 * 能分别产出两个包：包名、桌面图标、登录页、小组件车标、「我的」页入口、
 * 使用范围文案、更新仓库地址都跟着切；版本号（versionName / versionCode）完全同步。
 *
 * 不传这个属性时行为与分叉前**逐字节一致**（包名 com.leapcontrol.app、官方 logo、
 * 无「支持作者」入口），完整版不受影响。
 */
val appEdition = (project.findProperty("leapcontrol.edition") as String?)
    ?.trim().orEmpty().ifEmpty { "full" }
val personalEdition = appEdition == "personal"

val ghTokenOverride = (
    localProps.getProperty("leapcontrol.gh.token")
        ?: System.getenv("LEAPCONTROL_GH_TOKEN")
        ?: ""
    ).trim()

val ghTokenEncrypted =
    when {
        // 公开版不内置任何令牌：公开仓库的更新检测走匿名接口
        personalEdition -> ""
        ghTokenOverride.isNotEmpty() -> encodeSecret(ghTokenOverride)
        else -> BUILTIN_GH_TOKEN_ENCRYPTED
    }

android {
    namespace = "com.leapcontrol.app"
    compileSdk = 35
    buildToolsVersion = "35.0.0"

    defaultConfig {
        // 公开版用独立包名：两版可以同时装在一台手机上，各自的登录与数据互不干扰，
        // 也不会出现「装了公开版把完整版覆盖掉」的情况。
        applicationId = if (personalEdition) "com.leapcontrol.personal" else "com.leapcontrol.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 20404
        versionName = "2.4.4"
        resourceConfigurations += setOf("zh", "zh-rCN")
        buildConfigField("String", "AMAP_WEB_KEY_ENCRYPTED", "\"\"")
        buildConfigField("String", "PGYER_API_KEY_ENCRYPTED", "\"\"")
        // 私有仓库的只读令牌（密文）：只用于 Releases 查询与附件下载
        buildConfigField("String", "GITHUB_TOKEN_ENCRYPTED", "\"$ghTokenEncrypted\"")
        // ---- 版本分叉 ----
        // 公开版：方向盘 logo、无登录标语、「我的」页恢复支持作者与开源致谢、
        // 使用范围改为「仅限个人学习研究使用」、更新检测指向公开仓库。
        buildConfigField("boolean", "PERSONAL_EDITION", personalEdition.toString())
        buildConfigField(
            "String",
            "UPDATE_REPO",
            "\"${if (personalEdition) "enjoyitxml/LeapControl-Personal-use-version" else "enjoyitxml/LeapControl"}\"",
        )
        // 桌面图标的默认 alias：公开版只留方向盘那一个（清单里用占位符写入 enabled）
        manifestPlaceholders["officialIconEnabled"] = (!personalEdition).toString()
        manifestPlaceholders["steeringIconEnabled"] = personalEdition.toString()
        ndk {
            abiFilters += setOf("arm64-v8a")
        }
    }

    buildTypes {
        release {
            /**
             * **必须用 debug keystore 签名**。
             *
             * 历史版本全部是 debug 签名的包（`assembleDebug` + 自带 debug keystore），
             * 车友装的也是这些包。Android 只允许同签名覆盖安装，换签名会直接
             * 「应用未安装 / INSTALL_FAILED_UPDATE_INCOMPATIBLE」，用户只能卸载重装，
             * 而卸载会带走零记、行程、登录态这些本地数据 —— 绝对不能发生。
             *
             * 所以 release 变体在这里只承担「R8 + 资源压缩」的体积收益，
             * 签名身份与 debug 完全一致。
             */
            signingConfig = signingConfigs.getByName("debug")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    buildFeatures {
        buildConfig = true
    }

    // 背景视频必须原样打包：压缩过的资源拿不到文件描述符，MediaPlayer 会起播失败
    androidResources {
        noCompress += "mp4"
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
        resources {
            excludes += setOf(
                "META-INF/*.version",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
                "META-INF/*.md",
                "org/bouncycastle/**/*.properties"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

/**
 * 让产物直接带上版本号：
 * - debug   → `LeapControl-2.1.1-debug.apk`（日常调试）
 * - release → `LeapControl-2.1.1.apk`（分发用，R8 + 资源压缩后约 3.4MB）
 *
 * 为什么不用 `archivesBaseName`：AGP 8 里它对 APK 不再生效（只影响 AAR/JAR），
 * 改输出文件名的正规入口是 variant output 的 `outputFileName`。
 * 版本号从 defaultConfig 读，保证与 `versionName` 永远一致，不需要在这儿再抄一份。
 */
androidComponents {
    val vName = android.defaultConfig.versionName ?: "0"
    // 公开版加 `-personal` 后缀：两个变体输出到同一个目录，
    // 不区分的话第二个构建会直接覆盖第一个的产物，发错包很难发现。
    val suffix = if (personalEdition) "-personal" else ""
    onVariants(selector().withBuildType("debug")) { variant ->
        variant.outputs.forEach { output ->
            // outputFileName 不在公共的 VariantOutput 接口上，只有 AGP 内部实现类
            // VariantOutputImpl 才暴露它，所以这里必须下转一次（AGP 8.x 的通行做法）。
            (output as? com.android.build.api.variant.impl.VariantOutputImpl)
                ?.outputFileName
                ?.set("LeapControl-$vName$suffix-debug.apk")
        }
    }
    // release 不再带 `-debug` 后缀：它现在是真正发给车友的包，名字里留着 debug
    // 只会让用户以为装错了。
    onVariants(selector().withBuildType("release")) { variant ->
        variant.outputs.forEach { output ->
            (output as? com.android.build.api.variant.impl.VariantOutputImpl)
                ?.outputFileName
                ?.set("LeapControl-$vName$suffix.apk")
        }
    }
}

dependencies {
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // 找车页地图：轻量开源地图控件（约 1.3MB，纯 Java，无 native）。选它而不是高德 Android SDK，
    // 是因为后者的 Key 必须绑包名 + 签名 SHA1，只能把 Key 打进 APK（谁都能反编译拿去用）。
    // 瓦片走高德栅格服务，坐标系正好也是 GCJ-02，和车端上报一致，不需要纠偏。
    implementation("org.osmdroid:osmdroid-android:6.1.20")
    implementation("org.bouncycastle:bcprov-jdk18on:1.86")
    debugImplementation("com.github.chuckerteam.chucker:library:4.1.0")
    releaseImplementation("com.github.chuckerteam.chucker:library-no-op:4.1.0")
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.3")
    // 仅用于 JVM 单测：Android 内置的 org.json 在本地单测里不存在，需要显式提供 jar
    testImplementation("org.json:json:20240303")
    testImplementation("junit:junit:4.13.2")
}
