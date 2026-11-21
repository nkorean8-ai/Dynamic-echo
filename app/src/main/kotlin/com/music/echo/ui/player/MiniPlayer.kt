package echo.music.iad1tya.ui.player

import android.content.res.Configuration
import android.os.Build
import android.view.HapticFeedbackConstants
import androidx.compose.foundation.Canvas
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableLongState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.Player
import androidx.palette.graphics.Palette
import coil3.compose.AsyncImage
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.toBitmap
import echo.music.iad1tya.LocalDatabase
import echo.music.iad1tya.LocalListenTogetherManager
import echo.music.iad1tya.LocalPlayerConnection
import echo.music.iad1tya.R
import echo.music.iad1tya.constants.CropAlbumArtKey
import echo.music.iad1tya.constants.DarkModeKey
import echo.music.iad1tya.constants.EnableClickHapticsKey
import echo.music.iad1tya.constants.EnableHapticsKey
import echo.music.iad1tya.constants.MiniPlayerBackgroundStyleKey
import echo.music.iad1tya.constants.MiniPlayerHeight
import echo.music.iad1tya.constants.MiniPlayerControlsMode
import echo.music.iad1tya.constants.MiniPlayerControlsModeKey
import echo.music.iad1tya.constants.PlayerBackgroundStyle
import echo.music.iad1tya.constants.PureBlackKey
import echo.music.iad1tya.constants.PureBlackMiniPlayerKey
import echo.music.iad1tya.constants.SwipeSensitivityKey
import echo.music.iad1tya.constants.SwipeThumbnailKey
import echo.music.iad1tya.constants.ThumbnailCornerRadius
import echo.music.iad1tya.constants.UseFloatingNavBarKey
import echo.music.iad1tya.constants.UseNewMiniPlayerDesignKey
import echo.music.iad1tya.echomusic.AudioDeviceBottomSheet
import echo.music.iad1tya.echomusic.isBluetoothHeadphoneConnected
import echo.music.iad1tya.listentogether.ListenTogetherManager
import echo.music.iad1tya.models.MediaMetadata
import echo.music.iad1tya.playback.CastConnectionHandler
import echo.music.iad1tya.playback.PlayerConnection
import echo.music.iad1tya.ui.component.GlassComponent
import echo.music.iad1tya.ui.component.HeartBurstIcon
import echo.music.iad1tya.ui.component.Icon as MIcon
import echo.music.iad1tya.ui.component.LocalGlassEffectConfig
import echo.music.iad1tya.ui.component.isGlassSupported
import echo.music.iad1tya.ui.component.liquidGlass
import echo.music.iad1tya.ui.screens.settings.DarkMode
import echo.music.iad1tya.ui.theme.PlayerColorExtractor
import echo.music.iad1tya.utils.rememberEnumPreference
import echo.music.iad1tya.utils.rememberPreference
import kotlin.math.absoluteValue
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private data class PolygonCookieShape(val sides: Int, val indent: Float) : Shape {
  override fun createOutline(
    size: Size,
    layoutDirection: LayoutDirection,
    density: Density
  ): Outline {
    val path = Path()
    val maxRadius = minOf(size.width, size.height) / 2f
    val cx = size.width / 2f
    val cy = size.height / 2f

    val steps = 120
    for (i in 0..steps) {
      val angle = i * Math.PI * 2 / steps
      val r = maxRadius * (1f - indent + indent * cos(sides * angle))
      val x = cx + r * cos(angle)
      val y = cy + r * sin(angle)
      if (i == 0) path.moveTo(x.toFloat(), y.toFloat()) else path.lineTo(x.toFloat(), y.toFloat())
    }
    path.close()
    return Outline.Generic(path)
  }
}

@Stable
class ProgressState(
  private val positionState: MutableLongState,
  private val durationState: MutableLongState
) {
  val progress: Float
    get() {
      val duration = durationState.longValue
      return if (duration > 0) (positionState.longValue.toFloat() / duration).coerceIn(0f, 1f)
      else 0f
    }
}

@Composable
fun MiniPlayer(
  positionState: MutableLongState,
  durationState: MutableLongState,
  modifier: Modifier = Modifier,
  onClick: () -> Unit = {}
) {
  val useNewMiniPlayerDesign by rememberPreference(UseNewMiniPlayerDesignKey, true)

  val (useFloatingNavBar) = rememberPreference(UseFloatingNavBarKey, defaultValue = false)
  val progressState = remember { ProgressState(positionState, durationState) }

  if (useFloatingNavBar) {
    val miniPlayerBackground by
      rememberEnumPreference(
        MiniPlayerBackgroundStyleKey,
        defaultValue = PlayerBackgroundStyle.DEFAULT
      )
    val pureBlackMini by rememberPreference(PureBlackMiniPlayerKey, defaultValue = false)

    val isSystemInDarkTheme = androidx.compose.foundation.isSystemInDarkTheme()
    val darkTheme by rememberEnumPreference(DarkModeKey, defaultValue = DarkMode.AUTO)
    val useDarkTheme =
      remember(darkTheme, isSystemInDarkTheme) {
        if (darkTheme == DarkMode.AUTO) isSystemInDarkTheme else darkTheme == DarkMode.ON
      }
    val pureBlackGlobalPref by
      rememberPreference(echo.music.iad1tya.constants.PureBlackKey, defaultValue = false)
    val globalPureBlack = pureBlackGlobalPref && useDarkTheme

    val isFollowTheme = miniPlayerBackground == PlayerBackgroundStyle.DEFAULT
    val pureBlack = if (isFollowTheme) globalPureBlack else pureBlackMini

    val glassConfig = LocalGlassEffectConfig.current
    val useGlass =
      (miniPlayerBackground == PlayerBackgroundStyle.LIQUID_GLASS ||
        glassConfig.isEnabledFor(GlassComponent.MINI_PLAYER)) && isGlassSupported()

    val contentColor =
      if (useGlass && glassConfig.textColor != androidx.compose.ui.graphics.Color.Unspecified)
        glassConfig.textColor
      else if (pureBlack) Color.White else MaterialTheme.colorScheme.onSurface
    val bgTint =
      if (useGlass) Color.Transparent
      else if (pureBlack) Color.Black else MaterialTheme.colorScheme.surfaceContainerHigh
    android.util.Log.d(
      "COLOR_MATCH",
      "MiniPlayer - isFollowTheme: $isFollowTheme, globalPureBlack: $globalPureBlack, pureBlackMini: $pureBlackMini, pureBlack final: $pureBlack, bgTint: $bgTint"
    )

    val tabBarContentModifier =
      if (useGlass) {
        Modifier.liquidGlass(
          config = glassConfig,
          shape = RoundedCornerShape(percent = 50),
        )
      } else {
        Modifier.clip(RoundedCornerShape(percent = 50)).background(bgTint)
      }

    Box(
      modifier =
        modifier
          .fillMaxWidth()
          .height(MiniPlayerHeight)
          .padding(horizontal = 16.dp, vertical = 8.dp),
      contentAlignment = Alignment.Center
    ) {
      FloatingMiniPlayer(
        isInline = false,
        contentColor =
          if (useGlass && glassConfig.textColor != androidx.compose.ui.graphics.Color.Unspecified)
            glassConfig.textColor
          else if (pureBlack) androidx.compose.ui.graphics.Color.White
          else androidx.compose.material3.MaterialTheme.colorScheme.onSurface,
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().then(tabBarContentModifier)
      )
    }
  } else if (useNewMiniPlayerDesign) {
    val controlsMode by
      rememberEnumPreference(MiniPlayerControlsModeKey, MiniPlayerControlsMode.BUTTONS)
    NewMiniPlayer(
      progressState = progressState,
      controlsMode = controlsMode,
      onClick = onClick,
      modifier = modifier,
    )
  } else {
    val controlsMode by
      rememberEnumPreference(MiniPlayerControlsModeKey, MiniPlayerControlsMode.BUTTONS)
    Box(modifier = modifier.fillMaxWidth()) {
      LegacyMiniPlayer(
        progressState = progressState,
        controlsMode = controlsMode,
        onClick = onClick,
        modifier = Modifier.align(Alignment.Center),
      )
    }
  }
}

