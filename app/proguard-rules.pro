# The Shizuku UserService class is instantiated by Shizuku inside a separate app_process
# via reflection, so it must survive R8 shrinking/renaming and keep a public no-arg ctor.
-keep class com.pvz2.auto.shizuku.Pvz2UserService { *; }
-keep class com.pvz2.auto.IPvz2Service { *; }
-keep class com.pvz2.auto.IPvz2Service$* { *; }

# Shizuku API itself reflects over its own classes.
-keep class rikka.shizuku.** { *; }
-keep class moe.shizuku.** { *; }

# 无障碍降级方案已整个移除（用户明确要求不要降级路径），
# 原来这里 keep 的 fallback.Pvz2AccessibilityService 已不存在。
