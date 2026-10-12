# The Shizuku UserService class is instantiated by Shizuku inside a separate app_process
# via reflection, so it must survive R8 shrinking/renaming and keep a public no-arg ctor.
-keep class com.pvz2.auto.shizuku.Pvz2UserService { *; }
-keep class com.pvz2.auto.IPvz2Service { *; }
-keep class com.pvz2.auto.IPvz2Service$* { *; }

# Shizuku API itself reflects over its own classes.
-keep class rikka.shizuku.** { *; }
-keep class moe.shizuku.** { *; }

# The fallback AccessibilityService is instantiated by the framework from the manifest.
-keep class com.pvz2.auto.fallback.Pvz2AccessibilityService { *; }
