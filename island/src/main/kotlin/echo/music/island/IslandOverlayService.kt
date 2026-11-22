package echo.music.island

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.Slider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect as ComposeRect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.width
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import coil3.compose.AsyncImage
import coil3.imageLoader
import coil3.request.ErrorResult
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.toBitmap
import dagger.hilt.android.AndroidEntryPoint
import androidx.palette.graphics.Palette
import javax.inject.Inject
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class IslandPhase {
  COLLAPSED,
  EXPANDING,
  EXPANDED,
  COLLAPSING,
}

private const val ISLAND_LOG_TAG = "IslandOverlayService"
private const val EXPANDED_WIDTH_DP = 360
private const val EXPANDED_HEIGHT_DP = 112
private const val MAX_PALETTE_CACHE_SIZE = 8
private const val COMPACT_ART_SIZE_DP = 28
private const val COMPACT_WAVEFORM_WIDTH_DP = 20
private const val COMPACT_HORIZONTAL_PADDING_DP = 7
private const val CENTER_GAP_MARGIN_DP = 8
private const val ISLAND_VISIBILITY_DURATION_MS = 200L
private const val ISLAND_HIDDEN_SCALE = 0.94f
private const val SWIPE_TRANSITION_DURATION_MS = 600L

@AndroidEntryPoint
class IslandOverlayService : Service() {
  @Inject lateinit var controller: IslandController

  private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
  private val gestureHandler = Handler(Looper.getMainLooper())
  private var windowManager: WindowManager? = null
  private var overlayView: IslandOverlayComposeView? = null
  private var overlayViewAdded = false
  private var hideJob: Job? = null
  private var playbackUnavailableJob: Job? = null
  private var visibilityJob: Job? = null
  private var inactivityJob: Job? = null
  private var trackChangeJob: Job? = null
  private var swipeDirectionResetJob: Job? = null
  private var collapseAnimationFallback: Runnable? = null
  private var isSeekDragging = false
  private var seekSliderBounds: ComposeRect? = null
  private var tapCount = 0
  private var tapTimeout: Runnable? = null
  private var lastTapX = 0f
  private var lastTapY = 0f
  private var lastTapUpTime = 0L
  private var pendingTapPhase = IslandPhase.COLLAPSED
  private var gesturePointerId = MotionEvent.INVALID_POINTER_ID
  private var gestureStartX = 0f
  private var gestureStartY = 0f
  private var gestureDownTime = 0L
  private var gestureStartPhase = IslandPhase.COLLAPSED
  private var gestureActive = false
  private var gestureMoved = false
  private var gestureHandled = false
  private var islandVisible by mutableStateOf(false)
  private var swipeOffsetPx by mutableFloatStateOf(0f)
  private var swipeDirection by mutableStateOf(0)
  private var pausedAutoHideSeconds: Int? = null
  private var pausedAutoHidePlaybackKey: String? = null
  private var phase by mutableStateOf(IslandPhase.COLLAPSED)
  private var autoHiddenPlaybackKey: String? = null
  private var appInForeground = false
  private var processObserver: DefaultLifecycleObserver? = null
  private var screenReceiverRegistered = false
  private var lastInactivitySeconds: Int? = null
  private var lastLayoutSettings: IslandSettings? = null
  private var isPrimaryInstance = false
  private var suppressSwipeTrackExpansion = false
  private var suppressSwipeTrackExpansionReset: Runnable? = null
  private var stableCompactX: Int? = null
  private var stableCompactY: Int? = null

