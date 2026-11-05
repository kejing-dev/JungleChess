package io.embodit.junglechess.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Design tokens (design package assets / sample §9 palette). Window roles go through PicoTheme
 * (JungleTheme); the board tokens are used by the 3D scene and the small cards.
 */
object JungleColors {
    // ---- ColorScheme role overrides ----
    val Interaction = Color(0xFF2F6B3A) // design-style: fixed-figma-color sample §9 jungle green (icon ground)
    val Passable = Color(0xFF2F7440) // design-style: fixed-figma-color sample §9 passable
    val Alert = Color(0xFFA86A1C) // design-style: fixed-figma-color sample §9 alert (den alarm)
    val Error = Color(0xFFB0413A) // design-style: fixed-figma-color sample §9 error
    val OnInteraction = Color(0xFFFFFFFF) // design-style: fixed-figma-color labelPrimaryLight
    val FillTertiary = Color(0xFFE2ECDD) // design-style: fixed-figma-color sample §9 fillTertiary
    val DividerLine = Color(0xFFC9D6C4) // design-style: fixed-figma-color sample §9 divider

    // ---- board / cards ----
    val Red = Color(0xFFB5452E) // design-style: fixed-figma-color team red
    val Blue = Color(0xFF2F5F9E) // design-style: fixed-figma-color team blue
    val Ink = Color(0xFF1F2A1E) // design-style: fixed-figma-color card ink (solid, not Vibrant grey, D13)
    val Paper = Color(0xFFF7F1E3) // design-style: fixed-figma-color card text
    val Grass = Color(0xFF5A9A44) // design-style: fixed-figma-color turf
    val River = Color(0xFF2F7FB8) // design-style: fixed-figma-color river
    val Wood = Color(0xFFE3C08E) // design-style: fixed-figma-color animal wood
    val Gold = Color(0xFFD9B45A) // design-style: fixed-figma-color rank ring
    val Hint = Color(0xFFF2C12E) // design-style: fixed-figma-color hint yellow
    val Legal = Color(0xFF3D8BE0) // design-style: fixed-figma-color legal blue dot
    val Capture = Color(0xFFF08A24) // design-style: fixed-figma-color capture orange
    val Bad = Color(0xFFE0453A) // design-style: fixed-figma-color illegal red
}
