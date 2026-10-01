package dev.cued.app.widget

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.Preferences
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalSize
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.glance.background
import androidx.glance.currentState
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.ContentScale
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import androidx.media3.common.util.UnstableApi
import dev.cued.app.MainActivity
import dev.cued.app.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The home-screen widget. One provider, three layouts chosen by the size the
 * launcher gives it:
 *
 *   bar   (1 row)   cover · title · ⏮ ⏯ ⏭
 *   card  (2 rows)  cover · title / artist, controls underneath
 *   tall  (3+ rows) big cover with text and controls below
 *
 * Tapping the cover or text opens Now Playing in the app.
 */
@UnstableApi
class CuedWidget : GlanceAppWidget() {
    override val stateDefinition = PreferencesGlanceStateDefinition
    override val sizeMode = SizeMode.Responsive(setOf(BAR_SMALL, BAR, CARD, TALL))

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val art = withContext(Dispatchers.IO) { WidgetState.loadArt(context) }
        provideContent {
            val prefs = currentState<Preferences>()
            val hasTrack = prefs[WidgetState.HAS_TRACK] ?: false
            val title = prefs[WidgetState.TITLE].orEmpty().ifBlank { "Nothing playing" }
            val artist = prefs[WidgetState.ARTIST].orEmpty().ifBlank { if (hasTrack) "" else "Tap to open CUEd" }
            val playing = prefs[WidgetState.PLAYING] ?: false
            val size = LocalSize.current
            val open = actionStartActivity<MainActivity>()
            Box(
                GlanceModifier.fillMaxSize().background(ImageProvider(R.drawable.widget_bg)).cornerRadius(20.dp).padding(10.dp).clickable(open),
                contentAlignment = Alignment.Center,
            ) {
                when {
                    size.height >= TALL.height -> Tall(art, title, artist, playing, hasTrack)
                    size.height >= CARD.height -> Card(art, title, artist, playing, hasTrack)
                    else -> Bar(art, title, artist, playing, hasTrack, compact = size.width < BAR.width)
                }
            }
        }
    }

    @Composable
    private fun Bar(art: Bitmap?, title: String, artist: String, playing: Boolean, hasTrack: Boolean, compact: Boolean) {
        Row(GlanceModifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
            Cover(art, 40.dp)
            Spacer(GlanceModifier.width(10.dp))
            Column(GlanceModifier.defaultWeight()) {
                Text(title, style = titleStyle(14), maxLines = 1)
                if (artist.isNotBlank()) Text(artist, style = subStyle(12), maxLines = 1)
            }
            Spacer(GlanceModifier.width(6.dp))
            Controls(playing, hasTrack, 34.dp, showSkip = !compact)
        }
    }

    @Composable
    private fun Card(art: Bitmap?, title: String, artist: String, playing: Boolean, hasTrack: Boolean) {
        Column(GlanceModifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
            Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Cover(art, 56.dp)
                Spacer(GlanceModifier.width(12.dp))
                Column(GlanceModifier.defaultWeight()) {
                    Text(title, style = titleStyle(15), maxLines = 1)
                    if (artist.isNotBlank()) Text(artist, style = subStyle(13), maxLines = 1)
                }
            }
            Spacer(GlanceModifier.height(10.dp))
            Row(GlanceModifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) { Controls(playing, hasTrack, 40.dp, showSkip = true) }
        }
    }

    @Composable
    private fun Tall(art: Bitmap?, title: String, artist: String, playing: Boolean, hasTrack: Boolean) {
        val size = LocalSize.current
        val coverSide = minOf(size.width - 20.dp, size.height - 110.dp)
        Column(GlanceModifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalAlignment = Alignment.CenterVertically) {
            Cover(art, coverSide)
            Spacer(GlanceModifier.height(8.dp))
            Text(title, style = titleStyle(15), maxLines = 1)
            if (artist.isNotBlank()) Text(artist, style = subStyle(13), maxLines = 1)
            Spacer(GlanceModifier.height(8.dp))
            Row(horizontalAlignment = Alignment.CenterHorizontally) { Controls(playing, hasTrack, 44.dp, showSkip = true) }
        }
    }

    @Composable
    private fun Cover(art: Bitmap?, side: androidx.compose.ui.unit.Dp) {
        val m = GlanceModifier.size(side).cornerRadius(10.dp)
        if (art != null) Image(ImageProvider(art), contentDescription = null, modifier = m, contentScale = ContentScale.Crop)
        else Box(m.background(ColorProvider(Ink3)), contentAlignment = Alignment.Center) {
            Image(ImageProvider(R.drawable.ic_notification), contentDescription = null, modifier = GlanceModifier.size(side / 2), colorFilter = ColorFilter.tint(ColorProvider(Muted)))
        }
    }

    @Composable
    private fun Controls(playing: Boolean, hasTrack: Boolean, side: androidx.compose.ui.unit.Dp, showSkip: Boolean) {
        val tint = ColorProvider(if (hasTrack) Mist else Muted)
        if (showSkip) {
            Image(ImageProvider(R.drawable.ic_skip_previous), "Previous", GlanceModifier.size(side).padding(6.dp).clickable(actionRunCallback<PreviousAction>()), colorFilter = ColorFilter.tint(tint))
            Spacer(GlanceModifier.width(4.dp))
        }
        Box(GlanceModifier.size(side).background(ImageProvider(R.drawable.widget_button_bg)).clickable(actionRunCallback<PlayPauseAction>()), contentAlignment = Alignment.Center) {
            Image(ImageProvider(if (playing) R.drawable.ic_pause else R.drawable.ic_play), if (playing) "Pause" else "Play", GlanceModifier.size(side - 12.dp), colorFilter = ColorFilter.tint(ColorProvider(Ink)))
        }
        if (showSkip) {
            Spacer(GlanceModifier.width(4.dp))
            Image(ImageProvider(R.drawable.ic_skip_next), "Next", GlanceModifier.size(side).padding(6.dp).clickable(actionRunCallback<NextAction>()), colorFilter = ColorFilter.tint(tint))
        }
    }

    private fun titleStyle(sp: Int) = TextStyle(color = ColorProvider(Mist), fontSize = sp.sp, fontWeight = FontWeight.Bold)
    private fun subStyle(sp: Int) = TextStyle(color = ColorProvider(Muted), fontSize = sp.sp)

    companion object {
        val BAR_SMALL = DpSize(110.dp, 48.dp)
        val BAR = DpSize(200.dp, 48.dp)
        val CARD = DpSize(200.dp, 100.dp)
        val TALL = DpSize(200.dp, 190.dp)
        private val Ink = Color(0xFF0E0F13)
        private val Ink3 = Color(0xFF222530)
        private val Mist = Color(0xFFE8EAF0)
        private val Muted = Color(0xFF8A90A3)
    }
}
