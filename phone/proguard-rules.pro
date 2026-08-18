# Add project specific ProGuard rules here.

# Blanket keep on the app's own code, matching wear/proguard-rules.pro (#211).
#
# The same rule, for the same reason and with the same admission: it fixes no measured failure. It
# was added to :wear defensively when minification was first enabled (#127, 2026-08-06), and it is
# repeated here rather than narrowed because a keep whose reason nobody knows cannot be safely
# narrowed, and this module has even less runtime evidence behind it than the watch does.
#
# `com.racetimer.**` covers com.racetimer.phone as well as com.racetimer.wear, so :phone could in
# principle have relied on :wear's copy — it cannot, because proguardFiles is per module and R8 for
# :phone never reads the watch's file. Two files, one rule, stated in both.
#
# What it costs today: R8 does not rename or shrink com.racetimer.**, so the archived mapping.txt
# maps library frames only and this app's own stack traces arrive already readable.
#
# Narrowing is deliberately left to #128 along with the watch's, and the failure mode is the same —
# reflection over Compose entry points and a service instantiated by name from the manifest does not
# show up in a release build that merely compiles.
-keep class com.racetimer.** { *; }
