package org.openstrap.app

import android.content.Context
import android.content.res.Resources
import androidx.annotation.StringRes

/**
 * The app's words, from its string resources: in the phone's language, or the one picked for OpenStrap in the
 * system settings, and English otherwise. Most of the text is put together in plain Kotlin (the *Copy objects, the
 * data engines, notifications, widgets), away from any Context, so it's looked up here rather than with
 * stringResource. [OpenStrapApp] installs the resources as the process starts.
 *
 * Plain JVM unit tests have no resources: there the English is read from res/values/strings.xml, whose path the
 * Gradle test task passes in as the `openstrap.strings` system property.
 */
object Texts {
    @Volatile private var resources: Resources? = null

    fun install(context: Context) {
        // Early in attachBaseContext there's no application context yet: the base context's resources do.
        resources = (context.applicationContext ?: context).resources
    }

    internal fun raw(@StringRes id: Int): String = resources?.getString(id) ?: English.text(id)

    /** The English strings by resource id, for unit tests. */
    private object English {
        private val byId: Map<Int, String> by lazy {
            val path = System.getProperty("openstrap.strings") ?: error("Texts.install wasn't called, and no openstrap.strings for tests")
            val names = R.string::class.java.fields.associate { it.name to it.getInt(null) }
            val doc = javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(java.io.File(path))
            val nodes = doc.getElementsByTagName("string")
            buildMap {
                for (i in 0 until nodes.length) {
                    val el = nodes.item(i) as org.w3c.dom.Element
                    names[el.getAttribute("name")]?.let { put(it, unescape(el.textContent)) }
                }
            }
        }

        fun text(id: Int): String = byId[id] ?: error("No English string for resource $id")

        /** As aapt reads a value: quotes keep spaces and are dropped, backslash escapes. */
        private fun unescape(s: String): String {
            val v = if (s.length >= 2 && s.startsWith('"') && s.endsWith('"')) s.substring(1, s.length - 1) else s.trim()
            val out = StringBuilder()
            var i = 0
            while (i < v.length) {
                val c = v[i]
                if (c == '\\' && i + 1 < v.length) {
                    out.append(
                        when (val n = v[i + 1]) {
                            'n' -> '\n'
                            't' -> '\t'
                            else -> n
                        },
                    )
                    i += 2
                } else {
                    out.append(c); i++
                }
            }
            return out.toString()
        }
    }
}

/** The string [id] with [args] put in its `%1$s`-style places (and `%%` as a percent sign). */
fun tr(@StringRes id: Int, vararg args: Any?): String = String.format(Texts.raw(id), *args)

/** The string [id] as it's written, for a caller that formats it itself (`trRaw(id).format(locale, steps)`). */
fun trRaw(@StringRes id: Int): String = Texts.raw(id)
