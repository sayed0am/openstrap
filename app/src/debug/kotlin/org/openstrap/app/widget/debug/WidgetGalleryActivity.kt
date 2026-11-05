package org.openstrap.app.widget.debug

import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.util.SizeF
import android.util.TypedValue
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import org.openstrap.app.ui.glow.draw.GlowMood
import org.openstrap.app.data.WidgetKind
import org.openstrap.app.data.catching
import org.openstrap.app.widget.WidgetRefresh
import org.openstrap.app.widget.WidgetSizes
import org.openstrap.app.widget.widgets

/**
 * Debug builds only: a real widget hosted at exactly its three sizes (Small, Wide, Large) on black, for checking on
 * the phone over adb. Binding needs the permission first:
 *
 *     adb shell appwidget grantbind --package org.openstrap.app --user 0
 *     adb shell am start -n org.openstrap.app/.widget.debug.WidgetGalleryActivity --es widget steps --es mood night
 *
 * `widget` is the kind to host (moment, recovery, steps, sleep, start), Recovery without it. `mood` is day, evening, night or auto
 * (the moment's). Sleep is always night and ignores it. `--es pin sleep` asks the launcher to pin a widget of that
 * kind to the home screen. `--es cells oneui` hosts at One UI's real cells (2 × 2 about 180 × 220, 4 × 2 about
 * 353 × 220, 4 × 3 about 353 × 330) instead of the three minimums, to see what people see; `--es cells min` goes
 * back. For Moment, `--es moment Evening` shows that moment (a Moment name, `auto` for the clock's) and
 * `--es placed recovery,sleep` takes those widgets as placed for the duplicate rule (`none` for none, `auto` for the
 * launcher's). `--es strap stale` shows every widget's strap status line in a state (notpaired, low, stale, yesterday,
 * week, syncing, none for no line, auto for the strap's). The gallery's own hosted widgets count as placed too while it shows them. The ids it binds are deleted
 * when it closes or hosts another kind.
 */