@Composable
private fun NewMiniPlayer(
  progressState: ProgressState,
  controlsMode: MiniPlayerControlsMode,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val playerConnection = LocalPlayerConnection.current ?: return

  val pureBlackMini by rememberPreference(PureBlackMiniPlayerKey, defaultValue = false)
  val isSystemInDarkTheme = isSystemInDarkTheme()
  val darkTheme by rememberEnumPreference(DarkModeKey, defaultValue = DarkMode.AUTO)
  val useDarkTheme =
    remember(darkTheme, isSystemInDarkTheme) {
      if (darkTheme == DarkMode.AUTO) isSystemInDarkTheme else darkTheme == DarkMode.ON
    }

  val miniPlayerBackground by
    rememberEnumPreference(
      MiniPlayerBackgroundStyleKey,
      defaultValue = PlayerBackgroundStyle.DEFAULT
    )

  val pureBlackGlobalPref by
    rememberPreference(echo.music.iad1tya.constants.PureBlackKey, defaultValue = false)
  val globalPureBlack = pureBlackGlobalPref && useDarkTheme
  val isFollowTheme = miniPlayerBackground == PlayerBackgroundStyle.DEFAULT
  val pureBlack = if (isFollowTheme) globalPureBlack else pureBlackMini

  val glassConfig = LocalGlassEffectConfig.current
  val useGlass =
    (miniPlayerBackground == PlayerBackgroundStyle.LIQUID_GLASS ||
      glassConfig.isEnabledFor(GlassComponent.MINI_PLAYER)) && isGlassSupported()
  val bgTint =
    if (useGlass) Color.Transparent
    else if (pureBlack) Color.Black else MaterialTheme.colorScheme.surfaceContainerHigh

  val playbackState by playerConnection.playbackState.collectAsState()
  val mediaMetadata by playerConnection.mediaMetadata.collectAsState()
  val canSkipNext by playerConnection.canSkipNext.collectAsState()
  val canSkipPrevious by playerConnection.canSkipPrevious.collectAsState()

  val castHandler =
    remember(playerConnection) {
      try {
        playerConnection.service.castConnectionHandler
      } catch (e: Exception) {
        null
      }
    }
  val isCasting by castHandler?.isCasting?.collectAsState() ?: remember { mutableStateOf(false) }
  val castIsPlaying by
    castHandler?.castIsPlaying?.collectAsState() ?: remember { mutableStateOf(false) }
  val castDeviceName by
    castHandler?.castDeviceName?.collectAsState() ?: remember { mutableStateOf(null) }

  val context = LocalContext.current
  val isBluetoothConnected = isBluetoothHeadphoneConnected(context)
  var showAudioDeviceBottomSheet by remember { mutableStateOf(false) }

  val swipeSensitivity by rememberPreference(SwipeSensitivityKey, 0.73f)
  val swipeThumbnailPref by rememberPreference(SwipeThumbnailKey, true)
  val hapticsEnabled by rememberPreference(EnableHapticsKey, false)
  val clickHapticsEnabled by rememberPreference(EnableClickHapticsKey, true)

  val listenTogetherManager = LocalListenTogetherManager.current
  val isListenTogetherGuest = listenTogetherManager?.let { it.isGuestPlaybackRestricted } ?: false
  val swipeThumbnail = swipeThumbnailPref && !isListenTogetherGuest
  val gesturesMode =
    controlsMode == MiniPlayerControlsMode.GESTURES && swipeThumbnail

  val layoutDirection = LocalLayoutDirection.current
  val coroutineScope = rememberCoroutineScope()
  val miniPlayerView = LocalView.current
  val maxSwipeFollowPx = with(LocalDensity.current) { 10.dp.toPx() }
  val unavailableSwipeResistancePx = with(LocalDensity.current) { 3.dp.toPx() }

  val configuration = LocalConfiguration.current
  val isTabletLandscape =
    remember(configuration.screenWidthDp, configuration.orientation) {
      configuration.screenWidthDp >= 600 &&
        configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    }

  val offsetXAnimatable = remember { Animatable(0f) }
  var dragStartTime by remember { mutableLongStateOf(0L) }
  var totalDragDistance by remember { mutableFloatStateOf(0f) }
  var totalDragDisplacement by remember { mutableFloatStateOf(0f) }
  var totalPhysicalDragDisplacement by remember { mutableFloatStateOf(0f) }
  var trackSwipeDirection by remember { mutableStateOf(0) }
  var trackDirectionGeneration by remember { mutableStateOf(0) }

  val animationSpec = remember {
    spring<Float>(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessLow)
  }

  val autoSwipeThreshold =
    remember(swipeSensitivity) {
      (600 / (1f + kotlin.math.exp(-(-11.44748 * swipeSensitivity + 9.04945)))).roundToInt()
    }

  val (gradientColors, onGradientColorsChange) =
    remember { mutableStateOf<List<Color>>(emptyList()) }

  MiniPlayerColorExtractor(
    artworkUri = mediaMetadata?.thumbnailUrl,
    miniPlayerBackground = miniPlayerBackground,
    onGradientColorsChange = onGradientColorsChange
  )

  val isDynamicBackground = miniPlayerBackground != PlayerBackgroundStyle.DEFAULT
  val backgroundColor =
    if (useGlass) {
      Color.Transparent
    } else if (pureBlack && useDarkTheme) {
      Color.Black
    } else {
      MaterialTheme.colorScheme.surfaceContainerHigh
    }

  val primaryColor =
    if (useGlass && glassConfig.textColor != androidx.compose.ui.graphics.Color.Unspecified)
      glassConfig.textColor
    else if (isDynamicBackground && !useGlass) androidx.compose.ui.graphics.Color.White
    else androidx.compose.material3.MaterialTheme.colorScheme.onSurface
  val onPrimaryColor =
    if (useGlass && glassConfig.textColor != androidx.compose.ui.graphics.Color.Unspecified)
      glassConfig.textColor
    else if (isDynamicBackground && !useGlass) androidx.compose.ui.graphics.Color.Black
    else androidx.compose.material3.MaterialTheme.colorScheme.surface
  val outlineColor =
    if (useGlass && glassConfig.textColor != androidx.compose.ui.graphics.Color.Unspecified)
      glassConfig.textColor.copy(alpha = 0.5f)
    else if (isDynamicBackground && !useGlass)
      androidx.compose.ui.graphics.Color.White.copy(alpha = 0.5f)
    else androidx.compose.material3.MaterialTheme.colorScheme.outline
  val onSurfaceColor =
    if (useGlass && glassConfig.textColor != androidx.compose.ui.graphics.Color.Unspecified)
      glassConfig.textColor
    else if (isDynamicBackground && !useGlass) androidx.compose.ui.graphics.Color.White
    else androidx.compose.material3.MaterialTheme.colorScheme.onSurface
  val errorColor = MaterialTheme.colorScheme.error

  Box(
    modifier =
      modifier
        .fillMaxWidth()
        .wrapContentWidth(Alignment.CenterHorizontally)
        .widthIn(max = 340.dp)
        .height(MiniPlayerHeight)
        .windowInsetsPadding(WindowInsets.systemBars.only(WindowInsetsSides.Horizontal))
        .padding(horizontal = 12.dp)
        .let { baseModifier ->
          if (gesturesMode) {
            baseModifier
              .combinedClickable(
                onClick = onClick,
                onDoubleClick = {
                  if (!isListenTogetherGuest) {
                    performMiniPlayerPlayPause(
                      playerConnection,
                      playbackState,
                      isCasting,
                      castIsPlaying,
                      castHandler,
                      isListenTogetherGuest,
                    )
                  }
                },
              )
              .miniPlayerGestureSemantics(
                enabled = true,
                isGuest = isListenTogetherGuest,
                onPrevious = { playerConnection.player.seekToPreviousMediaItem() },
                onPlayPause = {
                  performMiniPlayerPlayPause(
                    playerConnection,
                    playbackState,
                    isCasting,
                    castIsPlaying,
                    castHandler,
                    isListenTogetherGuest,
                  )
                },
                onNext = { playerConnection.player.seekToNext() },
              )
          } else {
            baseModifier
          }
        }
        .let { baseModifier ->
          if (swipeThumbnail) {
            baseModifier.pointerInput(Unit) {
              detectHorizontalDragGestures(
                onDragStart = {
                  dragStartTime = System.currentTimeMillis()
                  totalDragDistance = 0f
                  totalDragDisplacement = 0f
                  totalPhysicalDragDisplacement = 0f
                },
                onDragCancel = {
                  coroutineScope.launch { offsetXAnimatable.animateTo(0f, animationSpec) }
                },
                onHorizontalDrag = { _, dragAmount ->
                  val adjustedDragAmount =
                    if (layoutDirection == LayoutDirection.Rtl) -dragAmount else dragAmount
                  val canSkipPrevious = playerConnection.player.previousMediaItemIndex != -1
                  val canSkipNext = playerConnection.player.nextMediaItemIndex != -1
                  val canSkipInDirection =
                    if (adjustedDragAmount > 0) canSkipNext else canSkipPrevious
                  totalDragDistance += kotlin.math.abs(adjustedDragAmount)
                  totalDragDisplacement += adjustedDragAmount
                  totalPhysicalDragDisplacement += dragAmount
                  val visualDelta =
                    if (canSkipInDirection) dragAmount else dragAmount * 0.16f
                  val limit =
                    if (canSkipInDirection) maxSwipeFollowPx else unavailableSwipeResistancePx
                  coroutineScope.launch {
                    offsetXAnimatable.snapTo(
                      (offsetXAnimatable.value + visualDelta).coerceIn(-limit, limit)
                    )
                  }
                },
                onDragEnd = {
                  val dragDuration = System.currentTimeMillis() - dragStartTime
                  val velocity = if (dragDuration > 0) totalDragDistance / dragDuration else 0f
                  val currentOffset = totalDragDisplacement
                  val minDistanceThreshold = 50f
                  val velocityThreshold = (swipeSensitivity * -8.25f) + 8.5f

                  val shouldChangeSong =
                    (kotlin.math.abs(currentOffset) > minDistanceThreshold &&
                      velocity > velocityThreshold) ||
                      (kotlin.math.abs(currentOffset) > autoSwipeThreshold)

                  if (shouldChangeSong) {
                    val canSkipPrevious = playerConnection.player.previousMediaItemIndex != -1
                    val canSkipNext = playerConnection.player.nextMediaItemIndex != -1
                    val willSkip =
                      if (currentOffset > 0) canSkipNext else canSkipPrevious
                    if (currentOffset > 0 && canSkipNext) {
                      playerConnection.player.seekToNext()
                    } else if (currentOffset < 0 && canSkipPrevious) {
                      playerConnection.player.seekToPreviousMediaItem()
                    }
                    if (willSkip) {
                      trackSwipeDirection =
                        if (totalPhysicalDragDisplacement > 0f) 1 else -1
                      val generation = ++trackDirectionGeneration
                      coroutineScope.launch {
                        delay(MINI_PLAYER_SWIPE_TRANSITION_MS)
                        if (trackDirectionGeneration == generation) trackSwipeDirection = 0
                      }
                      if (hapticsEnabled && clickHapticsEnabled) {
                        miniPlayerView.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                      }
                    }
                  }
                  totalDragDisplacement = 0f
                  totalPhysicalDragDisplacement = 0f
                  coroutineScope.launch { offsetXAnimatable.animateTo(0f, animationSpec) }
                }
              )
            }
          } else baseModifier
        }
  ) {
    Box(
      modifier =
        Modifier.then(
            if (isTabletLandscape) Modifier.width(480.dp).align(Alignment.Center)
            else Modifier.fillMaxWidth()
          )
          .height(MiniPlayerHeight)
          .let { m ->
            val shape = RoundedCornerShape(percent = 50)
            if (useGlass) {
                m.clip(shape).liquidGlass(config = glassConfig, shape = shape)
              } else {
                m.clip(shape).background(bgTint)
              }
              .border(1.dp, outlineColor.copy(alpha = 0.3f), shape)
          }
    ) {
      MiniPlayerBackgroundLayer(
        style = miniPlayerBackground,
        artworkUri = mediaMetadata?.thumbnailUrl,
        gradientColors = gradientColors,
      )

      Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 8.dp),
      ) {
        Box(
          modifier = Modifier.weight(1f).fillMaxHeight().clipToBounds(),
        ) {
          AnimatedContent(
            targetState = mediaMetadata,
            transitionSpec = { miniPlayerTrackTransition(trackSwipeDirection) },
            modifier =
              Modifier.fillMaxSize()
                .offset { IntOffset(offsetXAnimatable.value.roundToInt(), 0) },
            label = "miniPlayerTrack",
          ) { trackMetadata ->
            Row(
              verticalAlignment = Alignment.CenterVertically,
              modifier = Modifier.fillMaxSize(),
            ) {
              NewMiniPlayerThumbnail(
                progressState = progressState,
                mediaMetadata = trackMetadata,
                primaryColor = primaryColor,
                outlineColor = outlineColor,
              )

              Spacer(modifier = Modifier.width(16.dp))

              NewMiniPlayerSongInfo(
                mediaMetadata = trackMetadata,
                onSurfaceColor = onSurfaceColor,
                errorColor = errorColor,
                modifier = Modifier.weight(1f),
              )
            }
          }
        }

        Spacer(modifier = Modifier.width(4.dp))

        if (isCasting) {
          Icon(
            painter = painterResource(R.drawable.cast_connected),
            contentDescription = "Casting to ${castDeviceName ?: "device"}",
            tint = primaryColor,
            modifier = Modifier.size(20.dp)
          )
          if (castDeviceName != null) {
            Spacer(modifier = Modifier.width(4.dp))
            Text(
              text = castDeviceName!!,
              style = MaterialTheme.typography.labelSmall,
              color = primaryColor,
              maxLines = 1
            )
          }
          Spacer(modifier = Modifier.width(8.dp))
        }

        if (!gesturesMode) {
          MiniPlayerControls(
            playerConnection = playerConnection,
            playbackState = playbackState,
            isCasting = isCasting,
            castHandler = castHandler,
            listenTogetherManager = listenTogetherManager,
            canSkipPrevious = canSkipPrevious,
            canSkipNext = canSkipNext,
            onSurfaceColor = onSurfaceColor,
            primaryColor = primaryColor,
            onPrimaryColor = onPrimaryColor
          )
        }
      }
      if (offsetXAnimatable.value.absoluteValue > 1f) {
        MiniPlayerSwipeChevron(
          direction = if (offsetXAnimatable.value > 0f) 1 else -1,
          alpha = (offsetXAnimatable.value.absoluteValue / maxSwipeFollowPx).coerceIn(0.18f, 0.9f),
          color = primaryColor,
          modifier =
            Modifier.align(if (offsetXAnimatable.value > 0f) Alignment.CenterEnd else Alignment.CenterStart)
              .padding(horizontal = 10.dp),
        )
      }
    }
  }

  if (showAudioDeviceBottomSheet) {
    AudioDeviceBottomSheet(onDismiss = { showAudioDeviceBottomSheet = false })
  }
}

