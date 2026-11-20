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
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
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
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

private enum class IslandPhase {
  COLLAPSED,
  EXPANDING,
  EXPANDED,
  COLLAPSING,
}

@AndroidEntryPoint
class IslandOverlayService : Service() {
  @Inject lateinit var controller: IslandController

  private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
  private var windowManager: WindowManager? = null
  private var overlayView: ComposeView? = null
  private var hideJob: Job? = null
  private var inactivityJob: Job? = null
  private var trackChangeJob: Job? = null
  private var collapseJob: Job? = null
  private var isSeekDragging = false
  private var pausedAutoHideSeconds: Int? = null
  private var phase by mutableStateOf(IslandPhase.COLLAPSED)
  private var dismissedTrackId: String? = null
  private var appInForeground = false
  private var processObserver: DefaultLifecycleObserver? = null
  private var screenReceiverRegistered = false

  private val screenReceiver =
    object : BroadcastReceiver() {
      override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_SCREEN_OFF) {
          collapseImmediately()
          stopPausedAutoHide()
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
        render()
        if (phase == IslandPhase.EXPANDED) resetInactivityTimer()
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
    if (!settings.enabled || dismissedTrackId == state.trackId) {
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
    if (wasNewTrack) {
      lastTrackId = state.trackId
      if (settings.showTrackChange) beginExpand(trackChange = true)
      else if (phase == IslandPhase.EXPANDED || phase == IslandPhase.EXPANDING) collapseImmediately()
    }
    if (overlayView == null) {
      addOverlay()
    } else {
      overlayView?.invalidate()
    }
  }

  private var lastTrackId: String? = null

  private fun addOverlay() {
    overlayOwners = OverlayOwners()
    val view =
      ComposeView(this).apply {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        setViewTreeLifecycleOwner(overlayOwners)
        setViewTreeSavedStateRegistryOwner(overlayOwners)
        setViewTreeViewModelStoreOwner(overlayOwners)
        setContent {
          IslandContent(
            state = controller.playback.collectAsState().value,
            phase = phase,
            onExpand = { beginExpand(trackChange = false) },
            onCollapse = { beginCollapse() },
            onUserInteraction = ::resetInactivityTimer,
            isSeekDragging = { isSeekDragging },
            onSeekStart = ::startSeekDrag,
            onSeekChange = ::seekDuringDrag,
            onSeekEnd = ::endSeekDrag,
            onDismiss = {
              dismissedTrackId = controller.playback.value?.trackId
              removeOverlay()
            },
            onOpenApp = controller::openApp,
            onPlayPause = controller::playPause,
            onPrevious = controller::previous,
            onNext = controller::next,
          )
        }
        setOnTouchListener { _, event ->
          if (event.action == MotionEvent.ACTION_OUTSIDE) {
            if (phase == IslandPhase.EXPANDED || phase == IslandPhase.EXPANDING) {
              beginCollapse()
            }
          }
          false
        }
        ViewCompat.setOnApplyWindowInsetsListener(this) { _, insets ->
          updateOverlayPosition(insets)
          insets
        }
      }
    overlayView = view
    overlayOwners.start()
    val compact = isCompactPhase(phase)
    val params =
      WindowManager.LayoutParams(
        dp(if (compact) COMPACT_WIDTH_DP else EXPANDED_WIDTH_DP),
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
        y = dp(controller.settings.value.verticalOffsetDp)
      }
    try {
      windowManager?.addView(view, params)
    } catch (error: WindowManager.BadTokenException) {
      Log.e(TAG, "Unable to attach the island overlay window", error)
      overlayView = null
      overlayOwners.stop()
      stopSelf()
    } catch (error: IllegalArgumentException) {
      Log.e(TAG, "Invalid island overlay window parameters", error)
      overlayView = null
      overlayOwners.stop()
      stopSelf()
    } catch (error: SecurityException) {
      Log.e(TAG, "Overlay permission was revoked while attaching the island", error)
      overlayView = null
      overlayOwners.stop()
      stopSelf()
    }
  }

  private fun updateOverlayPosition(insets: WindowInsetsCompat) {
    val view = overlayView ?: return
    val params = view.layoutParams as? WindowManager.LayoutParams ?: return
    val cutoutTop =
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        view.rootWindowInsets?.displayCutout?.safeInsetTop ?: 0
      } else {
        0
      }
    val safeTop = maxOf(cutoutTop, insets.getInsets(WindowInsetsCompat.Type.statusBars()).top)
    val targetY = safeTop + dp(controller.settings.value.verticalOffsetDp)
    if (params.y != targetY) {
      params.y = targetY
      updateOverlayLayout(view, params)
    }
  }

