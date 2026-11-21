package com.odininputmirror.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.odininputmirror.domain.model.ControllerDevice
import com.odininputmirror.domain.model.matchesManualPick
import com.odininputmirror.ui.theme.Palette

// The two modal surfaces of the mirror screen: the dead end for an unsupported device, and the
// picker for telling the app which pad is the built-in one when it cannot tell.

@Composable
internal fun UnsupportedDeviceDialog(serviceUnresponsive: Boolean) {
    // No dismiss handler: the mirror cannot run here, so the notice stays put.
    Dialog(onDismissRequest = {}) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(Color(0xFF1A2230))
                .border(1.dp, Palette.border, RoundedCornerShape(14.dp))
                .padding(horizontal = 24.dp, vertical = 22.dp),
        ) {
            Text("Unsupported device", color = Palette.danger, fontSize = 17.sp, fontWeight = FontWeight.ExtraBold)
            Spacer(Modifier.height(12.dp))
            Text(
                if (serviceUnresponsive) {
                    // Some firmware publishes the service while the process behind it is dead.
                    // Saying "your device doesn't have it" would be plainly wrong on hardware that
                    // ships it, so this case gets its own wording.
                    "Your device ships the PServer service the mirror needs, but it isn't " +
                        "responding. This can happen on some firmware versions."
                } else {
                    "This app needs the built-in PServer service that ships on handhelds like the AYN " +
                        "Odin 2 family. Your device doesn't have it, so the dock mirror can't run here."
                },
                color = Palette.textSecondary,
                fontSize = 14.sp,
            )
        }
    }
}

@Composable
internal fun ControllerPicker(
    title: String,
    emptyMessage: String,
    devices: List<ControllerDevice>,
    selectedGuid: String?,
    // Matched too when given: a guid is only vendor:product, so two devices can share one (the RP5's
    // virtual mouse and a republished pad), and the guid alone would mark both as selected.
    selectedName: String? = null,
    // The picked device, or null for automatic. Devices without a guid cannot be saved as a pick.
    onSelect: (ControllerDevice?) -> Unit,
    onClose: () -> Unit,
) {
    Dialog(onDismissRequest = onClose) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(Color(0xFF1A2230))
                .border(1.dp, Palette.border, RoundedCornerShape(14.dp))
                .padding(horizontal = 24.dp, vertical = 20.dp),
        ) {
            Text(title, color = Palette.textPrimary, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold)
            Spacer(Modifier.height(14.dp))
            Column(
                modifier = Modifier
                    .heightIn(max = 280.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                PickerOption(name = "Automatic (detect)", selected = selectedGuid == null) { onSelect(null) }
                if (devices.isEmpty()) {
                    Text(
                        emptyMessage,
                        color = Palette.textSecondary,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(vertical = 12.dp),
                    )
                } else {
                    devices.forEach { device ->
                        PickerOption(
                            name = device.name,
                            selected = device.matchesManualPick(selectedGuid, selectedName),
                        ) { if (device.guid.isNotEmpty()) onSelect(device) }
                    }
                }
            }
        }
    }
}

@Composable
internal fun PickerOption(name: String, selected: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 58.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (focused) Palette.focusBg else Color.Transparent)
            .clickable(interactionSource = interaction, indication = null) { onClick() }
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(20.dp)
                .clip(CircleShape)
                .border(2.dp, if (selected) Palette.accent else Color(0xFF5B6175), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) {
                Box(modifier = Modifier.size(10.dp).clip(CircleShape).background(Palette.accent))
            }
        }
        Spacer(Modifier.width(12.dp))
        Text(
            name,
            color = Palette.textPrimary,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