  private val screenReceiver =
    object : BroadcastReceiver() {
      override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
          Intent.ACTION_SCREEN_OFF -> {
            collapseImmediately()
            removeOverlay()
          }
          Intent.ACTION_SCREEN_ON -> render()
        }
      }
    }
  override fun onCreate() {
    super.onCreate()
    if (activeInstance != null && activeInstance !== this) {
      Log.w(TAG, "Another island overlay service instance is already active; stopping duplicate")
      stopSelf()
      return
    }
    if (!controller.hasOverlayPermission()) {
      stopSelf()
      return
    }
    activeInstance = this
    isPrimaryInstance = true
    windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
    createNotificationChannel()
    startForeground(NOTIFICATION_ID, buildNotification())
    processObserver =
      object : DefaultLifecycleObserver {
        override fun onStart(owner: LifecycleOwner) {
          appInForeground = true
          if (controller.settings.value.hideInForeground) removeOverlay() else render()
        }

        override fun onStop(owner: LifecycleOwner) {
          appInForeground = false
          render()
        }
      }
    ProcessLifecycleOwner.get().lifecycle.addObserver(processObserver!!)
    appInForeground =
      ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
    registerScreenReceiver()
    serviceScope.launch {
      controller.playback.collectLatest {
        render()
      }
    }
    serviceScope.launch {
      controller.settings.collectLatest {
        val previousSettings = lastLayoutSettings
        val inactivityChanged = lastInactivitySeconds?.let { previous ->
          it.inactivitySeconds != previous
        } ?: false
        lastInactivitySeconds = it.inactivitySeconds
        lastLayoutSettings = it
        render()
        if (
          previousSettings != null &&
            (it.verticalCalibrationDp != previousSettings.verticalCalibrationDp ||
              it.horizontalOffsetDp != previousSettings.horizontalOffsetDp ||
              it.compactWidthDp != previousSettings.compactWidthDp ||
              it.compactHeightDp != previousSettings.compactHeightDp)
        ) {
          overlayView?.let { view ->
            resizeOverlay(expanded = phase != IslandPhase.COLLAPSED)
            ViewCompat.requestApplyInsets(view)
          }
        }
        if (phase == IslandPhase.EXPANDED && inactivityChanged) {
          resetInactivityTimer(cancelTrackChange = false)
        }
      }
    }
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    if (!isPrimaryInstance) {
      stopSelf()
      return START_NOT_STICKY
    }
    render()
    return START_NOT_STICKY
  }

  override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
    super.onConfigurationChanged(newConfig)
    collapseImmediately()
    if (newConfig.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE) {
      removeOverlay()
    } else {
      overlayView?.let(ViewCompat::requestApplyInsets)
      render()
    }
  }

  private fun render() {
    val settings = controller.settings.value
    if (
      (appInForeground && settings.hideInForeground) ||
      !settings.enabled ||
      !controller.hasOverlayPermission() ||
      !getSystemService(PowerManager::class.java).isInteractive ||
      resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
    ) {
      collapseImmediately()
      removeOverlay()
      return
    }
    val state = controller.playback.value ?: run {
      schedulePlaybackUnavailableHide()
      return
    }
    playbackUnavailableJob?.cancel()
    playbackUnavailableJob = null
    if (state.isStopped) {
      collapseImmediately()
      hideOverlayWithTransition()
      return
    }
    val playbackKey = state.visibilityKey()
    if (
      autoHiddenPlaybackKey != null &&
        (state.isPlaying || autoHiddenPlaybackKey != playbackKey)
    ) {
      autoHiddenPlaybackKey = null
    }
    val autoHiddenCurrentTrack = autoHiddenPlaybackKey == playbackKey
    if (autoHiddenCurrentTrack) {
      collapseImmediately()
      hideOverlayWithTransition()
      return
    }
    if (state.isPlaying) {
      stopPausedAutoHide()
    } else if (state.isPaused) {
      startPausedAutoHide(settings.autoHideSeconds, playbackKey)
    } else {
      stopPausedAutoHide()
    }
    val wasNewTrack = state.trackId != lastTrackId
    if (wasNewTrack) lastTrackId = state.trackId
    if (overlayView == null) {
      addOverlay()
    }
    if (overlayView == null) return
    visibilityJob?.cancel()
    visibilityJob = null
    if (!islandVisible) islandVisible = true
    if (wasNewTrack) {
      if (state.suppressTrackChangeExpansion || suppressSwipeTrackExpansion) {
        suppressSwipeTrackExpansion = false
        cancelSuppressSwipeTrackExpansionReset()
        collapseImmediately()
      } else if (settings.showTrackChange) {
        beginExpand(trackChange = true)
      } else if (phase == IslandPhase.EXPANDED || phase == IslandPhase.EXPANDING) {
        collapseImmediately()
      }
    }
  }

  private var lastTrackId: String? = null

  private fun IslandPlaybackState.visibilityKey(): String =
    trackId ?: artworkUri ?: "$title\u0000$artist"

  private fun addOverlay() {
    overlayOwners = OverlayOwners()
    val view =
      IslandOverlayComposeView(
        context = this,
        shouldForwardToCompose = ::isSeekSliderTouch,
        onIslandTouch = ::handleIslandGesture,
      ).apply {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
        setViewTreeLifecycleOwner(overlayOwners)
        setViewTreeSavedStateRegistryOwner(overlayOwners)
        setViewTreeViewModelStoreOwner(overlayOwners)
        setContent {
          IslandContent(
            state = controller.playback.collectAsState().value,
            settings = controller.settings.collectAsState().value,
            renderers = controller.renderers(),
            phase = phase,
            onCollapseAnimationFinished = ::finishCollapseAnimation,
            visible = islandVisible,
            swipeOffsetPx = swipeOffsetPx,
            swipeDirection = swipeDirection,
            compactWidthDp = compactWidthDp(controller.settings.value),
            centerGapDp = centerGapDp(),
            isSeekDragging = { isSeekDragging },
            onSeekBoundsChanged = { seekSliderBounds = it },
            onSeekStart = ::startSeekDrag,
            onSeekChange = ::seekDuringDrag,
            onSeekEnd = ::endSeekDrag,
          )
        }
        isClickable = true
        ViewCompat.setOnApplyWindowInsetsListener(this) { _, insets ->
          updateOverlayPosition(insets)
          insets
        }
      }
    overlayView = view
    overlayOwners.start()
    val compact = isCompactPhase(phase)
    val manager =
      windowManager ?: run {
        Log.e(TAG, "Window manager unavailable while attaching the island overlay")
        overlayView = null
        overlayViewAdded = false
        overlayOwners.stop()
        stopSelf()
        return
      }
    val settings = controller.settings.value
    val params =
      WindowManager.LayoutParams(
        dp(if (compact) compactWidthDp(settings) else expandedWindowWidthDp()),
        dp(if (compact) settings.compactHeightDp else EXPANDED_HEIGHT_DP),
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
          WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
          WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
          WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
          WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT,
      ).apply {
        gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        x = dp(settings.horizontalOffsetDp)
        y = overlayTopPosition(settings)
      }
    try {
      manager.addView(view, params)
      overlayViewAdded = true
      stableCompactX = params.x
      stableCompactY = params.y
      val cutout = currentDisplayCutout()
      val anchor = resolveIslandCutoutAnchor(cutout, resources.displayMetrics.density)
      Log.i(
        TAG,
        "Overlay attached density=${resources.displayMetrics.density}, " +
          "display=${resources.displayMetrics.widthPixels}x${resources.displayMetrics.heightPixels}, " +
          "statusInset=${initialTopInset()}, " +
          "cutoutRects=${cutout?.boundingRects}, pathBounds=${anchor?.pathBounds}, " +
          "anchor=${if (anchor?.usesPath == true) "path" else "rect/fallback"}, " +
          "chosen=(${params.x},${params.y}), size=${params.width}x${params.height}",
      )
      if (view.isAttachedToWindow) ViewCompat.requestApplyInsets(view)
    } catch (error: WindowManager.BadTokenException) {
      Log.e(TAG, "Unable to attach the island overlay window", error)
      overlayView = null
      overlayViewAdded = false
      overlayOwners.stop()
      stopSelf()
    } catch (error: IllegalArgumentException) {
      Log.e(TAG, "Invalid island overlay window parameters", error)
      overlayView = null
      overlayViewAdded = false
      overlayOwners.stop()
      stopSelf()
    } catch (error: IllegalStateException) {
      Log.e(TAG, "Island overlay could not be attached in its current state", error)
      overlayView = null
      overlayViewAdded = false
      overlayOwners.stop()
      stopSelf()
    } catch (error: SecurityException) {
      Log.e(TAG, "Overlay permission was revoked while attaching the island", error)
      overlayView = null
      overlayViewAdded = false
      overlayOwners.stop()
      stopSelf()
    }
  }

  private fun updateOverlayPosition(insets: WindowInsetsCompat) {
    val view = overlayView ?: return
    val params = view.layoutParams as? WindowManager.LayoutParams ?: return
    if (isCompactPhase(phase)) {
      return
    }
    val cutout =
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        view.rootWindowInsets?.displayCutout ?: currentDisplayCutout()
      } else {
        null
      }
    val settings = controller.settings.value
    val targetY =
      stableCompactY ?: overlayTopPosition(settings, view, cutout)
    val targetX = dp(settings.horizontalOffsetDp)
    if (params.x != targetX || params.y != targetY) {
      params.x = targetX
      params.y = targetY
      updateOverlayLayout(view, params)
    }
  }

  private fun initialTopInset(): Int {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
      val insets = windowManager?.currentWindowMetrics?.windowInsets
      if (insets != null) {
        val statusBarTop = insets.getInsets(WindowInsets.Type.statusBars()).top
        val cutoutTop = insets.displayCutout?.safeInsetTop ?: 0
        return maxOf(statusBarTop, cutoutTop)
      }
    }
    val statusBarHeightId = resources.getIdentifier("status_bar_height", "dimen", "android")
    return if (statusBarHeightId != 0) resources.getDimensionPixelSize(statusBarHeightId) else 0
  }

  private fun initialCutoutHorizontalOffset(): Int {
    return 0
  }

  private fun currentDisplayCutout(): android.view.DisplayCutout? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
    return windowManager?.currentWindowMetrics?.windowInsets?.displayCutout
  }

  private fun cutoutHorizontalOffset(cutout: android.view.DisplayCutout?): Int {
    return 0
  }

  private fun removeOverlay() {
    stopPausedAutoHide()
    stableCompactX = null
    stableCompactY = null
    playbackUnavailableJob?.cancel()
    playbackUnavailableJob = null
    cancelSuppressSwipeTrackExpansionReset()
    suppressSwipeTrackExpansion = false
    visibilityJob?.cancel()
    visibilityJob = null
    cancelInactivityTimer()
    cancelTapSequence()
    resetGesture()
    cancelCollapseAnimationFallback()
    trackChangeJob?.cancel()
    trackChangeJob = null
    swipeDirectionResetJob?.cancel()
    swipeDirectionResetJob = null
    isSeekDragging = false
    seekSliderBounds = null
    islandVisible = false
    swipeOffsetPx = 0f
    phase = IslandPhase.COLLAPSED
    val manager = windowManager
    val view = overlayView
    if (view != null && manager != null && overlayViewAdded) {
      try {
        manager.removeViewImmediate(view)
        overlayViewAdded = false
      } catch (error: WindowManager.BadTokenException) {
        Log.w(TAG, "Island overlay token expired during removal", error)
        if (!view.isAttachedToWindow) overlayViewAdded = false
      } catch (error: IllegalArgumentException) {
        Log.w(TAG, "Island overlay was not registered during removal", error)
        overlayViewAdded = false
      } catch (error: IllegalStateException) {
        Log.w(TAG, "Island overlay was already removed", error)
        if (!view.isAttachedToWindow) overlayViewAdded = false
      } catch (error: SecurityException) {
        Log.e(TAG, "Overlay permission was revoked during removal", error)
        if (!view.isAttachedToWindow) overlayViewAdded = false
      }
    }
    if (!overlayViewAdded && (view == null || !view.isAttachedToWindow)) {
      overlayView = null
      if (overlayOwners.lifecycle.currentState.isAtLeast(Lifecycle.State.CREATED)) {
        overlayOwners.stop()
      }
    }
  }

  private fun isCompactPhase(value: IslandPhase): Boolean =
    value == IslandPhase.COLLAPSED || value == IslandPhase.COLLAPSING

  private fun hideOverlayWithTransition() {
    val view = overlayView ?: return
    if (!islandVisible) {
      removeOverlay()
      return
    }
    visibilityJob?.cancel()
    islandVisible = false
    visibilityJob =
      serviceScope.launch {
        delay(ISLAND_VISIBILITY_DURATION_MS)
        if (overlayView === view && !islandVisible) removeOverlay()
      }
  }

  private fun schedulePlaybackUnavailableHide() {
    if (overlayView == null || playbackUnavailableJob?.isActive == true) return
    playbackUnavailableJob =
      serviceScope.launch {
        delay(PLAYBACK_UNAVAILABLE_DEBOUNCE_MS)
        if (controller.playback.value == null) {
          Log.d(TAG, "Playback remained unavailable; hiding island")
          collapseImmediately()
          hideOverlayWithTransition()
        } else {
          Log.d(TAG, "Playback recovered before hide; keeping island visible")
        }
        playbackUnavailableJob = null
      }
  }

  private fun beginExpand(trackChange: Boolean) {
    if (!controller.hasOverlayPermission() || appInForeground) return
    cancelTapSequence()
    when (phase) {
      IslandPhase.EXPANDED,
      IslandPhase.EXPANDING -> {
        if (!trackChange) resetInactivityTimer()
      }
      IslandPhase.COLLAPSING -> return
      IslandPhase.COLLAPSED -> {
        val view = overlayView ?: return
        phase = IslandPhase.EXPANDING
        swipeOffsetPx = 0f
        resizeOverlay(expanded = true)
        if (overlayView !== view) return
        if (!view.isAttachedToWindow) {
          removeOverlay()
          return
        }
        view.postOnAnimation {
          if (phase == IslandPhase.EXPANDING) {
            phase = IslandPhase.EXPANDED
            if (trackChangeJob?.isActive != true) {
              resetInactivityTimer(cancelTrackChange = false)
            }
          }
        }
        if (trackChange) {
          trackChangeJob?.cancel()
          trackChangeJob =
            serviceScope.launch {
              delay(TRACK_EXPANSION_MS)
              if (phase == IslandPhase.EXPANDED) beginCollapse()
              trackChangeJob = null
            }
        }
      }
    }
  }

  private fun beginCollapse() {
    when (phase) {
      IslandPhase.COLLAPSED,
      IslandPhase.COLLAPSING -> return
      IslandPhase.EXPANDING -> collapseImmediately()
      IslandPhase.EXPANDED -> {
        trackChangeJob?.cancel()
        trackChangeJob = null
        phase = IslandPhase.COLLAPSING
        cancelInactivityTimer()
        cancelCollapseAnimationFallback()
        collapseAnimationFallback =
          Runnable {
            if (phase == IslandPhase.COLLAPSING) finishCollapseAnimation()
          }.also { gestureHandler.postDelayed(it, COLLAPSE_ANIMATION_TIMEOUT_MS) }
      }
    }
  }

  private fun finishCollapseAnimation() {
    if (phase != IslandPhase.COLLAPSING) return
    cancelCollapseAnimationFallback()
    resizeOverlay(expanded = false)
    if (overlayView?.isAttachedToWindow != true) {
      removeOverlay()
      return
    }
    phase = IslandPhase.COLLAPSED
    isSeekDragging = false
    swipeOffsetPx = 0f
  }

  private fun collapseImmediately() {
    cancelTapSequence()
    resetGesture()
    cancelCollapseAnimationFallback()
    trackChangeJob?.cancel()
    trackChangeJob = null
    cancelInactivityTimer()
    isSeekDragging = false
    phase = IslandPhase.COLLAPSED
    overlayView?.let { view ->
      val params = view.layoutParams as? WindowManager.LayoutParams ?: return
      resizeOverlay(expanded = false, viewOverride = view, paramsOverride = params)
    }
  }

  private fun cancelCollapseAnimationFallback() {
    collapseAnimationFallback?.let(gestureHandler::removeCallbacks)
    collapseAnimationFallback = null
  }

  private fun resizeOverlay(
    expanded: Boolean,
    viewOverride: View? = null,
    paramsOverride: WindowManager.LayoutParams? = null,
  ) {
    val view = viewOverride ?: overlayView ?: return
    val params = paramsOverride ?: view.layoutParams as? WindowManager.LayoutParams ?: return
    val settings = controller.settings.value
    val previousWidth = params.width
    val previousHeight = params.height
    val previousX = params.x
    val previousY = params.y
    params.width = dp(if (expanded) expandedWindowWidthDp() else compactWidthDp(settings))
    params.height = dp(if (expanded) EXPANDED_HEIGHT_DP else settings.compactHeightDp)
    val cutout =
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        view.rootWindowInsets?.displayCutout ?: currentDisplayCutout()
      } else {
        null
      }
    params.x = dp(settings.horizontalOffsetDp)
    params.y =
      stableCompactY ?: overlayTopPosition(settings, view, cutout)
    val layoutChanged =
      previousWidth != params.width ||
        previousHeight != params.height ||
        previousX != params.x ||
        previousY != params.y
    if (layoutChanged) updateOverlayLayout(view, params)
  }

  private fun overlayTopPosition(
    settings: IslandSettings,
    view: View? = null,
    cutout: android.view.DisplayCutout? = null,
  ): Int {
    val activeCutout =
      cutout ?: view?.rootWindowInsets?.displayCutout ?: currentDisplayCutout()
    val anchor = resolveIslandCutoutAnchor(activeCutout, resources.displayMetrics.density)
    val anchorY =
      anchor?.centerY?.toInt()
        ?: view?.let(::getStatusAndCutoutTop)
        ?: initialTopInset()
    val baseline =
      if (anchor?.usesPath == true) 0 else settings.verticalBaselineCorrectionDp
    val desiredTop =
      anchorY - dp(settings.compactHeightDp / 2) +
        dp(settings.verticalCalibrationDp + baseline)
    val safeTop =
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        activeCutout?.safeInsetTop ?: initialTopInset()
      } else {
        initialTopInset()
      }
    return maxOf(desiredTop, safeTop)
  }

  private fun centerGapDp(): Int {
    val cutout = currentDisplayCutout()
    val anchor = resolveIslandCutoutAnchor(cutout, resources.displayMetrics.density)
    val cutoutWidthPx =
      if (anchor?.usesPath == true) {
        anchor.pathBounds?.width()
      } else {
        anchor?.boundingRect?.width()?.toFloat()
      }
    val cutoutWidthDp = cutoutWidthPx?.div(resources.displayMetrics.density) ?: 28f
    return cutoutWidthDp.toInt().coerceAtLeast(1) + CENTER_GAP_MARGIN_DP * 2
  }

  private fun compactWidthDp(settings: IslandSettings): Int =
    maxOf(
      settings.compactWidthDp,
      COMPACT_ART_SIZE_DP + COMPACT_WAVEFORM_WIDTH_DP + centerGapDp() +
        COMPACT_HORIZONTAL_PADDING_DP * 2,
    )

  private fun updateOverlayLayout(view: View, params: WindowManager.LayoutParams) {
    if (!view.isAttachedToWindow) return
    try {
      windowManager?.updateViewLayout(view, params)
    } catch (error: WindowManager.BadTokenException) {
      Log.e(TAG, "Island overlay window token is no longer valid", error)
      removeOverlay()
      stopSelf()
    } catch (error: IllegalArgumentException) {
      Log.e(TAG, "Invalid island overlay layout parameters", error)
      removeOverlay()
    } catch (error: IllegalStateException) {
      Log.w(TAG, "Island overlay was detached during layout update", error)
      removeOverlay()
    } catch (error: SecurityException) {
      Log.e(TAG, "Overlay permission was revoked during layout update", error)
      removeOverlay()
      stopSelf()
    }
  }

  private fun getStatusAndCutoutTop(view: View): Int {
    val insets = view.rootWindowInsets ?: return initialTopInset()
    val statusTop =
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        androidx.core.view.WindowInsetsCompat.toWindowInsetsCompat(insets)
          .getInsets(WindowInsetsCompat.Type.statusBars()).top
      } else {
        0
      }
    val cutoutTop =
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        insets.displayCutout?.safeInsetTop ?: 0
      } else {
        0
      }
    return maxOf(statusTop, cutoutTop)
  }

  private fun resetInactivityTimer() {
    resetInactivityTimer(cancelTrackChange = true)
  }

  private fun resetInactivityTimer(cancelTrackChange: Boolean) {
    if (cancelTrackChange) {
      trackChangeJob?.cancel()
      trackChangeJob = null
    }
    cancelInactivityTimer()
    if (phase != IslandPhase.EXPANDED || isSeekDragging || gestureActive) return
    val seconds = controller.settings.value.inactivitySeconds
    if (seconds <= 0) return
    inactivityJob =
      serviceScope.launch {
        delay(seconds * 1000L)
        if (phase == IslandPhase.EXPANDED && !isSeekDragging) beginCollapse()
        inactivityJob = null
      }
  }

  private fun cancelInactivityTimer() {
    inactivityJob?.cancel()
    inactivityJob = null
  }

  private fun cancelSuppressSwipeTrackExpansionReset() {
    suppressSwipeTrackExpansionReset?.let(gestureHandler::removeCallbacks)
    suppressSwipeTrackExpansionReset = null
  }

  private fun isSeekSliderTouch(x: Float, y: Float): Boolean =
    (phase == IslandPhase.EXPANDED || phase == IslandPhase.EXPANDING) &&
      seekSliderBounds?.contains(Offset(x, y)) == true

  private fun handleIslandGesture(event: MotionEvent): Boolean {
    if (event.actionMasked == MotionEvent.ACTION_OUTSIDE) {
      Log.d(TAG, "Root dispatch ACTION_OUTSIDE phase=$phase")
      if (phase == IslandPhase.EXPANDED || phase == IslandPhase.EXPANDING) beginCollapse()
      return true
    }

    when (event.actionMasked) {
      MotionEvent.ACTION_DOWN -> {
        Log.d(TAG, "Root dispatch DOWN phase=$phase x=${event.x} y=${event.y}")
        if (phase == IslandPhase.EXPANDING || phase == IslandPhase.COLLAPSING ||
          event.pointerCount != 1
        ) {
          cancelTapSequence()
          resetGesture()
          return true
        }

        val tapSlop = ViewConfiguration.get(this).scaledDoubleTapSlop.toFloat()
        val elapsedSinceTap = event.eventTime - lastTapUpTime
        if (
          tapCount > 0 &&
            (pendingTapPhase != phase ||
              elapsedSinceTap > MULTI_TAP_TIMEOUT_MS ||
              distance(event.x - lastTapX, event.y - lastTapY) > tapSlop)
        ) {
          dispatchPendingTap()
          if (phase == IslandPhase.EXPANDING || phase == IslandPhase.COLLAPSING) return true
        }

        tapTimeout?.let(gestureHandler::removeCallbacks)
        tapTimeout = null
        gesturePointerId = event.getPointerId(0)
        gestureStartX = event.x
        gestureStartY = event.y
        gestureDownTime = event.downTime
        gestureStartPhase = phase
        gestureActive = true
        gestureMoved = false
        gestureHandled = false
        if (phase == IslandPhase.EXPANDED) cancelInactivityTimer()
        return true
      }

      MotionEvent.ACTION_MOVE -> {
        if (!gestureActive) return true
        val pointerIndex = event.findPointerIndex(gesturePointerId)
        if (pointerIndex < 0) {
          cancelTapSequence()
          resetGesture()
          return true
        }

        val deltaX = event.getX(pointerIndex) - gestureStartX
        val deltaY = event.getY(pointerIndex) - gestureStartY
        val touchSlop = ViewConfiguration.get(this).scaledTouchSlop.toFloat()
        if (!gestureMoved && distance(deltaX, deltaY) > touchSlop) {
          gestureMoved = true
          Log.d(TAG, "Root dispatch MOVE crossed touch slop phase=$gestureStartPhase")
          cancelTapSequence()
        }
        if (gestureMoved && deltaX != 0f) swipeDirection = if (deltaX > 0f) 1 else -1
        val maxFollowPx = dp(SWIPE_FOLLOW_MAX_DP).toFloat()
        swipeOffsetPx = deltaX.coerceIn(-maxFollowPx, maxFollowPx)

        val swipeThreshold = maxOf(touchSlop * 2f, dp(COMPACT_SWIPE_THRESHOLD_DP).toFloat())
        if (!gestureHandled) {
          when {
            gestureStartPhase == IslandPhase.EXPANDED &&
              deltaY < -swipeThreshold &&
              kotlin.math.abs(deltaY) > kotlin.math.abs(deltaX) * SWIPE_AXIS_RATIO -> {
              gestureHandled = true
              swipeOffsetPx = 0f
              swipeDirection = 0
              Log.d(TAG, "Gesture callback swipe-up -> collapse")
              beginCollapse()
            }
            kotlin.math.abs(deltaX) >= swipeThreshold &&
              kotlin.math.abs(deltaX) > kotlin.math.abs(deltaY) * SWIPE_AXIS_RATIO -> {
              gestureHandled = true
              val next = (deltaX > 0f) != controller.settings.value.invertSwipes
              swipeDirection = if (deltaX > 0f) 1 else -1
              swipeDirectionResetJob?.cancel()
              swipeDirectionResetJob =
                serviceScope.launch {
                  delay(SWIPE_TRANSITION_DURATION_MS)
                  swipeDirection = 0
                  swipeDirectionResetJob = null
                }
              performSwipeHaptic()
              Log.d(TAG, "Gesture callback swipe ${if (next) "next" else "previous"}")
              if (gestureStartPhase == IslandPhase.COLLAPSED) {
                suppressSwipeTrackExpansion = true
                suppressSwipeTrackExpansionReset?.let(gestureHandler::removeCallbacks)
                suppressSwipeTrackExpansionReset =
                  Runnable {
                    suppressSwipeTrackExpansion = false
                    suppressSwipeTrackExpansionReset = null
                  }.also {
                    gestureHandler.postDelayed(it, 1500L)
                  }
              }
              if (next) controller.next() else controller.previous()
            }
          }
        }
        return true
      }

      MotionEvent.ACTION_UP -> {
        Log.d(TAG, "Root dispatch UP phase=$phase")
        val wasHandled = gestureHandled
        if (gestureActive &&
          !gestureMoved &&
          !gestureHandled &&
          event.eventTime - gestureDownTime <= ViewConfiguration.getLongPressTimeout() &&
          phase == gestureStartPhase
        ) {
          recordTap(event.x, event.y, event.eventTime, gestureStartPhase)
        } else if (gestureActive && !gestureMoved && !gestureHandled) {
          cancelTapSequence()
        }
        resetGesture()
        swipeOffsetPx = 0f
        if (!wasHandled) swipeDirection = 0
        if (phase == IslandPhase.EXPANDED) resetInactivityTimer()
        return true
      }

      MotionEvent.ACTION_POINTER_DOWN,
      MotionEvent.ACTION_POINTER_UP,
      MotionEvent.ACTION_CANCEL -> {
        cancelTapSequence()
        resetGesture()
        swipeOffsetPx = 0f
        swipeDirection = 0
        if (phase == IslandPhase.EXPANDED) resetInactivityTimer()
        return true
      }
    }
    return true
  }

  private fun recordTap(x: Float, y: Float, eventTime: Long, tapPhase: IslandPhase) {
    if (tapCount == 0) pendingTapPhase = tapPhase
    tapCount += 1
    lastTapX = x
    lastTapY = y
    lastTapUpTime = eventTime
    if (tapCount == 3) {
      cancelTapSequence()
      Log.d(TAG, "Gesture callback triple tap -> open app")
      performTapHaptic(tripleTap = true)
      controller.openApp()
      return
    }

    tapTimeout =
      Runnable {
        tapTimeout = null
        dispatchPendingTap()
      }.also { gestureHandler.postDelayed(it, MULTI_TAP_TIMEOUT_MS) }
  }

  private fun dispatchPendingTap() {
    val count = tapCount
    val tapPhase = pendingTapPhase
    cancelTapSequence()
    when (count) {
      1 -> when (tapPhase) {
        IslandPhase.COLLAPSED -> if (controller.settings.value.singleTapExpand) {
          Log.d(TAG, "Gesture callback compact single tap -> expand")
          beginExpand(trackChange = false)
        }
        IslandPhase.EXPANDED -> {
          Log.d(TAG, "Gesture callback expanded single tap -> reset timeout")
          resetInactivityTimer()
        }
        else -> Unit
      }
      2 -> {
        Log.d(TAG, "Gesture callback double tap -> play/pause")
        performTapHaptic(tripleTap = false)
        controller.playPause()
      }
    }
  }

  private fun performTapHaptic(tripleTap: Boolean) {
    val settings = controller.settings.value
    if (!settings.hapticsEnabled || !settings.clickHapticsEnabled) return

    val feedback =
      if (tripleTap) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
          HapticFeedbackConstants.CONFIRM
        } else {
          HapticFeedbackConstants.LONG_PRESS
        }
      } else {
        HapticFeedbackConstants.KEYBOARD_TAP
      }
    overlayView?.performHapticFeedback(feedback)
  }

  private fun performSwipeHaptic() {
    val settings = controller.settings.value
    if (!settings.hapticsEnabled || !settings.clickHapticsEnabled) return
    overlayView?.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
  }

  private fun cancelTapSequence() {
    tapTimeout?.let(gestureHandler::removeCallbacks)
    tapTimeout = null
    tapCount = 0
  }

  private fun resetGesture() {
    gesturePointerId = MotionEvent.INVALID_POINTER_ID
    gestureDownTime = 0L
    gestureActive = false
    gestureMoved = false
    gestureHandled = false
  }

  private fun distance(x: Float, y: Float): Float =
    kotlin.math.sqrt(x * x + y * y)

  private fun startSeekDrag() {
    Log.d(TAG, "Expanded seek drag started")
    isSeekDragging = true
    cancelInactivityTimer()
  }

  private fun seekDuringDrag(positionMs: Long) {
    isSeekDragging = true
    cancelInactivityTimer()
    controller.seek(positionMs)
  }

  private fun endSeekDrag() {
    Log.d(TAG, "Expanded seek drag ended")
    isSeekDragging = false
    resetInactivityTimer()
  }

  private fun startPausedAutoHide(seconds: Int, playbackKey: String) {
    if (seconds <= 0) {
      stopPausedAutoHide()
      return
    }
    if (
      hideJob?.isActive == true &&
        pausedAutoHideSeconds == seconds &&
        pausedAutoHidePlaybackKey == playbackKey
    ) {
      return
    }
    stopPausedAutoHide()
    pausedAutoHideSeconds = seconds
    pausedAutoHidePlaybackKey = playbackKey
    hideJob =
      serviceScope.launch {
        delay(seconds * 1000L)
        val currentState = controller.playback.value
        if (
          currentState != null &&
            currentState.isPaused &&
            currentState.visibilityKey() == playbackKey
        ) {
          autoHiddenPlaybackKey = playbackKey
          collapseImmediately()
          hideOverlayWithTransition()
        }
      }.also { job ->
        job.invokeOnCompletion {
          if (hideJob === job) hideJob = null
        }
      }
  }

  private fun stopPausedAutoHide() {
    hideJob?.cancel()
    hideJob = null
    pausedAutoHideSeconds = null
    pausedAutoHidePlaybackKey = null
  }

  private fun registerScreenReceiver() {
    if (screenReceiverRegistered) return
    ContextCompat.registerReceiver(
      this,
      screenReceiver,
      IntentFilter().apply {
        addAction(Intent.ACTION_SCREEN_OFF)
        addAction(Intent.ACTION_SCREEN_ON)
      },
      ContextCompat.RECEIVER_NOT_EXPORTED,
    )
    screenReceiverRegistered = true
  }

  override fun onDestroy() {
    if (activeInstance === this) activeInstance = null
    processObserver?.let { ProcessLifecycleOwner.get().lifecycle.removeObserver(it) }
    if (screenReceiverRegistered) {
      unregisterReceiver(screenReceiver)
      screenReceiverRegistered = false
    }
    removeOverlay()
    serviceScope.coroutineContext[Job]?.cancel()
    stopForeground(STOP_FOREGROUND_REMOVE)
    super.onDestroy()
  }

  override fun onBind(intent: Intent?): IBinder? = null

  private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

  private fun expandedWindowWidthDp(): Int {
    val widthPixels =
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        windowManager?.currentWindowMetrics?.bounds?.width() ?: resources.displayMetrics.widthPixels
      } else {
        resources.displayMetrics.widthPixels
      }
    val screenWidthDp = (widthPixels / resources.displayMetrics.density).toInt()
    return minOf(
      EXPANDED_WIDTH_DP,
      (screenWidthDp - 16).coerceAtLeast(compactWidthDp(controller.settings.value)),
    )
  }

  private fun createNotificationChannel() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      getSystemService(NotificationManager::class.java).createNotificationChannel(
        NotificationChannel(CHANNEL_ID, "Dynamic Island", NotificationManager.IMPORTANCE_LOW)
      )
    }
  }

  private fun buildNotification(): Notification =
    Notification.Builder(this, CHANNEL_ID)
      .setSmallIcon(android.R.drawable.ic_media_play)
      .setContentTitle("Echo Music")
      .setContentText("Dynamic Island is active")
      .setOngoing(true)
      .build()

  private var overlayOwners = OverlayOwners()

  private class OverlayOwners : LifecycleOwner, SavedStateRegistryOwner, ViewModelStoreOwner {
    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateController = SavedStateRegistryController.create(this)
    override val lifecycle: Lifecycle = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry = savedStateController.savedStateRegistry
    override val viewModelStore = ViewModelStore()

    fun start() {
      savedStateController.performAttach()
      savedStateController.performRestore(null)
      lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
      lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
      lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
    }

    fun stop() {
      if (lifecycleRegistry.currentState.isAtLeast(Lifecycle.State.CREATED)) {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
      }
      viewModelStore.clear()
    }
  }

  private class IslandOverlayComposeView(
    context: Context,
    private val shouldForwardToCompose: (Float, Float) -> Boolean,
    private val onIslandTouch: (MotionEvent) -> Boolean,
  ) : FrameLayout(context) {
    private val composeView = ComposeView(context)
    private var forwardingSeekGesture = false

    init {
      addView(
        composeView,
        LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT),
      )
    }

    fun setContent(content: @Composable () -> Unit) {
      composeView.setContent(content)
    }

    fun setViewCompositionStrategy(strategy: ViewCompositionStrategy) {
      composeView.setViewCompositionStrategy(strategy)
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
      val action = event.actionMasked
      if (action == MotionEvent.ACTION_OUTSIDE) {
        forwardingSeekGesture = false
        return onIslandTouch(event)
      }

      if (forwardingSeekGesture) {
        val handled = super.dispatchTouchEvent(event)
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
          forwardingSeekGesture = false
        }
        return handled
      }

      if (action == MotionEvent.ACTION_DOWN && shouldForwardToCompose(event.x, event.y)) {
        forwardingSeekGesture = true
        Log.d(ISLAND_LOG_TAG, "Root dispatch DOWN routed to Compose seek slider")
        return super.dispatchTouchEvent(event)
      }

      val handled = onIslandTouch(event)
      return if (handled) true else super.dispatchTouchEvent(event)
    }
  }

  private companion object {
    const val TAG = ISLAND_LOG_TAG
    @Volatile var activeInstance: IslandOverlayService? = null
    const val CHANNEL_ID = "echo_island"
    const val NOTIFICATION_ID = 1948
    const val TRACK_EXPANSION_MS = 3500L
    const val MULTI_TAP_TIMEOUT_MS = 300L
    const val COMPACT_SWIPE_THRESHOLD_DP = 36
    const val SWIPE_AXIS_RATIO = 1.2f
    const val SWIPE_FOLLOW_MAX_DP = 10
    const val COLLAPSE_ANIMATION_TIMEOUT_MS = 1500L
    const val PLAYBACK_UNAVAILABLE_DEBOUNCE_MS = 1500L
  }
}