@Composable
private fun NewMiniPlayerThumbnail(
  progressState: ProgressState,
  mediaMetadata: MediaMetadata?,
  primaryColor: Color,
  outlineColor: Color,
) {
  val trackColor = outlineColor.copy(alpha = 0.2f)
  val strokeWidth = 3.dp

  Box(
    contentAlignment = Alignment.Center,
    modifier =
      Modifier.size(48.dp).drawWithContent {
        drawContent()

        val progress = progressState.progress
        val stroke = Stroke(width = strokeWidth.toPx(), cap = StrokeCap.Round)
        val startAngle = -90f
        val sweepAngle = 360f * progress
        val diameter = size.minDimension
        val topLeft = Offset((size.width - diameter) / 2, (size.height - diameter) / 2)

        drawArc(
          color = trackColor,
          startAngle = 0f,
          sweepAngle = 360f,
          useCenter = false,
          topLeft = topLeft,
          size = Size(diameter, diameter),
          style = stroke
        )

        drawArc(
          color = primaryColor,
          startAngle = startAngle,
          sweepAngle = sweepAngle,
          useCenter = false,
          topLeft = topLeft,
          size = Size(diameter, diameter),
          style = stroke
        )
      }
  ) {
    Box(
      contentAlignment = Alignment.Center,
      modifier =
        Modifier.size(40.dp)
          .clip(CircleShape)
          .border(1.dp, outlineColor.copy(alpha = 0.3f), CircleShape)
    ) {
      mediaMetadata?.let { metadata ->
        AsyncImage(
          model = ImageRequest.Builder(LocalContext.current).data(metadata.thumbnailUrl).build(),
          contentDescription = null,
          contentScale = ContentScale.Crop,
          modifier = Modifier.fillMaxSize().clip(CircleShape)
        )
      }
    }
  }
}

