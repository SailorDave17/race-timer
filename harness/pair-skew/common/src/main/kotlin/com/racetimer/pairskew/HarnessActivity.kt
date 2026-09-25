package com.racetimer.pairskew

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.racetimer.pairskew.protocol.Side

/**
 * One screen, the same on both devices: what the engine is doing, and the three things a run needs
 * from the owner — start or stop rounds, ask one burst, arm a gun.
 */
class HarnessActivity : Activity() {

    private val ui = Handler(Looper.getMainLooper())
    private lateinit var engine: Engine
    private lateinit var status: TextView
    private lateinit var rounds: Button
    private var plainText = Color.WHITE

    private val tick = object : Runnable {
        override fun run() {
            engine.refresh()
            render()
            ui.postDelayed(this, 500L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The screen stays on for the whole run. A watch that sleeps falls back to its watch face, and
        // a backgrounded app can be frozen, which would stop it answering rounds.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        engine = Engine.get(this)
        val watch = engine.side == Side.WEAR
        val density = resources.displayMetrics.density
        fun px(dp: Int) = (dp * density).toInt()

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            // On a round watch every line has to stay well inside the circle.
            if (watch) setPadding(px(28), px(30), px(28), px(72)) else setPadding(px(16), px(16), px(16), px(24))
        }
        column.addView(TextView(this).apply {
            text = "PairSkew · ${engine.side.wire}"
            textSize = if (watch) 13f else 20f
            gravity = Gravity.CENTER
        })
        status = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = if (watch) 10f else 14f
            gravity = if (watch) Gravity.CENTER_HORIZONTAL else Gravity.START
            setPadding(px(4), px(6), px(4), px(6))
        }
        plainText = status.currentTextColor
        column.addView(status, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        rounds = button(column, "Start rounds") { engine.toggleAuto() }
        button(column, "Burst ×5") { engine.burstOnce() }
        button(column, "Arm gun +3:00") { engine.armGun() }
        setContentView(ScrollView(this).apply {
            fitsSystemWindows = true
            addView(column)
        })
    }

    override fun onResume() {
        super.onResume()
        ui.post(tick)
    }

    override fun onPause() {
        ui.removeCallbacks(tick)
        super.onPause()
    }

    private fun button(column: LinearLayout, label: String, onClick: () -> Unit): Button =
        Button(this).apply {
            text = label
            isAllCaps = false
            setOnClickListener { onClick() }
            column.addView(this, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }

    private fun render() {
        val s = engine.snapshot
        status.text = s.text
        rounds.text = if (s.auto) "Stop rounds" else "Start rounds"
        val flashing = SystemClock.elapsedRealtime() < s.flashUntilMs
        status.setBackgroundColor(if (flashing) Color.rgb(0xFF, 0xC1, 0x07) else Color.TRANSPARENT)
        status.setTextColor(if (flashing) Color.BLACK else plainText)
    }
}
