package com.racetimer.pairskew.analysis

import com.racetimer.pairskew.protocol.Record
import java.io.File
import kotlin.system.exitProcess

/**
 * Reads PairSkew captures — the apps' own log files, logcat captures with their prefixes, in any mix
 * — and writes the capture report.
 *
 *     analyse [--ppm=N] <report.md> <capture> [<capture>...]
 *
 * `--ppm` is the drift rate stated to translateGun wherever a bound is aged. It defaults to the rate the
 * harness stated on the devices; a lower one shows what a production link stating it would get.
 */
fun main(args: Array<String>) {
    val ppm = args.firstOrNull { it.startsWith("--ppm=") }?.substringAfter('=')?.toLongOrNull()
    val paths = args.filterNot { it.startsWith("--") }
    if (paths.size < 2 || (ppm != null && ppm < 0) || args.any { it.startsWith("--") && ppm == null }) {
        System.err.println("usage: analyse [--ppm=N] <report.md> <capture> [<capture>...]")
        exitProcess(2)
    }
    val out = File(paths[0])
    val records = paths.drop(1).flatMap { path -> File(path).readLines().mapNotNull(Record::parse) }
    val capture = Capture.of(records)
    val analysis = if (ppm == null) Analysis(capture) else Analysis(capture, ppm = ppm)
    out.writeText(Report(analysis).markdown())
    println("${out.path}: ${capture.records.size} records, ${capture.rounds.size} rounds, ${capture.failures.size} failures, ${analysis.ppm} ppm")
}
