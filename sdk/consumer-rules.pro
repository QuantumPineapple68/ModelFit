# ModelFit SDK Consumer ProGuard Rules
# Preserve ModelFit public API, models, and data classes
-keep class dev.modelfit.** { *; }
-keepattributes Signature
-keepattributes *Annotation*
