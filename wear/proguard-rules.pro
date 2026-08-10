# Add project specific ProGuard rules here.

# Blanket keep on the app's own code.
#
# Which failure does it fix? None that was ever measured. Recorded here 2026-08-06 (#127) after
# checking the history: the rule was added defensively when minification was first enabled, not in
# response to a crash. Saying so plainly is the point of the criterion that asked for this comment —
# a keep whose reason nobody knows can never be safely narrowed, so an invented reason would be worse
# than none.
#
# What it costs today: R8 does not rename or shrink com.racetimer.**, so the archived mapping.txt
# maps library frames only and this app's own stack traces arrive already readable.
#
# Narrowing it is deliberately left to #128. The failure mode of an over-narrow keep here — reflection
# over Compose/Wear entry points, TimerService instantiated by name from the manifest — does not show
# up in a release build that merely compiles; it shows up on the watch. #127 is scoped to what a
# session can verify, and this is not that.
-keep class com.racetimer.** { *; }

# TEMPORARY MUTATION - proving CI goes red on an R8 failure (#129 AC 4).
# Reverted in the very next commit. If you are reading this on develop, something
# went wrong: this line must never survive past the revert commit.
-keepp class com.racetimer.** { *; }
