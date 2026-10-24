# ML Kit loads its recognisers and barcode decoder reflectively from internal packages, so R8
# cannot see they are used. Latin text, Devanagari text and barcode scanning each live in a
# different internal package - keep them all, or release builds crash where debug works.
-keep class com.google.mlkit.** { *; }
-keep class com.google.android.gms.internal.mlkit_** { *; }
-keep class com.google.android.odml.** { *; }
-dontwarn com.google.mlkit.**
-dontwarn com.google.android.gms.internal.mlkit_**
