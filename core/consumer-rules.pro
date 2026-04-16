# Keep all concrete classes that implement StartupSyncInitializer or StartupAsyncInitializer.
# These classes are instantiated by name via reflection from the AndroidManifest meta-data,
# so R8 cannot statically trace the reference and would strip them during shrinking.
# The no-arg constructor (<init>()) must also survive because AppStartupInitializer calls
# getDeclaredConstructor().newInstance() at runtime.
-keep class * implements io.github.santimattius.android.startup.initializer.StartupSyncInitializer {
    <init>();
}
-keep class * implements io.github.santimattius.android.startup.initializer.StartupAsyncInitializer {
    <init>();
}

# Keep InitializationProvider: it is registered in the manifest by fully-qualified name
# and auto-started by the OS. Renaming it would break the ContentProvider lookup.
-keep class io.github.santimattius.android.startup.InitializationProvider
