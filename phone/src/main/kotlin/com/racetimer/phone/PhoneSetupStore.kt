package com.racetimer.phone

import android.content.Context
import com.racetimer.android.PairRaces
import com.racetimer.shared.BuiltInSequences
import com.racetimer.shared.SetupChoice
import com.racetimer.shared.leadInBaseId

/**
 * The phone's pre-start setup as the pair reads and writes it (#221): the pick it opens on and the
 * alert its lead-in picker opens on, in the preferences #209 and #207 already keep them in.
 *
 * Through [PhoneRacePersistence] rather than beside it, which keeps the phone's one sanctioned store
 * the one place that touches preferences (`ModuleBoundaryTest`).
 */
internal object PhoneSetupStore : PairRaces.SetupStore {

    /**
     * What the phone opens on: the remembered pick when it still names a sequence, and otherwise the
     * first the console offers — the launch plan's own fallback, so the two cannot disagree.
     */
    override fun load(context: Context): SetupChoice {
        val persistence = PhoneRacePersistence(context)
        val picked = persistence.pickedSequenceId()?.takeIf { BuiltInSequences.resolve(it) != null }
            ?: PhoneRaceRunner.CONSOLE_SEQUENCES.first().id
        return SetupChoice(leadInBaseId(picked), persistence.lastBoxAlertSeconds())
    }

    override fun save(context: Context, choice: SetupChoice) {
        val persistence = PhoneRacePersistence(context)
        persistence.savePickedSequenceId(choice.sequenceId)
        persistence.saveLastBoxAlertSeconds(choice.boxAlertSeconds)
    }
}
