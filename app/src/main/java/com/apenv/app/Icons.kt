package com.apenv.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// Every icon is drawn on a 24 x 24 grid with rounded outline strokes, so the whole
// set scales cleanly and picks up any tint. No emoji, no font glyphs.
enum class Ic {
    FOLDER, ARCHIVE, DOC, MODEL, TEXTURE, MAP, COLLISION, IMAGE, AUDIO, SCRIPT, DB, FILE,
    SEARCH, UP, BACK, CHEVRON, MORE, CLOSE, PLUS, ARROW_R, ARROW_L,
    COPY, CUT, PASTE, RENAME, TRASH, PERSON, GTAV, TARGET, CHECK, REFRESH, CUBES, SCAN, EYE, CAR, OIV
}

fun iconKindFor(name: String, dir: Boolean): Ic {
    if (dir) return Ic.FOLDER
    return when (name.substringAfterLast('.', "").lowercase()) {
        "rpf" -> Ic.ARCHIVE
        "xml", "meta", "ini", "cfg", "json", "csv", "dat", "gxt2" -> Ic.DOC
        "txt", "log" -> Ic.DOC
        "ytd" -> Ic.TEXTURE
        "ydr", "yft", "ydd" -> Ic.MODEL
        "ybn" -> Ic.COLLISION
        "ymap", "ytyp" -> Ic.MAP
        "png", "jpg", "jpeg", "webp", "bmp" -> Ic.IMAGE
        "mp3", "wav", "ogg" -> Ic.AUDIO
        "db" -> Ic.DB
        "lua" -> Ic.SCRIPT
        else -> Ic.FILE
    }
}

fun tintFor(ic: Ic): Color = when (ic) {
    Ic.FOLDER -> Color(0xFFE6B450)
    Ic.ARCHIVE -> Color(0xFFB394FF)
    Ic.DOC -> Color(0xFF9FB8B5)
    Ic.MODEL -> Color(0xFF6DB6FF)
    Ic.TEXTURE -> Color(0xFF4FD1C5)
    Ic.MAP -> Color(0xFFFF9B6B)
    Ic.COLLISION -> Color(0xFFD98C8C)
    Ic.IMAGE -> Color(0xFF7BDB8F)
    Ic.AUDIO -> Color(0xFFFF7FAE)
    Ic.SCRIPT -> Color(0xFFE8D66B)
    Ic.DB -> Color(0xFF8FA3FF)
    else -> Color(0xFF8E9A99)
}

@Composable
fun AppIcon(
    ic: Ic,
    tint: Color,
    size: Dp = 20.dp,
    modifier: Modifier = Modifier,
    strokeWidth: Float = 1.7f
) {
    Canvas(modifier.size(size)) {
        val s = this.size.minDimension / 24f
        withTransform({ scale(s, s, pivot = Offset.Zero) }) {
            drawIcon(ic, tint, strokeWidth)
        }
    }
}

// File or folder icon: outline glyph on a softly tinted tile.
@Composable
fun FileIcon(name: String, dir: Boolean, tile: Dp = 32.dp) {
    val ic = iconKindFor(name, dir)
    val c = tintFor(ic)
    Box(
        Modifier.size(tile).background(c.copy(alpha = 0.13f), RoundedCornerShape(9.dp)),
        contentAlignment = Alignment.Center
    ) {
        AppIcon(ic, c, tile * 0.58f)
    }
}

private fun path(block: Path.() -> Unit): Path = Path().apply(block)

