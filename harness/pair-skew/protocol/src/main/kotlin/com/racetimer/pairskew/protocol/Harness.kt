package com.racetimer.pairskew.protocol

/** What both apps and the analysis agree on. */
object Harness {
    const val TAG = "PairSkew"

    /** The capability each app advertises, so each side finds the node running the harness. */
    const val CAPABILITY = "pairskew"

    const val PATH_PING = "/pairskew/ping"
    const val PATH_PONG = "/pairskew/pong"
    const val PATH_RPC = "/pairskew/rpc"
    const val PATH_SAMPLE = "/pairskew/sample"
    const val PATH_GUN = "/pairskew/gun"

    /**
     * The drift rate the harness states to translateGun on the devices. It is the harness's own
     * parameter, chosen wide so the on-device translations stay sound while the real rate is unknown.
     * **It is not a proposal for production.** Measuring the rate production should state is part of
     * what the analysis is for.
     */
    const val STATED_DRIFT_PPM = 100L

    /** translateGun always takes a budget. The harness has none to give: D2 is what it measures for. */
    const val NO_BUDGET = Long.MAX_VALUE

    /** Rounds are asked in bursts, back to back, so the data holds both a cold link and a warm one. */
    const val BURST_SIZE = 5
    const val BURST_GAP_MS = 10_000L
    const val ROUND_TIMEOUT_MS = 5_000L

    /** How far ahead "Arm gun" puts the gun: room to drop the link and bring it back before it fires. */
    const val GUN_LEAD_MS = 180_000L

    const val STATE_EVERY_MS = 5_000L
}
