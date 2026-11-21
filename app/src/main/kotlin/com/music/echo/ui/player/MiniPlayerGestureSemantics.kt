package echo.music.iad1tya.ui.player

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import echo.music.iad1tya.R

@Composable
internal fun Modifier.miniPlayerGestureSemantics(
  enabled: Boolean,
  isGuest: Boolean,
  onPrevious: () -> Unit,
  onPlayPause: () -> Unit,
  onNext: () -> Unit,
): Modifier {
  if (!enabled || isGuest) return this
  val previousLabel = stringResource(R.string.mini_player_accessibility_previous)
  val playPauseLabel = stringResource(R.string.mini_player_accessibility_play_pause)
  val nextLabel = stringResource(R.string.mini_player_accessibility_next)
  return semantics {
    customActions =
      listOf(
        CustomAccessibilityAction(previousLabel) {
          onPrevious()
          true
        },
        CustomAccessibilityAction(playPauseLabel) {
          onPlayPause()
          true
        },
        CustomAccessibilityAction(nextLabel) {
          onNext()
          true
        },
      )
  }
}