@Composable
private fun IslandContent(
  state: IslandPlaybackState?,
  settings: IslandSettings,
  renderers: IslandRenderers?,
  phase: IslandPhase,
  onCollapseAnimationFinished: () -> Unit,
  visible: Boolean,
  swipeOffsetPx: Float,
  swipeDirection: Int,
  compactWidthDp: Int,
  centerGapDp: Int,
  isSeekDragging: () -> Boolean,
  onSeekBoundsChanged: (ComposeRect) -> Unit,
  onSeekStart: () -> Unit,
  onSeekChange: (Long) -> Unit,
  onSeekEnd: () -> Unit,
) {
  if (state == null) return
  val expandedWidthDp =
    minOf(
      EXPANDED_WIDTH_DP,
      (LocalConfiguration.current.screenWidthDp - 16).coerceAtLeast(compactWidthDp),
    )
  val isExpanded = phase == IslandPhase.EXPANDED || phase == IslandPhase.EXPANDING
  val showExpandedContent = phase == IslandPhase.EXPANDED
  val appliesStyle =
    when (settings.backgroundTarget) {
      "COLLAPSED" -> !isExpanded
      "BOTH" -> true
      else -> isExpanded
    }
  val backgroundStyle =
    if (appliesStyle) {
      if (settings.followMiniPlayerStyle) settings.miniPlayerBackgroundStyle
      else settings.backgroundStyle
    } else {
      "DEFAULT"
    }
  val paletteColors =
    if (renderers == null) {
      rememberIslandPalette(state.trackId, state.artworkUri, backgroundStyle)
    } else {
      emptyList()
    }
  var finishedMorphComponents by remember { mutableStateOf(emptySet<String>()) }
  LaunchedEffect(phase) {
    finishedMorphComponents = emptySet()
  }
  fun onMorphAnimationFinished(component: String) {
    if (phase != IslandPhase.COLLAPSING || component in finishedMorphComponents) return
    val finished = finishedMorphComponents + component
    finishedMorphComponents = finished
    if (finished.size == 3) onCollapseAnimationFinished()
  }
  val morphWidthState =
    animateDpAsState(
      targetValue =
        if (showExpandedContent) expandedWidthDp.dp else compactWidthDp.dp,
      animationSpec =
        if (phase == IslandPhase.COLLAPSING) {
          tween(180)
        } else {
          spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium,
          )
        },
      label = "islandWidth",
      finishedListener = { onMorphAnimationFinished("width") },
    )
  val morphWidth by morphWidthState
  val morphHeightState =
    animateDpAsState(
      targetValue =
        if (showExpandedContent) EXPANDED_HEIGHT_DP.dp else settings.compactHeightDp.dp,
      animationSpec =
        if (phase == IslandPhase.COLLAPSING) {
          tween(180)
        } else {
          spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium,
          )
        },
      label = "islandHeight",
      finishedListener = { onMorphAnimationFinished("height") },
    )
  val morphHeight by morphHeightState
  val cornerRadiusState =
    animateDpAsState(
      targetValue = if (showExpandedContent) 28.dp else 19.dp,
      animationSpec =
        if (phase == IslandPhase.COLLAPSING) {
          tween(180)
        } else {
          spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium,
          )
        },
      label = "islandCornerRadius",
      finishedListener = { onMorphAnimationFinished("corner") },
    )
  val cornerRadius by cornerRadiusState
  val shape = RoundedCornerShape(cornerRadius)
  val visibilityAlpha by
    animateFloatAsState(
      targetValue = if (visible) 1f else 0f,
      animationSpec = tween(ISLAND_VISIBILITY_DURATION_MS.toInt()),
      label = "islandVisibilityAlpha",
    )
  val visibilityScale by
    animateFloatAsState(
      targetValue = if (visible) 1f else ISLAND_HIDDEN_SCALE,
      animationSpec = tween(ISLAND_VISIBILITY_DURATION_MS.toInt()),
      label = "islandVisibilityScale",
    )
  val animatedSwipeOffset by
    animateFloatAsState(
      targetValue = swipeOffsetPx,
      animationSpec = tween(if (phase == IslandPhase.COLLAPSING) 180 else 120),
      label = "islandSwipeOffset",
    )
  val trackVisual = IslandTrackVisual(state.trackId, state.title, state.artist, state.artworkUri)
  Box(
    modifier =
      Modifier.fillMaxSize().graphicsLayer {
        alpha = visibilityAlpha
        scaleX = visibilityScale
        scaleY = visibilityScale
        transformOrigin = TransformOrigin(0.5f, 0f)
      },
    contentAlignment = Alignment.TopCenter,
  ) {
    Box(
      modifier =
        Modifier.width(morphWidth)
          .height(morphHeight)
          .clip(shape),
      contentAlignment = Alignment.TopCenter,
    ) {
      IslandBackgroundLayer(
        style = backgroundStyle,
        artworkUri = state.artworkUri,
        paletteColors = paletteColors,
        isCollapsed = !showExpandedContent,
        isPlaying = state.isPlaying,
        cornerRadius = cornerRadius,
        renderers = renderers,
      )
      AnimatedContent(
        targetState = showExpandedContent,
        modifier = Modifier.fillMaxSize(),
        transitionSpec = {
          fadeIn(
            tween(
              180,
              easing = androidx.compose.animation.core.LinearEasing,
            )
          ) togetherWith
            fadeOut(
              tween(
                180,
                easing = androidx.compose.animation.core.LinearEasing,
              )
            )
        },
        label = "islandContent",
      ) { showExpanded ->
        if (showExpanded) {
          ExpandedIsland(
            state = state,
            isSeekDragging = isSeekDragging,
            onSeekStart = onSeekStart,
            onSeekChange = onSeekChange,
            onSeekEnd = onSeekEnd,
            onSeekBoundsChanged = onSeekBoundsChanged,
            renderers = renderers,
            sliderStyle =
              if (settings.followPlayerSliderStyle) settings.playerSliderStyle
              else settings.sliderStyle,
            sliderSquiggly = settings.playerSliderSquiggly,
            trackVisual = trackVisual,
            swipeOffsetPx = animatedSwipeOffset,
            swipeDirection = swipeDirection,
          )
        } else {
          CompactIsland(
            state = state,
            renderers = renderers,
            trackVisual = trackVisual,
            compactHeightDp = settings.compactHeightDp,
            centerGapDp = centerGapDp,
            swipeOffsetPx = animatedSwipeOffset,
            swipeDirection = swipeDirection,
          )
        }
      }
    }
  }
}