@Composable
private fun NewMiniPlayerSongInfo(
  mediaMetadata: MediaMetadata?,
  onSurfaceColor: Color,
  errorColor: Color,
  modifier: Modifier = Modifier
) {
  val error by
    LocalPlayerConnection.current?.error?.collectAsState() ?: remember { mutableStateOf(null) }

  Column(modifier = modifier, verticalArrangement = Arrangement.Center) {
    mediaMetadata?.let { metadata ->
      Text(
        text = metadata.title,
        color = onSurfaceColor,
        fontSize = 14.sp,
        fontWeight = FontWeight.Medium,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier =
          Modifier.basicMarquee(iterations = 1, initialDelayMillis = 3000, velocity = 30.dp),
      )
      Row(
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        if (metadata.explicit) MIcon.Explicit()
        if (metadata.artists.any { it.name.isNotBlank() }) {
          Text(
            text = metadata.artists.joinToString { it.name },
            color = onSurfaceColor.copy(alpha = 0.7f),
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier =
              Modifier.basicMarquee(iterations = 1, initialDelayMillis = 3000, velocity = 30.dp),
          )
        }
      }

      AnimatedVisibility(visible = error != null, enter = fadeIn(), exit = fadeOut()) {
        Text(
          text = stringResource(R.string.error_playing),
          color = errorColor,
          fontSize = 10.sp,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
      }
    }
  }
}

@Composable
private fun LegacyMiniPlayer(
  progressState: ProgressState,
  controlsMode: MiniPlayerControlsMode,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val playerConnection = LocalPlayerConnection.current ?: return
  val pureBlackMini by rememberPreference(PureBlackMiniPlayerKey, defaultValue = false)
  val isSystemInDarkTheme = isSystemInDarkTheme()
  val darkTheme by rememberEnumPreference(DarkModeKey, defaultValue = DarkMode.AUTO)
  val useDarkTheme =
    remember(darkTheme, isSystemInDarkTheme) {
      if (darkTheme == DarkMode.AUTO) isSystemInDarkTheme else darkTheme == DarkMode.ON
    }

  val miniPlayerBackground by
    rememberEnumPreference(
      MiniPlayerBackgroundStyleKey,
      defaultValue = PlayerBackgroundStyle.DEFAULT
    )

  val pureBlackGlobalPref by
    rememberPreference(echo.music.iad1tya.constants.PureBlackKey, defaultValue = false)
  val globalPureBlack = pureBlackGlobalPref && useDarkTheme
  val isFollowTheme = miniPlayerBackground == PlayerBackgroundStyle.DEFAULT
  val pureBlack = if (isFollowTheme) globalPureBlack else pureBlackMini
  val glassConfig = LocalGlassEffectConfig.current
  val useGlass =
    (miniPlayerBackground == PlayerBackgroundStyle.LIQUID_GLASS ||
      glassConfig.isEnabledFor(GlassComponent.MINI_PLAYER)) && isGlassSupported()
  val bgTint =
    if (useGlass) Color.Transparent
    else if (pureBlack) Color.Black else MaterialTheme.colorScheme.surfaceContainerHigh

  val playbackState by playerConnection.playbackState.collectAsState()
  val mediaMetadata by playerConnection.mediaMetadata.collectAsState()
  val canSkipNext by playerConnection.canSkipNext.collectAsState()
  val canSkipPrevious by playerConnection.canSkipPrevious.collectAsState()

  val castHandler =
    remember(playerConnection) {
      try {
        playerConnection.service.castConnectionHandler
      } catch (e: Exception) {
        null
      }
    }
  val isCasting by castHandler?.isCasting?.collectAsState() ?: remember { mutableStateOf(false) }
  val castIsPlaying by
    castHandler?.castIsPlaying?.collectAsState() ?: remember { mutableStateOf(false) }

  val swipeSensitivity by rememberPreference(SwipeSensitivityKey, 0.73f)
  val swipeThumbnailPref by rememberPreference(SwipeThumbnailKey, true)
  val hapticsEnabled by rememberPreference(EnableHapticsKey, false)
  val clickHapticsEnabled by rememberPreference(EnableClickHapticsKey, true)

  val listenTogetherManager = LocalListenTogetherManager.current
  val isListenTogetherGuest = listenTogetherManager?.let { it.isGuestPlaybackRestricted } ?: false
  val swipeThumbnail = swipeThumbnailPref && !isListenTogetherGuest
  val gesturesMode =
    controlsMode == MiniPlayerControlsMode.GESTURES && swipeThumbnail

  val layoutDirection = LocalLayoutDirection.current
  val coroutineScope = rememberCoroutineScope()
  val miniPlayerView = LocalView.current
  val maxSwipeFollowPx = with(LocalDensity.current) { 10.dp.toPx() }
  val unavailableSwipeResistancePx = with(LocalDensity.current) { 3.dp.toPx() }

  val configuration = LocalConfiguration.current
  val isTabletLandscape =
    remember(configuration.screenWidthDp, configuration.orientation) {
      configuration.screenWidthDp >= 600 &&
        configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    }

  val offsetXAnimatable = remember { Animatable(0f) }
  var dragStartTime by remember { mutableLongStateOf(0L) }
  var totalDragDistance by remember { mutableFloatStateOf(0f) }
  var totalDragDisplacement by remember { mutableFloatStateOf(0f) }
  var totalPhysicalDragDisplacement by remember { mutableFloatStateOf(0f) }
  var trackSwipeDirection by remember { mutableStateOf(0) }
  var trackDirectionGeneration by remember { mutableStateOf(0) }

  val animationSpec = remember {
    spring<Float>(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessLow)
  }

  val autoSwipeThreshold =
    remember(swipeSensitivity) {
      (600 / (1f + kotlin.math.exp(-(-11.44748 * swipeSensitivity + 9.04945)))).roundToInt()
    }

  val primaryColor = MaterialTheme.colorScheme.onSurface
  val trackColor = MaterialTheme.colorScheme.surfaceVariant

  Box(
    modifier =
      modifier
        .fillMaxWidth()
        .wrapContentWidth(Alignment.CenterHorizontally)
        .widthIn(max = 340.dp)
        .height(MiniPlayerHeight)
        .windowInsetsPadding(WindowInsets.systemBars.only(WindowInsetsSides.Horizontal))
        .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
        .background(
          if (pureBlack && isSystemInDarkTheme()) Color.Black
          else MaterialTheme.colorScheme.surfaceContainer
        )
        .let { baseModifier ->
          if (gesturesMode) {
            baseModifier
              .combinedClickable(
                onClick = onClick,
                onDoubleClick = {
                  if (!isListenTogetherGuest) {
                    performMiniPlayerPlayPause(
                      playerConnection,
                      playbackState,
                      isCasting,
                      castIsPlaying,
                      castHandler,
                      isListenTogetherGuest,
                    )
                  }
                },
              )
              .miniPlayerGestureSemantics(
                enabled = true,
                isGuest = isListenTogetherGuest,
                onPrevious = { playerConnection.player.seekToPreviousMediaItem() },
                onPlayPause = {
                  performMiniPlayerPlayPause(
                    playerConnection,
                    playbackState,
                    isCasting,
                    castIsPlaying,
                    castHandler,
                    isListenTogetherGuest,
                  )
                },
                onNext = { playerConnection.player.seekToNext() },
              )
          } else {
            baseModifier
          }
        }
        .let { baseModifier ->
          if (swipeThumbnail) {
            baseModifier.pointerInput(Unit) {
              detectHorizontalDragGestures(
                onDragStart = {
                  dragStartTime = System.currentTimeMillis()
                  totalDragDistance = 0f
                  totalDragDisplacement = 0f
                  totalPhysicalDragDisplacement = 0f
                },
                onDragCancel = {
                  coroutineScope.launch { offsetXAnimatable.animateTo(0f, animationSpec) }
                },
                onHorizontalDrag = { _, dragAmount ->
                  val adjustedDragAmount =
                    if (layoutDirection == LayoutDirection.Rtl) -dragAmount else dragAmount
                  val canSkipPrevious = playerConnection.player.previousMediaItemIndex != -1
                  val canSkipNext = playerConnection.player.nextMediaItemIndex != -1
                  val canSkipInDirection =
                    if (adjustedDragAmount > 0) canSkipNext else canSkipPrevious
                  totalDragDistance += kotlin.math.abs(adjustedDragAmount)
                  totalDragDisplacement += adjustedDragAmount
                  totalPhysicalDragDisplacement += dragAmount
                  val visualDelta =
                    if (canSkipInDirection) dragAmount else dragAmount * 0.16f
                  val limit =
                    if (canSkipInDirection) maxSwipeFollowPx else unavailableSwipeResistancePx
                  coroutineScope.launch {
                    offsetXAnimatable.snapTo(
                      (offsetXAnimatable.value + visualDelta).coerceIn(-limit, limit)
                    )
                  }
                },
                onDragEnd = {
                  val dragDuration = System.currentTimeMillis() - dragStartTime
                  val velocity = if (dragDuration > 0) totalDragDistance / dragDuration else 0f
                  val currentOffset = totalDragDisplacement
                  val minDistanceThreshold = 50f
                  val velocityThreshold = (swipeSensitivity * -8.25f) + 8.5f

                  val shouldChangeSong =
                    (kotlin.math.abs(currentOffset) > minDistanceThreshold &&
                      velocity > velocityThreshold) ||
                      (kotlin.math.abs(currentOffset) > autoSwipeThreshold)

                  if (shouldChangeSong) {
                    val canSkipPrevious = playerConnection.player.previousMediaItemIndex != -1
                    val canSkipNext = playerConnection.player.nextMediaItemIndex != -1
                    val willSkip =
                      if (currentOffset > 0) canSkipNext else canSkipPrevious
                    if (currentOffset > 0 && canSkipNext) {
                      playerConnection.player.seekToNext()
                    } else if (currentOffset < 0 && canSkipPrevious) {
                      playerConnection.player.seekToPreviousMediaItem()
                    }
                    if (willSkip) {
                      trackSwipeDirection =
                        if (totalPhysicalDragDisplacement > 0f) 1 else -1
                      val generation = ++trackDirectionGeneration
                      coroutineScope.launch {
                        delay(MINI_PLAYER_SWIPE_TRANSITION_MS)
                        if (trackDirectionGeneration == generation) trackSwipeDirection = 0
                      }
                      if (hapticsEnabled && clickHapticsEnabled) {
                        miniPlayerView.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                      }
                    }
                  }
                  totalDragDisplacement = 0f
                  totalPhysicalDragDisplacement = 0f
                  coroutineScope.launch { offsetXAnimatable.animateTo(0f, animationSpec) }
                }
              )
            }
          } else baseModifier
        }
  ) {
    Box(
      modifier =
        Modifier.fillMaxWidth().height(2.dp).align(Alignment.BottomCenter)
          .offset { IntOffset(offsetXAnimatable.value.roundToInt(), 0) }
          .drawWithContent {
          val progress = progressState.progress
          drawRect(trackColor)
          drawRect(primaryColor, size = Size(size.width * progress, size.height))
        }
    )

    Row(
      verticalAlignment = Alignment.CenterVertically,
      modifier =
        Modifier.fillMaxSize()
          .padding(end = 12.dp),
    ) {
      Box(Modifier.weight(1f).fillMaxHeight().clipToBounds()) {
        AnimatedContent(
          targetState = mediaMetadata,
          transitionSpec = { miniPlayerTrackTransition(trackSwipeDirection) },
          modifier =
            Modifier.fillMaxSize()
              .offset { IntOffset(offsetXAnimatable.value.roundToInt(), 0) },
          label = "legacyMiniPlayerTrack",
        ) { trackMetadata ->
          trackMetadata?.let {
            LegacyMiniMediaInfo(
              mediaMetadata = it,
              pureBlack = pureBlack,
              modifier = Modifier.fillMaxSize().padding(horizontal = 6.dp),
            )
          }
        }
      }

      if (!gesturesMode) {
        LegacyPlayPauseButton(
          playbackState = playbackState,
          isCasting = isCasting,
          castHandler = castHandler,
          playerConnection = playerConnection,
          listenTogetherManager = listenTogetherManager
        )

        IconButton(
          enabled = canSkipNext && !isListenTogetherGuest,
          onClick = if (isListenTogetherGuest) ({}) else ({ playerConnection.seekToNext() }),
        ) {
          Icon(painter = painterResource(R.drawable.skip_next), contentDescription = null)
        }
      }
    }

    if (offsetXAnimatable.value.absoluteValue > 1f) {
      MiniPlayerSwipeChevron(
        direction = if (offsetXAnimatable.value > 0f) 1 else -1,
        alpha = (offsetXAnimatable.value.absoluteValue / maxSwipeFollowPx).coerceIn(0.18f, 0.9f),
        color = primaryColor,
        modifier =
          Modifier.align(if (offsetXAnimatable.value > 0f) Alignment.CenterEnd else Alignment.CenterStart)
            .padding(horizontal = 10.dp),
      )
    }
  }
}

@Composable
private fun LegacyPlayPauseButton(
  playbackState: Int,
  isCasting: Boolean,
  castHandler: CastConnectionHandler?,
  playerConnection: PlayerConnection,
  listenTogetherManager: ListenTogetherManager?,
  tint: Color = androidx.compose.material3.LocalContentColor.current
) {
  val isPlaying by playerConnection.isPlaying.collectAsState()
  val castIsPlaying by
    castHandler?.castIsPlaying?.collectAsState() ?: remember { mutableStateOf(false) }
  val effectiveIsPlaying = if (isCasting) castIsPlaying else isPlaying
  val isListenTogetherGuest = listenTogetherManager?.let { it.isGuestPlaybackRestricted } ?: false
  val isMuted by playerConnection.isMuted.collectAsState()

  IconButton(
    onClick = {
      performMiniPlayerPlayPause(
        playerConnection,
        playbackState,
        isCasting,
        castIsPlaying,
        castHandler,
        isListenTogetherGuest,
      )
    },
  ) {
    Icon(
      painter =
        painterResource(
          when {
            isListenTogetherGuest -> if (isMuted) R.drawable.volume_off else R.drawable.volume_up
            playbackState == Player.STATE_ENDED -> R.drawable.replay
            effectiveIsPlaying -> R.drawable.pause
            else -> R.drawable.play
          }
        ),
      contentDescription = null,
      tint = tint,
    )
  }
}

private fun performMiniPlayerPlayPause(
  playerConnection: PlayerConnection,
  playbackState: Int,
  isCasting: Boolean,
  castIsPlaying: Boolean,
  castHandler: CastConnectionHandler?,
  isListenTogetherGuest: Boolean,
) {
  if (isListenTogetherGuest) {
    playerConnection.toggleMute()
  } else if (isCasting) {
    if (castIsPlaying) castHandler?.pause() else castHandler?.play()
  } else if (playbackState == Player.STATE_ENDED) {
    playerConnection.player.seekTo(0, 0)
    playerConnection.player.playWhenReady = true
  } else {
    playerConnection.togglePlayPause()
  }
}

@Composable
internal fun MiniPlayerSwipeChevron(
  direction: Int,
  alpha: Float,
  color: Color = Color.White,
  modifier: Modifier = Modifier,
) {
  if (direction == 0) return
  Canvas(modifier.size(14.dp)) {
    val hintColor = color.copy(alpha = alpha)
    val centerY = size.height / 2f
    val innerX = if (direction > 0) size.width * 0.35f else size.width * 0.65f
    val outerX = if (direction > 0) size.width * 0.68f else size.width * 0.32f
    val strokeWidth = 2.dp.toPx()
    drawLine(
      hintColor,
      Offset(innerX, centerY - size.height * 0.28f),
      Offset(outerX, centerY),
      strokeWidth,
      StrokeCap.Round,
    )
    drawLine(
      hintColor,
      Offset(outerX, centerY),
      Offset(innerX, centerY + size.height * 0.28f),
      strokeWidth,
      StrokeCap.Round,
    )
  }
}

internal data class MiniPlayerTrackVisual(
  val id: String?,
  val title: String,
  val artist: String,
  val artwork: String?,
)

internal const val MINI_PLAYER_SWIPE_TRANSITION_MS = 600L

internal fun miniPlayerTrackTransition(direction: Int): ContentTransform =
  if (direction == 0) {
    fadeIn(tween(120)) togetherWith fadeOut(tween(120))
  } else {
    (
      slideInHorizontally(
        initialOffsetX = { if (direction > 0) -it else it },
        animationSpec =
          spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessLow,
          ),
      ) + fadeIn(tween(120))
    ) togetherWith
      (
        slideOutHorizontally(
          targetOffsetX = { if (direction > 0) it else -it },
          animationSpec =
            spring(
              dampingRatio = Spring.DampingRatioNoBouncy,
              stiffness = Spring.StiffnessLow,
            ),
        ) + fadeOut(tween(120))
      )
  }

