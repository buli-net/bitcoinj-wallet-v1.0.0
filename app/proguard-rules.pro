# bitcoinj / protobuf-generated wallet classes
-keep,includedescriptorclasses class org.bitcoinj.wallet.Protos$** { *; }
-keepclassmembers class org.bitcoinj.wallet.Protos {
    com.google.protobuf.Descriptors$FileDescriptor descriptor;
}

-keep,includedescriptorclasses class org.bitcoin.protocols.payments.Protos$** { *; }
-keepclassmembers class org.bitcoin.protocols.payments.Protos {
    com.google.protobuf.Descriptors$FileDescriptor descriptor;
}

# bitcoinj optional / platform-specific references
-dontwarn org.bitcoinj.store.LevelDBBlockStore
-dontwarn org.bitcoinj.store.LevelDBFullPrunedBlockStore**
-dontwarn javax.naming.**

# Guava / Java platform references
-dontwarn sun.misc.Unsafe
-dontwarn java.lang.ClassValue
-dontwarn java.lang.invoke.**
-dontwarn com.google.errorprone.annotations.**
-dontwarn module-info
