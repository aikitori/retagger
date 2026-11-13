package de.aikitori.retagger.ui

import android.graphics.BitmapFactory
import android.os.Build
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Album
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import de.aikitori.retagger.R
import de.aikitori.retagger.core.Artwork
import de.aikitori.retagger.core.Field
import de.aikitori.retagger.core.FilenamePattern

@Composable
fun RetaggerTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val colors = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> darkColorScheme(primary = Color(0xFF8FD1B8), secondary = Color(0xFFE5C9A0))
        else -> lightColorScheme(primary = Color(0xFF2E5E4E), secondary = Color(0xFF7A5C33))
    }
    MaterialTheme(colorScheme = colors, content = content)
}

@Composable
fun Field.label(): String = stringResource(
    when (this) {
        Field.TITLE -> R.string.field_title
        Field.ARTIST -> R.string.field_artist
        Field.ALBUM -> R.string.field_album
        Field.ALBUM_ARTIST -> R.string.field_album_artist
        Field.YEAR -> R.string.field_year
        Field.TRACK -> R.string.field_track
        Field.TRACK_TOTAL -> R.string.field_track_total
        Field.DISC -> R.string.field_disc
        Field.DISC_TOTAL -> R.string.field_disc_total
        Field.GENRE -> R.string.field_genre
        Field.COMPOSER -> R.string.field_composer
        Field.COMMENT -> R.string.field_comment
    },
)

/** Fields shown side by side in the editor ("track 3 of 12"). */
val PAIRED = mapOf(Field.TRACK to Field.TRACK_TOTAL, Field.DISC to Field.DISC_TOTAL)

/**
 * All tag fields in a column, track and disc each next to their total.
 * [field] draws a single field.
 */
@Composable
fun FieldList(field: @Composable (Field, Modifier) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (f in Field.entries) {
            if (f in PAIRED.values) continue
            val partner = PAIRED[f]
            if (partner != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    field(f, Modifier.weight(1f))
                    field(partner, Modifier.weight(1f))
                }
            } else {
                field(f, Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
fun TagField(
    field: Field,
    value: String,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    various: Boolean = false,
    enabled: Boolean = true,
    trailing: @Composable (() -> Unit)? = null,
) {
    val invalid = field.numeric && !value.trim().all(Char::isDigit)
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        modifier = modifier,
        enabled = enabled,
        label = { Text(if (various) "${field.label()} ${stringResource(R.string.various)}" else field.label()) },
        placeholder = placeholder?.let { { Text(it) } },
        singleLine = field != Field.COMMENT,
        isError = invalid,
        supportingText = if (invalid) ({ Text(stringResource(R.string.invalid_number)) }) else null,
        trailingIcon = trailing,
        keyboardOptions = KeyboardOptions(
            keyboardType = if (field.numeric) KeyboardType.Number else KeyboardType.Text,
            capitalization = if (field.numeric || field == Field.YEAR) KeyboardCapitalization.None else KeyboardCapitalization.Words,
        ),
    )
}

@Composable
fun Cover(artwork: Artwork?, size: Dp = 112.dp) {
    val bitmap = remember(artwork) {
        artwork?.let { runCatching { BitmapFactory.decodeByteArray(it.data, 0, it.data.size)?.asImageBitmap() }.getOrNull() }
    }
    Box(
        Modifier
            .size(size)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null) {
            Image(bitmap, contentDescription = stringResource(R.string.cover), contentScale = ContentScale.Crop, modifier = Modifier.size(size))
        } else {
            Icon(Icons.Outlined.Album, contentDescription = stringResource(R.string.no_cover), modifier = Modifier.size(size / 2), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * Dialog for a file name pattern with suggestions and a preview.
 * [preview] returns rows of file name and result for the pattern (null = no match).
 */
@Composable
fun PatternDialog(
    title: String,
    initial: String,
    preview: (String) -> List<Pair<String, String?>>,
    summary: (@Composable (List<Pair<String, String?>>) -> String)? = null,
    onDismiss: () -> Unit,
    onApply: (String) -> Unit,
) {
    var pattern by rememberSaveable { mutableStateOf(initial) }
    val rows = remember(pattern) { preview(pattern) }
    val usable = FilenamePattern(pattern).fields.isNotEmpty() && rows.any { it.second != null }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = pattern,
                    onValueChange = { pattern = it },
                    label = { Text(stringResource(R.string.pattern)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (suggestion in FilenamePattern.SUGGESTIONS) {
                        AssistChip(onClick = { pattern = suggestion }, label = { Text(suggestion) })
                    }
                }
                Text(stringResource(R.string.pattern_help), style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(4.dp))
                summary?.let { Text(it(rows), style = MaterialTheme.typography.labelLarge) }
                for ((from, to) in rows.take(4)) {
                    Column {
                        Text(from, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            "→ " + (to ?: stringResource(R.string.no_match)),
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (to == null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onApply(pattern) }, enabled = usable) { Text(stringResource(R.string.apply)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

@Composable
fun fieldLabels(): Map<Field, String> = Field.entries.associateWith { it.label() }

/** Parsed fields as a short line for the preview. */
fun describe(fields: Map<Field, String>, labels: Map<Field, String>): String =
    fields.entries.joinToString(" · ") { (field, value) -> "${labels[field]}: $value" }

@Composable
fun DiscardDialog(onDismiss: () -> Unit, onDiscard: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.discard_title)) },
        text = { Text(stringResource(R.string.discard_text)) },
        confirmButton = { TextButton(onClick = onDiscard) { Text(stringResource(R.string.discard)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
