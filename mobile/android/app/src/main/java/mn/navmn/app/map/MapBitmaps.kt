package mn.navmn.app.map

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import androidx.core.graphics.createBitmap
import mn.navmn.app.ui.theme.TokenColours

/** Puck chevron and pin bitmaps drawn from tokens (map-style §7.1, §7.4; own shapes, no third-party artwork). */
object MapBitmaps {
    /** 40 dp chevron with a 3 dp ring and a 1 dp outer hairline (nav.puck-*). */
    fun puck(density: Float, c: TokenColours, stale: Boolean): Bitmap {
        val size = (40 * density).toInt()
        val bmp = createBitmap(size, size)
        val canvas = Canvas(bmp)
        val s = size.toFloat()
        val path = Path().apply {
            moveTo(s * 0.5f, s * 0.08f)
            lineTo(s * 0.86f, s * 0.88f)
            lineTo(s * 0.5f, s * 0.70f)
            lineTo(s * 0.14f, s * 0.88f)
            close()
        }
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeJoin = Paint.Join.ROUND }
        p.style = Paint.Style.STROKE
        p.strokeWidth = 5 * density
        p.color = c.navPuckOutline.toInt()
        canvas.drawPath(path, p)
        p.strokeWidth = 3 * density
        p.color = (if (stale) c.navPuckStaleStroke else c.navPuckStroke).toInt()
        canvas.drawPath(path, p)
        p.style = Paint.Style.FILL
        p.color = (if (stale) c.navPuckStaleFill else c.navPuckFill).toInt()
        canvas.drawPath(path, p)
        return bmp
    }

    /** 28 × 40 dp teardrop pin (pin.*); [candidate] = outline variant (§7.3). */
    fun pin(density: Float, c: TokenColours, candidate: Boolean): Bitmap {
        val w = (28 * density).toInt()
        val h = (40 * density).toInt()
        val bmp = createBitmap(w, h)
        val canvas = Canvas(bmp)
        val sx = w / 28f
        val sy = h / 40f
        val body = Path().apply {
            moveTo(14 * sx, 39 * sy)
            cubicTo(14 * sx, 39 * sy, 2 * sx, 24.5f * sy, 2 * sx, 14 * sy)
            arcTo(2 * sx, 2 * sy, 26 * sx, 26 * sy, 180f, 180f, false)
            cubicTo(26 * sx, 24.5f * sy, 14 * sx, 39 * sy, 14 * sx, 39 * sy)
            close()
        }
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.style = Paint.Style.FILL
        p.color = (if (candidate) c.uiSurface else c.pinFill).toInt()
        canvas.drawPath(body, p)
        p.style = Paint.Style.STROKE
        p.strokeWidth = 2 * density
        p.color = (if (candidate) c.pinFill else c.pinStroke).toInt()
        canvas.drawPath(body, p)
        p.style = Paint.Style.FILL
        p.color = (if (candidate) c.pinFill else c.pinCenter).toInt()
        canvas.drawCircle(14 * sx, 14 * sy, 4.5f * density, p)
        return bmp
    }
}
