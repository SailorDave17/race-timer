package com.racetimer.phone.ui

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag

/**
 * Test tags for the confirm-end-race dialog (#281 AC 4), so its assertions find the two controls by
 * identity rather than by display text — the destructive one especially, since a test that located
 * it by wording would go quiet the day the wording changed.
 */
const val TAG_CONFIRM_END_RACE = "confirm-end-race"
const val TAG_KEEP_RUNNING_RACE = "confirm-keep-running"

/**
 * Ask before a sequence selection discards a race in progress (#281 AC 4).
 *
 * ### Why this exists at all
 *
 * `TimerEngine.load` resets the engine to IDLE unconditionally, so selecting a sequence is the same
 * call as discarding the race that is running. #281 measured the consequence: after a recreated
 * activity the officer's only obvious tap — the sequence they were already running, in the picker —
 * killed the live race and its cue queue with no warning and no undo. `PhoneRaceRunner.select`
 * now refuses that outright, and this dialog is the officer's route past the refusal.
 *
 * ### Why it is defence rather than a path
 *
 * #281 AC 1 puts a bind onto a live race straight on the timer screen, and Back is disabled while a
 * race is active — so after that story the picker is not reachable with a race in progress, and
 * this dialog should never render in the shipped app. It is kept because **the invariant would
 * otherwise live only in the navigation**, and navigation state held in a `remember` is precisely
 * what produced #281 in the first place. Two independent things now have to fail before a race can
 * be lost.
 *
 * ### Where it may appear, and the watch rule it does not break
 *
 * Only over the picker or the Custom stepper, never over a running countdown — the timer screen has
 * no control that selects a sequence. `docs/message-surface.md` rule 3 ("blocking is pre-start
 * only; once the sequence is running, nothing takes the screen") is a **watch** rule, as that
 * document's own title says, and its stated reason is a modal covering the readout the officer is
 * reading. Nothing here can cover a readout. The rule is honoured by construction rather than
 * argued around: the caller renders this from the picker branch and the phone has no tiered message
 * surface for it to belong to (see `TimerScreen`'s notice line).
 *
 * ### Which way a mis-tap falls
 *
 * Dismissing — a tap outside, or Back — **keeps the race**, which is the whole point: the officer
 * is on a boat, the phone is propped, and the recoverable outcome must be the one you get by
 * fumbling. Only [TAG_CONFIRM_END_RACE] destroys anything, and it is the plainer of the two
 * controls for the same reason.
 *
 * @param sequenceName the sequence the officer just picked — named so the question is about a
 *   concrete swap rather than an abstract warning.
 * @param onConfirm end the running race and take the new selection.
 * @param onDismiss leave the running race alone; the selection is discarded.
 */
@Composable
fun ConfirmEndRaceDialog(
    sequenceName: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = "A race is still running") },
        text = {
            // The consequence, not the cause — message-surface rule 5, which is a writing rule
            // rather than a surface rule and so carries across from the watch cleanly. Two things
            // are lost and the second is the one an officer would not think of.
            Text(
                text = "Starting $sequenceName ends it. The clock stops and the signals left " +
                    "in it will not sound.",
            )
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                modifier = Modifier.testTag(TAG_CONFIRM_END_RACE),
            ) {
                Text(
                    text = "End it and switch",
                    color = MaterialTheme.colorScheme.error,
                )
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.testTag(TAG_KEEP_RUNNING_RACE),
            ) {
                Text(text = "Keep running")
            }
        },
    )
}
