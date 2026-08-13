package com.racetimer.phone

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel

/**
 * What the officer decided the console screen should do today (#225).
 *
 * Two independent switches, because **the wants come apart**: shore power wants both, a dying
 * battery under overcast wants neither, and direct sun with a free thumb wants brightness alone.
 * #199 kept them independent at the window; coupling them here would put the coupling back in the
 * only place it could still do harm.
 */
data class DisplayChoice(val keepScreenOn: Boolean, val fullBrightness: Boolean) {
    companion object {
        /**
         * Where the surface opens.
         *
         * Screen-on preselected and full brightness not, so that an officer who taps straight
         * through gets a clock that does not sleep and does not silently pay the panel cost. These
         * two positions are the ratified default and the thing a test has to pin — a tap-through is
         * the commonest path, so its outcome is a product decision rather than an initialiser.
         */
        val INITIAL = DisplayChoice(keepScreenOn = true, fullBrightness = false)
    }
}

/**
 * Holds the choice for the life of the process, and no longer (#225).
 *
 * A `ViewModel` is doing exact double duty here, and both halves are criteria:
 *
 *  - it **survives configuration change**, so a console phone picked up and turned does not ask the
 *    officer again mid-start, and
 *  - it **dies with the process**, which is the whole retention policy. The right answer is a
 *    property of *the day* — this sun, this boat, whether there is a charger aboard — not of the
 *    officer, so a remembered value would be confidently wrong on the next race day. Nothing here
 *    is written to `SharedPreferences` or a `DataStore`, and
 *    `ModuleBoundaryTest#the display choice is written to no persistent store` asserts that by
 *    reading the module's own source rather than trusting this paragraph.
 *
 * [answered] is separate from [choice] on purpose: the initial positions are *a choice already
 * populated*, so "what is selected" cannot tell you "has the officer been through the surface". One
 * flag would conflate a tap-through with never having been asked, and AC 1 turns on exactly that
 * distinction.
 */
class DisplayChoiceViewModel : ViewModel() {

    var choice by mutableStateOf(DisplayChoice.INITIAL)
        private set

    /** False until the officer continues past the surface. Never reset — see the class doc. */
    var answered by mutableStateOf(false)
        private set

    fun setKeepScreenOn(on: Boolean) {
        choice = choice.copy(keepScreenOn = on)
    }

    fun setFullBrightness(on: Boolean) {
        choice = choice.copy(fullBrightness = on)
    }

    fun confirm() {
        answered = true
    }
}
