package dev.jdtech.jellyfin.presentation.book.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily as ComposeFontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.jdtech.jellyfin.core.R as CoreR
import dev.jdtech.jellyfin.presentation.theme.spacings
import kotlin.math.roundToInt

data class ReaderPreferencesState(
    val fontSize: Float = 1.0f,
    val fontFamily: String = "default",
    val theme: String = "dark",
    val scroll: Boolean = false,
    val orientation: String = "sensor",
    val readingProgression: String = "auto",
    val isImageBased: Boolean = false,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderAppearanceSheet(
    state: ReaderPreferencesState,
    onFontSizeChanged: (Float) -> Unit,
    onFontFamilyChanged: (String) -> Unit,
    onThemeChanged: (String) -> Unit,
    onScrollChanged: (Boolean) -> Unit,
    onOrientationChanged: (String) -> Unit,
    onProgressionChanged: (String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 6.dp,
    ) {
        Column(
            modifier =
                Modifier.fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 16.dp)
                    .verticalScroll(rememberScrollState()),
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(CoreR.string.reader_appearance),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                IconButton(onClick = onDismiss) {
                    Icon(
                        painter = painterResource(CoreR.drawable.ic_x),
                        contentDescription = stringResource(CoreR.string.close),
                    )
                }
            }

            Spacer(Modifier.height(MaterialTheme.spacings.default))

            // Reading Theme Selection
            Text(
                text = stringResource(CoreR.string.reader_theme),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(MaterialTheme.spacings.small))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.small),
            ) {
                ThemeCard(
                    title = stringResource(CoreR.string.reader_theme_light),
                    backgroundColor = Color(0xFFFAFAFA),
                    textColor = Color(0xFF1E1E1E),
                    isSelected = state.theme == "light",
                    onClick = { onThemeChanged("light") },
                    modifier = Modifier.weight(1f),
                )
                ThemeCard(
                    title = stringResource(CoreR.string.reader_theme_sepia),
                    backgroundColor = Color(0xFFFBF0D9),
                    textColor = Color(0xFF5F4B32),
                    isSelected = state.theme == "sepia",
                    onClick = { onThemeChanged("sepia") },
                    modifier = Modifier.weight(1f),
                )
                ThemeCard(
                    title = stringResource(CoreR.string.reader_theme_dark),
                    backgroundColor = Color(0xFF1A1A1A),
                    textColor = Color(0xFFECECEC),
                    isSelected = state.theme == "dark",
                    onClick = { onThemeChanged("dark") },
                    modifier = Modifier.weight(1f),
                )
            }

            Spacer(Modifier.height(MaterialTheme.spacings.default))

            // Reading Direction
            Text(
                text = stringResource(CoreR.string.reader_reading_progression),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(MaterialTheme.spacings.extraSmall))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(
                    selected = state.readingProgression == "auto",
                    onClick = { onProgressionChanged("auto") },
                    label = { Text(stringResource(CoreR.string.reader_progression_auto)) },
                )
                FilterChip(
                    selected = state.readingProgression == "ltr",
                    onClick = { onProgressionChanged("ltr") },
                    label = { Text(stringResource(CoreR.string.reader_progression_ltr)) },
                )
                FilterChip(
                    selected = state.readingProgression == "rtl",
                    onClick = { onProgressionChanged("rtl") },
                    label = { Text(stringResource(CoreR.string.reader_progression_rtl)) },
                )
            }

            if (!state.isImageBased) {
                Spacer(Modifier.height(MaterialTheme.spacings.default))

                // Font Size
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(CoreR.string.reader_font_size),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = "${(state.fontSize * 100).roundToInt()}%",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    FilledTonalIconButton(
                        onClick = {
                            val newSize = (state.fontSize - 0.1f).coerceIn(0.6f, 2.5f)
                            onFontSizeChanged((newSize * 10).roundToInt() / 10f)
                        },
                        modifier = Modifier.size(36.dp),
                    ) {
                        Text("A-", style = MaterialTheme.typography.labelLarge)
                    }
                    Slider(
                        value = state.fontSize,
                        onValueChange = { onFontSizeChanged((it * 10).roundToInt() / 10f) },
                        valueRange = 0.6f..2.5f,
                        steps = 18,
                        modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
                    )
                    FilledTonalIconButton(
                        onClick = {
                            val newSize = (state.fontSize + 0.1f).coerceIn(0.6f, 2.5f)
                            onFontSizeChanged((newSize * 10).roundToInt() / 10f)
                        },
                        modifier = Modifier.size(36.dp),
                    ) {
                        Text("A+", style = MaterialTheme.typography.labelLarge)
                    }
                }

                Spacer(Modifier.height(MaterialTheme.spacings.default))

                // Font Family
                Text(
                    text = stringResource(CoreR.string.reader_font_family),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(MaterialTheme.spacings.extraSmall))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilterChip(
                        selected = state.fontFamily == "default",
                        onClick = { onFontFamilyChanged("default") },
                        label = { Text(stringResource(CoreR.string.reader_font_default)) },
                    )
                    FilterChip(
                        selected = state.fontFamily == "sans",
                        onClick = { onFontFamilyChanged("sans") },
                        label = { Text(stringResource(CoreR.string.reader_font_sans), fontFamily = ComposeFontFamily.SansSerif) },
                    )
                    FilterChip(
                        selected = state.fontFamily == "serif",
                        onClick = { onFontFamilyChanged("serif") },
                        label = { Text(stringResource(CoreR.string.reader_font_serif), fontFamily = ComposeFontFamily.Serif) },
                    )
                    FilterChip(
                        selected = state.fontFamily == "opendyslexic",
                        onClick = { onFontFamilyChanged("opendyslexic") },
                        label = { Text(stringResource(CoreR.string.reader_font_opendyslexic)) },
                    )
                }

                Spacer(Modifier.height(MaterialTheme.spacings.default))

                // Reading Mode (Layout)
                Text(
                    text = stringResource(CoreR.string.reader_layout),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(MaterialTheme.spacings.extraSmall))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilterChip(
                        selected = !state.scroll,
                        onClick = { onScrollChanged(false) },
                        label = { Text(stringResource(CoreR.string.reader_layout_paged)) },
                    )
                    FilterChip(
                        selected = state.scroll,
                        onClick = { onScrollChanged(true) },
                        label = { Text(stringResource(CoreR.string.reader_layout_scroll)) },
                    )
                }
            }

            Spacer(Modifier.height(MaterialTheme.spacings.default))

            // Orientation
            Text(
                text = stringResource(CoreR.string.reader_orientation),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(MaterialTheme.spacings.extraSmall))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(
                    selected = state.orientation == "sensor",
                    onClick = { onOrientationChanged("sensor") },
                    label = { Text(stringResource(CoreR.string.reader_orientation_auto)) },
                )
                FilterChip(
                    selected = state.orientation == "portrait",
                    onClick = { onOrientationChanged("portrait") },
                    label = { Text(stringResource(CoreR.string.reader_orientation_portrait)) },
                )
                FilterChip(
                    selected = state.orientation == "landscape",
                    onClick = { onOrientationChanged("landscape") },
                    label = { Text(stringResource(CoreR.string.reader_orientation_landscape)) },
                )
            }

            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun ThemeCard(
    title: String,
    backgroundColor: Color,
    textColor: Color,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier =
            modifier
                .height(48.dp)
                .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        color = backgroundColor,
        border =
            if (isSelected) {
                BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
            } else {
                BorderStroke(1.dp, Color.Gray.copy(alpha = 0.3f))
            },
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Text(
                text = title,
                color = textColor,
                fontSize = 13.sp,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
            )
        }
    }
}
