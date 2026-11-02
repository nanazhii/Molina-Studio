package com.molina.studio.cinema

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp

private val MolinaPurple = Color(0xFF6B4FBB)
private val MolinaGold = Color(0xFFE8B84A)
private val MolinaSurface = Color(0xFF1C1B22)
private val MolinaText = Color(0xFFE6E1EC)
private val MolinaMuted = Color(0xFF8E8A99)
private val HairlineWidth = 0.75.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MolinaLinkSheet(
    recent: List<String> = emptyList(),
    onDismiss: () -> Unit,
    onSubmit: (String) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var focused by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    val focus = remember { FocusRequester() }
    val isUrl = query.trim().let { it.startsWith("http://") || it.startsWith("https://") }
    val submit = { if (query.isNotBlank()) onSubmit(query.trim()) }
    val sheetShape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    val fieldShape = RoundedCornerShape(16.dp)

    LaunchedEffect(Unit) { focus.requestFocus() }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MolinaSurface,
        shape = sheetShape,
        tonalElevation = 0.dp,
        dragHandle = null,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .border(
                    HairlineWidth,
                    Brush.verticalGradient(listOf(MolinaPurple.copy(alpha = 0.7f), Color.Transparent)),
                    sheetShape,
                )
                .padding(start = 24.dp, end = 24.dp, top = 12.dp, bottom = 24.dp)
                .imePadding(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Box(
                Modifier
                    .align(Alignment.CenterHorizontally)
                    .width(36.dp).height(3.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Color.White.copy(alpha = 0.2f))
            )

            Text(
                "Putar dari tautan atau judul",
                style = MaterialTheme.typography.titleLarge,
                color = MolinaText,
            )

            BasicTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = MolinaText),
                cursorBrush = SolidColor(MolinaGold),
                keyboardOptions = KeyboardOptions(imeAction = if (isUrl) ImeAction.Go else ImeAction.Search),
                keyboardActions = KeyboardActions(onGo = { submit() }, onSearch = { submit() }),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .focusRequester(focus)
                    .onFocusChanged { focused = it.isFocused }
                    .border(
                        HairlineWidth,
                        if (focused) MolinaPurple else MolinaPurple.copy(alpha = 0.35f),
                        fieldShape,
                    ),
                decorationBox = { inner ->
                    Row(
                        Modifier.fillMaxHeight().padding(horizontal = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Default.Search, null, Modifier.size(20.dp), tint = MolinaPurple)
                        Spacer(Modifier.width(10.dp))
                        Box(Modifier.weight(1f)) {
                            if (query.isEmpty()) {
                                Text(
                                    "Tempel URL atau ketik judul video",
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MolinaMuted,
                                    maxLines = 1,
                                )
                            }
                            inner()
                        }
                        if (query.isEmpty()) {
                            Text(
                                "Tempel",
                                color = MolinaPurple,
                                style = MaterialTheme.typography.labelLarge,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable { clipboard.getText()?.text?.let { query = it } }
                                    .padding(horizontal = 8.dp, vertical = 6.dp),
                            )
                        } else {
                            Icon(
                                Icons.Default.Close, "Hapus",
                                Modifier
                                    .size(32.dp)
                                    .clip(RoundedCornerShape(16.dp))
                                    .clickable { query = "" }
                                    .padding(6.dp),
                                tint = MolinaMuted,
                            )
                        }
                    }
                },
            )

            if (recent.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    recent.take(3).forEach { item ->
                        Text(
                            item,
                            maxLines = 1,
                            color = MolinaMuted,
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier
                                .clip(RoundedCornerShape(50))
                                .border(HairlineWidth, MolinaPurple.copy(alpha = 0.35f), RoundedCornerShape(50))
                                .clickable { query = item }
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                        )
                    }
                }
            }

            Button(
                onClick = submit,
                enabled = query.isNotBlank(),
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = fieldShape,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MolinaGold,
                    contentColor = Color(0xFF1A1400),
                    disabledContainerColor = MolinaGold.copy(alpha = 0.25f),
                    disabledContentColor = Color(0xFF1A1400).copy(alpha = 0.5f),
                ),
            ) {
                Icon(if (isUrl) Icons.Default.PlayArrow else Icons.Default.Search, null, Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(if (isUrl) "Putar" else "Cari di YouTube")
            }
        }
    }
}
