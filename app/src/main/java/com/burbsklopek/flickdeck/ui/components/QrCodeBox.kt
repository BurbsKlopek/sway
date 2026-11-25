package com.burbsklopek.flickdeck.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter

@Composable
fun QrCodeBox(
    content: String,
    size: Dp = 156.dp,
    modifier: Modifier = Modifier
) {
    val matrix = remember(content) {
        runCatching {
            val hints = mapOf(EncodeHintType.MARGIN to 1)
            QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, 0, 0, hints)
        }.getOrNull()
    }

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .shadow(16.dp, RoundedCornerShape(20.dp))
            .clip(RoundedCornerShape(20.dp))
            .background(Color.White)
            .size(size)
            .padding(12.dp)
    ) {
        if (matrix != null) {
            Canvas(modifier = Modifier.matchParentSize()) {
                val cols = matrix.width
                val rows = matrix.height
                if (cols > 0 && rows > 0) {
                    val cellW = this.size.width / cols
                    val cellH = this.size.height / rows
                    for (y in 0 until rows) {
                        for (x in 0 until cols) {
                            if (matrix.get(x, y)) {
                                drawRoundRect(
                                    color = Color(0xFF0A0918),
                                    topLeft = Offset(x * cellW, y * cellH),
                                    size = Size(cellW * 1.03f, cellH * 1.03f),
                                    cornerRadius = CornerRadius(cellW * 0.2f, cellH * 0.2f)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
