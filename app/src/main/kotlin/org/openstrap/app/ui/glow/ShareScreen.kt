package org.openstrap.app.ui.glow

import org.openstrap.app.R
import androidx.compose.ui.res.stringResource
import android.content.ClipData
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.openstrap.app.data.catching
import org.openstrap.app.data.Route
import org.openstrap.app.data.WorkoutAnalysis
import org.openstrap.app.data.Workouts
import org.openstrap.app.ui.theme.AppIcons
import org.openstrap.app.presentation.EarnedLine
import org.openstrap.app.ui.workout.FamilySummary
import org.openstrap.app.ui.workout.PhoneSummary

/*
 * The share screen (workouts spec 2.9, boards ShareRun to ShareGame): the workout's story card, a photo of yours
 * behind it if you like, a run's route over it, then Save to the gallery or Share to another app, both as the card
 * drawn at 1080 by 1920.
 */

private const val TAG = "ShareScreen"

/** The saved picture's size in px: three to each of the card's dp. */
private const val STORY_PX_PER_DP = 3f

/** Room under the card for the photo button and its gap. */
private val PhotoRoom = 64.dp

/** How long "Saved" stays on the button. */
private const val SAVED_MS = 2_000L

/** What the card is being drawn for. */
private enum class Export { Save, Share }

/**
 * The share screen for [summary]'s workout, from the [hero] its summary shows and what it [earned]. [liveRoute] is the
 * route still in memory when the workout's wasn't kept; [workouts] are all of them, for a game's usual Load.
 */
@Composable
internal fun WorkoutShareScreen(
    summary: PhoneSummary, hero: SummaryHero, earned: List<EarnedLine>, liveRoute: List<Route.Point>, workouts: List<WorkoutAnalysis>,
    contentPadding: PaddingValues, onClose: () -> Unit,
) {
    val a = summary.analysis
    val card = remember(summary, hero, earned, workouts) {
        val usualLoad = Workouts.usualLoad(workouts.filter { it.workout.start != a.workout.start }.map { it.workout.trainingLoad })
        storyCard(FamilySummary.familyOf(a.workout.sportType, summary.structure), summary, hero, earned, usualLoad, System.currentTimeMillis() / 1000)
    }
    val route = remember(summary, liveRoute) { summary.route.ifEmpty { liveRoute.filter { it.t in a.shownStart..a.end } } }
    ShareScreen(card, route, "unstrap_${a.workout.start}.png", contentPadding, onClose)
}

/**
 * Hides the app's tab bar while it's given true: the share screen covers the whole screen, even from a page that sits
 * over the tabs (the weekly recap).
 */
val LocalHideTabBar = androidx.compose.runtime.staticCompositionLocalOf<(Boolean) -> Unit> { {} }

/** The share screen for a week's [card], from the recap's Share week. */
@Composable
internal fun WeekShareScreen(card: StoryCard.Week, fileName: String, onClose: () -> Unit) =
    // Just the navigation bar (the page adds the status bar itself): the recap's padding leaves room for the tab bar
    // this screen hides.
    ShareScreen(card, emptyList(), fileName, androidx.compose.foundation.layout.WindowInsets.navigationBars.asPaddingValues(), onClose)

/**
 * The card for [card] in the middle at 9 by 16, close and the title over it, the photo button and a run's route
 * choices under it, then Save and Share. [route] is the workout's, drawn only on a run; the picture saves as [fileName].
 */
