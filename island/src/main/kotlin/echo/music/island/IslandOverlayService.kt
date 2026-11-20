package echo.music.island

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.Gravity
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
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.width
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

@AndroidEntryPoint
class IslandOverlayService : Service() {
  @Inject lateinit var controller: IslandController

  private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
  private var windowManager: WindowManager? = null
  private var overlayView: ComposeView? = null
  private var hideJob: Job? = null
  private var expanded by mutableStateOf(false)
  private var dismissedTrackId: String? = null
  private var appInForeground = false
  private var processObserver: DefaultLifecycleObserver? = null

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
    serviceScope.launch {
      controller.playback.collectLatest {
        render()
      }
    }
    serviceScope.launch {
      controller.settings.collectLatest {
        render()
      }
    }
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    render()
    return START_NOT_STICKY
  }

  private fun render() {
    if (appInForeground || !controller.hasOverlayPermission()) {
      removeOverlay()
      return
    }
    val state = controller.playback.value ?: run {
      removeOverlay()
      return
    }
    val settings = controller.settings.value
    if (!settings.enabled || dismissedTrackId == state.trackId) {
      removeOverlay()
      return
    }
    if (state.isPlaying) {
      hideJob?.cancel()
    } else {
      hideJob?.cancel()
      hideJob =
        serviceScope.launch {
          delay(settings.autoHideSeconds.coerceAtLeast(5) * 1000L)
          removeOverlay()
        }
    }
    val wasNewTrack = state.trackId != lastTrackId
    if (wasNewTrack) {
      lastTrackId = state.trackId
      expanded = settings.showTrackChange
      if (expanded) {
        serviceScope.launch {
          delay(TRACK_EXPANSION_MS)
          expanded = false
          overlayView?.invalidate()
        }
      }
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
            expanded = expanded,
            onExpand = { expanded = true; invalidate() },
            onDismiss = {
              dismissedTrackId = controller.playback.value?.trackId
              removeOverlay()
            },
            onOpenApp = controller::openApp,
            onPlayPause = controller::playPause,
            onPrevious = controller::previous,
            onNext = controller::next,
            onSeek = controller::seek,
          )
        }
        ViewCompat.setOnApplyWindowInsetsListener(this) { _, insets ->
          updateOverlayPosition(insets)
          insets
        }
      }
    overlayView = view
    overlayOwners.start()
    val params =
      WindowManager.LayoutParams(
        dp(340),
        dp(112),
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
          WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT,
      ).apply {
        gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        y = dp(controller.settings.value.verticalOffsetDp)
      }
    try {
      windowManager?.addView(view, params)
    } catch (error: WindowManager.BadTokenException) {
      overlayView = null
      overlayOwners.stop()
      stopSelf()
    } catch (error: SecurityException) {
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
      windowManager?.updateViewLayout(view, params)
    }
  }

  private fun removeOverlay() {
    hideJob?.cancel()
    val manager = windowManager ?: return
    overlayView?.let {
      try {
        manager.removeView(it)
      } catch (_: IllegalArgumentException) {
        // The window may already have been removed by the system.
      }
      overlayView = null
    }
    overlayOwners.stop()
  }

  override fun onDestroy() {
    processObserver?.let { ProcessLifecycleOwner.get().lifecycle.removeObserver(it) }
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
    const val CHANNEL_ID = "echo_island"
    const val NOTIFICATION_ID = 1948
    const val TRACK_EXPANSION_MS = 3500L
  }
}

@Composable
private fun IslandContent(
  state: IslandPlaybackState?,
  expanded: Boolean,
  onExpand: () -> Unit,
  onDismiss: () -> Unit,
  onOpenApp: () -> Unit,
  onPlayPause: () -> Unit,
  onPrevious: () -> Unit,
  onNext: () -> Unit,
  onSeek: (Long) -> Unit,
) {
  if (state == null) return
  Box(
    modifier =
      Modifier.fillMaxSize()
        .padding(horizontal = 8.dp)
        .pointerInput(Unit) {
          detectDragGestures(
            onDragEnd = {},
            onDragCancel = {},
            onDrag = { _, amount ->
              if (amount.y < -12f) onDismiss()
            },
          )
        },
    contentAlignment = Alignment.TopCenter,
  ) {
    AnimatedContent(
      targetState = expanded,
      modifier = Modifier.animateContentSize(),
      label = "islandShape",
    ) { isExpanded ->
      if (isExpanded) {
        ExpandedIsland(
          state,
          onCollapse = onExpand,
          onOpenApp,
          onPlayPause,
          onPrevious,
          onNext,
          onSeek,
        )
      } else {
        CompactIsland(state, onExpand, onOpenApp)
      }
    }
  }
}

@Composable
private fun CompactIsland(
  state: IslandPlaybackState,
  onExpand: () -> Unit,
  onOpenApp: () -> Unit,
) {
  Row(
    modifier =
      Modifier.background(Color.Black, RoundedCornerShape(32.dp))
        .clickable(onClick = onExpand)
        .padding(horizontal = 10.dp, vertical = 7.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    AsyncImage(
      model = state.artworkUri,
      contentDescription = null,
      contentScale = ContentScale.Crop,
      modifier = Modifier.size(28.dp).clickable(onClick = onOpenApp),
    )
    EqualizerBars()
    Text(
      text = state.title,
      color = Color.White,
      maxLines = 1,
      modifier = Modifier.weight(1f, fill = false),
    )
  }
}

@Composable
private fun ExpandedIsland(
  state: IslandPlaybackState,
  onCollapse: () -> Unit,
  onOpenApp: () -> Unit,
  onPlayPause: () -> Unit,
  onPrevious: () -> Unit,
  onNext: () -> Unit,
  onSeek: (Long) -> Unit,
) {
  Column(
    modifier =
      Modifier.background(Color.Black, RoundedCornerShape(28.dp))
        .padding(12.dp)
        .pointerInput(state.durationMs) {
          detectTapGestures { offset ->
            if (state.durationMs > 0) onSeek((offset.x / 280f * state.durationMs).toLong())
          }
        },
  ) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      AsyncImage(
        model = state.artworkUri,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = Modifier.size(42.dp).clickable(onClick = onOpenApp),
      )
      Column(modifier = Modifier.weight(1f).padding(horizontal = 10.dp)) {
        Text(state.title, color = Color.White, maxLines = 1)
        Text(state.artist, color = Color.LightGray, maxLines = 1)
      }
      Text("‹", color = Color.White, modifier = Modifier.clickable(onClick = onPrevious).padding(8.dp))
      Text(if (state.isPlaying) "Ⅱ" else "▶", color = Color.White, modifier = Modifier.clickable(onClick = onPlayPause).padding(8.dp))
      Text("›", color = Color.White, modifier = Modifier.clickable(onClick = onNext).padding(8.dp))
    }
    Spacer(Modifier.height(8.dp))
    ProgressLine(state.positionMs, state.durationMs)
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