@Composable
private fun CompactIsland(
  state: IslandPlaybackState,
  renderers: IslandRenderers?,
  trackVisual: IslandTrackVisual,
  compactHeightDp: Int,
  centerGapDp: Int,
  swipeOffsetPx: Float,
  swipeDirection: Int,
) {
  val compactRotationTransition = rememberInfiniteTransition(label = "compactIslandArtworkRotation")
  val compactRotationBy by
    compactRotationTransition.animateFloat(
      initialValue = 0f,
      targetValue = 360f,
      animationSpec =
        infiniteRepeatable(
          animation = tween(8000, easing = androidx.compose.animation.core.LinearEasing),
          repeatMode = RepeatMode.Restart,
        ),
      label = "compactIslandArtworkAngle",
    )
  val compactArtworkRotation = if (state.isPlaying) compactRotationBy else 0f
  Box(Modifier.fillMaxSize().clipToBounds(), contentAlignment = Alignment.Center) {
    AnimatedContent(
      targetState = trackVisual,
      transitionSpec = { islandTrackTransition(swipeDirection) },
      modifier =
        Modifier.fillMaxSize()
          .padding(horizontal = COMPACT_HORIZONTAL_PADDING_DP.dp),
      label = "islandCompactTrack",
    ) { track ->
      Row(
        modifier = Modifier.fillMaxSize(),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        AsyncImage(
          model = track.artworkUri,
          contentDescription = null,
          contentScale = ContentScale.Crop,
          modifier =
            Modifier.size((compactHeightDp - 4).coerceIn(16, 28).dp)
              .graphicsLayer { rotationZ = compactArtworkRotation }
              .clip(RoundedCornerShape(50)),
        )
        Spacer(Modifier.width(centerGapDp.dp).weight(1f))
        if (renderers != null) {
          renderers.Waveform(
            isPlaying = state.isPlaying,
            modifier =
              Modifier.width(COMPACT_WAVEFORM_WIDTH_DP.dp)
                .height((compactHeightDp - 8).coerceIn(12, 16).dp),
          )
        } else {
          Spacer(Modifier.width(COMPACT_WAVEFORM_WIDTH_DP.dp))
        }
      }
    }
    if (kotlin.math.abs(swipeOffsetPx) > 1f) {
      IslandSwipeHint(
        direction = swipeDirection,
        alpha =
          (kotlin.math.abs(swipeOffsetPx) / with(LocalDensity.current) { 10.dp.toPx() })
            .coerceIn(0.18f, 0.9f),
        modifier =
          Modifier.align(if (swipeDirection > 0) Alignment.CenterEnd else Alignment.CenterStart)
            .padding(horizontal = 7.dp),
      )
    }
  }
}

