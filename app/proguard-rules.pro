# JNI bridge: method names must match the C symbols in imci_jni.c
-keep class org.imci.transcriber.WhisperNative { *; }

# sherpa-onnx: the native library reads these Kotlin config/result classes by field name (JNI) - do not rename or strip
-keep class com.k2fsa.sherpa.onnx.** { *; }

# JNI bridge to llama.cpp (imci_llm.cpp)
-keep class org.imci.transcriber.LlamaNative { *; }

# ONNX Runtime Java API (JNI)
-keep class ai.onnxruntime.** { *; }