@Composable
private fun ShareScreen(card: StoryCard, route: List<Route.Point>, fileName: String, contentPadding: PaddingValues, onClose: () -> Unit) {
    BackHandler(onBack = onClose)
    val hideTabBar = LocalHideTabBar.current
    androidx.compose.runtime.DisposableEffect(Unit) {
        hideTabBar(true)
        onDispose { hideTabBar(false) }
    }
    val context = LocalContext.current
    var photoUri by rememberSaveable { mutableStateOf<Uri?>(null) }
    val photo by produceState<ImageBitmap?>(null, photoUri) { value = photoUri?.let { decodePhoto(context, it) } }
    var routeMode by rememberSaveable { mutableStateOf(StoryRoute.Line) }
    var hideEnds by rememberSaveable { mutableStateOf(true) }
    val hasRoute = card is StoryCard.Run && Route.mappable(route)
    val shown = remember(route, hasRoute, routeMode, hideEnds) {
        if (!hasRoute || routeMode == StoryRoute.Off) emptyList() else if (hideEnds) StoryRouteShape.trimEnds(route) else route
    }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> if (uri != null) photoUri = uri }

    // Save and Share draw the card again off screen at 1080 by 1920, record it, and turn the recording into a PNG.
    var export by remember { mutableStateOf<Export?>(null) }
    var saved by remember { mutableStateOf<Boolean?>(null) }
    val layer = rememberGraphicsLayer()
    val recorded = remember(export) { CompletableDeferred<Unit>() }
    fun save() {
        export = Export.Save
    }
    LaunchedEffect(export) {
        val doing = export ?: return@LaunchedEffect
        // Clearing export ends this effect, so it waits until the picture has gone where it's going.
        try {
            recorded.await()
            val picture = catching { layer.toImageBitmap().asAndroidBitmap().let { if (it.config == Bitmap.Config.HARDWARE) it.copy(Bitmap.Config.ARGB_8888, false) else it } }
                .onFailure { Log.w(TAG, "drawing the card failed", it) }.getOrNull()
            when {
                picture == null -> if (doing == Export.Save) saved = false
                doing == Export.Save -> saved = withContext(Dispatchers.IO) { StoryFiles.saveToGallery(context, picture, fileName) }
                else -> withContext(Dispatchers.IO) { StoryFiles.forSharing(context, picture) }?.let { uri ->
                    val send = Intent(Intent.ACTION_SEND).setType("image/png").putExtra(Intent.EXTRA_STREAM, uri)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION).also { it.clipData = ClipData.newRawUri(null, uri) }
                    context.startActivity(Intent.createChooser(send, null))
                }
            }
        } finally {
            export = null
        }
    }
    LaunchedEffect(saved) {
        if (saved == null) return@LaunchedEffect
        delay(SAVED_MS)
        saved = null
    }

    WorkoutPage(
        Color.Transparent, contentPadding, scroll = false,
        pinned = {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                val saveLabel = stringResource(
                    when (saved) {
                        true -> R.string.glow_share_saved
                        false -> R.string.glow_share_not_saved
                        null -> R.string.glow_save
                    },
                )
                WorkoutButton(saveLabel, Raised, Color.White, { if (export == null) save() }, Modifier.weight(1f), icon = if (saved == true) AppIcons.Tick02 else AppIcons.DownloadSimple)
                WorkoutButton(
                    stringResource(R.string.glow_share), Color.White, Color.Black, { if (export == null) export = Export.Share },
                    Modifier.weight(1f), icon = AppIcons.UploadSimple,
                )
            }
        },
    ) {
        // Close on the left, the title in the middle (board ShareRun).
        Box(Modifier.fillMaxWidth().height(44.dp), contentAlignment = Alignment.Center) {
            val close = stringResource(R.string.glow_close)
            Box(Modifier.align(Alignment.CenterStart)) { RoundIconButton(AppIcons.X, close, onClose, size = 44.dp) }
            Text(stringResource(R.string.glow_share), color = Color.White, style = GlowType.cardTitle, maxLines = 1)
            // The picture for Save and Share: the same card at 1080 by 1920, recorded but never drawn on screen.
            if (export != null) Box(
                Modifier.layout { measurable, _ ->
                    val p = measurable.measure(Constraints())
                    layout(0, 0) { p.place(0, 0) }
                },
            ) {
                CompositionLocalProvider(LocalDensity provides Density(STORY_PX_PER_DP, 1f)) {
                    StoryCardView(card, photo, shown, Modifier.drawWithContent { layer.record { this@drawWithContent.drawContent() }; recorded.complete(Unit) })
                }
            }
        }
        // The card as large as the space allows, at 9 by 16, with the photo button right under it, so the button
        // never sits in the fade over Save and Share.
        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.TopCenter) {
            val height = minOf(maxHeight - PhotoRoom, maxWidth * 16f / 9f, MaxPreviewHeight)
            val width = height * 9f / 16f
            val density = LocalDensity.current
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.size(width, height).clip(GlowLayout.TileShape)) {
                    CompositionLocalProvider(LocalDensity provides Density(density.density * (width / StoryWidth), 1f)) {
                        StoryCardView(card, photo, shown)
                    }
                    if (photo != null) Box(Modifier.align(Alignment.TopEnd).padding(10.dp)) {
                        RoundIconButton(AppIcons.X, stringResource(R.string.glow_share_remove_photo), { photoUri = null }, size = 32.dp)
                    }
                }
                Spacer(Modifier.height(12.dp))
                IconPill(
                    stringResource(if (photo != null) R.string.glow_share_change_photo else R.string.glow_share_add_photo),
                    { pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }, icon = AppIcons.Image,
                )
            }
        }
        if (hasRoute) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.glow_share_route), color = Color.White.copy(alpha = 0.6f), style = GlowType.secondary,
                    maxLines = 1, modifier = Modifier.width(84.dp),
                )
                val routes = StoryRoute.entries
                val routeNames = routes.map {
                    stringResource(
                        when (it) {
                            StoryRoute.Off -> R.string.glow_share_route_off
                            StoryRoute.Line -> R.string.glow_share_route_line
                        },
                    )
                }
                GlowSegments(routeNames, routeMode.ordinal, { routeMode = routes[it] }, Modifier.weight(1f))
            }
            if (routeMode == StoryRoute.Line) {
                PageSwitchRow(stringResource(R.string.glow_share_hide_ends), null, hideEnds, { hideEnds = it }, divider = false)
            }
        }
    }
}

