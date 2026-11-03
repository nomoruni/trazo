package com.trazo.scanner.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowForward
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.trazo.scanner.R
import com.trazo.scanner.ui.theme.Gold

@Composable
fun HomeScreen(
    onCamera: () -> Unit,
    onImagePicked: (Uri) -> Unit,
) {
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let(onImagePicked)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .padding(horizontal = 24.dp),
    ) {
        Spacer(Modifier.height(36.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(8.dp)
                    .background(Gold, CircleShape),
            )
            Spacer(Modifier.size(8.dp))
            Text(
                text = "TRAZO",
                style = MaterialTheme.typography.labelSmall,
                letterSpacing = 4.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(Modifier.height(18.dp))

        Text(
            text = "Tu firma,\nsolo el trazo.",
            style = MaterialTheme.typography.displayLarge.copy(fontSize = 44.sp, lineHeight = 48.sp),
            color = MaterialTheme.colorScheme.onBackground,
        )

        Spacer(Modifier.height(12.dp))

        Text(
            text = stringResource(R.string.home_subtitle),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(8.dp))

        SignatureScribble(
            modifier = Modifier
                .fillMaxWidth()
                .height(86.dp),
        )

        Spacer(Modifier.weight(1f))

        ActionCard(
            icon = Icons.Rounded.PhotoCamera,
            title = stringResource(R.string.home_camera),
            subtitle = stringResource(R.string.home_camera_desc),
            onClick = onCamera,
        )

        Spacer(Modifier.height(12.dp))

        ActionCard(
            icon = Icons.Rounded.PhotoLibrary,
            title = stringResource(R.string.home_gallery),
            subtitle = stringResource(R.string.home_gallery_desc),
            onClick = {
                picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            },
        )

        Spacer(Modifier.height(20.dp))

        Text(
            text = stringResource(R.string.home_tip),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
        )

        Spacer(Modifier.height(28.dp))
    }
}

@Composable
private fun ActionCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = 0.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .background(MaterialTheme.colorScheme.primary, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(24.dp),
                )
            }
            Spacer(Modifier.size(16.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                imageVector = Icons.Rounded.ArrowForward,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/** Trazo decorativo, como una firma sobre el papel. */
@Composable
private fun SignatureScribble(modifier: Modifier = Modifier) {
    val ink = MaterialTheme.colorScheme.onBackground
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val path = Path().apply {
            moveTo(w * 0.04f, h * 0.62f)
            cubicTo(w * 0.10f, h * 0.10f, w * 0.16f, h * 0.05f, w * 0.20f, h * 0.50f)
            cubicTo(w * 0.23f, h * 0.82f, w * 0.26f, h * 0.30f, w * 0.32f, h * 0.60f)
            cubicTo(w * 0.37f, h * 0.86f, w * 0.41f, h * 0.12f, w * 0.48f, h * 0.45f)
            cubicTo(w * 0.54f, h * 0.75f, w * 0.58f, h * 0.90f, w * 0.64f, h * 0.38f)
            cubicTo(w * 0.68f, h * 0.05f, w * 0.72f, h * 0.62f, w * 0.80f, h * 0.58f)
        }
        drawPath(
            path = path,
            color = ink.copy(alpha = 0.85f),
            style = Stroke(width = 2.4.dp.toPx(), cap = StrokeCap.Round),
        )
        drawLine(
            color = Gold,
            start = Offset(w * 0.03f, h * 0.86f),
            end = Offset(w * 0.86f, h * 0.80f),
            strokeWidth = 2.dp.toPx(),
            cap = StrokeCap.Round,
        )
        drawCircle(
            color = Gold,
            radius = 2.6.dp.toPx(),
            center = Offset(w * 0.71f, h * 0.50f),
        )
    }
}
