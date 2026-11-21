package echo.music.island

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

interface IslandRenderers {
  @Composable
  fun Background(
    style: String,
    artworkUri: String?,
    isPlaying: Boolean,
    modifier: Modifier,
  )

  @Composable
  fun Waveform(isPlaying: Boolean, modifier: Modifier)

  @Composable
  fun SeekSlider(
    style: String,
    squiggly: Boolean,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    isPlaying: Boolean,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit,
    modifier: Modifier,
  )
}