private data class IslandTrackVisual(
  val trackId: String?,
  val title: String,
  val artist: String,
  val artworkUri: String?,
)

@Composable
private fun ExpandedIsland(
  state: IslandPlaybackState,
  isSeekDragging: () -> Boolean,
  onSeekStart: () -> Unit,
  onSeekChange: (Long) -> Unit,
  onSeekEnd: () -> Unit,
  onSeekBoundsChanged: (ComposeRect) -> Unit,
  renderers: IslandRenderers?,
  sliderStyle: String,
  sliderSquiggly: Boolean,
  trackVisual: IslandTrackVisual,
  swipeOffsetPx: Float,
  swipeDirection: Int,
) {
  val expandedRotationTransition = rememberInfiniteTransition(label = "expandedIslandArtworkRotation")
  val expandedRotationBy by
    expandedRotationTransition.animateFloat(
      initialValue = 0f,
      targetValue = 360f,
      animationSpec =
        infiniteRepeatable(
          animation = tween(8000, easing = androidx.compose.animation.core.LinearEasing),
          repeatMode = RepeatMode.Restart,
        ),
      label = "expandedIslandArtworkAngle",
    )
  val expandedArtworkRotation = if (state.isPlaying) expandedRotationBy else 0f
  var sliderPosition by remember(state.trackId) {
    mutableStateOf(state.positionMs.toFloat())
  }
  LaunchedEffect(state.trackId, state.positionMs) {
    if (!isSeekDragging()) {
      sliderPosition = state.positionMs.toFloat()
    }
  }

  Column(Modifier.fillMaxSize().padding(12.dp)) {
    Box(Modifier.fillMaxWidth().height(42.dp).clipToBounds()) {
      AnimatedContent(
        targetState = trackVisual,
        transitionSpec = { islandTrackTransition(swipeDirection) },
        modifier =
          Modifier.fillMaxSize()
            .offset { androidx.compose.ui.unit.IntOffset(swipeOffsetPx.roundToInt(), 0) },
        label = "islandExpandedTrack",
      ) { track ->
        Row(verticalAlignment = Alignment.CenterVertically) {
          AsyncImage(
            model = track.artworkUri,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier =
              Modifier.size(42.dp)
                .graphicsLayer { rotationZ = expandedArtworkRotation }
                .clip(RoundedCornerShape(50)),
          )
          Column(modifier = Modifier.weight(1f).padding(horizontal = 10.dp)) {
            Text(track.title, color = Color.White, maxLines = 1)
            Text(track.artist, color = Color.LightGray, maxLines = 1)
          }
        }
      }
      if (kotlin.math.abs(swipeOffsetPx) > 1f) {
        IslandSwipeHint(
          direction = swipeDirection,
          alpha =
            (kotlin.math.abs(swipeOffsetPx) / with(LocalDensity.current) { 10.dp.toPx() })
              .coerceIn(0.18f, 0.9f),
          modifier =
            Modifier.align(if (swipeDirection > 0) Alignment.CenterEnd else Alignment.CenterStart)
              .padding(horizontal = 7.dp),
        )
      }
    }
    Spacer(Modifier.height(8.dp))
    if (state.durationMs > 0L) {
      val sliderModifier =
        Modifier.fillMaxWidth()
          .height(20.dp)
          .onGloballyPositioned { onSeekBoundsChanged(it.boundsInRoot()) }
          .pointerInput(onSeekStart, onSeekEnd) {
            awaitEachGesture {
              awaitFirstDown(pass = PointerEventPass.Initial)
              Log.d(ISLAND_LOG_TAG, "Compose layer seek DOWN")
              onSeekStart()
              var loggedMove = false
              try {
                do {
                  val event = awaitPointerEvent(PointerEventPass.Initial)
                  if (!loggedMove && event.changes.any { it.position != it.previousPosition }) {
                    Log.d(ISLAND_LOG_TAG, "Compose layer seek MOVE")
                    loggedMove = true
                  }
                } while (event.changes.any { it.pressed })
              } finally {
                Log.d(ISLAND_LOG_TAG, "Compose layer seek UP/CANCEL")
                onSeekEnd()
              }
            }
          }
      if (renderers != null) {
        renderers.SeekSlider(
          style = sliderStyle,
          squiggly = sliderSquiggly,
          value = sliderPosition.coerceIn(0f, state.durationMs.toFloat()),
          valueRange = 0f..state.durationMs.toFloat(),
          isPlaying = state.isPlaying,
          onValueChange = { value ->
            sliderPosition = value
            onSeekChange(value.toLong())
          },
          onValueChangeFinished = onSeekEnd,
          modifier = sliderModifier,
        )
      } else {
        Slider(
          value = sliderPosition.coerceIn(0f, state.durationMs.toFloat()),
          onValueChange = { value ->
            sliderPosition = value
            onSeekChange(value.toLong())
          },
          onValueChangeFinished = onSeekEnd,
          valueRange = 0f..state.durationMs.toFloat(),
          modifier = sliderModifier,
        )
      }
    } else {
      ProgressLine(state.positionMs, state.durationMs)
    }
  }
}