class WidgetGalleryActivity : ComponentActivity() {
    private lateinit var host: AppWidgetHost
    private val bound = mutableListOf<Int>()
    private lateinit var column: LinearLayout
    private lateinit var hosted: LinearLayout
    private var kind = WidgetKind.Recovery
    private var sizes = SIZES

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        host = AppWidgetHost(this, HOST_ID)
        // Ids from a run that never reached onDestroy (an install kills the process) stay bound and count as
        // placed, so every widget update would render them all: clear the host's leftovers first.
        host.deleteHost()
        applyMood(intent.getStringExtra(EXTRA_MOOD))
        applyCells(intent.getStringExtra(EXTRA_CELLS))
        applyMoment(intent)
        applyStrap(intent)
        kind = kindOf(intent.getStringExtra(EXTRA_WIDGET)) ?: WidgetKind.Recovery
        column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(48), dp(16), dp(48))
        }
        setContentView(ScrollView(this).apply {
            setBackgroundColor(android.graphics.Color.BLACK)
            addView(column)
        })
        column.addView(controls())
        hosted = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        column.addView(hosted)
        bindAll()
        kindOf(intent.getStringExtra(EXTRA_PIN))?.let(::requestPin)
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val next = kindOf(intent.getStringExtra(EXTRA_WIDGET))
        if (intent.hasExtra(EXTRA_MOOD)) applyMood(intent.getStringExtra(EXTRA_MOOD))
        val cellsChanged = intent.hasExtra(EXTRA_CELLS) && applyCells(intent.getStringExtra(EXTRA_CELLS))
        val momentChanged = applyMoment(intent) or applyStrap(intent)
        when {
            next != null && next != kind -> show(next)
            cellsChanged -> show(kind)
            intent.hasExtra(EXTRA_MOOD) || momentChanged -> refresh()
        }
        kindOf(intent.getStringExtra(EXTRA_PIN))?.let(::requestPin)
    }

    override fun onStart() {
        super.onStart()
        host.startListening()
    }

    override fun onStop() {
        host.stopListening()
        super.onStop()
    }

    override fun onDestroy() {
        unbindAll()
        // Placed widgets go back to the moment's mood, and Moment to the clock's moment and the launcher's widgets.
        if (WidgetRefresh.debugMood != null || WidgetRefresh.debugMoment != null || WidgetRefresh.debugPlaced != null || WidgetRefresh.debugStrap != null) {
            WidgetRefresh.debugMood = null
            WidgetRefresh.debugMoment = null
            WidgetRefresh.debugPlaced = null
            WidgetRefresh.debugStrap = null
            widgets.changedAll()
        }
        super.onDestroy()
    }

    /** The kind named [name] ("steps", "recovery", "sleep", "start") that has a widget yet; null for none. */
    private fun kindOf(name: String?): WidgetKind? =
        WidgetKind.entries.firstOrNull { it.name.equals(name, ignoreCase = true) }?.takeIf { widgets.registration(it) != null }

    /** Hosts [next] in place of the kind shown. */
    private fun show(next: WidgetKind) {
        unbindAll()
        kind = next
        bindAll()
    }

    private fun unbindAll() {
        bound.forEach { host.deleteAppWidgetId(it) }
        bound.clear()
        hosted.removeAllViews()
    }

    /** Binds an id per size and hosts its view at that size exactly. */
    private fun bindAll() {
        val manager = AppWidgetManager.getInstance(this)
        val registration = widgets.registration(kind) ?: run { note("No $kind widget yet."); return }
        val provider = ComponentName(this, registration.receiver)
        val info: AppWidgetProviderInfo? = manager.getInstalledProvidersForPackage(packageName, null).firstOrNull { it.provider == provider }
        if (info == null) { note("The $kind widget's provider isn't installed."); return }
        for ((name, size) in sizes) {
            val id = host.allocateAppWidgetId()
            if (!manager.bindAppWidgetIdIfAllowed(id, provider)) {
                host.deleteAppWidgetId(id)
                note("Not allowed to bind. Run: adb shell appwidget grantbind --package $packageName --user 0")
                return
            }
            bound += id
            manager.updateAppWidgetOptions(id, sizeOptions(size))
            val view: AppWidgetHostView = host.createView(applicationContext, id, info).apply { setPadding(0, 0, 0, 0) }
            label("$kind $name ${size.width.value.toInt()} × ${size.height.value.toInt()}")
            hosted.addView(view, LinearLayout.LayoutParams(dp(size.width.value), dp(size.height.value)).apply { bottomMargin = dp(24) })
        }
        refresh()
    }

    /** The size bundle Glance reads to pick its layout: the size list, and the min and max for launchers without it. */
    private fun sizeOptions(size: DpSize): Bundle = Bundle().apply {
        val w = size.width.value.toInt()
        val h = size.height.value.toInt()
        putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, w)
        putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, w)
        putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, h)
        putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, h)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            putParcelableArrayList(AppWidgetManager.OPTION_APPWIDGET_SIZES, arrayListOf(SizeF(w.toFloat(), h.toFloat())))
        }
    }

    /** Loads the widget again and renders every bound id. */
    private fun refresh() {
        val ids = bound.toList()
        val shown = kind
        val widget = widgets.registration(shown)?.widget ?: return
        lifecycleScope.launch {
            catching {
                WidgetRefresh.bump(shown)
                val glance = GlanceAppWidgetManager(applicationContext)
                ids.forEach { widget().update(applicationContext, glance.getGlanceIdBy(it)) }
            }.onFailure { Log.w(TAG, "widget gallery: update failed: $it") }
        }
    }

    /** "oneui" for One UI's cells, anything else for the minimums; true when that changed what's hosted. */
    private fun applyCells(name: String?): Boolean {
        val next = if (name.equals("oneui", ignoreCase = true)) ONE_UI_SIZES else SIZES
        val changed = next != sizes
        sizes = next
        return changed
    }

    /** The `moment` and `placed` extras, for Moment; true when either was given. */
    private fun applyMoment(intent: android.content.Intent): Boolean {
        val moment = intent.getStringExtra(EXTRA_MOMENT)
        val placed = intent.getStringExtra(EXTRA_PLACED)
        if (moment != null) {
            WidgetRefresh.debugMoment = org.openstrap.app.data.Moment.entries.firstOrNull { it.name.equals(moment, ignoreCase = true) }
        }
        if (placed != null) {
            WidgetRefresh.debugPlaced = when (placed.lowercase()) {
                "auto" -> null
                "none", "" -> emptySet()
                else -> placed.split(',').mapNotNull { n -> WidgetKind.entries.firstOrNull { it.name.equals(n.trim(), ignoreCase = true) } }.toSet()
            }
        }
        return moment != null || placed != null
    }

    /** The `strap` extra: the status line every widget shows; true when it was given. */
    private fun applyStrap(intent: android.content.Intent): Boolean {
        val strap = intent.getStringExtra(EXTRA_STRAP) ?: return false
        WidgetRefresh.debugStrap = strap.takeUnless { it.equals("auto", ignoreCase = true) }
        return true
    }

    private fun applyMood(name: String?) {
        WidgetRefresh.debugMood = when (name?.lowercase()) {
            null, "", "auto" -> null
            else -> GlowMood.entries.firstOrNull { it.name.equals(name, ignoreCase = true) }
        }
    }

    /** Asks the launcher to pin a widget of [pin] to the home screen. */
    private fun requestPin(pin: WidgetKind) {
        val manager = AppWidgetManager.getInstance(this)
        val registration = widgets.registration(pin) ?: return
        if (!manager.isRequestPinAppWidgetSupported) {
            Toast.makeText(this, "This launcher can't pin widgets", Toast.LENGTH_SHORT).show()
            return
        }
        manager.requestPinAppWidget(ComponentName(this, registration.receiver), null, null)
    }

    /** A button per kind, mood buttons and Pin, for checking by hand. */
    private fun controls() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(LinearLayout(this@WidgetGalleryActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            WidgetKind.entries.filter { widgets.registration(it) != null }.forEach { k ->
                addView(Button(this@WidgetGalleryActivity).apply {
                    text = k.name
                    setOnClickListener { if (k != kind) show(k) }
                })
            }
        })
        addView(moodRow())
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(16) }
    }

    private fun moodRow() = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        listOf("Auto" to "auto", "Day" to "day", "Evening" to "evening", "Night" to "night").forEach { (text, mood) ->
            addView(Button(this@WidgetGalleryActivity).apply {
                this.text = text
                setOnClickListener { applyMood(mood); refresh() }
            })
        }
        addView(Button(this@WidgetGalleryActivity).apply {
            text = "Pin"
            setOnClickListener { requestPin(kind) }
        })
    }

    private fun label(text: String) = hosted.addView(TextView(this).apply {
        this.text = text
        setTextColor(0x99FFFFFF.toInt())
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        setPadding(0, 0, 0, dp(8))
    })

    private fun note(text: String) {
        Log.w(TAG, "widget gallery: $text")
        label(text)
    }

    /** Rounded up, so a hosted view is never a fraction of a dp short of its size and laid out as a smaller one. */
    private fun dp(v: Float): Int = kotlin.math.ceil(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)).toInt()
    private fun dp(v: Int): Int = dp(v.toFloat())

    private companion object {
        const val TAG = "OpenStrap"
        const val HOST_ID = 0x5753
        const val EXTRA_MOOD = "mood"
        const val EXTRA_PIN = "pin"
        const val EXTRA_WIDGET = "widget"
        const val EXTRA_CELLS = "cells"
        const val EXTRA_MOMENT = "moment"
        const val EXTRA_PLACED = "placed"
        const val EXTRA_STRAP = "strap"
        val SIZES = listOf("Small" to WidgetSizes.Small, "Wide" to WidgetSizes.Wide, "Large" to WidgetSizes.Large)

        /** One UI's cells on the phone, roughly (spec 2.1): what the three layouts stretch to there. */
        val ONE_UI_SIZES = listOf(
            "Small (One UI 2 × 2)" to DpSize(180.dp, 220.dp),
            "Wide (One UI 4 × 2)" to DpSize(353.dp, 220.dp),
            "Large (One UI 4 × 3)" to DpSize(353.dp, 330.dp),
        )
    }
}
