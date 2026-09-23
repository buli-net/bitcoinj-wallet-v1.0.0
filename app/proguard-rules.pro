# Release documentation:
# Keep this file limited to rules required by runtime reflection or generated code.
# R8 handles the normal application/dependency reachability analysis.

# AndroidAnnotations generates the *_ classes used by the manifest and activities.
-keep class **_ { *; }

# bitcoinj may instantiate wallet/network classes through reflection or serialized
# protobuf/runtime metadata. Keep bitcoinj public API names while allowing R8 to
# remove unreachable implementation details.
-keepnames class org.bitcoinj.**

# Preserve SLF4J binding discovery metadata when present.
-keep class org.slf4j.impl.** { *; }