private fun islandTrackTransition(direction: Int) =
  if (direction == 0) {
    fadeIn(tween(120)) togetherWith fadeOut(tween(120))
  } else {
    (
      slideInHorizontally(
        initialOffsetX = { if (direction > 0) -it else it },
        animationSpec =
          spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium,
          ),
      ) + fadeIn(tween(120))
    ) togetherWith
      (
        slideOutHorizontally(
          targetOffsetX = { if (direction > 0) it else -it },
          animationSpec =
            spring(
              dampingRatio = Spring.DampingRatioMediumBouncy,
              stiffness = Spring.StiffnessMedium,
            ),
        ) + fadeOut(tween(120))
      )
  }

@Composable
private fun IslandSwipeHint(direction: Int, alpha: Float, modifier: Modifier = Modifier) {
  Canvas(modifier.size(14.dp)) {
    val color = Color.White.copy(alpha = alpha)
    val centerY = size.height / 2f
    val innerX = if (direction > 0) size.width * 0.35f else size.width * 0.65f
    val outerX = if (direction > 0) size.width * 0.68f else size.width * 0.32f
    val strokeWidth = 2.dp.toPx()
    drawLine(
      color,
      Offset(innerX, centerY - size.height * 0.28f),
      Offset(outerX, centerY),
      strokeWidth,
      StrokeCap.Round,
    )
    drawLine(
      color,
      Offset(outerX, centerY),
      Offset(innerX, centerY + size.height * 0.28f),
      strokeWidth,
      StrokeCap.Round,
    )
  }
}

