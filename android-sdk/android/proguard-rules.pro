-keep class io.logto.sdk.core.type.** { *; }

# SLF4J 1.7 uses its NOP fallback when the optional logger binding is absent.
-dontwarn org.slf4j.impl.StaticLoggerBinder
