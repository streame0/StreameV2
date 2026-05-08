# Force Hilt Application classes into the primary DEX so they're
# available during the very first moments of process startup.
-keep class com.streame.tv.StreameApplication { *; }
-keep class com.streame.tv.Hilt_StreameApplication { *; }
-keep class com.streame.tv.Hilt_StreameApplication$1 { *; }
-keep class com.streame.tv.StreameApplication_GeneratedInjector { *; }
-keep class com.streame.tv.DaggerStreameApplication_HiltComponents_SingletonC { *; }
-keep class com.streame.tv.DaggerStreameApplication_HiltComponents_SingletonC$Builder { *; }
