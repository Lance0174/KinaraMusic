/* Kinara — Copyright (C) 2026 Kinara contributors. GPL-3.0. */
package com.kinara.music.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kinara.music.ui.theme.KinaraGlassPanel
import com.kinara.music.ui.theme.LocalKinaraTheme

enum class KinaraNav(val label: String, val glyph: Glyph) {
    LIBRARY("音乐库", Glyph.LIBRARY), SEARCH("搜索", Glyph.SEARCH), SETTINGS("设置", Glyph.SETTINGS)
}

@Composable
fun KinaraNavBar(current: KinaraNav, onSelect: (KinaraNav) -> Unit, modifier: Modifier = Modifier) {
    val c = LocalKinaraTheme.current.colors
    KinaraGlassPanel(modifier = modifier.fillMaxWidth(), shape = RoundedCornerShape(32.dp)) {
        Row(Modifier.fillMaxWidth().selectableGroup().padding(6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            KinaraNav.entries.forEach { item ->
                val selected = current == item
                Column(Modifier.weight(1f).clip(com.kinara.music.ui.theme.kinaraShape(26.dp))
                    .background(if (selected) c.accent.copy(alpha = if (c.isDark) 0.2f else 0.12f) else Color.Transparent)
                    .selectable(selected, role = Role.Tab, onClick = { onSelect(item) }).padding(vertical = 10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    KinaraIcon(item.glyph, tint = if (selected) c.accent else c.textDim)
                    Spacer(Modifier.height(4.dp))
                    Text(item.label, fontSize = 12.sp, color = if (selected) c.accent else c.textDim,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
                }
            }
        }
    }
}
