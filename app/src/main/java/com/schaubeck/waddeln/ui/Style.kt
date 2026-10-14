package com.schaubeck.waddeln.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import com.schaubeck.waddeln.R

/** Shared look of all screens: wooden table, dark panels, golden highlights. */
val BarGreen = Color(0xFF2F5D1E)
val Panel = Color(0xCC1B140D)
val Highlight = Color(0xFFFFD54F)
val SchubOrange = Color(0xFFE65100)
val WoodBrown = Color(0xFF6D4C2F)

@Composable
fun woodButton() = ButtonDefaults.buttonColors(containerColor = WoodBrown)

@Composable
fun WoodBackground(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    Box(modifier.fillMaxSize().background(Color(0xFF3B2A1A))) {
        Image(
            painter = painterResource(R.drawable.table_wood),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        content()
    }
}
