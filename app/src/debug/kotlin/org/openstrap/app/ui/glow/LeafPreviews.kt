package org.openstrap.app.ui.glow

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import org.openstrap.app.ui.theme.OpenStrapTheme
import java.time.LocalDate
import java.util.Locale

/*
 * Shared pieces for the Android Studio previews of the glow leaves (audit K12). They live in the debug source set, so
 * they never reach the release APK, and they read only fixtures (WidgetSamples, fixed values), never a live container.
 * The app has one theme, dark, so each preview comes in English and in Arabic, the right to left check.
 */

/** A leaf in English and in Arabic, right to left, on the app's black. */
// detekt's compose rules want a Preview prefix here and Slack's lint wants a Previews suffix: lint's name is kept.
@Suppress("PreviewAnnotationNaming")
@Preview(name = "en", widthDp = 360, showBackground = true, backgroundColor = 0xFF000000)
@Preview(name = "ar, right to left", widthDp = 360, showBackground = true, backgroundColor = 0xFF000000, locale = "ar")
annotation class LeafPreviews

/** A whole page, as a phone shows it, in English and in Arabic. */
// As above, lint's Previews suffix over detekt's Preview prefix.
@Suppress("PreviewAnnotationNaming")
@Preview(name = "en", widthDp = 411, heightDp = 860, showBackground = true, backgroundColor = 0xFF000000)
@Preview(name = "ar, right to left", widthDp = 411, heightDp = 860, showBackground = true, backgroundColor = 0xFF000000, locale = "ar")
annotation class PagePreviews

/** The fixtures' day, fixed so a preview looks the same every day it's opened. */
internal val PreviewDay: LocalDate = LocalDate.of(2026, 10, 30)

/** The locale the preview runs in, for the fixtures' words and day letters. */
@Composable
@ReadOnlyComposable
internal fun previewLocale(): Locale = LocalConfiguration.current.locales[0]

/** [en] or, in the Arabic preview, [ar], so the right to left variant also carries right to left text. */
@Composable
@ReadOnlyComposable
internal fun say(en: String, ar: String): String = if (previewLocale().language == "ar") ar else en

/** The app's theme and black page around a leaf, with the screen gutter. */
@Composable
internal fun PreviewPage(content: @Composable ColumnScope.() -> Unit) {
    OpenStrapTheme {
        Column(Modifier.background(Color.Black).padding(16.dp), content = content)
    }
}