private fun DrawScope.drawIcon(ic: Ic, c: Color, sw: Float) {
    val st = Stroke(width = sw, cap = StrokeCap.Round, join = StrokeJoin.Round)
    val soft = c.copy(alpha = 0.18f)

    fun outline(p: Path, fill: Boolean = false) {
        if (fill) drawPath(p, soft, style = Fill)
        drawPath(p, c, style = st)
    }

    when (ic) {
        Ic.FOLDER -> outline(path {
            moveTo(3f, 7f); quadraticBezierTo(3f, 5f, 5f, 5f); lineTo(9f, 5f)
            lineTo(11f, 7.5f); lineTo(19f, 7.5f); quadraticBezierTo(21f, 7.5f, 21f, 9.5f)
            lineTo(21f, 17f); quadraticBezierTo(21f, 19f, 19f, 19f); lineTo(5f, 19f)
            quadraticBezierTo(3f, 19f, 3f, 17f); close()
        }, fill = true)

        Ic.ARCHIVE -> {
            drawRoundRect(soft, Offset(3f, 5f), Size(18f, 5f), CornerRadius(1.5f))
            drawRoundRect(c, Offset(3f, 5f), Size(18f, 5f), CornerRadius(1.5f), style = st)
            outline(path {
                moveTo(5f, 10f); lineTo(5f, 18f); quadraticBezierTo(5f, 20f, 7f, 20f)
                lineTo(17f, 20f); quadraticBezierTo(19f, 20f, 19f, 18f); lineTo(19f, 10f)
            })
            outline(path { moveTo(10f, 14f); lineTo(14f, 14f) })
        }

        Ic.DOC, Ic.FILE -> {
            outline(path {
                moveTo(7f, 3f); lineTo(14f, 3f); lineTo(19f, 8f); lineTo(19f, 19f)
                quadraticBezierTo(19f, 21f, 17f, 21f); lineTo(7f, 21f)
                quadraticBezierTo(5f, 21f, 5f, 19f); lineTo(5f, 5f)
                quadraticBezierTo(5f, 3f, 7f, 3f); close()
            }, fill = true)
            outline(path { moveTo(14f, 3f); lineTo(14f, 8f); lineTo(19f, 8f) })
            if (ic == Ic.DOC) {
                outline(path { moveTo(8.5f, 13f); lineTo(15.5f, 13f) })
                outline(path { moveTo(8.5f, 16.5f); lineTo(13f, 16.5f) })
            }
        }

        Ic.MODEL -> {
            outline(path {
                moveTo(12f, 3f); lineTo(20f, 7.5f); lineTo(20f, 16.5f)
                lineTo(12f, 21f); lineTo(4f, 16.5f); lineTo(4f, 7.5f); close()
            }, fill = true)
            outline(path { moveTo(4f, 7.5f); lineTo(12f, 12f); lineTo(20f, 7.5f) })
            outline(path { moveTo(12f, 12f); lineTo(12f, 21f) })
        }

        // Four small isometric cubes that read as one larger cube.
        Ic.SCAN -> {
            outline(path { moveTo(4f, 9f); lineTo(4f, 6f); quadraticBezierTo(4f, 4f, 6f, 4f); lineTo(9f, 4f) })
            outline(path { moveTo(15f, 4f); lineTo(18f, 4f); quadraticBezierTo(20f, 4f, 20f, 6f); lineTo(20f, 9f) })
            outline(path { moveTo(20f, 15f); lineTo(20f, 18f); quadraticBezierTo(20f, 20f, 18f, 20f); lineTo(15f, 20f) })
            outline(path { moveTo(9f, 20f); lineTo(6f, 20f); quadraticBezierTo(4f, 20f, 4f, 18f); lineTo(4f, 15f) })
            outline(path { moveTo(7.5f, 12f); lineTo(16.5f, 12f) })
        }

        Ic.CUBES -> {
            val r = 4.2f
            val k = 0.866f * r
            for ((cx, cy) in listOf(12f to 6.2f, 8.36f to 12.5f, 15.64f to 12.5f, 12f to 18.8f)) {
                outline(path {
                    moveTo(cx, cy - r); lineTo(cx + k, cy - r / 2); lineTo(cx + k, cy + r / 2)
                    lineTo(cx, cy + r); lineTo(cx - k, cy + r / 2); lineTo(cx - k, cy - r / 2); close()
                }, fill = true)
                outline(path {
                    moveTo(cx - k, cy - r / 2); lineTo(cx, cy); lineTo(cx + k, cy - r / 2)
                    moveTo(cx, cy); lineTo(cx, cy + r)
                })
            }
        }

        Ic.TEXTURE -> {
            drawRect(soft, Offset(4f, 4f), Size(8f, 8f))
            drawRect(soft, Offset(12f, 12f), Size(8f, 8f))
            drawRoundRect(c, Offset(4f, 4f), Size(16f, 16f), CornerRadius(2.5f), style = st)
            outline(path { moveTo(12f, 4f); lineTo(12f, 20f) })
            outline(path { moveTo(4f, 12f); lineTo(20f, 12f) })
        }

        Ic.IMAGE -> {
            drawRoundRect(soft, Offset(3.5f, 5f), Size(17f, 14f), CornerRadius(2.5f))
            drawRoundRect(c, Offset(3.5f, 5f), Size(17f, 14f), CornerRadius(2.5f), style = st)
            drawCircle(c, 1.5f, Offset(9f, 10f), style = st)
            outline(path {
                moveTo(4f, 17f); lineTo(9.5f, 12.5f); lineTo(13f, 15.5f)
                lineTo(15.5f, 13.5f); lineTo(20f, 17.5f)
            })
        }

        Ic.MAP -> {
            outline(path {
                moveTo(3f, 6f); lineTo(9f, 4f); lineTo(15f, 6f); lineTo(21f, 4f)
                lineTo(21f, 18f); lineTo(15f, 20f); lineTo(9f, 18f); lineTo(3f, 20f); close()
            }, fill = true)
            outline(path { moveTo(9f, 4f); lineTo(9f, 18f) })
            outline(path { moveTo(15f, 6f); lineTo(15f, 20f) })
        }

        Ic.COLLISION -> {
            drawRoundRect(soft, Offset(4f, 5f), Size(16f, 14f), CornerRadius(2f))
            drawRoundRect(c, Offset(4f, 5f), Size(16f, 14f), CornerRadius(2f), style = st)
            outline(path { moveTo(4f, 12f); lineTo(20f, 12f) })
            outline(path { moveTo(10f, 5f); lineTo(10f, 12f) })
            outline(path { moveTo(15f, 12f); lineTo(15f, 19f) })
        }

        Ic.AUDIO -> {
            drawCircle(soft, 2.6f, Offset(8f, 17f))
            drawCircle(soft, 2.6f, Offset(17f, 15f))
            drawCircle(c, 2.6f, Offset(8f, 17f), style = st)
            drawCircle(c, 2.6f, Offset(17f, 15f), style = st)
            outline(path { moveTo(10.6f, 17f); lineTo(10.6f, 6f); lineTo(19.6f, 4f); lineTo(19.6f, 15f) })
        }

        Ic.SCRIPT -> {
            outline(path { moveTo(9f, 7f); lineTo(4f, 12f); lineTo(9f, 17f) })
            outline(path { moveTo(15f, 7f); lineTo(20f, 12f); lineTo(15f, 17f) })
            outline(path { moveTo(13.5f, 5f); lineTo(10.5f, 19f) })
        }

        Ic.DB -> {
            val top = Rect(5f, 4f, 19f, 9f)
            outline(path { addOval(top) }, fill = true)
            outline(path { moveTo(5f, 6.5f); lineTo(5f, 17.5f) })
            outline(path { moveTo(19f, 6.5f); lineTo(19f, 17.5f) })
            outline(path { moveTo(19f, 12f); arcTo(Rect(5f, 9.5f, 19f, 14.5f), 0f, 180f, false) })
            outline(path { moveTo(19f, 17.5f); arcTo(Rect(5f, 15f, 19f, 20f), 0f, 180f, false) })
        }

        Ic.SEARCH -> {
            drawCircle(c, 6f, Offset(10.5f, 10.5f), style = st)
            outline(path { moveTo(15f, 15f); lineTo(20f, 20f) })
        }

        Ic.EYE -> {
            outline(path {
                moveTo(2.5f, 12f)
                cubicTo(6f, 6f, 18f, 6f, 21.5f, 12f)
                cubicTo(18f, 18f, 6f, 18f, 2.5f, 12f)
            })
            drawCircle(c, 3f, Offset(12f, 12f), style = st)
        }

        Ic.CAR -> {
            outline(path { moveTo(5.29f, 9.45f); lineTo(7.73f, 3.96f); lineTo(16.27f, 3.96f); lineTo(18.71f, 9.45f) })
            outline(path {
                moveTo(2.24f, 9.45f); lineTo(21.76f, 9.45f); quadraticBezierTo(22.98f, 9.45f, 22.98f, 10.67f)
                lineTo(22.98f, 17.99f); lineTo(1.02f, 17.99f); lineTo(1.02f, 10.67f); quadraticBezierTo(1.02f, 9.45f, 2.24f, 9.45f)
            })
            outline(path { moveTo(4.68f, 17.99f); lineTo(4.68f, 21.04f) })
            outline(path { moveTo(19.32f, 17.99f); lineTo(19.32f, 21.04f) })
            outline(path { moveTo(9.56f, 14.94f); lineTo(14.44f, 14.94f) })
            drawCircle(c, 1.5f, Offset(5.29f, 13.72f), style = Fill)
            drawCircle(c, 1.5f, Offset(18.71f, 13.72f), style = Fill)
        }

        Ic.OIV -> {
            outline(path { arcTo(Rect(4f, 4f, 20f, 20f), 110f, 320f, true) })
            outline(path { moveTo(12f, 11f); lineTo(12f, 22f) })
            drawCircle(c, 1.2f, Offset(12f, 7.6f), style = Fill)
        }

        Ic.UP -> {
            outline(path { moveTo(12f, 19f); lineTo(12f, 6f) })
            outline(path { moveTo(6.5f, 11f); lineTo(12f, 5.5f); lineTo(17.5f, 11f) })
        }

        Ic.BACK -> outline(path { moveTo(15f, 5f); lineTo(8f, 12f); lineTo(15f, 19f) })
        Ic.CHEVRON -> outline(path { moveTo(9f, 5f); lineTo(16f, 12f); lineTo(9f, 19f) })

        Ic.MORE -> {
            drawCircle(c, 1.9f, Offset(12f, 5f), style = Fill)
            drawCircle(c, 1.9f, Offset(12f, 12f), style = Fill)
            drawCircle(c, 1.9f, Offset(12f, 19f), style = Fill)
        }

        Ic.CLOSE -> {
            outline(path { moveTo(6f, 6f); lineTo(18f, 18f) })
            outline(path { moveTo(18f, 6f); lineTo(6f, 18f) })
        }

        Ic.PLUS -> {
            outline(path { moveTo(12f, 5f); lineTo(12f, 19f) })
            outline(path { moveTo(5f, 12f); lineTo(19f, 12f) })
        }

        Ic.ARROW_R -> {
            outline(path { moveTo(5f, 12f); lineTo(19f, 12f) })
            outline(path { moveTo(13f, 6f); lineTo(19f, 12f); lineTo(13f, 18f) })
        }

        Ic.ARROW_L -> {
            outline(path { moveTo(19f, 12f); lineTo(5f, 12f) })
            outline(path { moveTo(11f, 6f); lineTo(5f, 12f); lineTo(11f, 18f) })
        }

        Ic.COPY -> {
            drawRoundRect(soft, Offset(9f, 9f), Size(11f, 11f), CornerRadius(2.2f))
            drawRoundRect(c, Offset(9f, 9f), Size(11f, 11f), CornerRadius(2.2f), style = st)
            outline(path {
                moveTo(15f, 9f); lineTo(15f, 6f); quadraticBezierTo(15f, 4f, 13f, 4f)
                lineTo(6f, 4f); quadraticBezierTo(4f, 4f, 4f, 6f); lineTo(4f, 13f)
                quadraticBezierTo(4f, 15f, 6f, 15f); lineTo(9f, 15f)
            })
        }

        Ic.CUT -> {
            drawCircle(c, 2.6f, Offset(7f, 17.5f), style = st)
            drawCircle(c, 2.6f, Offset(17f, 17.5f), style = st)
            outline(path { moveTo(8.8f, 15.5f); lineTo(17f, 4f) })
            outline(path { moveTo(15.2f, 15.5f); lineTo(7f, 4f) })
        }

        Ic.PASTE -> {
            drawRoundRect(soft, Offset(5f, 5f), Size(14f, 16f), CornerRadius(2.2f))
            drawRoundRect(c, Offset(5f, 5f), Size(14f, 16f), CornerRadius(2.2f), style = st)
            drawRoundRect(c, Offset(9f, 3f), Size(6f, 4f), CornerRadius(1.2f), style = st)
            outline(path { moveTo(8.5f, 12f); lineTo(15.5f, 12f) })
            outline(path { moveTo(8.5f, 16f); lineTo(13f, 16f) })
        }

        Ic.RENAME -> {
            outline(path {
                moveTo(4.5f, 19.5f); lineTo(5.5f, 15f); lineTo(16f, 4.5f)
                quadraticBezierTo(17f, 3.5f, 18f, 4.5f); lineTo(19.5f, 6f)
                quadraticBezierTo(20.5f, 7f, 19.5f, 8f); lineTo(9f, 18.5f); close()
            }, fill = true)
            outline(path { moveTo(14f, 6.5f); lineTo(17.5f, 10f) })
        }

        Ic.TARGET -> {
            drawCircle(c, 6.5f, Offset(12f, 12f), style = st)
            drawCircle(c, 1.8f, Offset(12f, 12f), style = Fill)
            outline(path { moveTo(12f, 2.5f); lineTo(12f, 6f) })
            outline(path { moveTo(12f, 18f); lineTo(12f, 21.5f) })
            outline(path { moveTo(2.5f, 12f); lineTo(6f, 12f) })
            outline(path { moveTo(18f, 12f); lineTo(21.5f, 12f) })
        }

        Ic.REFRESH -> {
            outline(path { arcTo(Rect(5f, 5f, 19f, 19f), 25f, 270f, true) })
            outline(path { moveTo(13.2f, 1.9f); lineTo(14.96f, 5.66f); lineTo(10.9f, 6.75f) })
        }

        Ic.CHECK -> outline(path { moveTo(4.5f, 12.5f); lineTo(9.5f, 17.5f); lineTo(19.5f, 6.5f) })

        // Bold V with an inner channel. Outline is left open on the right side and the
        // right inner line stops short, so the mark reads as unfinished.
        Ic.GTAV -> {
            outline(path {
                moveTo(15.75f, 15.9f); lineTo(13.5f, 21f); lineTo(10.5f, 21f); lineTo(3f, 4f)
                lineTo(9f, 4f); lineTo(12f, 14f); lineTo(15f, 4f); lineTo(21f, 4f); lineTo(17.6f, 11.65f)
            })
            outline(path { moveTo(6.5f, 6.5f); lineTo(11.1f, 17f) })
            outline(path { moveTo(17.5f, 6.5f); lineTo(15.4f, 11.3f) })
        }

        // Unfinished stick figure: head ring with a gap, and a right leg that breaks off.
        Ic.PERSON -> {
            outline(path { arcTo(Rect(9f, 2.5f, 15f, 8.5f), -35f, 295f, true) })
            outline(path { moveTo(12f, 9.5f); lineTo(12f, 15f) })
            outline(path { moveTo(5.5f, 12.5f); lineTo(12f, 10.5f); lineTo(18.5f, 12.5f) })
            outline(path { moveTo(12f, 15f); lineTo(7.5f, 21f) })
            outline(path { moveTo(12f, 15f); lineTo(14.2f, 18f) })
            outline(path { moveTo(15.6f, 19.9f); lineTo(16.2f, 20.8f) })
        }

        Ic.TRASH -> {
            outline(path { moveTo(4.5f, 7f); lineTo(19.5f, 7f) })
            outline(path { moveTo(9.5f, 7f); lineTo(9.5f, 4.5f); lineTo(14.5f, 4.5f); lineTo(14.5f, 7f) })
            outline(path {
                moveTo(6.5f, 7f); lineTo(7.5f, 19f); quadraticBezierTo(7.6f, 20.5f, 9f, 20.5f)
                lineTo(15f, 20.5f); quadraticBezierTo(16.4f, 20.5f, 16.5f, 19f); lineTo(17.5f, 7f)
            })
            outline(path { moveTo(10f, 11f); lineTo(10f, 16f) })
            outline(path { moveTo(14f, 11f); lineTo(14f, 16f) })
        }
    }
}
