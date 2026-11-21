package echo.music.iad1tya.ui.player

import android.os.Build
import android.util.Log
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.palette.graphics.Palette
import coil3.compose.AsyncImage
import coil3.imageLoader
import coil3.request.ErrorResult
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.toBitmap
import echo.music.iad1tya.constants.PlayerBackgroundStyle
import echo.music.iad1tya.ui.theme.PlayerColorExtractor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun MiniPlayerColorExtractor(
  artworkUri: String?,
  miniPlayerBackground: PlayerBackgroundStyle,
  onGradientColorsChange: (List<Color>) -> Unit,
) {
  val context = LocalContext.current
  val fallbackColor = MaterialTheme.colorScheme.surfaceContainer.toArgb()

  LaunchedEffect(artworkUri, miniPlayerBackground) {
    if (
      artworkUri == null ||
        miniPlayerBackground !in
          setOf(
            PlayerBackgroundStyle.GRADIENT,
            PlayerBackgroundStyle.GLOW_ANIMATED,
            PlayerBackgroundStyle.LIQUID_GLASS,
          )
    ) {
      onGradientColorsChange(emptyList())
      return@LaunchedEffect
    }

    val colors =
      withContext(Dispatchers.IO) {
        val request =
          ImageRequest.Builder(context)
            .data(artworkUri)
            .size(100, 100)
            .allowHardware(false)
            .build()
        val result = context.imageLoader.execute(request)
        if (result is ErrorResult) {
          Log.w("MiniPlayerBackground", "Unable to load artwork palette", result.throwable)
          return@withContext emptyList()
        }
        val bitmap = result?.image?.toBitmap() ?: return@withContext emptyList()
        val palette =
          withContext(Dispatchers.Default) {
            Palette.from(bitmap).maximumColorCount(8).resizeBitmapArea(100 * 100).generate()
          }
        val extracted =
          if (miniPlayerBackground == PlayerBackgroundStyle.GLOW_ANIMATED) {
            listOf(
                palette.getVibrantColor(fallbackColor),
                palette.getLightVibrantColor(fallbackColor),
                palette.getDarkVibrantColor(fallbackColor),
                palette.getMutedColor(fallbackColor),
                palette.getLightMutedColor(fallbackColor),
                palette.getDarkMutedColor(fallbackColor),
              )
              .distinct()
              .map { Color(it) }
          } else {
            PlayerColorExtractor.extractGradientColors(palette, fallbackColor)
          }
        extracted.distinct().takeIf { it.size >= 2 }.orEmpty()
      }

    withContext(Dispatchers.Main) { onGradientColorsChange(colors) }
  }
}

@Composable
fun MiniPlayerBackgroundLayer(
  style: PlayerBackgroundStyle,
  artworkUri: String?,
  gradientColors: List<Color>,
  modifier: Modifier = Modifier,
) {
  val context = LocalContext.current
  val safeColors = gradientColors.distinct().takeIf { it.size >= 2 }.orEmpty()

  when (style) {
    PlayerBackgroundStyle.BLUR -> {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        AsyncImage(
          model =
            ImageRequest.Builder(context)
              .data(artworkUri)
              .size(128, 128)
              .allowHardware(false)
              .build(),
          contentDescription = null,
          contentScale = ContentScale.Crop,
          modifier = modifier.fillMaxSize().blur(30.dp),
        )
        Box(
          modifier =
            modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f))
        )
      }
    }
    PlayerBackgroundStyle.GRADIENT,
    PlayerBackgroundStyle.LIQUID_GLASS -> {
      if (safeColors.size >= 2) {
        Box(
          modifier =
            modifier.fillMaxSize()
              .background(Brush.verticalGradient(safeColors))
              .background(Color.Black.copy(alpha = 0.2f))
        )
      }
    }
    PlayerBackgroundStyle.GLOW_ANIMATED -> {
      if (safeColors.size >= 2) {
        val infiniteTransition = rememberInfiniteTransition(label = "GlowAnimation")
        val progress =
          infiniteTransition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec =
              infiniteRepeatable(
                animation = tween(20000, easing = LinearEasing),
                repeatMode = RepeatMode.Restart
              ),
            label = "glowProgress"
          )
        Box(
          modifier =
            modifier.fillMaxSize().drawBehind {
              val p = progress.value
              val width = size.width
              val height = size.height

              fun rotatedColorAt(index: Int): Color {
                val count = safeColors.size
                val idx = index.toFloat() + p * count
                val a = kotlin.math.floor(idx).toInt() % count
                val b = (a + 1) % count
                val frac = idx - kotlin.math.floor(idx)
                return lerp(safeColors[a], safeColors[b], frac)
              }

              fun oscillate(min: Float, max: Float, phase: Float): Float {
                val v = kotlin.math.sin(2f * kotlin.math.PI.toFloat() * (p + phase))
                return min + (max - min) * ((v + 1f) * 0.5f)
              }

              val c1 = rotatedColorAt(0)
              val c2 = rotatedColorAt(1)
              val b1 =
                Brush.radialGradient(
                  colors = listOf(c1.copy(alpha = 0.8f), Color.Transparent),
                  center = Offset(width * oscillate(0f, 1f, 0f), height * oscillate(0f, 0.5f, 0.1f)),
                  radius = width * 1.2f
                )
              val b2 =
                Brush.radialGradient(
                  colors = listOf(c2.copy(alpha = 0.7f), Color.Transparent),
                  center = Offset(width * oscillate(1f, 0f, 0.2f), height * oscillate(0.5f, 1f, 0.3f)),
                  radius = width
                )
              drawRect(Color(0xFF050505))
              drawRect(b1)
              drawRect(b2)
            }
        )
      }
    }
    PlayerBackgroundStyle.LIVE_MESH -> {
      val infiniteTransition = rememberInfiniteTransition(label = "liveMesh")
      val rotation =
        infiniteTransition.animateFloat(
          initialValue = 0f,
          targetValue = 360f,
          animationSpec =
            infiniteRepeatable(
              animation = tween(60000, easing = LinearEasing),
              repeatMode = RepeatMode.Restart
            ),
          label = "rotation"
        )
      Box(
        modifier =
          modifier.fillMaxSize().graphicsLayer {
            scaleX = 1.5f
            scaleY = 1.5f
          }
      ) {
        val matrix = remember { ColorMatrix().apply { setToSaturation(1.6f) } }
        AsyncImage(
          model =
            ImageRequest.Builder(context)
              .data(artworkUri)
              .size(128, 128)
              .allowHardware(false)
              .build(),
          contentDescription = null,
          contentScale = ContentScale.Crop,
          colorFilter = ColorFilter.colorMatrix(matrix),
          modifier =
            Modifier.fillMaxSize().blur(40.dp).graphicsLayer { rotationZ = rotation.value }
        )
        Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.3f)))
      }
    }
    else -> Unit
  }
}

@Composable
fun MiniPlayerArtworkBackground(
  style: PlayerBackgroundStyle,
  artworkUri: String?,
  modifier: Modifier = Modifier,
) {
  var gradientColors by remember(artworkUri, style) {
    mutableStateOf<List<Color>>(emptyList())
  }
  MiniPlayerColorExtractor(artworkUri, style) { gradientColors = it }
  MiniPlayerBackgroundLayer(style, artworkUri, gradientColors, modifier)
}
