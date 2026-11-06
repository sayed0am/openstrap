package org.openstrap.app.ui.glow.pictures

/*
 * The pictures a picture card draws (spec 2.7), one composable per kind in [org.openstrap.app.ui.dashboard.PictureKind].
 * Each draws in its metric's ink at a Small or a Wide, never dotted or dashed, and says where its off part sits so the
 * card's rim lights there ([org.openstrap.app.ui.glow.Rim]). Line-like pictures live in LinePictures.kt, levels and
 * counts in LevelPictures.kt. The debug gallery shows every one: `--es section pictures`.
 */

/** How big a picture is drawn: on a Small (176 by 160 dp) or across a Wide. */
enum class PictureSize { Small, Wide }
