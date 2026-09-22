package br.mpi.fumaca

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.View
import kotlin.math.PI
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * Cena: céu ao entardecer, uma montanha distante (onde está o outro celular) e a
 * fogueira do jogador em primeiro plano. Tocar na fogueira chama [onFireTapped];
 * [showSmokeSignal] faz subir um sinal de fumaça no topo da montanha distante.
 */
class SceneView(context: Context) : View(context) {

    private companion object {
        /** Intervalo entre as nuvens de um sinal, para que fiquem separadas no céu. */
        const val PUFF_INTERVAL = 1.5f
    }

    var onFireTapped: (() -> Unit)? = null

    /** Linha de status no topo (quem sou eu / com quem estou conectado). */
    var header: String = ""
    private var message: String = ""
    private var messageUntil = 0f

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        setShadowLayer(6f, 0f, 2f, Color.argb(160, 0, 0, 0))
    }
    private val path = Path()

    private val startNs = System.nanoTime()
    private val now get() = (System.nanoTime() - startNs) / 1e9f

    private class Puff(val born: Float, val seed: Float)
    private class Spark(val born: Float, val vx: Float, val vy: Float, val life: Float)

    private val puffs = ArrayList<Puff>()
    private val sparks = ArrayList<Spark>()
    private var flareAt = -10f
    private var signalUntil = -10f

    private val stars = List(45) { floatArrayOf(Random.nextFloat(), Random.nextFloat() * 0.38f, Random.nextFloat() * 6f) }

    private var skyShader: Shader? = null
    private var glowShader: Shader? = null

    // Geometria (recalculada em onSizeChanged)
    private var w = 1f
    private var h = 1f
    private var fireX = 0f
    private var fireY = 0f
    private var peakX = 0f
    private var peakY = 0f

    init {
        contentDescription = "Montanha com céu e fogueira. Toque na fogueira para enviar um sinal de fumaça."
    }

    override fun onSizeChanged(nw: Int, nh: Int, ow: Int, oh: Int) {
        w = nw.toFloat()
        h = nh.toFloat()
        fireX = w * 0.5f
        fireY = h * 0.86f
        peakX = w * 0.32f
        peakY = h * 0.30f
        skyShader = LinearGradient(
            0f, 0f, 0f, h * 0.70f,
            intArrayOf(0xFF0B1D3A.toInt(), 0xFF2E3E7A.toInt(), 0xFF8E5A8C.toInt(), 0xFFF3A25A.toInt()),
            floatArrayOf(0f, 0.40f, 0.72f, 1f),
            Shader.TileMode.CLAMP,
        )
        glowShader = RadialGradient(
            fireX, fireY - w * 0.06f, w * 0.45f,
            intArrayOf(Color.argb(150, 255, 150, 60), Color.argb(0, 255, 120, 40)),
            null, Shader.TileMode.CLAMP,
        )
        text.textSize = w * 0.042f
    }

    /** Recebeu uma mensagem MPI: sobe um sinal de [count] nuvens de fumaça. */
    fun showSmokeSignal(count: Int) {
        val t = now
        val start = max(t, (puffs.maxOfOrNull { it.born } ?: t) + PUFF_INTERVAL)
        for (i in 0 until count.coerceIn(1, 8)) {
            puffs += Puff(start + i * PUFF_INTERVAL, Random.nextFloat() * 10f)
        }
        signalUntil = start + count * PUFF_INTERVAL + 1.5f
        showMessage("Sinal de fumaça recebido!", 5f)
    }

    /** Toque local: a fogueira aviva e solta faíscas. */
    fun flare() {
        val t = now
        flareAt = t
        repeat(26) {
            val a = (-PI / 2 + (Random.nextFloat() - 0.5f) * 1.2f).toFloat()
            val speed = w * (0.25f + Random.nextFloat() * 0.35f)
            sparks += Spark(t, kotlin.math.cos(a) * speed, sin(a) * speed, 0.8f + Random.nextFloat() * 0.8f)
        }
    }

    fun showMessage(msg: String, seconds: Float = 3f) {
        message = msg
        messageUntil = now + seconds
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_DOWN) {
            val d = hypot(event.x - fireX, event.y - (fireY - w * 0.08f))
            if (d < w * 0.22f) {
                onFireTapped?.invoke()
                performClick()
            }
            return true
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    override fun onDraw(canvas: Canvas) {
        val t = now
        drawSky(canvas, t)
        drawMountains(canvas, t)
        drawSmoke(canvas, t)
        drawForeground(canvas)
        drawFire(canvas, t)
        drawSparks(canvas, t)
        drawTexts(canvas, t)
        postInvalidateOnAnimation()
    }

    private fun drawSky(c: Canvas, t: Float) {
        fill.shader = skyShader
        c.drawRect(0f, 0f, w, h, fill)
        fill.shader = null
        for (s in stars) {
            val a = (140 + 110 * sin(t * 1.3f + s[2])).toInt().coerceIn(0, 255)
            fill.color = Color.argb(a, 255, 255, 240)
            c.drawCircle(s[0] * w, s[1] * h, w * 0.0035f + (s[2] % 1f) * w * 0.002f, fill)
        }
        // Sol se pondo atrás das montanhas
        fill.color = Color.argb(70, 255, 210, 120)
        c.drawCircle(w * 0.70f, h * 0.49f, w * 0.13f, fill)
        fill.color = 0xFFFFD27A.toInt()
        c.drawCircle(w * 0.70f, h * 0.49f, w * 0.085f, fill)
    }

    private fun drawMountains(c: Canvas, t: Float) {
        // Cordilheira ao fundo
        fill.color = 0xFF6B5E8E.toInt()
        polygon(c, -0.05f to 0.66f, 0.10f to 0.52f, 0.22f to 0.58f, 0.48f to 0.45f,
            0.62f to 0.53f, 0.80f to 0.44f, 0.95f to 0.52f, 1.05f to 0.48f, 1.05f to 0.72f, -0.05f to 0.72f)

        // Montanha principal: é onde está o outro celular
        fill.color = 0xFF3E3A66.toInt()
        polygon(c, -0.15f to 0.72f, 0.12f to 0.44f, 0.20f to 0.39f, 0.32f to 0.30f,
            0.44f to 0.40f, 0.56f to 0.46f, 0.82f to 0.72f)
        // Face iluminada pelo sol
        fill.color = 0xFF534B7A.toInt()
        polygon(c, 0.32f to 0.30f, 0.44f to 0.40f, 0.56f to 0.46f, 0.82f to 0.72f, 0.46f to 0.72f, 0.40f to 0.50f)
        // Neve no pico
        fill.color = 0xFFEDEBF5.toInt()
        polygon(c, 0.32f to 0.30f, 0.365f to 0.335f, 0.35f to 0.35f, 0.335f to 0.34f,
            0.315f to 0.36f, 0.295f to 0.345f, 0.27f to 0.355f)

        // Pequena fogueira no pico, visível enquanto o sinal está subindo
        if (t < signalUntil) {
            val flick = 0.8f + 0.2f * sin(t * 25f)
            fill.color = Color.argb((200 * flick).toInt(), 255, 150, 50)
            c.drawCircle(peakX, peakY, w * 0.012f * flick, fill)
            fill.color = Color.argb(60, 255, 150, 50)
            c.drawCircle(peakX, peakY, w * 0.035f, fill)
        }

        // Colinas intermediárias
        fill.color = 0xFF2A3A4A.toInt()
        path.reset()
        path.moveTo(0f, h * 0.74f)
        path.cubicTo(w * 0.25f, h * 0.66f, w * 0.55f, h * 0.73f, w * 0.75f, h * 0.68f)
        path.cubicTo(w * 0.88f, h * 0.65f, w * 0.96f, h * 0.69f, w, h * 0.70f)
        path.lineTo(w, h)
        path.lineTo(0f, h)
        path.close()
        c.drawPath(path, fill)
    }

    private fun drawSmoke(c: Canvas, t: Float) {
        val life = 6.5f
        puffs.removeAll { t - it.born > life }
        for (p in puffs) {
            val age = t - p.born
            if (age < 0f) continue
            val k = age / life
            val rise = age * h * 0.05f
            val x = peakX + sin(age * 0.9f + p.seed) * w * 0.012f + age * w * 0.012f
            val y = peakY - w * 0.03f - rise
            val r = w * (0.03f + 0.03f * min(1f, age / 3f))
            val alpha = (min(1f, age / 0.4f) * (1f - k * k) * 235).toInt().coerceIn(0, 255)
            // Cada sinal é uma "bola" de fumaça formada por vários círculos
            fill.color = Color.argb(alpha / 3, 120, 110, 120)
            c.drawCircle(x + r * 0.15f, y + r * 0.2f, r * 1.05f, fill)
            fill.color = Color.argb(alpha, 232, 228, 222)
            for (i in 0 until 6) {
                val a = i / 6f * 2f * PI.toFloat() + p.seed
                val o = r * 0.45f
                c.drawCircle(x + kotlin.math.cos(a) * o, y + sin(a) * o * 0.7f, r * 0.62f, fill)
            }
            c.drawCircle(x, y, r * 0.7f, fill)
        }
    }

    private fun drawForeground(c: Canvas) {
        fill.color = 0xFF1B2A22.toInt()
        path.reset()
        path.moveTo(0f, h * 0.80f)
        path.cubicTo(w * 0.30f, h * 0.76f, w * 0.70f, h * 0.76f, w, h * 0.81f)
        path.lineTo(w, h)
        path.lineTo(0f, h)
        path.close()
        c.drawPath(path, fill)

        // Pinheiros
        fill.color = 0xFF10201A.toInt()
        for ((px, s) in listOf(0.08f to 1.0f, 0.16f to 0.75f, 0.86f to 1.1f, 0.94f to 0.8f)) {
            val base = h * 0.815f
            val th = w * 0.20f * s
            polygonAbs(c, px * w to base - th, px * w + th * 0.28f to base, px * w - th * 0.28f to base)
        }
    }

    private fun drawFire(c: Canvas, t: Float) {
        val boost = 1f + 0.6f * max(0f, 1f - (t - flareAt) / 1.2f)

        fill.shader = glowShader
        c.save()
        c.scale(boost, boost, fireX, fireY)
        c.drawCircle(fireX, fireY - w * 0.06f, w * 0.45f, fill)
        c.restore()
        fill.shader = null

        // Pedras
        fill.color = 0xFF5E5A57.toInt()
        for (i in 0 until 7) {
            val a = PI.toFloat() * (i / 6f)
            val x = fireX + kotlin.math.cos(a) * w * 0.14f
            val y = fireY + w * 0.02f + sin(a) * w * 0.025f
            c.drawOval(RectF(x - w * 0.035f, y - w * 0.022f, x + w * 0.035f, y + w * 0.022f), fill)
        }
        // Lenha
        fill.color = 0xFF6B3E1E.toInt()
        for (angle in listOf(-18f, 18f)) {
            c.save()
            c.rotate(angle, fireX, fireY)
            c.drawRoundRect(RectF(fireX - w * 0.13f, fireY - w * 0.02f, fireX + w * 0.13f, fireY + w * 0.02f), w * 0.02f, w * 0.02f, fill)
            c.restore()
        }

        // Chamas: três camadas que tremulam
        val layers = listOf(
            Triple(0xFFE8411C.toInt(), 1.00f, 0f),
            Triple(0xFFFF8A1F.toInt(), 0.72f, 1.7f),
            Triple(0xFFFFE066.toInt(), 0.42f, 3.1f),
        )
        for ((color, scale, phase) in layers) {
            val fw = w * 0.11f * scale * (0.9f + 0.1f * boost)
            val fh = w * 0.26f * scale * boost * (0.92f + 0.08f * sin(t * 11f + phase))
            val sway = sin(t * 6f + phase) * fw * 0.25f
            val base = fireY - w * 0.005f
            path.reset()
            path.moveTo(fireX - fw, base)
            path.cubicTo(fireX - fw * 1.1f, base - fh * 0.45f, fireX - fw * 0.2f + sway, base - fh * 0.7f, fireX + sway * 1.6f, base - fh)
            path.cubicTo(fireX + fw * 0.25f + sway, base - fh * 0.65f, fireX + fw * 1.1f, base - fh * 0.45f, fireX + fw, base)
            path.close()
            fill.color = color
            c.drawPath(path, fill)
        }
    }

    private fun drawSparks(c: Canvas, t: Float) {
        sparks.removeAll { t - it.born > it.life }
        for (s in sparks) {
            val a = t - s.born
            val x = fireX + s.vx * a
            val y = fireY - w * 0.1f + s.vy * a + 0.5f * w * 0.4f * a * a
            fill.color = Color.argb(((1f - a / s.life) * 255).toInt().coerceIn(0, 255), 255, 200, 90)
            c.drawCircle(x, y, w * 0.006f, fill)
        }
    }

    private fun drawTexts(c: Canvas, t: Float) {
        text.typeface = Typeface.DEFAULT
        text.textSize = w * 0.036f
        text.color = Color.argb(220, 255, 255, 255)
        c.drawText(header, w / 2, h * 0.055f, text)

        if (t < messageUntil) {
            val a = min(1f, (messageUntil - t) / 0.5f)
            text.typeface = Typeface.DEFAULT_BOLD
            text.textSize = w * 0.055f
            text.color = Color.argb((255 * a).toInt(), 255, 236, 200)
            c.drawText(message, w / 2, h * 0.12f, text)
        }

        text.typeface = Typeface.DEFAULT
        text.textSize = w * 0.038f
        text.color = Color.argb(200, 255, 230, 200)
        c.drawText("Toque na fogueira para enviar um sinal", w / 2, h * 0.965f, text)
    }

    private fun polygon(c: Canvas, vararg pts: Pair<Float, Float>) =
        polygonAbs(c, *pts.map { it.first * w to it.second * h }.toTypedArray())

    private fun polygonAbs(c: Canvas, vararg pts: Pair<Float, Float>) {
        path.reset()
        path.moveTo(pts[0].first, pts[0].second)
        for (i in 1 until pts.size) path.lineTo(pts[i].first, pts[i].second)
        path.close()
        c.drawPath(path, fill)
    }
}
