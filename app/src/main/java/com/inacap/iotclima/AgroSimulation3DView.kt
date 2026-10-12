package com.inacap.iotclima

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import kotlin.math.*
import kotlin.random.Random

/**
 * Vista personalizada interactiva en 3D / Isométrica desarrollada para vamp9 IoT.
 * Simula físicamente:
 * 1. Compuerta de ventilación motorizada (Servo SG90) con rotación tridimensional en perspectiva.
 * 2. Sistema de riego por aspersión (Bomba / Relé) con partículas cinéticas de agua y ondas en el suelo.
 * 3. Transición dinámica e inspección 3D al activar el Modo Manual.
 */
class AgroSimulation3DView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // Estados del sistema
    var isManualMode: Boolean = false
        private set
    var isVentilationOpen: Boolean = false
        private set
    var isPumpActive: Boolean = false
        private set

    var exactTemperature: Double = 0.0
        private set
    var exactHumidity: Double = 0.0
        private set

    // Ángulos de rotación y cámara 3D
    private var cameraRotX: Float = 22f      // Inclinación inicial
    private var cameraRotY: Float = -15f     // Ángulo oblicuo inicial
    private var baseRotY: Float = -15f
    private var targetRotY: Float = -15f

    // Estado animado de la compuerta (0.0 = cerrada, 1.0 = completamente abierta a ~85 grados)
    private var gateOpenProgress: Float = 0f
    private var gateAnimator: ValueAnimator? = null

    // Animación de pulso / modo manual
    private var manualScanProgress: Float = 0f
    private var manualScanAnimator: ValueAnimator? = null

    // Partículas de agua para la bomba de riego
    private class WaterDroplet(
        var x: Float,
        var y: Float,
        var vx: Float,
        var vy: Float,
        var alpha: Int,
        var maxLife: Float,
        var currentLife: Float
    )
    private val droplets = mutableListOf<WaterDroplet>()
    private val random = Random(System.currentTimeMillis())

    // Partículas de brisa / flujo de aire para la ventilación
    private class AirParticle(
        var x: Float,
        var y: Float,
        var length: Float,
        var speed: Float,
        var alpha: Int
    )
    private val airParticles = mutableListOf<AirParticle>()

    // Ondas en el suelo húmedo
    private class SoilRipple(
        var x: Float,
        var y: Float,
        var radius: Float,
        var maxRadius: Float,
        var alpha: Int
    )
    private val ripples = mutableListOf<SoilRipple>()

    // Herramientas de dibujo
    private val camera3D = Camera()
    private val matrix3D = Matrix()

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#E2E8F0")
        strokeWidth = 2f
        style = Paint.Style.STROKE
    }
    private val platformPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#F1F5F9")
        style = Paint.Style.FILL
    }
    private val platformBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#CBD5E1")
        strokeWidth = 3f
        style = Paint.Style.STROKE
    }
    private val soilPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#D97706")
        style = Paint.Style.FILL
    }
    private val soilWetPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#92400E")
        style = Paint.Style.FILL
    }
    private val plantStemPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#059669")
        strokeWidth = 4f
        strokeCap = Paint.Cap.ROUND
        style = Paint.Style.STROKE
    }
    private val plantLeafPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#10B981")
        style = Paint.Style.FILL
    }
    private val framePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#475569")
        strokeWidth = 5f
        strokeCap = Paint.Cap.ROUND
        style = Paint.Style.STROKE
    }
    private val gatePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#0284C7")
        style = Paint.Style.FILL
    }
    private val gateBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#0369A1")
        strokeWidth = 3f
        style = Paint.Style.STROKE
    }
    private val pipePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#64748B")
        strokeWidth = 6f
        strokeCap = Paint.Cap.ROUND
        style = Paint.Style.STROKE
    }
    private val waterPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#38BDF8")
        strokeWidth = 3f
        strokeCap = Paint.Cap.ROUND
        style = Paint.Style.STROKE
    }
    private val airPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#60A5FA")
        strokeWidth = 2.5f
        strokeCap = Paint.Cap.ROUND
        style = Paint.Style.STROKE
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#0F172A")
        textSize = 28f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val badgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val badgeBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }
    private val badgeTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 22f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }

    // Touch interaction para rotación manual ligera
    private var lastTouchX = 0f
    private var isDragging = false

    init {
        // Inicializar partículas de brisa
        for (i in 0..12) {
            airParticles.add(
                AirParticle(
                    x = random.nextFloat() * 200f - 100f,
                    y = random.nextFloat() * 100f - 50f,
                    length = 20f + random.nextFloat() * 30f,
                    speed = 3f + random.nextFloat() * 4f,
                    alpha = 150 + random.nextInt(100)
                )
            )
        }
    }

    /**
     * Notifica el cambio de modo manual e inicia la animación de inspección 3D.
     */
    fun setManualMode(enabled: Boolean) {
        if (this.isManualMode == enabled) return
        this.isManualMode = enabled

        // Animación suave de la cámara al entrar en modo manual
        val startRot = cameraRotY
        val targetRot = if (enabled) 20f else -15f
        baseRotY = targetRot

        val anim = ValueAnimator.ofFloat(startRot, targetRot)
        anim.duration = 750
        anim.interpolator = DecelerateInterpolator()
        anim.addUpdateListener { va ->
            cameraRotY = va.animatedValue as Float
            invalidate()
        }
        anim.start()

        // Pulso de escaneo 3D
        if (enabled) {
            manualScanAnimator?.cancel()
            manualScanAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 1200
                interpolator = DecelerateInterpolator()
                addUpdateListener { va ->
                    manualScanProgress = va.animatedValue as Float
                    invalidate()
                }
                start()
            }
        }

        invalidate()
    }

    /**
     * Controla la apertura física del servo SG90 con animación suave.
     */
    fun setVentilation(open: Boolean) {
        if (this.isVentilationOpen == open) return
        this.isVentilationOpen = open

        gateAnimator?.cancel()
        val target = if (open) 1f else 0f
        gateAnimator = ValueAnimator.ofFloat(gateOpenProgress, target).apply {
            duration = 650
            interpolator = DecelerateInterpolator()
            addUpdateListener { va ->
                gateOpenProgress = va.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    /**
     * Activa o detiene la simulación de bomba de riego y partículas de agua.
     */
    fun setPump(active: Boolean) {
        this.isPumpActive = active
        if (!active) {
            droplets.clear()
            ripples.clear()
        }
        invalidate()
    }

    /**
     * Actualiza la telemetría exacta en la vista para reflejarla en los badges y suelo.
     */
    fun updateTelemetry(temperature: Double, humidity: Double) {
        this.exactTemperature = temperature
        this.exactHumidity = humidity
        invalidate()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastTouchX = event.x
                isDragging = true
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (isDragging) {
                    val dx = event.x - lastTouchX
                    lastTouchX = event.x
                    // Limitar rotación interactiva para mantener perspectiva cómoda
                    cameraRotY = (cameraRotY + dx * 0.25f).coerceIn(-35f, 35f)
                    invalidate()
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                isDragging = false
                // Regresar suavemente al ángulo base
                val anim = ValueAnimator.ofFloat(cameraRotY, baseRotY).apply {
                    duration = 400
                    interpolator = DecelerateInterpolator()
                    addUpdateListener { va ->
                        cameraRotY = va.animatedValue as Float
                        invalidate()
                    }
                }
                anim.start()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return

        // 1. Fondo suave con gradiente moderno claro
        val bgShader = LinearGradient(
            0f, 0f, 0f, h,
            intArrayOf(Color.parseColor("#F8FAFC"), Color.parseColor("#EFF6FF")),
            null,
            Shader.TileMode.CLAMP
        )
        bgPaint.shader = bgShader
        canvas.drawRoundRect(0f, 0f, w, h, 32f, 32f, bgPaint)

        // 2. Proyección y transformación 3D centrada
        canvas.save()
        val centerX = w * 0.5f
        val centerY = h * 0.52f

        camera3D.save()
        camera3D.rotateX(cameraRotX)
        camera3D.rotateY(cameraRotY)
        camera3D.getMatrix(matrix3D)
        camera3D.restore()

        matrix3D.preTranslate(-centerX, -centerY)
        matrix3D.postTranslate(centerX, centerY)
        canvas.concat(matrix3D)

        // Dibujar componentes de la escena 3D
        drawPodiumAndGrid(canvas, centerX, centerY)
        drawCropsAndSoil(canvas, centerX, centerY)
        drawVentilationServo(canvas, centerX, centerY)
        drawIrrigationSystem(canvas, centerX, centerY)

        canvas.restore()

        // 3. HUD Overlay 2D (Badges de estado en tiempo real, etiquetas limpias)
        drawHudOverlay(canvas, w, h)

        // Actualizar bucle de animación continua si hay actuadores activos
        var needRedraw = false

        if (isPumpActive) {
            updateDropletsAndRipples(centerX, centerY)
            needRedraw = true
        }

        if (gateOpenProgress > 0.05f) {
            updateAirParticles()
            needRedraw = true
        }

        if (isManualMode && manualScanProgress < 1f) {
            needRedraw = true
        }

        if (needRedraw) {
            postInvalidateOnAnimation()
        }
    }

    /**
     * Dibuja el podio base y cuadrícula tecnológica isométrica
     */
    private fun drawPodiumAndGrid(canvas: Canvas, cx: Float, cy: Float) {
        val pw = 280f
        val ph = 180f
        val depth = 28f

        // Sombra suave inferior
        val shadowPath = Path().apply {
            moveTo(cx - pw, cy)
            lineTo(cx, cy - ph * 0.6f)
            lineTo(cx + pw, cy)
            lineTo(cx, cy + ph * 0.6f + depth)
            close()
        }
        val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#15000000")
            maskFilter = BlurMaskFilter(16f, BlurMaskFilter.Blur.NORMAL)
        }
        canvas.drawPath(shadowPath, shadowPaint)

        // Borde lateral del podio (efecto volumen 3D)
        val edgePath = Path().apply {
            moveTo(cx - pw, cy)
            lineTo(cx, cy + ph * 0.6f)
            lineTo(cx + pw, cy)
            lineTo(cx + pw, cy + depth)
            lineTo(cx, cy + ph * 0.6f + depth)
            lineTo(cx - pw, cy + depth)
            close()
        }
        val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#E2E8F0")
            style = Paint.Style.FILL
        }
        canvas.drawPath(edgePath, edgePaint)

        // Superficie superior del podio
        val topPath = Path().apply {
            moveTo(cx - pw, cy)
            lineTo(cx, cy - ph * 0.6f)
            lineTo(cx + pw, cy)
            lineTo(cx, cy + ph * 0.6f)
            close()
        }
        canvas.drawPath(topPath, platformPaint)
        canvas.drawPath(topPath, platformBorderPaint)

        // Cuadrícula tecnológica
        val steps = 4
        for (i in 1 until steps) {
            val t = i.toFloat() / steps
            // Líneas oblicuas 1
            val x1 = cx - pw * (1 - t)
            val y1 = cy - (ph * 0.6f) * t
            val x2 = cx + pw * t
            val y2 = cy + (ph * 0.6f) * (1 - t)
            canvas.drawLine(x1, y1, x2, y2, gridPaint)

            // Líneas oblicuas 2
            val x3 = cx - pw * t
            val y3 = cy + (ph * 0.6f) * (1 - t)
            val x4 = cx + pw * (1 - t)
            val y4 = cy - (ph * 0.6f) * t
            canvas.drawLine(x3, y3, x4, y4, gridPaint)
        }

        // Si el modo manual está activado, dibujar anillo de escaneo
        if (isManualMode && manualScanProgress in 0.01f..0.99f) {
            val scanPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#7C3AED")
                strokeWidth = 3f
                style = Paint.Style.STROKE
                alpha = ((1f - manualScanProgress) * 200).toInt()
            }
            val scanR = pw * manualScanProgress
            canvas.drawOval(cx - scanR, cy - (ph * 0.6f * manualScanProgress), cx + scanR, cy + (ph * 0.6f * manualScanProgress), scanPaint)
        }
    }

    /**
     * Dibuja el suelo de cultivo agrícola y las plantas
     */
    private fun drawCropsAndSoil(canvas: Canvas, cx: Float, cy: Float) {
        val sw = 180f
        val sh = 100f

        // Cama de siembra
        val soilPath = Path().apply {
            moveTo(cx - sw, cy)
            lineTo(cx, cy - sh * 0.6f)
            lineTo(cx + sw, cy)
            lineTo(cx, cy + sh * 0.6f)
            close()
        }

        // Tono del suelo cambia si la bomba está regando o si la humedad es alta
        val isMoist = isPumpActive || exactHumidity > 60.0
        val currentSoilPaint = if (isMoist) soilWetPaint else soilPaint
        canvas.drawPath(soilPath, currentSoilPaint)

        // Ondas de agua en el suelo cuando la bomba está activa
        if (isPumpActive && ripples.isNotEmpty()) {
            val rPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#38BDF8")
                style = Paint.Style.STROKE
                strokeWidth = 2f
            }
            for (rip in ripples) {
                rPaint.alpha = rip.alpha
                canvas.drawOval(
                    rip.x - rip.radius, rip.y - rip.radius * 0.4f,
                    rip.x + rip.radius, rip.y + rip.radius * 0.4f,
                    rPaint
                )
            }
        }

        // Dibujar pequeños brotes de cultivo (plantines) en perspectiva
        val plantPositions = listOf(
            Pair(cx - 70f, cy - 10f),
            Pair(cx - 20f, cy + 20f),
            Pair(cx + 40f, cy - 20f),
            Pair(cx + 80f, cy + 10f),
            Pair(cx + 10f, cy - 35f)
        )

        for ((px, py) in plantPositions) {
            // Tallo
            canvas.drawLine(px, py, px, py - 26f, plantStemPaint)
            // Hoja izquierda
            val leafLeft = Path().apply {
                moveTo(px, py - 18f)
                quadTo(px - 14f, py - 24f, px - 12f, py - 16f)
                quadTo(px - 4f, py - 14f, px, py - 18f)
                close()
            }
            canvas.drawPath(leafLeft, plantLeafPaint)
            // Hoja derecha
            val leafRight = Path().apply {
                moveTo(px, py - 22f)
                quadTo(px + 14f, py - 28f, px + 12f, py - 20f)
                quadTo(px + 4f, py - 18f, px, py - 22f)
                close()
            }
            canvas.drawPath(leafRight, plantLeafPaint)
        }
    }

    /**
     * Dibuja la compuerta de ventilación 3D impulsada por el Servo SG90
     */
    private fun drawVentilationServo(canvas: Canvas, cx: Float, cy: Float) {
        // Estructura de soporte de la compuerta en la parte superior izquierda
        val gateX = cx - 110f
        val gateY = cy - 130f
        val gateWidth = 110f
        val gateHeight = 70f

        // Marco exterior
        canvas.drawRect(gateX, gateY, gateX + gateWidth, gateY + gateHeight, framePaint)

        // Servomotor SG90 (bloque azul de micro-servo en la esquina superior)
        val servoPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#2563EB")
            style = Paint.Style.FILL
        }
        canvas.drawRoundRect(gateX - 22f, gateY + 10f, gateX + 2f, gateY + 44f, 6f, 6f, servoPaint)
        // Eje de rotor blanco
        val servoGearPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.FILL
        }
        canvas.drawCircle(gateX - 8f, gateY + 27f, 6f, servoGearPaint)

        // Compuerta giratoria con rotación 3D
        // gateOpenProgress va de 0f (cerrada plana) a 1f (abierta a ~80°)
        val angleDeg = gateOpenProgress * 80f
        val flapPath = Path()

        // Calcular deformación en perspectiva de la compuerta que pivota en la bisagra superior
        val topY = gateY + 4f
        val bottomY = gateY + gateHeight - 4f
        val lift = (bottomY - topY) * (1f - cos(Math.toRadians(angleDeg.toDouble()))).toFloat()
        val tiltOffset = sin(Math.toRadians(angleDeg.toDouble())).toFloat() * 35f

        val leftX = gateX + 6f
        val rightX = gateX + gateWidth - 6f

        flapPath.moveTo(leftX, topY)
        flapPath.lineTo(rightX, topY)
        flapPath.lineTo(rightX + tiltOffset, bottomY - lift)
        flapPath.lineTo(leftX + tiltOffset, bottomY - lift)
        flapPath.close()

        // Color de la compuerta con sombreado dinámico según el ángulo
        gatePaint.color = if (isVentilationOpen) Color.parseColor("#38BDF8") else Color.parseColor("#64748B")
        gatePaint.alpha = 220
        canvas.drawPath(flapPath, gatePaint)
        canvas.drawPath(flapPath, gateBorderPaint)

        // Líneas de ventilación / lamas
        val steps = 3
        for (i in 1..steps) {
            val t = i.toFloat() / (steps + 1)
            val currentY = topY + (bottomY - lift - topY) * t
            val curLeftX = leftX + tiltOffset * t
            val curRightX = rightX + tiltOffset * t
            canvas.drawLine(curLeftX, currentY, curRightX, currentY, gateBorderPaint)
        }

        // Si la compuerta está abierta, dibujar flujo de aire saliendo
        if (gateOpenProgress > 0.1f) {
            for (p in airParticles) {
                airPaint.alpha = (p.alpha * gateOpenProgress).toInt()
                canvas.drawLine(
                    gateX + gateWidth * 0.5f + p.x,
                    gateY + gateHeight * 0.5f + p.y,
                    gateX + gateWidth * 0.5f + p.x + p.length,
                    gateY + gateHeight * 0.5f + p.y - 8f,
                    airPaint
                )
            }
        }
    }

    /**
     * Dibuja el sistema de riego (tubería, toberas de aspersión y partículas de agua)
     */
    private fun drawIrrigationSystem(canvas: Canvas, cx: Float, cy: Float) {
        val pipeStartY = cy - 80f
        val pipeStartX = cx - 90f
        val pipeEndX = cx + 110f

        // Tubería principal superior
        canvas.drawLine(pipeStartX, pipeStartY, pipeEndX, pipeStartY, pipePaint)

        // Toberas de aspersión (3 aspersores)
        val nozzles = listOf(
            Pair(cx - 50f, pipeStartY),
            Pair(cx + 10f, pipeStartY),
            Pair(cx + 70f, pipeStartY)
        )

        val nozzlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#1E293B")
            style = Paint.Style.FILL
        }

        for ((nx, ny) in nozzles) {
            canvas.drawRect(nx - 4f, ny, nx + 4f, ny + 10f, nozzlePaint)

            // Si la bomba está activa, dibujar aspersión de agua
            if (isPumpActive) {
                // Rociador de agua cónico
                val sprayPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.parseColor("#7DD3FC")
                    style = Paint.Style.STROKE
                    strokeWidth = 1.5f
                    alpha = 130
                }
                for (a in -2..2) {
                    val angleRad = Math.toRadians((a * 15.0) + 90.0)
                    val ex = nx + (cos(angleRad) * 45f).toFloat()
                    val ey = ny + 10f + (sin(angleRad) * 45f).toFloat()
                    canvas.drawLine(nx, ny + 10f, ex, ey, sprayPaint)
                }
            }
        }

        // Gotas de agua cayendo con física
        if (isPumpActive && droplets.isNotEmpty()) {
            for (d in droplets) {
                waterPaint.alpha = d.alpha
                canvas.drawCircle(d.x, d.y, 3.5f, waterPaint)
            }
        }
    }

    /**
     * Actualiza la simulación física de gotas de agua y ondas en el suelo
     */
    private fun updateDropletsAndRipples(cx: Float, cy: Float) {
        val pipeStartY = cy - 80f
        val nozzles = listOf(cx - 50f, cx + 10f, cx + 70f)

        // Generar nuevas gotas
        if (droplets.size < 40 && random.nextFloat() < 0.6f) {
            val nx = nozzles[random.nextInt(nozzles.size)]
            val vx = (random.nextFloat() - 0.5f) * 3f
            val vy = 3f + random.nextFloat() * 4f
            droplets.add(
                WaterDroplet(
                    x = nx,
                    y = pipeStartY + 10f,
                    vx = vx,
                    vy = vy,
                    alpha = 230,
                    maxLife = 35f,
                    currentLife = 0f
                )
            )
        }

        // Mover gotas
        val it = droplets.iterator()
        while (it.hasNext()) {
            val d = it.next()
            d.x += d.vx
            d.y += d.vy
            d.vy += 0.35f // Gravedad
            d.currentLife += 1f

            // Impacto en el suelo
            if (d.y >= cy + 10f || d.currentLife >= d.maxLife) {
                // Crear onda de impacto
                if (ripples.size < 15) {
                    ripples.add(
                        SoilRipple(
                            x = d.x,
                            y = d.y,
                            radius = 4f,
                            maxRadius = 24f,
                            alpha = 180
                        )
                    )
                }
                it.remove()
            }
        }

        // Actualizar ondas
        val rit = ripples.iterator()
        while (rit.hasNext()) {
            val r = rit.next()
            r.radius += 1.2f
            r.alpha = ((1f - (r.radius / r.maxRadius)) * 180).toInt().coerceAtLeast(0)
            if (r.radius >= r.maxRadius || r.alpha <= 0) {
                rit.remove()
            }
        }
    }

    /**
     * Actualiza la posición de las partículas de brisa
     */
    private fun updateAirParticles() {
        for (p in airParticles) {
            p.x += p.speed
            if (p.x > 140f) {
                p.x = -80f
                p.y = (random.nextFloat() * 80f) - 40f
            }
        }
    }

    /**
     * Dibuja el HUD 2D superior con los badges de estado para Servo, Bomba y Modo
     */
    private fun drawHudOverlay(canvas: Canvas, w: Float, h: Float) {
        val pad = 24f

        // 1. Badge superior izquierdo: MODO ACTUAL
        val modeText = if (isManualMode) "MODO MANUAL ACTIVO" else "MODO AUTOMÁTICO (IOT)"
        val modeColor = if (isManualMode) Color.parseColor("#7C3AED") else Color.parseColor("#059669")
        val modeBg = if (isManualMode) Color.parseColor("#F5F3FF") else Color.parseColor("#ECFDF5")

        badgePaint.color = modeBg
        badgeBorderPaint.color = modeColor
        val badgeW = badgeTextPaint.measureText(modeText) + 36f
        val badgeRect = RectF(pad, pad, pad + badgeW, pad + 38f)
        canvas.drawRoundRect(badgeRect, 19f, 19f, badgePaint)
        canvas.drawRoundRect(badgeRect, 19f, 19f, badgeBorderPaint)

        badgeTextPaint.color = modeColor
        canvas.drawText(modeText, pad + 18f, pad + 27f, badgeTextPaint)

        // 2. Indicador 3D / Perspectiva badge
        val hintText = "VISTA 3D • ARRASTRA PARA INSPECCIONAR"
        val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#64748B")
            textSize = 20f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val hintW = hintPaint.measureText(hintText)
        canvas.drawText(hintText, w - pad - hintW, pad + 27f, hintPaint)

        // 3. Badges inferiores en tarjeta (Estado físico de los 2 actuadores)
        val bottomY = h - pad - 36f

        // Badge Servo
        val servoLabel = if (isVentilationOpen) "SERVO SG90: 90° (VENTILACIÓN ON)" else "SERVO SG90: 0° (CERRADA)"
        val servoColor = if (isVentilationOpen) Color.parseColor("#0284C7") else Color.parseColor("#64748B")
        val servoBg = if (isVentilationOpen) Color.parseColor("#F0F9FF") else Color.parseColor("#F8FAFC")

        badgePaint.color = servoBg
        badgeBorderPaint.color = servoColor
        val servoW = badgeTextPaint.measureText(servoLabel) + 30f
        val servoRect = RectF(pad, bottomY, pad + servoW, bottomY + 36f)
        canvas.drawRoundRect(servoRect, 18f, 18f, badgePaint)
        canvas.drawRoundRect(servoRect, 18f, 18f, badgeBorderPaint)
        badgeTextPaint.color = servoColor
        canvas.drawText(servoLabel, pad + 15f, bottomY + 25f, badgeTextPaint)

        // Badge Bomba
        val pumpLabel = if (isPumpActive) "BOMBA: REGANDO (FLUJO ACTIVO)" else "BOMBA: EN ESPERA"
        val pumpColor = if (isPumpActive) Color.parseColor("#10B981") else Color.parseColor("#64748B")
        val pumpBg = if (isPumpActive) Color.parseColor("#ECFDF5") else Color.parseColor("#F8FAFC")

        badgePaint.color = pumpBg
        badgeBorderPaint.color = pumpColor
        val pumpW = badgeTextPaint.measureText(pumpLabel) + 30f
        val pumpRect = RectF(w - pad - pumpW, bottomY, w - pad, bottomY + 36f)
        canvas.drawRoundRect(pumpRect, 18f, 18f, badgePaint)
        canvas.drawRoundRect(pumpRect, 18f, 18f, badgeBorderPaint)
        badgeTextPaint.color = pumpColor
        canvas.drawText(pumpLabel, w - pad - pumpW + 15f, bottomY + 25f, badgeTextPaint)
    }
}