@Composable
private fun LegacyMiniMediaInfo(
  mediaMetadata: MediaMetadata,
  pureBlack: Boolean,
  modifier: Modifier = Modifier,
) {
  val error by
    LocalPlayerConnection.current?.error?.collectAsState() ?: remember { mutableStateOf(null) }
  val cropAlbumArt by rememberPreference(CropAlbumArtKey, false)

  Row(
    verticalAlignment = Alignment.CenterVertically,
    modifier = modifier,
  ) {
    Box(
      modifier = Modifier.padding(6.dp).size(48.dp).clip(RoundedCornerShape(ThumbnailCornerRadius))
    ) {
      Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant))

      AsyncImage(
        model = ImageRequest.Builder(LocalContext.current).data(mediaMetadata.thumbnailUrl).build(),
        contentDescription = null,
        contentScale = if (cropAlbumArt) ContentScale.Crop else ContentScale.Fit,
        modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(ThumbnailCornerRadius)),
      )

      androidx.compose.animation.AnimatedVisibility(
        visible = error != null,
        enter = fadeIn(),
        exit = fadeOut()
      ) {
        Box(
          Modifier.fillMaxSize()
            .background(
              color = if (pureBlack) Color.Black else Color.Black.copy(alpha = 0.6f),
              shape = RoundedCornerShape(ThumbnailCornerRadius),
            ),
        ) {
          Icon(
            painter = painterResource(R.drawable.info),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.align(Alignment.Center),
          )
        }
      }
    }

    Column(
      modifier = Modifier.weight(1f).padding(horizontal = 6.dp),
    ) {
      Text(
        text = mediaMetadata.title,
        color = MaterialTheme.colorScheme.onSurface,
        fontSize = 16.sp,
        fontWeight = FontWeight.Bold,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.basicMarquee(),
      )

      if (mediaMetadata.artists.any { it.name.isNotBlank() }) {
        Text(
          text = mediaMetadata.artists.joinToString { it.name },
          color = MaterialTheme.colorScheme.secondary,
          fontSize = 12.sp,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
      }
    }
  }
}

