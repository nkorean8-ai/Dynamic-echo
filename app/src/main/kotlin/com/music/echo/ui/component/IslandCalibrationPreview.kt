package echo.music.iad1tya.ui.component

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

@androidx.compose.runtime.Composable
fun IslandCalibrationPreview(
  verticalOffsetDp: Int,
  verticalBaselineCorrectionDp: Int,
  horizontalOffsetDp: Int,
  compactWidthDp: Int,
  compactHeightDp: Int,
  modifier: Modifier = Modifier,
) {
  Canvas(
    modifier =
      modifier.fillMaxWidth().height(88.dp)
        .clip(RoundedCornerShape(16.dp))
        .background(Color(0xFF171717))
  ) {
    val centerX = size.width / 2f + horizontalOffsetDp.dp.toPx()
    val anchorY =
      size.height * 0.4f + verticalBaselineCorrectionDp.dp.toPx()
    val centerY = anchorY + verticalOffsetDp.dp.toPx()
    val width = compactWidthDp.dp.toPx()
    val height = compactHeightDp.dp.toPx()
    drawCircle(Color.Black, radius = 8.dp.toPx(), center = Offset(size.width / 2f, anchorY))
    drawLine(
      color = Color.White.copy(alpha = 0.55f),
      start = Offset(centerX - 18.dp.toPx(), centerY),
      end = Offset(centerX + 18.dp.toPx(), centerY),
      strokeWidth = 1.dp.toPx(),
    )
    drawLine(
      color = Color.White.copy(alpha = 0.55f),
      start = Offset(centerX, centerY - 18.dp.toPx()),
      end = Offset(centerX, centerY + 18.dp.toPx()),
      strokeWidth = 1.dp.toPx(),
    )
    drawRoundRect(
      color = Color.White.copy(alpha = 0.85f),
      topLeft = Offset(centerX - width / 2f, centerY - height / 2f),
      size = Size(width, height),
      cornerRadius = CornerRadius(height / 2f),
      style = Stroke(width = 1.5.dp.toPx()),
    )
  }
}
