# bitcoinj protobuf wallet classes
-keep class org.bitcoinj.wallet.Protos { *; }
-keep class org.bitcoinj.wallet.Protos$* { *; }

# bitcoinj payment protocol protobuf classes
-keep class org.bitcoin.protocols.payments.Protos { *; }
-keep class org.bitcoin.protocols.payments.Protos$* { *; }

# bitcoinj optional classes
-dontwarn org.bitcoinj.store.LevelDBBlockStore
-dontwarn org.bitcoinj.store.LevelDBFullPrunedBlockStore**
-dontwarn javax.naming.**

# Guava / Java platform references
-dontwarn sun.misc.Unsafe
-dontwarn java.lang.ClassValue
-dontwarn java.lang.invoke.**
-dontwarn com.google.errorprone.annotations.**
-dontwarn module-info
