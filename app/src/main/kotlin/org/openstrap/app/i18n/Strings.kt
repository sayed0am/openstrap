package org.openstrap.app.i18n

import android.content.Context
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import java.util.ServiceLoader

/**
 * The app's text for plain Kotlin code, the copy objects, notifiers and widget models, which has no composition to call
 * `stringResource` from. Each call means what the same `Resources` call means, so text and formatting follow the app's
 * language: [format] runs `String.format` in the resources' locale even with no arguments, and [plural] leaves the
 * text unformatted when no arguments are given, as `getQuantityString(id, count)` does. Pass the count again in
 * [args] for a `%d`.
 *
 * Copy takes it as its last parameter, defaulting to [app], the way it already takes the zone and the locale
 * (docs/audits/2026-11-04-i18n-plan.md, How to move a file).
 */
interface Strings {
    fun get(@StringRes id: Int): String

    fun format(@StringRes id: Int, vararg args: Any): String

    fun plural(@PluralsRes id: Int, count: Int, vararg args: Any): String

    companion object {
        @Volatile private var installed: Strings? = null

        /**
         * The one the unit tests' classpath offers. Plain JVM tests have no `Resources`, so the test sources register
         * a double that reads `res/values` from disk, and the English asserts run with no setup. The app has none.
         */
        private val offered: Strings? by lazy {
            ServiceLoader.load(Strings::class.java, Strings::class.java.classLoader).firstOrNull()
        }

        /** The app's strings, installed by `AppContainer` as the app starts, before anything reads text. */
        val app: Strings
            get() = installed ?: offered ?: error("Strings.app read before AppContainer installed it")

        fun install(strings: Strings) {
            installed = strings
        }
    }
}

/**
 * [Strings] from the application context's resources. The context is read on each call rather than its `Resources`
 * kept, so a change of the app's language (Android 13 and up) is followed without a restart.
 */
// Each call hands its arguments on to Resources unchanged, and text is read a few times a screen, not in a loop.
@Suppress("SpreadOperator")
class ResourceStrings(private val context: Context) : Strings {
    override fun get(id: Int): String = context.resources.getString(id)

    override fun format(id: Int, vararg args: Any): String = context.resources.getString(id, *args)

    override fun plural(id: Int, count: Int, vararg args: Any): String =
        if (args.isEmpty()) context.resources.getQuantityString(id, count) else context.resources.getQuantityString(id, count, *args)
}
