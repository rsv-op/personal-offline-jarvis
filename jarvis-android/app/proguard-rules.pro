# JARVIS application-specific R8 rules.
# The current scaffold uses Android framework APIs and does not require
# custom keep rules for its existing launcher activity.

# Preserve source and line information for useful crash reports.
-keepattributes SourceFile,LineNumberTable

# Preserve runtime annotation metadata for future annotated components.
-keepattributes RuntimeVisibleAnnotations,RuntimeInvisibleAnnotations
-keepattributes RuntimeVisibleParameterAnnotations
-keepattributes AnnotationDefault
