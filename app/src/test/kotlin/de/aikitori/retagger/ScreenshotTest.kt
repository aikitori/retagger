package de.aikitori.retagger

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.media.MediaFormat
import android.net.Uri
import androidx.compose.material3.SnackbarHostState
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import de.aikitori.retagger.core.Artwork
import de.aikitori.retagger.core.AudioInfo
import de.aikitori.retagger.core.Field
import de.aikitori.retagger.core.Tags
import de.aikitori.retagger.data.Entry
import de.aikitori.retagger.ui.AppViewModel
import de.aikitori.retagger.ui.BatchScreen
import de.aikitori.retagger.ui.BatchState
import de.aikitori.retagger.ui.ConvertDialog
import de.aikitori.retagger.ui.EditorScreen
import de.aikitori.retagger.ui.EditorState
import de.aikitori.retagger.ui.Folder
import de.aikitori.retagger.ui.FolderScreen
import de.aikitori.retagger.ui.RetaggerTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.MediaCodecInfoBuilder
import org.robolectric.shadows.ShadowMediaCodecList
import java.io.ByteArrayOutputStream

/**
 * Pictures for the README without a phone: `./gradlew :app:recordRoborazziDebug` writes them to `docs/`.
 * A normal test run only draws the screens (catches crashes) without saving images.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-night-xxhdpi")
class ScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    private val tree = Uri.parse("content://com.android.externalstorage.documents/tree/primary%3AMusic")
    private val path = listOf(Folder("primary:Music", "Music"), Folder("a", "Harbor Lights"), Folder("b", "Night Ferry (2019)"))
    private val titles = listOf(
        "Departure", "Salt Air", "Lanterns", "Undertow", "Harbor Lights", "Fog Signal",
        "Paper Boats", "North Pier", "Low Tide", "Last Crossing",
    )
    private val files = titles.mapIndexed { i, title ->
        val ext = if (i in 2..4) "m4a" else "mp3"
        val name = "%02d - %s.%s".format(i + 1, title, ext)
        Entry(name, Uri.parse("content://x/$i"), name, isDirectory = false)
    }

    private fun tags(i: Int) = Tags()
        .with(Field.TITLE, titles[i]).with(Field.ARTIST, "Harbor Lights").with(Field.ALBUM, "Night Ferry")
        .with(Field.TRACK, "${i + 1}").with(Field.TRACK_TOTAL, "10").with(Field.GENRE, "Indie Folk")
        .copy(artwork = cover)

    @Test
    fun folder() = capture("folder") { model -> model.showSample(tree, path, files) }

    @Test
    fun editor() = capture("editor") { model ->
        // album artist and year empty, so their suggestions from the folder names show
        val original = tags(2)
        model.showSample(
            tree, path, files,
            editor = EditorState(files[2], original, AudioInfo("Aac", 254, "256"), original.fields, cover, busy = false),
        )
    }

    @Test
    fun batch() = capture("batch") { model ->
        val all = files.indices.map { tags(it) }
        model.showSample(
            tree, path, files, selection = files.map { it.documentId }.toSet(),
            batch = BatchState(files, all, edits = mapOf(Field.YEAR to "2019")),
        )
    }

    @Test
    fun convert() {
        // Robolectric has no codecs; announce Android's AAC and Opus encoders for the format chips
        for ((name, mime) in listOf("c2.android.aac.encoder" to MediaFormat.MIMETYPE_AUDIO_AAC, "c2.android.opus.encoder" to MediaFormat.MIMETYPE_AUDIO_OPUS)) {
            ShadowMediaCodecList.addCodec(
                MediaCodecInfoBuilder.newBuilder().setName(name).setIsEncoder(true).setCapabilities(
                    MediaCodecInfoBuilder.CodecCapabilitiesBuilder.newBuilder()
                        .setMediaFormat(MediaFormat.createAudioFormat(mime, 48000, 2)).setIsEncoder(true).build(),
                ).build(),
            )
        }
        val selected = files.filter { it.name.endsWith(".m4a") }
        capture("convert", dialog = { model -> ConvertDialog(model, selected, onDismiss = {}) }) { model ->
            model.showSample(tree, path, files, selection = selected.map { it.documentId }.toSet())
        }
    }

    private fun capture(
        name: String,
        dialog: (@androidx.compose.runtime.Composable (AppViewModel) -> Unit)? = null,
        setup: (AppViewModel) -> Unit,
    ) {
        val model = AppViewModel(RuntimeEnvironment.getApplication())
        setup(model)
        val snackbar = SnackbarHostState()
        compose.setContent {
            RetaggerTheme(dynamic = false) {
                when {
                    model.editor != null -> EditorScreen(model, snackbar)
                    model.batch != null -> BatchScreen(model, snackbar)
                    else -> FolderScreen(model, snackbar)
                }
                dialog?.invoke(model)
            }
        }
        compose.onRoot().captureRoboImage("../docs/$name.png")
    }

    /** A made-up cover: a dusk gradient with a moon and waves. */
    private val cover: Artwork by lazy {
        val size = 600
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.shader = LinearGradient(0f, 0f, 0f, size.toFloat(), 0xFF1E3A5F.toInt(), 0xFFE8916B.toInt(), Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, size.toFloat(), size.toFloat(), paint)
        paint.shader = null
        paint.color = 0xFFF6E7C8.toInt()
        canvas.drawCircle(size * 0.68f, size * 0.32f, size * 0.12f, paint)
        for (i in 0 until 4) {
            paint.color = (0xFF14304D - i * 0x00030303).toInt()
            canvas.drawRect(0f, size * (0.66f + i * 0.085f), size.toFloat(), size.toFloat(), paint)
        }
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        Artwork(out.toByteArray(), "image/png")
    }
}
