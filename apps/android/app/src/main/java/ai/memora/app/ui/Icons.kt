package ai.memora.app.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/** A small, consistent set of original 24dp outline icons. */
object Glyph {
    private fun icon(name: String, path: String) = ImageVector.Builder(
        name = name, defaultWidth = 24.dp, defaultHeight = 24.dp,
        viewportWidth = 24f, viewportHeight = 24f,
    ).addPath(
        pathData = PathParser().parsePathString(path).toNodes(),
        stroke = SolidColor(Color.Black), strokeLineWidth = 1.7f,
        strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
    ).build()

    val Home = icon("Home", "M3 10L12 3L21 10V20A1 1 0 0 1 20 21H15V14H9V21H4A1 1 0 0 1 3 20Z")
    val Memory = icon("Memory", "M4 4H9A3 3 0 0 1 12 7V21A4 4 0 0 0 8 18H3V4ZM20 4H15A3 3 0 0 0 12 7V21A4 4 0 0 1 16 18H21V4Z")
    val Mic = icon("Microphone", "M9 5A3 3 0 0 1 15 5V11A3 3 0 0 1 9 11ZM5 10V11A7 7 0 0 0 19 11V10M12 18V22M9 22H15")
    val Tasks = icon("Tasks", "M9 4H20V20H4V9M3 4L5 6L9 2M8 11H16M8 15H14")
    val Grid = icon("More", "M3 3H9V9H3ZM15 3H21V9H15ZM3 15H9V21H3ZM15 15H21V21H15Z")
    val Search = icon("Search", "M19 10A7 7 0 1 1 5 10A7 7 0 1 1 19 10M15 15L21 21")
    val Arrow = icon("Arrow", "M5 12H20M14 6L20 12L14 18")
    val Back = icon("Back", "M19 12H4M10 6L4 12L10 18")
    val Chevron = icon("Chevron", "M9 5L16 12L9 19")
    val Check = icon("Check", "M5 12L10 17L20 6")
    val Edit = icon("Edit", "M14 4L20 10M4 20L9 19L21 7A2 2 0 0 0 17 3L5 15ZM3 21H21")
    val Plus = icon("Plus", "M12 4V20M4 12H20")
    val Settings = icon("Settings", "M12 8A4 4 0 1 1 12 16A4 4 0 1 1 12 8M9 3H15L16 6L19 7L21 12L19 17L16 18L15 21H9L8 18L5 17L3 12L5 7L8 6Z")
    val Note = icon("Note", "M5 3H15L20 8V21H5ZM14 3V9H20M9 13H16M9 17H14")
    val Wallet = icon("Wallet", "M20 7V4H5A2 2 0 0 0 5 8H21V21H5A2 2 0 0 1 3 19V6M21 12H15V17H21M17 14.5H17.1")
    val Person = icon("Person", "M16 7A4 4 0 1 1 8 7A4 4 0 1 1 16 7M4 21V19A8 8 0 0 1 20 19V21")
    val Folder = icon("Folder", "M3 6V20H21V7H12L10 4H3Z")
    val Shield = icon("Shield", "M12 2L21 6V12C21 18 15 21 12 22C9 21 3 18 3 12V6ZM8 12L11 15L16 9")
    val Spark = icon("Spark", "M12 3L14.5 9.5L21 12L14.5 14.5L12 21L9.5 14.5L3 12L9.5 9.5ZM20 2V6M18 4H22")
    val Bell = icon("Bell", "M5 17H19L17 14V9A5 5 0 0 0 7 9V14ZM10 21H14M12 2V4")
    val Close = icon("Close", "M6 6L18 18M18 6L6 18")
    val Inbox = icon("Inbox", "M3 14L6 4H18L21 14V21H3ZM3 14H8L10 17H14L16 14H21")
    val Chat = icon("Chat", "M4 3H20V17H10L4 22ZM8 8H16M8 12H13")
    val Sun = icon("Sun", "M16 12A4 4 0 1 1 8 12A4 4 0 1 1 16 12M12 1V3M12 21V23M1 12H3M21 12H23M4 4L6 6M18 18L20 20M4 20L6 18M18 6L20 4")
    val Link = icon("Link", "M10 14L14 10M8 16L6 18A4 4 0 0 1 0 12L5 7A4 4 0 0 1 11 7M13 17A4 4 0 0 0 19 17L24 12A4 4 0 0 0 18 6L16 8")
}