  private fun removeOverlay() {
    stopPausedAutoHide()
    cancelInactivityTimer()
    trackChangeJob?.cancel()
    trackChangeJob = null
    collapseJob?.cancel()
    collapseJob = null
    isSeekDragging = false
    phase = IslandPhase.COLLAPSED
    val manager = windowManager
    val view = overlayView
    if (manager != null && view != null && view.isAttachedToWindow) {
      try {
        manager.removeView(view)
      } catch (error: WindowManager.BadTokenException) {
        Log.w(TAG, "Island overlay was already detached during removal", error)
      } catch (error: IllegalArgumentException) {
        Log.w(TAG, "Island overlay could not be removed", error)
      } catch (error: IllegalStateException) {
        Log.w(TAG, "Island overlay was already removed", error)
      } catch (error: SecurityException) {
        Log.e(TAG, "Overlay permission was revoked while removing the island", error)
      }
    }
    if (view?.isAttachedToWindow == true && manager != null) {
      try {
        manager.removeViewImmediate(view)
      } catch (error: WindowManager.BadTokenException) {
        Log.w(TAG, "Island overlay token expired during immediate removal", error)
      } catch (error: IllegalArgumentException) {
        Log.w(TAG, "Island overlay was not registered during immediate removal", error)
      } catch (error: IllegalStateException) {
        Log.w(TAG, "Island overlay was already removed during immediate removal", error)
      } catch (error: SecurityException) {
        Log.e(TAG, "Overlay permission was revoked during immediate removal", error)
      }
    }
    if (view == null || !view.isAttachedToWindow) {
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
    when (phase) {
      IslandPhase.EXPANDED,
      IslandPhase.EXPANDING -> {
        if (!trackChange) resetInactivityTimer()
      }
      IslandPhase.COLLAPSING -> return
      IslandPhase.COLLAPSED -> {
        phase = IslandPhase.EXPANDING
        resizeOverlay(expanded = true)
        overlayView?.invalidate()
        phase = IslandPhase.EXPANDED
        resetInactivityTimer()
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
      IslandPhase.EXPANDING,
      IslandPhase.EXPANDED -> {
        phase = IslandPhase.COLLAPSING
        cancelInactivityTimer()
        overlayView?.invalidate()
        collapseJob?.cancel()
        collapseJob =
          serviceScope.launch {
            delay(COLLAPSE_ANIMATION_MS)
            if (phase != IslandPhase.COLLAPSING) return@launch
            resizeOverlay(expanded = false)
            phase = IslandPhase.COLLAPSED
            isSeekDragging = false
            collapseJob = null
          }
      }
    }
  }

  private fun collapseImmediately() {
    collapseJob?.cancel()
    collapseJob = null
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
    params.width = dp(if (expanded) EXPANDED_WIDTH_DP else COMPACT_WIDTH_DP)
    params.height = dp(if (expanded) EXPANDED_HEIGHT_DP else COMPACT_HEIGHT_DP)
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
    }
  }

  private fun getStatusAndCutoutTop(view: View): Int {
    val insetTop =
      view.rootWindowInsets?.let { insets ->
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
        maxOf(statusTop, cutoutTop)
      } ?: 0
    return insetTop
  }

  private fun resetInactivityTimer() {
    trackChangeJob?.cancel()
    trackChangeJob = null
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
      IntentFilter().apply { addAction(Intent.ACTION_SCREEN_OFF) },
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
    collapseJob?.cancel()
    removeOverlay()
    serviceScope.coroutineContext[Job]?.cancel()
    stopForeground(STOP_FOREGROUND_REMOVE)
    super.onDestroy()
  }

  override fun onBind(intent: Intent?): IBinder? = null

  private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

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
    const val TAG = "IslandOverlayService"
    const val CHANNEL_ID = "echo_island"
    const val NOTIFICATION_ID = 1948
    const val TRACK_EXPANSION_MS = 3500L
    const val COLLAPSE_ANIMATION_MS = 220L
    const val COMPACT_WIDTH_DP = 176
    const val COMPACT_HEIGHT_DP = 38
    const val EXPANDED_WIDTH_DP = 360
    const val EXPANDED_HEIGHT_DP = 112
  }
}

