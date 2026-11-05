package com.elianfabian.parikt.console

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/** Small shared Material-style vectors; no Android drawable or extended icon dependency. */
internal object ConsoleIcons {
    private fun icon(name: String, path: String) = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
        .addPath(addPathNodes(path), fill = SolidColor(Color.Black)).build()

    val Clear = icon("Clear", "M19,6.41 L17.59,5 L12,10.59 L6.41,5 L5,6.41 L10.59,12 L5,17.59 L6.41,19 L12,13.41 L17.59,19 L19,17.59 L13.41,12 Z")
    val Back = icon("Back", "M20,11 L7.83,11 L13.42,5.41 L12,4 L4,12 L12,20 L13.41,18.59 L7.83,13 L20,13 Z")
    val Forward = icon("Forward", "M12,4 L10.59,5.41 L16.17,11 L4,11 L4,13 L16.17,13 L10.59,18.59 L12,20 L20,12 Z")
    val Check = icon("Check", "M9,16.17 L4.83,12 L3.41,13.41 L9,19 L21,7 L19.59,5.59 Z")
    val More = icon("More", "M12,8 A2,2 0,1 0,12,4 A2,2 0,1 0,12,8 M12,14 A2,2 0,1 0,12,10 A2,2 0,1 0,12,14 M12,20 A2,2 0,1 0,12,16 A2,2 0,1 0,12,20")
    val Play = icon("Play", "M8,5 L8,19 L19,12 Z")
    val Load = icon("Load", "M19,3 L5,3 L5,5 L19,5 L19,19 L5,19 L5,21 L19,21 Q21,21 21,19 L21,5 Q21,3 19,3 M11,7 L9.59,8.41 L12.17,11 L3,11 L3,13 L12.17,13 L9.59,15.59 L11,17 L16,12 Z")
    val Edit = icon("Edit", "M3,17.25 L3,21 L6.75,21 L17.81,9.94 L14.06,6.19 Z M20.71,7.04 Q21.1,6.65 20.71,6.26 L17.74,3.29 Q17.35,2.9 16.96,3.29 L15.13,5.12 L18.88,8.87 Z")
    val Delete = icon("Delete", "M6,19 Q6,21 8,21 L16,21 Q18,21 18,19 L18,7 L6,7 Z M19,4 L15.5,4 L14.5,3 L9.5,3 L8.5,4 L5,4 L5,6 L19,6 Z")
}
