package com.burbsklopek.flickdeck.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.burbsklopek.flickdeck.model.DeviceAuthSession
import com.burbsklopek.flickdeck.model.GlassIntensity
import com.burbsklopek.flickdeck.model.ProviderAccountState
import com.burbsklopek.flickdeck.model.SyncProvider
import com.burbsklopek.flickdeck.ui.components.QrCodeBox
import com.burbsklopek.flickdeck.ui.theme.*
import com.burbsklopek.flickdeck.viewmodel.FlickDeckUiState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun SyncHubScreen(
    uiState: FlickDeckUiState,
    onStartAuth: (SyncProvider) -> Unit,
    onDisconnect: (SyncProvider) -> Unit,
    onCancelAuth: () -> Unit,
    onSyncNow: () -> Unit,
    onUpdateDrafts: (traktId: String?, traktSecret: String?, simklId: String?) -> Unit,
    onSaveDrafts: () -> Unit
) {
    val settings = LocalAppSettings.current
    val theme = settings.themePreset
    var showManualKeyEditor by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxSize()) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(28.dp),
            modifier = Modifier
                .fillMaxSize()
                .padding(start = 48.dp, end = 48.dp, bottom = 26.dp)
                .liquidGlass(theme, settings.glassIntensity, RoundedCornerShape(26.dp))
                .padding(horizontal = 32.dp, vertical = 26.dp)
        ) {
            // ── Left Column: Trakt & Simkl Accounts + Sync Controls ──
            Column(
                verticalArrangement = Arrangement.spacedBy(20.dp),
                modifier = Modifier
                    .weight(1.55f)
                    .verticalScroll(rememberScrollState())
            ) {
                ProviderSectionRow(
                    account = uiState.traktAccount,
                    brandAccent = Color(0xFFEF4444),
                    description = "Sync your Trakt Watchlist, watched history, and show ratings with a TV activation code.",
                    onConnect = { onStartAuth(SyncProvider.TRAKT) },
                    onDisconnect = { onDisconnect(SyncProvider.TRAKT) }
                )

                Box(Modifier.fillMaxWidth().height(1.dp).background(Color.White.copy(alpha = 0.08f)))

                ProviderSectionRow(
                    account = uiState.simklAccount,
                    brandAccent = Color(0xFF38BDF8),
                    description = "Sync Plan to Watch and Completed lists across TV shows, anime, and films via Simkl PIN.",
                    onConnect = { onStartAuth(SyncProvider.SIMKL) },
                    onDisconnect = { onDisconnect(SyncProvider.SIMKL) }
                )

                Box(Modifier.fillMaxWidth().height(1.dp).background(Color.White.copy(alpha = 0.08f)))

                if (!uiState.authError.isNullOrBlank()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(theme.passColor.copy(alpha = 0.16f))
                            .border(1.dp, theme.passColor.copy(alpha = 0.6f), RoundedCornerShape(12.dp))
                            .padding(horizontal = 16.dp, vertical = 11.dp)
                    ) {
                        Text(
                            text = uiState.authError,
                            color = SwayIvory,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }

                // Sync Queue & Manual Key Toggle
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Library sync",
                            color = SwayIvory,
                            fontFamily = LocalSwayFonts.current.display,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.ExtraBold
                        )
                        Text(
                            text = "${uiState.trackedMap.size} tracked locally, ${uiState.pendingSyncCount} queued to upload",
                            color = SwaySilver,
                            fontSize = 13.sp
                        )
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        GlassPillButton(
                            text = if (showManualKeyEditor) "Hide key fields" else "Enter keys on TV",
                            onClick = { showManualKeyEditor = !showManualKeyEditor }
                        )
                        GlassPillButton(
                            text = if (uiState.isSyncingNow) "Syncing..." else "Sync library",
                            selected = true,
                            accentColor = theme.primaryColor,
                            onClick = onSyncNow
                        )
                    }
                }

                if (showManualKeyEditor) {
                    ManualKeyFields(
                        traktId = uiState.traktClientIdDraft,
                        traktSecret = uiState.traktSecretDraft,
                        simklId = uiState.simklClientIdDraft,
                        onUpdateDrafts = onUpdateDrafts,
                        onSaveDrafts = onSaveDrafts
                    )
                }
            }

            // Vertical Structural Divider
            Box(
                modifier = Modifier
                    .width(1.dp)
                    .fillMaxHeight()
                    .background(Color.White.copy(alpha = 0.08f))
            )

            // ── Right Column: Phone Companion QR Setup ──
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier
                    .weight(0.9f)
                    .fillMaxHeight()
            ) {
                Column(horizontalAlignment = Alignment.Start, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "Paste keys from phone",
                        color = SwayIvory,
                        fontFamily = LocalSwayFonts.current.display,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "Scan on your home Wi-Fi to paste your Trakt or Simkl client IDs without typing on the TV remote.",
                        color = SwaySilver,
                        fontSize = 13.sp,
                        lineHeight = 19.sp
                    )
                }

                val companionUrl = uiState.companionUrl
                if (!companionUrl.isNullOrBlank()) {
                    QrCodeBox(content = companionUrl, size = 164.dp)
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = companionUrl,
                            color = theme.primaryColor,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.height(3.dp))
                        Text(
                            text = "Available on local network while Sync is open",
                            color = SwaySilver,
                            fontSize = 11.5.sp
                        )
                    }
                } else {
                    Text(
                        text = "Connect to Wi-Fi or Ethernet to enable phone QR setup, or select Enter keys on TV.",
                        color = SwaySilver,
                        fontSize = 13.sp
                    )
                }
            }
        }

        val session = uiState.activeAuthSession
        if (session != null) {
            DeviceAuthQrModal(
                session = session,
                onCancel = onCancelAuth
            )
        }
    }
}

