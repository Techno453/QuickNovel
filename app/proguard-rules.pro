# R8 rules for the release build (minifyEnabled + shrinkResources, see app/build.gradle).
# Applied on top of proguard-android-optimize.txt and the consumer rules that libraries ship
# (Room, WorkManager, Compose, coroutines, Coil, ML Kit, kotlin-reflect, OkHttp, Tink, ...).
# Every rule says why it is needed; do not add rules "just in case". Mapping files and how to
# de-obfuscate a crash trace: docs/ci.md, "Shrinking (R8) and crash traces".

# ---------------------------------------------------------------------------------------------
# Crash traces: keep file and line numbers so a trace from a user can be retraced with
# mapping.txt (uploaded with every release). The source file name itself is not needed.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Generic signatures (Jackson's TypeReference / readValue<List<T>>, Room, Kotlin reflection),
# annotations read at run time (@JsonProperty, @JavascriptInterface, Room), and the inner-class
# attributes R8 requires alongside Signature.
-keepattributes Signature,InnerClasses,EnclosingMethod,*Annotation*,AnnotationDefault,Exceptions

# ---------------------------------------------------------------------------------------------
# App code. Jackson (with jackson-module-kotlin) is the only JSON library and it maps by
# reflection: provider API responses (nicehttp's ResponseParser in MainActivity), everything
# DataStore/BackupUtils persists in SharedPreferences (setKey/getKey of arbitrary data classes,
# many without @JsonProperty), AppUtils.toJson/parseJson on any object, the translator's
# LLM protocol / story bible / glossary JSON, and the updater's GithubRelease/GithubAsset.
# jackson-module-kotlin reads constructor parameter names from each class's kotlin.Metadata and
# instantiates through the constructors, so these classes need their names, fields, getters,
# constructors and metadata intact; and since nothing calls those constructors directly, R8 would
# otherwise treat the classes as never instantiated and drop their methods. The model classes are
# spread over the whole package (providers, translator, ui, util) and new providers keep adding
# more, so the app package is kept as a whole instead of per class: it is a small part of the dex
# (libraries are what R8 shrinks), and it also covers the other reflective uses in app code:
# TranslatorAgents.valueOf on a stored name, WebViewResolver's @JavascriptInterface, Room
# entities/DAOs, Workers and Services named in the manifest, Parcelables in Bundles.
-keep class com.lagradost.quicknovel.** { *; }

# The bundled EPUB library (app/src/main/java/me/ag2s/epublib): Serializable domain classes and
# XmlPullParser setup; small, kept for the same reason as app code.
-keep class me.ag2s.epublib.** { *; }

# ---------------------------------------------------------------------------------------------
# Jackson. Its annotations are read reflectively (value(), required() ...), so keep them with
# their members. databind loads its optional JDK 7 / DOM support by class name
# (Java7Support -> "...ext.Java7SupportImpl", OptionalHandlerFactory), so keep the ext package;
# the javax/java.beans/DOM types it probes for do not exist on Android.
-keep class com.fasterxml.jackson.annotation.** { *; }
-keep class com.fasterxml.jackson.databind.ext.** { *; }
-dontwarn com.fasterxml.jackson.databind.ext.**
-dontwarn java.beans.**
# readValue<List<Foo>>() / parseJson<T>() create `object : TypeReference<T>() {}` and Jackson
# reads T from the subclass's generic superclass at run time. R8 full mode strips the Signature
# of classes no rule keeps, and then drops the subclasses' now-dangling `TypeReference<T>`
# signatures too ("TypeReference constructed without actual type information", seen in the
# shrunk build on a provider search). Same rule as Gson's TypeToken; names may still be obfuscated.
-keep,allowobfuscation,allowshrinking class com.fasterxml.jackson.core.type.TypeReference
-keep,allowobfuscation,allowshrinking class * extends com.fasterxml.jackson.core.type.TypeReference
# jackson-module-kotlin finds Kotlin classes via kotlin.Metadata (kept by kotlin-reflect's own
# consumer rules) and instantiates its KotlinModule components directly: nothing else needed.

# ---------------------------------------------------------------------------------------------
# NiceHttp's NiceResponse.text looks up the public field named "source" of OkHttp's
# BufferedSource (okio's RealBufferedSource) by reflection to read the body with a size limit;
# obfuscated, every request logs "Failed to get .source with reflection" and falls back.
-keepclassmembers class * implements okio.BufferedSource {
    public okio.Source source;
}

# ---------------------------------------------------------------------------------------------
# Optional dependencies that are not on the classpath (R8 fails on missing classes otherwise):
# pdfbox-android's JPEG 2000 filter uses com.gemalto.jp2 only if that library is added (it is
# not; JPX images in an imported PDF are skipped, as in the unshrunk build), and markwon-html's
# <s>/<del> handler checks for commonmark's strikethrough extension before using it.
-dontwarn com.gemalto.jp2.**
-dontwarn org.commonmark.ext.gfm.strikethrough.**

# ---------------------------------------------------------------------------------------------
# Reflection on library internals: DownloadFragment.reduceDragSensitivity reads
# ViewPager2.mRecyclerView and RecyclerView.mTouchSlop by name (not wrapped in a try; renaming
# them would crash the library screen).
-keepclassmembers class androidx.viewpager2.widget.ViewPager2 {
    private androidx.recyclerview.widget.RecyclerView mRecyclerView;
}
-keepclassmembers class androidx.recyclerview.widget.RecyclerView {
    private int mTouchSlop;
}
