# Application-specific R8 rules.
# Keep this file narrow so R8 can remove, optimize, and obfuscate unused code.

-keepattributes *Annotation*

# bitcoinj 0.17.1 protobuf classes are generated under org.bitcoinj.protobuf.wallet.
-keep,includedescriptorclasses class org.bitcoinj.protobuf.wallet.Protos$** { *; }
-keepclassmembers class org.bitcoinj.protobuf.wallet.Protos {
    com.google.protobuf.Descriptors$FileDescriptor descriptor;
}
-keep,includedescriptorclasses class org.bitcoin.protocols.payments.Protos$** { *; }
-keepclassmembers class org.bitcoin.protocols.payments.Protos {
    com.google.protobuf.Descriptors$FileDescriptor descriptor;
}
-dontwarn org.bitcoinj.store.LevelDBBlockStore
-dontwarn org.bitcoinj.store.LevelDBFullPrunedBlockStore**

# Bouncy Castle optional JNDI integration.
-dontwarn javax.naming.**

# protobuf-javalite uses reflection to access generated private fields.
# Optional protobuf/Guava JVM integrations not present on Android.
-dontwarn sun.misc.Unsafe
-dontwarn java.lang.ClassValue
-dontwarn com.google.errorprone.annotations.**
