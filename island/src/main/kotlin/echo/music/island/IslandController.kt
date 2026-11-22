package echo.music.island

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.graphics.Rect
import android.graphics.RectF
import android.os.Build
import android.view.DisplayCutout
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber

data class IslandPlaybackState(
  val trackId: String?,
  val title: String,
  val artist: String,
  val artworkUri: String?,
  val positionMs: Long,
  val durationMs: Long,
  val isPlaying: Boolean,
  val isPaused: Boolean = !isPlaying,
  val isStopped: Boolean = false,
  val suppressTrackChangeExpansion: Boolean = false,
)

data class IslandSettings(
  val enabled: Boolean = false,
  val singleTapExpand: Boolean = true,
  val autoHideSeconds: Int = 60,
  val inactivitySeconds: Int = 5,
  val showTrackChange: Boolean = true,
  val verticalCalibrationDp: Int = 0,
  val verticalBaselineCorrectionDp: Int = 0,
  val horizontalOffsetDp: Int = 0,
  val compactWidthDp: Int = 140,
  val compactHeightDp: Int = 32,
  val invertSwipes: Boolean = false,
  val hideInForeground: Boolean = true,
  val backgroundStyle: String = "DEFAULT",
  val followMiniPlayerStyle: Boolean = false,
  val miniPlayerBackgroundStyle: String = "DEFAULT",
  val backgroundTarget: String = "EXPANDED",
  val sliderStyle: String = "DEFAULT",
  val followPlayerSliderStyle: Boolean = false,
  val playerSliderStyle: String = "SLIM",
  val playerSliderSquiggly: Boolean = false,
  val hapticsEnabled: Boolean = false,
  val clickHapticsEnabled: Boolean = true,
)

data class IslandCutoutAnchor(
  val centerX: Float,
  val centerY: Float,
  val boundingRect: Rect?,
  val pathBounds: RectF?,
  val usesPath: Boolean,
)

fun resolveIslandCutoutAnchor(
  cutout: DisplayCutout?,
  density: Float,
): IslandCutoutAnchor? {
  val rect =
    cutout?.boundingRects
      ?.filter { it.top <= cutout.safeInsetTop }
      ?.maxByOrNull { it.width().toLong() * it.height().toLong() }
      ?: return null
  val pathBounds =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
      cutout.cutoutPath?.let { path ->
        RectF().also { bounds -> path.computeBounds(bounds, true) }
      }
    } else {
      null
    }
  val pathOffsetDp = pathBounds?.let { (it.centerY() - rect.exactCenterY()) / density }
  val usesPath =
    pathBounds != null &&
      pathBounds.width() > 0f &&
      pathBounds.height() > 0f &&
      kotlin.math.abs((pathOffsetDp ?: 0f) - 8f) <= 2f
  return if (usesPath) {
    IslandCutoutAnchor(pathBounds!!.centerX(), pathBounds.centerY(), rect, pathBounds, true)
  } else {
    IslandCutoutAnchor(rect.exactCenterX(), rect.exactCenterY(), rect, pathBounds, false)
  }
}

@Singleton
class IslandController @Inject constructor(
  @ApplicationContext private val context: Context,
) {
  private val _playback = MutableStateFlow<IslandPlaybackState?>(null)
  val playback: StateFlow<IslandPlaybackState?> = _playback.asStateFlow()

  private val _settings = MutableStateFlow(IslandSettings())
  val settings: StateFlow<IslandSettings> = _settings.asStateFlow()

  @Volatile private var onPlayPause: (() -> Unit)? = null
  @Volatile private var onPrevious: (() -> Unit)? = null
  @Volatile private var onNext: (() -> Unit)? = null
  @Volatile private var onSeek: ((Long) -> Unit)? = null
  @Volatile private var onSessionActivityFallback: (() -> Unit)? = null
  @Volatile private var islandRenderers: IslandRenderers? = null

  fun updatePlayback(state: IslandPlaybackState?) {
    _playback.value = state
    reconcileService()
  }

  fun updateSettings(settings: IslandSettings) {
    _settings.value = settings
    reconcileService()
  }

  fun setPlayerActions(
    playPause: () -> Unit,
    previous: () -> Unit,
    next: () -> Unit,
    seek: (Long) -> Unit,
  ) {
    onPlayPause = playPause
    onPrevious = previous
    onNext = next
    onSeek = seek
  }

  fun playPause() = onPlayPause?.invoke()
  fun previous() = onPrevious?.invoke()
  fun next() = onNext?.invoke()
  fun seek(positionMs: Long) = onSeek?.invoke(positionMs)

  fun setSessionActivityFallback(action: (() -> Unit)?) {
    onSessionActivityFallback = action
  }

  fun setRenderers(renderers: IslandRenderers?) {
    islandRenderers = renderers
  }

  fun renderers(): IslandRenderers? = islandRenderers

  fun openApp() {
    val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)
    if (launchIntent == null) {
      Timber.tag(TAG).w("Unable to resolve app launch intent for island")
      launchSessionActivityFallback()
      return
    }
    launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    try {
      ContextCompat.startActivity(context, launchIntent, null)
    } catch (error: ActivityNotFoundException) {
      Timber.tag(TAG).e(error, "Unable to open Echo from the island gesture")
      launchSessionActivityFallback()
    } catch (error: SecurityException) {
      Timber.tag(TAG).e(error, "Background activity launch was blocked for the island gesture")
      launchSessionActivityFallback()
    }
  }

  private fun launchSessionActivityFallback() {
    val fallback = onSessionActivityFallback
    if (fallback == null) {
      Timber.tag(TAG).w("Media session activity fallback is unavailable for the island")
      return
    }
    try {
      fallback()
    } catch (error: SecurityException) {
      Timber.tag(TAG).e(error, "Media session activity fallback was blocked")
    }
  }

  fun hasOverlayPermission(): Boolean =
    android.provider.Settings.canDrawOverlays(context)

  fun openOverlaySettings() {
    val intent =
      Intent(
        android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
        Uri.parse("package:${context.packageName}"),
      ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    ContextCompat.startActivity(context, intent, null)
  }

  private fun reconcileService() {
    if (!_settings.value.enabled || !hasOverlayPermission()) {
      context.stopService(Intent(context, IslandOverlayService::class.java))
      return
    }
    val state = _playback.value
    if (state?.isPlaying == true || state != null) {
      try {
        ContextCompat.startForegroundService(context, Intent(context, IslandOverlayService::class.java))
      } catch (error: IllegalStateException) {
        Timber.tag(TAG).w(error, "Unable to start island foreground service")
      } catch (error: SecurityException) {
        Timber.tag(TAG).w(error, "Overlay permission was revoked while starting island")
      }
    }
  }

  private companion object {
    const val TAG = "IslandController"
  }
}
