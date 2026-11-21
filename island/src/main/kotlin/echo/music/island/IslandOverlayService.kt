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
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
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
import kotlin.math.sin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class IslandPhase {
  COLLAPSED,
  EXPANDING,
  EXPANDED,
  COLLAPSING,
}

private const val ISLAND_LOG_TAG = "IslandOverlayService"
private const val COMPACT_WIDTH_DP = 176
private const val COMPACT_HEIGHT_DP = 38
private const val EXPANDED_WIDTH_DP = 360
private const val EXPANDED_HEIGHT_DP = 112
private const val MAX_PALETTE_CACHE_SIZE = 8

@AndroidEntryPoint
class IslandOverlayService : Service() {
  @Inject lateinit var controller: IslandController

  private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
  private val gestureHandler = Handler(Looper.getMainLooper())
  private var windowManager: WindowManager? = null
  private var overlayView: ComposeView? = null
  private var overlayViewAdded = false
  private var hideJob: Job? = null
  private var inactivityJob: Job? = null
  private var trackChangeJob: Job? = null
  private var isSeekDragging = false
  private var compactTapCount = 0
  private var compactTapTimeout: Runnable? = null
  private var compactLastTapX = 0f
  private var compactLastTapY = 0f
  private var compactLastTapUpTime = 0L
  private var compactGesturePointerId = MotionEvent.INVALID_POINTER_ID
  private var compactGestureStartX = 0f
  private var compactGestureStartY = 0f
  private var compactGestureDownTime = 0L
  private var compactGestureActive = false
  private var compactGestureMoved = false
  private var compactGestureHandled = false
  private var pausedAutoHideSeconds: Int? = null
  private var phase by mutableStateOf(IslandPhase.COLLAPSED)
  private var autoHiddenPlaybackKey: String? = null
  private var appInForeground = false
  private var processObserver: DefaultLifecycleObserver? = null
  private var screenReceiverRegistered = false
  private var lastInactivitySeconds: Int? = null
  private var lastVerticalOffsetDp: Int? = null

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
    if (!controller.hasOverlayPermission()) {
      stopSelf()
      return
    }
    windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
    createNotificationChannel()
    startForeground(NOTIFICATION_ID, buildNotification())
    processObserver =
      object : DefaultLifecycleObserver {
        override fun onStart(owner: LifecycleOwner) {
          appInForeground = true
          removeOverlay()
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
        val inactivityChanged = lastInactivitySeconds?.let { previous ->
          it.inactivitySeconds != previous
        } ?: false
        val verticalOffsetChanged = lastVerticalOffsetDp?.let { previous ->
          it.verticalOffsetDp != previous
        } ?: false
        lastInactivitySeconds = it.inactivitySeconds
        lastVerticalOffsetDp = it.verticalOffsetDp
        render()
        if (verticalOffsetChanged) overlayView?.let(ViewCompat::requestApplyInsets)
        if (phase == IslandPhase.EXPANDED && inactivityChanged) {
          resetInactivityTimer(cancelTrackChange = false)
        }
      }
    }
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
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
    if (
      appInForeground ||
      !controller.hasOverlayPermission() ||
      !getSystemService(PowerManager::class.java).isInteractive ||
      resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
    ) {
      collapseImmediately()
      removeOverlay()
      return
    }
    val state = controller.playback.value ?: run {
      collapseImmediately()
      removeOverlay()
      return
    }
    val settings = controller.settings.value
    val playbackKey = state.visibilityKey()
    if (
      autoHiddenPlaybackKey != null &&
        (state.isPlaying || autoHiddenPlaybackKey != playbackKey)
    ) {
      autoHiddenPlaybackKey = null
    }
    val autoHiddenCurrentTrack = autoHiddenPlaybackKey == playbackKey
    if (!settings.enabled || autoHiddenCurrentTrack) {
      collapseImmediately()
      removeOverlay()
      return
    }
    if (state.isPlaying) {
      stopPausedAutoHide()
    } else {
      startPausedAutoHide(settings.autoHideSeconds)
    }
    val wasNewTrack = state.trackId != lastTrackId
    if (wasNewTrack) lastTrackId = state.trackId
    if (overlayView == null) {
      addOverlay()
    } else {
      overlayView?.invalidate()
    }
    if (overlayView == null) return
    if (wasNewTrack) {
      if (settings.showTrackChange) beginExpand(trackChange = true)
      else if (phase == IslandPhase.EXPANDED || phase == IslandPhase.EXPANDING) collapseImmediately()
    }
  }

  private var lastTrackId: String? = null

  private fun IslandPlaybackState.visibilityKey(): String =
    trackId ?: artworkUri ?: "$title\u0000$artist"

  private fun addOverlay() {
    overlayOwners = OverlayOwners()
    val view =
      ComposeView(this).apply {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
        setViewTreeLifecycleOwner(overlayOwners)
        setViewTreeSavedStateRegistryOwner(overlayOwners)
        setViewTreeViewModelStoreOwner(overlayOwners)
        setContent {
          IslandContent(
            state = controller.playback.collectAsState().value,
            settings = controller.settings.collectAsState().value,
            phase = phase,
            onCollapse = { beginCollapse() },
            onCollapseAnimationFinished = ::finishCollapseAnimation,
            onUserInteraction = ::resetInactivityTimer,
            isSeekDragging = { isSeekDragging },
            onSeekStart = ::startSeekDrag,
            onSeekChange = ::seekDuringDrag,
            onSeekEnd = ::endSeekDrag,
            onPlayPause = controller::playPause,
            onPrevious = controller::previous,
            onNext = controller::next,
          )
        }
        setOnTouchListener { _, event ->
          if (event.actionMasked == MotionEvent.ACTION_OUTSIDE) {
            Log.d(TAG, "Overlay received ACTION_OUTSIDE in phase=$phase")
            if (phase == IslandPhase.EXPANDED || phase == IslandPhase.EXPANDING) {
              beginCollapse()
            }
            true
          } else if (phase == IslandPhase.COLLAPSED || compactGestureActive) {
            handleCompactGesture(event)
            false
          } else {
            false
          }
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
    val params =
      WindowManager.LayoutParams(
        dp(if (compact) COMPACT_WIDTH_DP else expandedWindowWidthDp()),
        dp(if (compact) COMPACT_HEIGHT_DP else EXPANDED_HEIGHT_DP),
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
          WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
          WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
          WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
          WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT,
      ).apply {
        gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        x = initialCutoutHorizontalOffset()
        y = initialTopInset() + dp(controller.settings.value.verticalOffsetDp)
      }
    try {
      manager.addView(view, params)
      overlayViewAdded = true
      Log.d(
        TAG,
        "Overlay added: type=${params.type}, flags=0x${params.flags.toString(16)}, " +
          "size=${params.width}x${params.height}, position=(${params.x},${params.y})",
      )
      view.post {
        if (overlayView === view) {
          Log.d(
            TAG,
            "Overlay root attached=${view.isAttachedToWindow}, bounds=${view.width}x${view.height}",
          )
        }
      }
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
    val cutout =
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        view.rootWindowInsets?.displayCutout ?: currentDisplayCutout()
      } else {
        null
      }
    val cutoutTop = cutout?.safeInsetTop ?: 0
    val safeTop = maxOf(cutoutTop, insets.getInsets(WindowInsetsCompat.Type.statusBars()).top)
    val targetY = safeTop + dp(controller.settings.value.verticalOffsetDp)
    val targetX = cutoutHorizontalOffset(cutout)
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
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return 0
    return cutoutHorizontalOffset(currentDisplayCutout())
  }

  private fun currentDisplayCutout(): android.view.DisplayCutout? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
    return windowManager?.currentWindowMetrics?.windowInsets?.displayCutout
  }

  private fun cutoutHorizontalOffset(cutout: android.view.DisplayCutout?): Int {
    val topCutout =
      cutout?.boundingRects
        ?.filter { it.top <= cutout.safeInsetTop }
        ?.maxByOrNull { it.width().toLong() * it.height().toLong() }
        ?: return 0
    val displayWidth =
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        windowManager?.currentWindowMetrics?.bounds?.width() ?: resources.displayMetrics.widthPixels
      } else {
        resources.displayMetrics.widthPixels
      }
    return topCutout.centerX() - displayWidth / 2
  }

  private fun removeOverlay() {
    stopPausedAutoHide()
    cancelInactivityTimer()
    cancelCompactTapSequence()
    resetCompactGesture()
    trackChangeJob?.cancel()
    trackChangeJob = null
    isSeekDragging = false
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

  private fun beginExpand(trackChange: Boolean) {
    if (!controller.hasOverlayPermission() || appInForeground) return
    cancelCompactTapSequence()
    when (phase) {
      IslandPhase.EXPANDED,
      IslandPhase.EXPANDING -> {
        if (!trackChange) resetInactivityTimer()
      }
      IslandPhase.COLLAPSING -> return
      IslandPhase.COLLAPSED -> {
        val view = overlayView ?: return
        phase = IslandPhase.EXPANDING
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
      }
    }
  }

  private fun finishCollapseAnimation() {
    if (phase != IslandPhase.COLLAPSING) return
    resizeOverlay(expanded = false)
    if (overlayView?.isAttachedToWindow != true) {
      removeOverlay()
      return
    }
    phase = IslandPhase.COLLAPSED
    isSeekDragging = false
  }

  private fun collapseImmediately() {
    cancelCompactTapSequence()
    resetCompactGesture()
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

  private fun resizeOverlay(
    expanded: Boolean,
    viewOverride: View? = null,
    paramsOverride: WindowManager.LayoutParams? = null,
  ) {
    val view = viewOverride ?: overlayView ?: return
    val params = paramsOverride ?: view.layoutParams as? WindowManager.LayoutParams ?: return
    params.width = dp(if (expanded) expandedWindowWidthDp() else COMPACT_WIDTH_DP)
    params.height = dp(if (expanded) EXPANDED_HEIGHT_DP else COMPACT_HEIGHT_DP)
    val cutout =
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        view.rootWindowInsets?.displayCutout ?: currentDisplayCutout()
      } else {
        null
      }
    params.x = cutoutHorizontalOffset(cutout)
    params.y = dp(controller.settings.value.verticalOffsetDp) +
      getStatusAndCutoutTop(view)
    updateOverlayLayout(view, params)
  }

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
    if (phase != IslandPhase.EXPANDED || isSeekDragging) return
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

  private fun handleCompactGesture(event: MotionEvent) {
    when (event.actionMasked) {
      MotionEvent.ACTION_DOWN -> {
        Log.d(
          TAG,
          "Compact touch DOWN: (${event.x},${event.y}), " +
            "bounds=${overlayView?.width}x${overlayView?.height}, phase=$phase",
        )
        if (phase != IslandPhase.COLLAPSED || event.pointerCount != 1) {
          cancelCompactTapSequence()
          resetCompactGesture()
          return
        }

        val tapSlop = ViewConfiguration.get(this).scaledDoubleTapSlop.toFloat()
        val elapsedSinceTap = event.eventTime - compactLastTapUpTime
        if (
          compactTapCount > 0 &&
            (elapsedSinceTap > MULTI_TAP_TIMEOUT_MS ||
              distance(event.x - compactLastTapX, event.y - compactLastTapY) > tapSlop)
        ) {
          dispatchPendingCompactTap()
          if (phase != IslandPhase.COLLAPSED) return
        }

        compactTapTimeout?.let(gestureHandler::removeCallbacks)
        compactTapTimeout = null
        compactGesturePointerId = event.getPointerId(0)
        compactGestureStartX = event.x
        compactGestureStartY = event.y
        compactGestureDownTime = event.downTime
        compactGestureActive = true
        compactGestureMoved = false
        compactGestureHandled = false
      }

      MotionEvent.ACTION_MOVE -> {
        if (!compactGestureActive) return
        val pointerIndex = event.findPointerIndex(compactGesturePointerId)
        if (pointerIndex < 0) {
          cancelCompactTapSequence()
          resetCompactGesture()
          return
        }

        val deltaX = event.getX(pointerIndex) - compactGestureStartX
        val deltaY = event.getY(pointerIndex) - compactGestureStartY
        val touchSlop = ViewConfiguration.get(this).scaledTouchSlop.toFloat()
        if (!compactGestureMoved && distance(deltaX, deltaY) > touchSlop) {
          compactGestureMoved = true
          cancelCompactTapSequence()
        }

        val swipeThreshold = maxOf(touchSlop * 2f, dp(COMPACT_SWIPE_THRESHOLD_DP).toFloat())
        if (
          compactGestureMoved &&
            !compactGestureHandled &&
            kotlin.math.abs(deltaX) >= swipeThreshold &&
            kotlin.math.abs(deltaX) > kotlin.math.abs(deltaY) * SWIPE_AXIS_RATIO
        ) {
          compactGestureHandled = true
          if (deltaX > 0f) {
            Log.d(TAG, "Compact horizontal swipe -> next")
            controller.next()
          } else {
            Log.d(TAG, "Compact horizontal swipe -> previous")
            controller.previous()
          }
        }
      }

      MotionEvent.ACTION_UP -> {
        Log.d(
          TAG,
          "Compact touch UP: moved=$compactGestureMoved, handled=$compactGestureHandled, " +
            "elapsed=${event.eventTime - compactGestureDownTime}ms",
        )
        if (compactGestureActive &&
          !compactGestureMoved &&
          !compactGestureHandled &&
          event.eventTime - compactGestureDownTime <= ViewConfiguration.getLongPressTimeout() &&
          phase == IslandPhase.COLLAPSED
        ) {
          recordCompactTap(event.x, event.y, event.eventTime)
        } else if (compactGestureActive && !compactGestureMoved && !compactGestureHandled) {
          cancelCompactTapSequence()
        }
        resetCompactGesture()
      }

      MotionEvent.ACTION_POINTER_DOWN,
      MotionEvent.ACTION_POINTER_UP,
      MotionEvent.ACTION_CANCEL -> {
        cancelCompactTapSequence()
        resetCompactGesture()
      }
    }
  }

  private fun recordCompactTap(x: Float, y: Float, eventTime: Long) {
    compactTapCount += 1
    compactLastTapX = x
    compactLastTapY = y
    compactLastTapUpTime = eventTime
    if (compactTapCount == 3) {
      cancelCompactTapSequence()
      Log.d(TAG, "Compact triple tap -> open app")
      performTapHaptic(tripleTap = true)
      controller.openApp()
      return
    }

    compactTapTimeout =
      Runnable {
        compactTapTimeout = null
        dispatchPendingCompactTap()
      }.also { gestureHandler.postDelayed(it, MULTI_TAP_TIMEOUT_MS) }
  }

  private fun dispatchPendingCompactTap() {
    val tapCount = compactTapCount
    cancelCompactTapSequence()
    when (tapCount) {
      1 -> if (controller.settings.value.singleTapExpand) {
        Log.d(TAG, "Compact single tap -> expand")
        beginExpand(trackChange = false)
      }
      2 -> {
        Log.d(TAG, "Compact double tap -> play/pause")
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

  private fun cancelCompactTapSequence() {
    compactTapTimeout?.let(gestureHandler::removeCallbacks)
    compactTapTimeout = null
    compactTapCount = 0
  }

  private fun resetCompactGesture() {
    compactGesturePointerId = MotionEvent.INVALID_POINTER_ID
    compactGestureDownTime = 0L
    compactGestureActive = false
    compactGestureMoved = false
    compactGestureHandled = false
  }

  private fun distance(x: Float, y: Float): Float =
    kotlin.math.sqrt(x * x + y * y)

  private fun startSeekDrag() {
    isSeekDragging = true
    cancelInactivityTimer()
  }

  private fun seekDuringDrag(positionMs: Long) {
    isSeekDragging = true
    cancelInactivityTimer()
    controller.seek(positionMs)
  }

  private fun endSeekDrag() {
    isSeekDragging = false
    resetInactivityTimer()
  }

  private fun startPausedAutoHide(seconds: Int) {
    if (seconds <= 0) {
      stopPausedAutoHide()
      return
    }
    if (hideJob?.isActive == true && pausedAutoHideSeconds == seconds) return
    stopPausedAutoHide()
    pausedAutoHideSeconds = seconds
    hideJob =
      serviceScope.launch {
        delay(seconds * 1000L)
        val state = controller.playback.value
        if (state != null && !state.isPlaying) {
          autoHiddenPlaybackKey = state.visibilityKey()
        }
        removeOverlay()
      }
  }

  private fun stopPausedAutoHide() {
    hideJob?.cancel()
    hideJob = null
    pausedAutoHideSeconds = null
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
    return minOf(EXPANDED_WIDTH_DP, (screenWidthDp - 16).coerceAtLeast(COMPACT_WIDTH_DP))
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

  private companion object {
    const val TAG = ISLAND_LOG_TAG
    const val CHANNEL_ID = "echo_island"
    const val NOTIFICATION_ID = 1948
    const val TRACK_EXPANSION_MS = 3500L
    const val MULTI_TAP_TIMEOUT_MS = 300L
    const val COMPACT_SWIPE_THRESHOLD_DP = 36
    const val SWIPE_AXIS_RATIO = 1.2f
  }
}

@Composable
private fun IslandContent(
  state: IslandPlaybackState?,
  settings: IslandSettings,
  phase: IslandPhase,
  onCollapse: () -> Unit,
  onCollapseAnimationFinished: () -> Unit,
  onUserInteraction: () -> Unit,
  isSeekDragging: () -> Boolean,
  onSeekStart: () -> Unit,
  onSeekChange: (Long) -> Unit,
  onSeekEnd: () -> Unit,
  onPlayPause: () -> Unit,
  onPrevious: () -> Unit,
  onNext: () -> Unit,
) {
  if (state == null) return
  val expandedWidthDp =
    minOf(
      EXPANDED_WIDTH_DP,
      (LocalConfiguration.current.screenWidthDp - 16).coerceAtLeast(COMPACT_WIDTH_DP),
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
  val paletteColors = rememberIslandPalette(state.trackId, state.artworkUri, backgroundStyle)
    val morphWidthState =
      animateDpAsState(
      targetValue = if (showExpandedContent) expandedWidthDp.dp else COMPACT_WIDTH_DP.dp,
      animationSpec =
        spring(
          dampingRatio = Spring.DampingRatioMediumBouncy,
          stiffness = Spring.StiffnessMedium,
        ),
      label = "islandWidth",
    )
  val morphWidth by morphWidthState
  val morphHeightState =
    animateDpAsState(
      targetValue = if (showExpandedContent) EXPANDED_HEIGHT_DP.dp else COMPACT_HEIGHT_DP.dp,
      animationSpec =
        spring(
          dampingRatio = Spring.DampingRatioMediumBouncy,
          stiffness = Spring.StiffnessMedium,
        ),
      label = "islandHeight",
    )
  val morphHeight by morphHeightState
  val cornerRadiusState =
    animateDpAsState(
      targetValue = if (showExpandedContent) 28.dp else 19.dp,
      animationSpec =
        spring(
          dampingRatio = Spring.DampingRatioMediumBouncy,
          stiffness = Spring.StiffnessMedium,
        ),
      label = "islandCornerRadius",
    )
  val cornerRadius by cornerRadiusState
  LaunchedEffect(phase) {
    if (phase != IslandPhase.COLLAPSING) return@LaunchedEffect
    snapshotFlow {
      morphWidthState.value == COMPACT_WIDTH_DP.dp &&
        morphHeightState.value == COMPACT_HEIGHT_DP.dp &&
        cornerRadiusState.value == 19.dp
    }.first { it }
    onCollapseAnimationFinished()
  }
  val shape = RoundedCornerShape(cornerRadius)
  val interactionSource = remember { MutableInteractionSource() }
  val swipeCollapseThresholdPx = with(LocalDensity.current) { 12.dp.toPx() }
  Box(
    modifier = Modifier.fillMaxSize(),
    contentAlignment = Alignment.TopCenter,
  ) {
    Box(
      modifier =
        Modifier.width(morphWidth)
          .height(morphHeight)
          .clip(shape)
          .then(
            if (isExpanded) {
              Modifier.clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onUserInteraction,
              )
            } else {
              Modifier
            }
          )
          .pointerInput(isExpanded) {
            if (!isExpanded) return@pointerInput
            awaitEachGesture {
              awaitFirstDown(pass = PointerEventPass.Initial)
              onUserInteraction()
              do {
                val event = awaitPointerEvent(PointerEventPass.Initial)
              } while (event.changes.any { it.pressed })
            }
          }
          .pointerInput(isExpanded) {
            if (!isExpanded) return@pointerInput
            var dragStartedOnSlider = false
            var totalDragX = 0f
            var totalDragY = 0f
            detectDragGestures(
              onDragStart = {
                dragStartedOnSlider = isSeekDragging()
                totalDragX = 0f
                totalDragY = 0f
              },
              onDrag = { _, amount ->
                totalDragX += amount.x
                totalDragY += amount.y
                if (!dragStartedOnSlider &&
                  totalDragY < -swipeCollapseThresholdPx &&
                  kotlin.math.abs(totalDragY) > kotlin.math.abs(totalDragX) * 1.15f
                ) {
                  onCollapse()
                }
              },
              onDragEnd = {},
              onDragCancel = {},
            )
          },
      contentAlignment = Alignment.TopCenter,
    ) {
      IslandBackgroundLayer(
        style = backgroundStyle,
        artworkUri = state.artworkUri,
        paletteColors = paletteColors,
        isCollapsed = !showExpandedContent,
        isPlaying = state.isPlaying,
        cornerRadius = cornerRadius,
      )
      AnimatedContent(
        targetState = showExpandedContent,
        modifier = Modifier.fillMaxSize(),
        label = "islandContent",
      ) { showExpanded ->
        if (showExpanded) {
          ExpandedIsland(
            state = state,
            isSeekDragging = isSeekDragging,
            onUserInteraction = onUserInteraction,
            onSeekStart = onSeekStart,
            onSeekChange = onSeekChange,
            onSeekEnd = onSeekEnd,
            onPlayPause = onPlayPause,
            onPrevious = onPrevious,
            onNext = onNext,
          )
        } else {
          CompactIsland(state)
        }
      }
    }
  }
}

@Composable
private fun CompactIsland(state: IslandPlaybackState) {
  Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
    Row(
      modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 5.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.Center,
    ) {
      AsyncImage(
        model = state.artworkUri,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = Modifier.size(28.dp).clip(RoundedCornerShape(50)),
      )
      Spacer(Modifier.width(8.dp))
      EqualizerBars(isPlaying = state.isPlaying)
    }
  }
}

@Composable
private fun ExpandedIsland(
  state: IslandPlaybackState,
  isSeekDragging: () -> Boolean,
  onUserInteraction: () -> Unit,
  onSeekStart: () -> Unit,
  onSeekChange: (Long) -> Unit,
  onSeekEnd: () -> Unit,
  onPlayPause: () -> Unit,
  onPrevious: () -> Unit,
  onNext: () -> Unit,
) {
  var sliderPosition by remember(state.trackId) {
    mutableStateOf(state.positionMs.toFloat())
  }
  LaunchedEffect(state.trackId, state.positionMs) {
    if (!isSeekDragging()) {
      sliderPosition = state.positionMs.toFloat()
    }
  }

  Column(Modifier.fillMaxSize().padding(12.dp)) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      AsyncImage(
        model = state.artworkUri,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier =
          Modifier.size(42.dp).clickable {
            onUserInteraction()
          },
      )
      Column(modifier = Modifier.weight(1f).padding(horizontal = 10.dp)) {
        Text(state.title, color = Color.White, maxLines = 1)
        Text(state.artist, color = Color.LightGray, maxLines = 1)
      }
      Text(
        "‹",
        color = Color.White,
        modifier =
          Modifier.clickable {
            onUserInteraction()
            onPrevious()
          }.padding(8.dp),
      )
      Text(
        if (state.isPlaying) "Ⅱ" else "▶",
        color = Color.White,
        modifier =
          Modifier.clickable {
            onUserInteraction()
            onPlayPause()
          }.padding(8.dp),
      )
      Text(
        "›",
        color = Color.White,
        modifier =
          Modifier.clickable {
            onUserInteraction()
            onNext()
          }.padding(8.dp),
      )
    }
    Spacer(Modifier.height(8.dp))
    if (state.durationMs > 0L) {
      Slider(
          value = sliderPosition.coerceIn(0f, state.durationMs.toFloat()),
          onValueChange = { value ->
            sliderPosition = value
            onSeekChange(value.toLong())
          },
          onValueChangeFinished = onSeekEnd,
          valueRange = 0f..state.durationMs.toFloat(),
          modifier =
            Modifier.fillMaxWidth()
              .height(20.dp)
              .pointerInput(onSeekStart, onSeekEnd) {
                awaitEachGesture {
                  awaitFirstDown(pass = PointerEventPass.Initial)
                  onSeekStart()
                  try {
                    do {
                      val event = awaitPointerEvent(PointerEventPass.Initial)
                    } while (event.changes.any { it.pressed })
                  } finally {
                    onSeekEnd()
                  }
                }
              },
      )
    } else {
      ProgressLine(state.positionMs, state.durationMs)
    }
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
) {
  val shape = RoundedCornerShape(cornerRadius)
  val fallbackColors = listOf(Color(0xFF30202A), Color(0xFF171217), Color(0xFF090909))
  val colors =
    when {
      paletteColors.size >= 2 -> paletteColors
      paletteColors.size == 1 -> paletteColors + fallbackColors.drop(1)
      else -> fallbackColors
    }
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
    when (style) {
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
        Box(
          Modifier.fillMaxSize()
            .background(Brush.verticalGradient(colors + Color(0xFF070707)))
        )
      }
      "GLOW_ANIMATED",
      "LIVE_MESH" -> {
        PaletteAnimatedBackground(
          colors = colors,
          isPlaying = isPlaying,
          liveMesh = style == "LIVE_MESH",
        )
      }
      "LIQUID_GLASS" -> {
        Box(
          Modifier.fillMaxSize()
            .background(Brush.verticalGradient(colors.map { it.copy(alpha = 0.72f) }))
        )
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

@Composable
private fun EqualizerBars(isPlaying: Boolean) {
  if (!isPlaying) {
    Row(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.Bottom) {
      listOf(4, 6, 6, 4).forEach { height ->
        Box(
          Modifier.width(2.dp).height(height.dp)
            .background(Color.White, RoundedCornerShape(2.dp))
        )
      }
    }
    return
  }

  val transition = rememberInfiniteTransition(label = "islandBars")
  val phase by transition.animateFloat(
    0f,
    1f,
    infiniteRepeatable(tween(650), RepeatMode.Reverse),
    label = "barPhase",
  )
  Row(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.Bottom) {
    repeat(4) { index ->
      val wave =
        ((sin((phase.toDouble() + index * 0.21) * 2.0 * PI).toFloat()) + 1f) / 2f
      Box(
        Modifier.width(2.dp).height((4f + wave * 9f).dp)
          .background(Color.White, RoundedCornerShape(2.dp))
      )
    }
  }
}