/** The tallest the preview grows on a big screen. */
private val MaxPreviewHeight = 560.dp

/** [uri] from the photo picker as a bitmap no bigger than the saved picture needs, upright; null when it can't be read. */
private suspend fun decodePhoto(context: Context, uri: Uri): ImageBitmap? = withContext(Dispatchers.IO) {
    runCatching {
        val source = ImageDecoder.createSource(context.contentResolver, uri)
        ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            val w = info.size.width
            val h = info.size.height
            // Enough to cover 1080 by 1920 once cropped, never more.
            val scale = maxOf(1080f / w, 1920f / h)
            if (scale < 1f) decoder.setTargetSize((w * scale).toInt().coerceAtLeast(1), (h * scale).toInt().coerceAtLeast(1))
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }.asImageBitmap()
    }.onFailure { Log.w(TAG, "reading the photo failed", it) }.getOrNull()
}

/** Where a card's picture goes: the gallery, or a cached file another app can read for a moment. */
internal object StoryFiles {
    /** The gallery folder, under Pictures. */
    const val FOLDER = "OpenStrap"

    /** The FileProvider's authority after the package name, as in the manifest. */
    const val AUTHORITY_SUFFIX = ".share"

    /** Writes [bitmap] as a PNG named [name] to Pictures/OpenStrap, and true once it's there. */
    fun saveToGallery(context: Context, bitmap: Bitmap, name: String): Boolean = runCatching {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/" + FOLDER)
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values) ?: return false
        // A half written picture is removed, and the failure goes on to the log below.
        var done = false
        try {
            val written = resolver.openOutputStream(uri)?.use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } == true
            check(written) { "nothing written" }
            resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
            done = true
        } finally {
            if (!done) resolver.delete(uri, null, null)
        }
        true
    }.onFailure { Log.w(TAG, "saving the card failed", it) }.getOrDefault(false)

    /** [bitmap] as a PNG in the cache, behind the app's FileProvider; null when it can't be written. */
    fun forSharing(context: Context, bitmap: Bitmap): Uri? = runCatching {
        val dir = File(context.cacheDir, "share").apply { mkdirs() }
        val file = File(dir, "workout.png")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        FileProvider.getUriForFile(context, context.packageName + AUTHORITY_SUFFIX, file)
    }.onFailure { Log.w(TAG, "writing the card to share failed", it) }.getOrNull()
}
