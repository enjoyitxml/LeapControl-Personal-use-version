# ============================================================================
# 零控 release 变体的混淆 / 裁剪规则
#
# 历史：这份文件长期是从上游项目（com.leapauto.app）直接拷过来的，-keep 的全是
# 上游包名，对本项目一条都不生效。2026-11-10 起 release 变体真正用于分发
# （R8 + 资源压缩把包从 26MB 压到 3.4MB），这里必须换成**本项目实际依赖**的规则。
# ============================================================================

# 允许改访问修饰符（R8 优化用，能多删一批桥接方法）
-allowaccessmodification

# 把所有可混淆的类挪进默认包：常量池里的包名全部变成短串，dex 更小。
# 前提是「系统按名字找的类」必须 keep —— manifest 组件由 AGP 自动处理，
# XML inflate 的自定义 View 本项目没有（全是 Compose），数美 SDK 在独立 dex 里也不受影响。
-repackageclasses ''

# 崩溃栈要能定位：保留源文件名与行号（CrashGuard 会把栈写给用户看）
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# ---------------------------------------------------------------------------
# 反射 / 系统按名回调的入口
# ---------------------------------------------------------------------------

# WebView 的客户端回调：系统按方法名调用（3D 车模的 JS 桥在这一层）
-keepclassmembers class * extends android.webkit.WebViewClient { *; }
-keepclassmembers class * extends android.webkit.WebChromeClient { *; }
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# 枚举的 values()/valueOf()：偏好里存的是枚举名字符串，重建时走 valueOf
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# Parcelable 的 CREATOR 由系统反射读取（进程被回收后恢复状态）
-keepclassmembers class * implements android.os.Parcelable {
    public static final android.os.Parcelable$Creator *;
}

# ---------------------------------------------------------------------------
# 第三方库里「可选依赖缺失」的情况，别让 R8 因此中断构建
# ---------------------------------------------------------------------------
-dontwarn org.bouncycastle.**
-dontwarn org.osmdroid.**

# 说明（别删，避免以后有人照着旧版规则又加一堆无效 keep）：
# - 数美反欺诈 SDK：运行时从 assets/shumei.dex 用 DexClassLoader 加载，
#   是独立 dex，R8 完全不碰它；ShumeiSecurityManager 用 Proxy 实现它的接口，
#   接口方法名由 SDK 侧决定，不需要额外规则。
# - 小组件 / 磁贴 / 服务 / Activity：都在 AndroidManifest 里声明，
#   AGP 的默认规则会 keep 并同步改写 manifest 里的类名，无需手写。
# - Chucker：release 变体用 no-op 版，本来就没有实现代码。