@Composable
private fun rememberIslandPalette(
  trackId: String?,
  artworkUri: String?,
  style: String,
): List<Color> {
  val context = androidx.compose.ui.platform.LocalContext.current
  val paletteCache = remember { LinkedHashMap<String, List<Color>>() }
  var colors by remember { mutableStateOf(emptyList<Color>()) }
  val cacheKey = "${trackId.orEmpty()}\u0000${artworkUri.orEmpty()}"

  DisposableEffect(paletteCache) {
    onDispose { paletteCache.clear() }
  }
  LaunchedEffect(cacheKey, artworkUri, style) {
    if (
      artworkUri == null ||
        style !in setOf(
          "GRADIENT",
          "GLOW_ANIMATED",
          "LIVE_MESH",
          "LIQUID_GLASS",
          "APPLE_MUSIC",
        )
    ) {
      colors = emptyList()
      return@LaunchedEffect
    }

    paletteCache[cacheKey]?.let {
      colors = it
      return@LaunchedEffect
    }

    val request =
      ImageRequest.Builder(context)
        .data(artworkUri)
        .size(100, 100)
        .allowHardware(false)
        .build()
    val extractedColors =
      withContext(Dispatchers.IO) {
        when (val result = context.imageLoader.execute(request)) {
          is ErrorResult -> {
            Log.w(ISLAND_LOG_TAG, "Unable to load artwork for the island background", result.throwable)
            emptyList()
          }
          else -> {
            val bitmap = result.image?.toBitmap()
            if (bitmap == null) {
              Log.w(ISLAND_LOG_TAG, "Artwork image was empty while extracting island colors")
              emptyList()
            } else {
              withContext(Dispatchers.Default) {
                val palette =
                  Palette.from(bitmap).maximumColorCount(8).resizeBitmapArea(100 * 100).generate()
                listOfNotNull(
                    palette.vibrantSwatch?.rgb,
                    palette.darkVibrantSwatch?.rgb,
                    palette.dominantSwatch?.rgb,
                    palette.mutedSwatch?.rgb,
                  )
                  .distinct()
                  .take(4)
                  .map { Color(it) }
              }
            }
          }
        }
      }

    if (extractedColors.isNotEmpty()) {
      paletteCache[cacheKey] = extractedColors
      if (paletteCache.size > MAX_PALETTE_CACHE_SIZE) {
        paletteCache.remove(paletteCache.keys.first())
      }
    }
    colors = extractedColors
  }
  return colors
}

