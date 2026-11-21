package echo.music.iad1tya.playback

import androidx.compose.material3.Slider
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import echo.music.iad1tya.constants.PlayerBackgroundStyle
import echo.music.iad1tya.constants.SliderStyle
import echo.music.iad1tya.ui.component.PlayingIndicator
import echo.music.iad1tya.ui.component.SquigglySlider
import echo.music.iad1tya.ui.component.WavySlider
import echo.music.iad1tya.ui.player.MiniPlayerArtworkBackground
import echo.music.iad1tya.ui.theme.PlayerSliderColors
import echo.music.island.IslandRenderers

internal object IslandAppRenderers : IslandRenderers {
  @Composable
  override fun Background(
    style: String,
    artworkUri: String?,
    isPlaying: Boolean,
    modifier: Modifier,
  ) {
    val backgroundStyle =
      PlayerBackgroundStyle.entries.firstOrNull { it.name == style }
        ?: PlayerBackgroundStyle.DEFAULT
    MiniPlayerArtworkBackground(backgroundStyle, artworkUri, modifier)
  }

  @Composable
  override fun Waveform(isPlaying: Boolean, modifier: Modifier) {
    if (isPlaying) {
      PlayingIndicator(
        color = Color.White,
        modifier = modifier,
        bars = 5,
        barWidth = 2.dp,
        cornerRadius = 1.dp,
      )
    } else {
      Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.Bottom,
      ) {
        listOf(4, 7, 5, 9, 6).forEach { height ->
          Box(
            Modifier.width(2.dp)
              .height(height.dp)
              .background(Color.White, RoundedCornerShape(1.dp))
          )
        }
      }
    }
  }

  @Composable
  override fun SeekSlider(
    style: String,
    squiggly: Boolean,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    isPlaying: Boolean,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit,
    modifier: Modifier,
  ) {
    val sliderStyle = SliderStyle.entries.firstOrNull { it.name == style } ?: SliderStyle.DEFAULT
    val colors =
      PlayerSliderColors.getSliderColors(
        activeColor = Color.White,
        playerBackground = PlayerBackgroundStyle.DEFAULT,
        useDarkTheme = true,
      )

    when {
      sliderStyle == SliderStyle.WAVY && squiggly ->
        SquigglySlider(
          value = value,
          valueRange = valueRange,
          onValueChange = onValueChange,
          onValueChangeFinished = onValueChangeFinished,
          colors = colors,
          isPlaying = isPlaying,
          modifier = modifier,
        )
      sliderStyle == SliderStyle.WAVY ->
        WavySlider(
          value = value,
          valueRange = valueRange,
          onValueChange = onValueChange,
          onValueChangeFinished = onValueChangeFinished,
          colors = colors,
          isPlaying = isPlaying,
          modifier = modifier,
        )
      else ->
        Slider(
          value = value,
          valueRange = valueRange,
          onValueChange = onValueChange,
          onValueChangeFinished = onValueChangeFinished,
          colors = colors,
          modifier = modifier,
        )
    }
  }
}
