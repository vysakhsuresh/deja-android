package com.layerbit.deja.ui.onboarding

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.layerbit.deja.ui.components.TapTarget
import com.layerbit.deja.ui.theme.DejaColors

/**
 * @param canAskAgain false once Android has stopped showing the system dialog. At that point
 *   asking again does nothing at all, so the screen has to send the user to Settings instead of
 *   offering a button that silently fails - which is what made a refused install look permanently
 *   broken.
 */
@Composable
fun OnboardingScreen(
    canAskAgain: Boolean,
    onRequest: () -> Unit,
    onOpenSettings: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DejaColors.Background)
            .systemBarsPadding()
            .padding(horizontal = 24.dp, vertical = 24.dp)
    ) {
        Text(
            text = "deja.",
            color = DejaColors.Text,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold
        )

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = if (canAskAgain) {
                    "You've already seen it.\nNow you can find it."
                } else {
                    "Deja needs your\nscreenshots folder."
                },
                color = DejaColors.Text,
                fontSize = 30.sp,
                lineHeight = 38.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(18.dp))
            Text(
                text = if (canAskAgain) {
                    "Deja reads your screenshots so you can search them in words instead of " +
                        "scrolling for them. To do that it needs to see your Screenshots folder."
                } else {
                    "Android won't show the permission prompt again after it has been declined, " +
                        "so it has to be switched on by hand. Open Settings → Permissions → " +
                        "Photos and videos, and choose Allow all."
                },
                color = DejaColors.Muted,
                fontSize = 15.sp,
                lineHeight = 23.sp
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(DejaColors.SurfaceDim)
                .padding(14.dp)
        ) {
            Icon(
                imageVector = Icons.Filled.Lock,
                contentDescription = null,
                tint = DejaColors.Green,
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.size(11.dp))
            Text(
                text = "Reading happens entirely on this phone. Deja has no internet " +
                    "permission at all, so nothing it reads can be sent anywhere.",
                color = DejaColors.Muted,
                fontSize = 12.5.sp,
                lineHeight = 18.sp
            )
        }

        Spacer(Modifier.height(12.dp))

        PrimaryButton(
            label = if (canAskAgain) "Choose the screenshots folder" else "Open Deja's settings",
            onClick = if (canAskAgain) onRequest else onOpenSettings
        )

        if (!canAskAgain) {
            Spacer(Modifier.height(10.dp))
            Text(
                text = "Already granted it? Come back and Deja will pick it up.",
                color = DejaColors.Dim,
                fontSize = 12.sp,
                modifier = Modifier.fillMaxWidth(),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
        }
    }
}

@Composable
private fun PrimaryButton(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(TapTarget + 6.dp)
            .clip(RoundedCornerShape(15.dp))
            .background(DejaColors.Amber)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            color = DejaColors.OnAmber,
            fontSize = 15.5.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}