@Composable
private fun ProviderSectionRow(
    account: ProviderAccountState,
    brandAccent: Color,
    description: String,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit
) {
    val theme = LocalAppSettings.current.themePreset

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier
                .weight(1f)
                .padding(end = 20.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = account.provider.displayName,
                    color = SwayIvory,
                    fontFamily = LocalSwayFonts.current.display,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.ExtraBold
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(
                            if (account.isConnected) Color(0xFF10B981).copy(alpha = 0.16f)
                            else Color.White.copy(alpha = 0.07f)
                        )
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                ) {
                    Box(
                        Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(if (account.isConnected) Color(0xFF34D399) else SwaySilver)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = if (account.isConnected) "Connected" else "Not signed in",
                        color = if (account.isConnected) Color(0xFF6EE7B7) else SwaySilver,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            Text(
                text = description,
                color = SwaySilver,
                fontSize = 13.sp,
                lineHeight = 18.5.sp
            )

            val lastSyncSuffix = if (account.lastSyncMs > 0L) {
                val fmt = SimpleDateFormat("HH:mm", Locale.UK)
                ", synced at ${fmt.format(Date(account.lastSyncMs))}"
            } else ""
            Text(
                text = account.statusMessage + lastSyncSuffix,
                color = theme.primaryColor,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (account.isConnected) {
                GlassPillButton(
                    text = "Reconnect",
                    accentColor = brandAccent,
                    onClick = onConnect
                )
                GlassPillButton(
                    text = "Sign out",
                    accentColor = theme.passColor,
                    onClick = onDisconnect
                )
            } else {
                GlassPillButton(
                    text = "Sign in with TV code",
                    selected = true,
                    accentColor = brandAccent,
                    onClick = onConnect
                )
            }
        }
    }
}

@Composable
private fun ManualKeyFields(
    traktId: String,
    traktSecret: String,
    simklId: String,
    onUpdateDrafts: (String?, String?, String?) -> Unit,
    onSaveDrafts: () -> Unit
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Color.Black.copy(alpha = 0.28f))
            .border(1.dp, Color.White.copy(alpha = 0.10f), RoundedCornerShape(14.dp))
            .padding(18.dp)
    ) {
        KeyField("Trakt client ID", traktId) { onUpdateDrafts(it, null, null) }
        KeyField("Trakt client secret", traktSecret) { onUpdateDrafts(null, it, null) }
        KeyField("Simkl client ID", simklId) { onUpdateDrafts(null, null, it) }
        Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
            GlassPillButton(
                text = "Save keys",
                selected = true,
                onClick = onSaveDrafts
            )
        }
    }
}

@Composable
private fun KeyField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit
) {
    val theme = LocalAppSettings.current.themePreset
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(text = label, color = SwaySilver, fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(Color.Black.copy(alpha = 0.50f))
                .border(1.dp, Color.White.copy(alpha = 0.15f), RoundedCornerShape(10.dp))
                .padding(horizontal = 12.dp, vertical = 9.dp)
        ) {
            if (value.isEmpty()) {
                Text(text = "Enter $label", color = SwaySilver.copy(alpha = 0.45f), fontSize = 13.sp)
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                cursorBrush = SolidColor(theme.primaryColor),
                textStyle = TextStyle(
                    color = SwayIvory,
                    fontFamily = LocalSwayFonts.current.body,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium
                ),
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun DeviceAuthQrModal(
    session: DeviceAuthSession,
    onCancel: () -> Unit
) {
    val settings = LocalAppSettings.current
    val theme = settings.themePreset

    BackHandler(onBack = onCancel)

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.84f))
            .modalScrimDismiss(onCancel)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(30.dp),
            modifier = Modifier
                .width(680.dp)
                .liquidGlass(theme, GlassIntensity.ULTRA_GLASS, RoundedCornerShape(26.dp), isFocused = true)
                .consumeModalTouches()
                .padding(32.dp)
        ) {
            QrCodeBox(content = session.verificationUrl, size = 186.dp)

            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = "Connect ${session.provider.displayName}",
                    color = SwayIvory,
                    fontFamily = LocalSwayFonts.current.display,
                    fontSize = 26.sp,
                    fontWeight = FontWeight.ExtraBold
                )
                Text(
                    text = "Scan the QR code or open ${session.verificationUrl} and enter this code:",
                    color = SwaySilver,
                    fontSize = 14.sp,
                    lineHeight = 20.sp
                )

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color.Black.copy(alpha = 0.60f))
                        .border(1.5.dp, theme.primaryColor, RoundedCornerShape(12.dp))
                        .padding(horizontal = 22.dp, vertical = 10.dp)
                ) {
                    Text(
                        text = session.userCode,
                        color = SwayIvory,
                        fontFamily = LocalSwayFonts.current.display,
                        fontSize = 32.sp,
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = 4.sp
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            color = theme.primaryColor,
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            text = "Waiting for confirmation",
                            color = SwaySilver,
                            fontSize = 13.sp
                        )
                    }

                    GlassPillButton(
                        text = "Cancel",
                        accentColor = theme.passColor,
                        onClick = onCancel
                    )
                }
            }
        }
    }
}