@Composable
private fun IslandContent(
  state: IslandPlaybackState?,
  phase: IslandPhase,
  onExpand: () -> Unit,
  onCollapse: () -> Unit,
  onUserInteraction: () -> Unit,
  isSeekDragging: () -> Boolean,
  onSeekStart: () -> Unit,
  onSeekChange: (Long) -> Unit,
  onSeekEnd: () -> Unit,
  onDismiss: () -> Unit,
  onOpenApp: () -> Unit,
  onPlayPause: () -> Unit,
  onPrevious: () -> Unit,
  onNext: () -> Unit,
) {
  if (state == null) return
  val isExpanded = phase == IslandPhase.EXPANDED || phase == IslandPhase.EXPANDING
  val interactionSource = remember { MutableInteractionSource() }
  Box(
    modifier =
      Modifier.fillMaxSize()
        .padding(horizontal = 8.dp)
        .clickable(
          interactionSource = interactionSource,
          indication = null,
        ) {
          if (isExpanded) onUserInteraction() else onExpand()
        }
        .pointerInput(isExpanded) {
          if (!isExpanded) return@pointerInput
          var dragStartedOnSlider = false
          detectDragGestures(
            onDragStart = { dragStartedOnSlider = isSeekDragging() },
            onDrag = { _, amount ->
              if (!dragStartedOnSlider &&
                amount.y < -12f &&
                kotlin.math.abs(amount.y) > kotlin.math.abs(amount.x) * 1.15f
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
    AnimatedContent(
      targetState = isExpanded,
      modifier = Modifier.animateContentSize(),
      label = "islandShape",
    ) { showExpanded ->
      if (showExpanded) {
        ExpandedIsland(
          state,
          onUserInteraction,
          onSeekStart,
          onSeekChange,
          onSeekEnd,
          onPlayPause,
          onPrevious,
          onNext,
        )
      } else {
        CompactIsland(state)
      }
    }
  }
}

@Composable
private fun CompactIsland(
  state: IslandPlaybackState,
) {
  Row(
    modifier =
      Modifier.background(Color.Black, RoundedCornerShape(32.dp))
        .padding(horizontal = 10.dp, vertical = 7.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    AsyncImage(
      model = state.artworkUri,
      contentDescription = null,
      contentScale = ContentScale.Crop,
      modifier = Modifier.size(28.dp),
    )
    EqualizerBars()
  }
}

@Composable
private fun ExpandedIsland(
  state: IslandPlaybackState,
  onUserInteraction: () -> Unit,
  onSeekStart: () -> Unit,
  onSeekChange: (Long) -> Unit,
  onSeekEnd: () -> Unit,
  onPlayPause: () -> Unit,
  onPrevious: () -> Unit,
  onNext: () -> Unit,
) {
  Column(
    modifier =
      Modifier.background(Color.Black, RoundedCornerShape(28.dp))
        .padding(12.dp)
  ) {
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
        value = state.positionMs.toFloat().coerceIn(0f, state.durationMs.toFloat()),
        onValueChange = { value ->
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
private fun EqualizerBars() {
  val transition = rememberInfiniteTransition(label = "islandBars")
  val phase by transition.animateFloat(
    0.35f,
    1f,
    infiniteRepeatable(tween(420), RepeatMode.Reverse),
    label = "barPhase",
  )
  Row(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.Bottom) {
    repeat(4) { index ->
      Box(
        Modifier.width(2.dp).height((8 + index * 3).dp * phase)
          .background(Color.White, RoundedCornerShape(2.dp))
      )
    }
  }
}