@Composable
private fun IslandBackgroundLayer(
  style: String,
  artworkUri: String?,
  paletteColors: List<Color>,
  isCollapsed: Boolean,
  isPlaying: Boolean,
  cornerRadius: Dp,
  renderers: IslandRenderers?,
) {
  val shape = RoundedCornerShape(cornerRadius)
  val colors = paletteColors.distinct()
  val hasValidPalette = colors.size >= 2
  val borderModifier =
    if (style == "LIQUID_GLASS") {
      Modifier.border(1.dp, Color.White.copy(alpha = if (isCollapsed) 0.12f else 0.2f), shape)
    } else {
      Modifier
    }

  Box(
    Modifier.fillMaxSize()
      .clip(shape)
      .background(Color(0xFF050505))
      .then(borderModifier)
  ) {
    if (renderers != null) {
      renderers.Background(
        style = style,
        artworkUri = artworkUri,
        isPlaying = isPlaying,
        modifier = Modifier.fillMaxSize(),
      )
    } else when (style) {
      "BLUR" -> {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
          AsyncImage(
            model = artworkUri,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize().blur(24.dp),
          )
        }
      }
      "GRADIENT",
      "APPLE_MUSIC" -> {
        if (hasValidPalette) {
          Box(
            Modifier.fillMaxSize()
              .background(Brush.verticalGradient(colors + Color(0xFF070707)))
          )
        }
      }
      "GLOW_ANIMATED",
      "LIVE_MESH" -> {
        if (hasValidPalette) {
          PaletteAnimatedBackground(
            colors = colors,
            isPlaying = isPlaying,
            liveMesh = style == "LIVE_MESH",
          )
        }
      }
      "LIQUID_GLASS" -> {
        if (hasValidPalette) {
          Box(
            Modifier.fillMaxSize()
              .background(Brush.verticalGradient(colors.map { it.copy(alpha = 0.72f) }))
          )
        }
      }
    }

    Box(
      Modifier.fillMaxSize()
        .background(Color.Black.copy(alpha = if (isCollapsed) 0.62f else 0.38f))
    )
  }
}

@Composable
private fun PaletteAnimatedBackground(
  colors: List<Color>,
  isPlaying: Boolean,
  liveMesh: Boolean,
) {
  if (!isPlaying) {
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(colors)))
    return
  }

  val transition = rememberInfiniteTransition(label = "islandArtworkBackground")
  val progress by
    transition.animateFloat(
      initialValue = 0f,
      targetValue = 1f,
      animationSpec =
        infiniteRepeatable(
          animation = tween(if (liveMesh) 18000 else 22000),
          repeatMode = RepeatMode.Restart,
        ),
      label = "islandArtworkBackgroundProgress",
    )

  Canvas(Modifier.fillMaxSize()) {
    drawRect(Color(0xFF080808))
    val maxRadius = size.maxDimension * if (liveMesh) 0.9f else 0.75f
    val firstCenter =
      Offset(
        x = size.width * (0.25f + 0.5f * progress),
        y = size.height * if (liveMesh) 0.25f else 0.5f,
      )
    val secondCenter =
      Offset(
        x =
          size.width *
            (0.75f - 0.5f * progress +
              if (liveMesh) 0.12f * sin(progress * 2f * PI).toFloat() else 0f),
        y = size.height * if (liveMesh) 0.8f else 0.45f,
      )
    drawCircle(
      brush =
        Brush.radialGradient(
          listOf(colors[0].copy(alpha = 0.78f), Color.Transparent),
          center = firstCenter,
          radius = maxRadius,
        ),
      radius = maxRadius,
      center = firstCenter,
    )
    drawCircle(
      brush =
        Brush.radialGradient(
          listOf(colors.getOrElse(1) { colors[0] }.copy(alpha = 0.66f), Color.Transparent),
          center = secondCenter,
          radius = maxRadius,
        ),
      radius = maxRadius,
      center = secondCenter,
    )
  }
}

@Composable
private fun ProgressLine(positionMs: Long, durationMs: Long) {
  Canvas(Modifier.fillMaxWidth().height(4.dp)) {
    val progress = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    drawRoundRect(Color.DarkGray, cornerRadius = androidx.compose.ui.geometry.CornerRadius(4f, 4f))
    drawRoundRect(
      Color.White,
      size = size.copy(width = size.width * progress),
      cornerRadius = androidx.compose.ui.geometry.CornerRadius(4f, 4f)
    )
  }
}