@Composable
private fun FavoriteButton(
  songId: String,
  onSurfaceColor: Color,
  errorColor: Color,
  outlineColor: Color
) {
  val database = LocalDatabase.current
  val playerConnection = LocalPlayerConnection.current ?: return
  val librarySong by database.song(songId).collectAsState(initial = null)
  val isLiked = librarySong?.song?.liked == true

  Box(
    contentAlignment = Alignment.Center,
    modifier =
      Modifier.size(40.dp)
        .clip(CircleShape)
        .border(
          width = 1.dp,
          color = if (isLiked) errorColor.copy(alpha = 0.5f) else outlineColor.copy(alpha = 0.3f),
          shape = CircleShape
        )
        .background(
          color = if (isLiked) errorColor.copy(alpha = 0.1f) else Color.Transparent,
          shape = CircleShape
        )
        .clickable { playerConnection.service.toggleLike() }
  ) {
    HeartBurstIcon(
      isLiked = isLiked,
      iconSize = 20.dp,
      unlikedColor = onSurfaceColor.copy(alpha = 0.7f)
    )
  }
}

@Composable
private fun MiniPlayerControls(
  playerConnection: PlayerConnection,
  playbackState: Int,
  isCasting: Boolean,
  castHandler: CastConnectionHandler?,
  listenTogetherManager: ListenTogetherManager?,
  canSkipPrevious: Boolean,
  canSkipNext: Boolean,
  onSurfaceColor: Color,
  primaryColor: Color,
  onPrimaryColor: Color
) {
  val isListenTogetherGuest = listenTogetherManager?.let { it.isGuestPlaybackRestricted } ?: false
  val isPlaying by playerConnection.isPlaying.collectAsState()
  val castIsPlaying by
    castHandler?.castIsPlaying?.collectAsState() ?: remember { mutableStateOf(false) }
  val effectiveIsPlaying = if (isCasting) castIsPlaying else isPlaying
  val isMuted by playerConnection.isMuted.collectAsState()
  val wavyPlayPause by echo.music.iad1tya.utils.rememberPreference(echo.music.iad1tya.constants.WavyPlayPauseKey, defaultValue = true)
  Row(verticalAlignment = Alignment.CenterVertically) {
    IconButton(
      enabled = canSkipPrevious && !isListenTogetherGuest,
      onClick =
        if (isListenTogetherGuest) ({})
        else ({ playerConnection.player.seekToPreviousMediaItem() }),
      modifier = Modifier.size(32.dp)
    ) {
      Icon(
        painter = painterResource(R.drawable.skip_previous),
        contentDescription = null,
        tint = onSurfaceColor,
        modifier = Modifier.size(20.dp)
      )
    }

    Spacer(modifier = Modifier.width(12.dp))

    val cookieIndent by
      androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (effectiveIsPlaying) 0.08f else 0f,
        animationSpec =
          androidx.compose.animation.core.tween(
            durationMillis = 300,
            easing = androidx.compose.animation.core.LinearEasing
          ),
        label = "cookieIndent",
      )

    val infiniteTransition =
      androidx.compose.animation.core.rememberInfiniteTransition(label = "rotation")
    val rotation by
      infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec =
          androidx.compose.animation.core.infiniteRepeatable(
            animation =
              androidx.compose.animation.core.tween(
                8000,
                easing = androidx.compose.animation.core.LinearEasing
              ),
            repeatMode = androidx.compose.animation.core.RepeatMode.Restart
          ),
        label = "rotation"
      )

    Box(
      contentAlignment = Alignment.Center,
      modifier =
        Modifier.size(48.dp).clip(CircleShape).clickable {
          performMiniPlayerPlayPause(
            playerConnection,
            playbackState,
            isCasting,
            castIsPlaying,
            castHandler,
            isListenTogetherGuest,
          )
        }
    ) {
      Box(
        modifier =
          Modifier.matchParentSize()
            .graphicsLayer {
              rotationZ = rotation
              clip = true
              shape = if (wavyPlayPause) PolygonCookieShape(sides = 9, indent = cookieIndent) else androidx.compose.foundation.shape.CircleShape
            }
            .background(primaryColor)
      )

      Icon(
        painter =
          painterResource(
            when {
              isListenTogetherGuest -> if (isMuted) R.drawable.volume_off else R.drawable.volume_up
              playbackState == Player.STATE_ENDED -> R.drawable.replay
              effectiveIsPlaying -> R.drawable.pause
              else -> R.drawable.play
            }
          ),
        contentDescription = null,
        tint = onPrimaryColor,
        modifier = Modifier.size(24.dp)
      )
    }

    Spacer(modifier = Modifier.width(12.dp))

    IconButton(
      enabled = canSkipNext && !isListenTogetherGuest,
      onClick = if (isListenTogetherGuest) ({}) else ({ playerConnection.player.seekToNext() }),
      modifier = Modifier.size(32.dp)
    ) {
      Icon(
        painter = painterResource(R.drawable.skip_next),
        contentDescription = null,
        tint = onSurfaceColor,
        modifier = Modifier.size(20.dp)
      )
    }
  }
}
