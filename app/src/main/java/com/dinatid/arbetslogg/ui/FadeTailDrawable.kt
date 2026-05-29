package com.dinatid.arbetslogg.ui

import android.graphics.*
import android.graphics.drawable.Drawable

class FadeTailDrawable(private val snakeColor: Int = Color.parseColor("#B72B08")) : Drawable() {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        // FIX: Ormen är fortfarande tjock och grov (32f)
        strokeWidth = 32f
        // FIX: Säkrar att nosen (och svansen) får snyggt runda hörn
        strokeCap = Paint.Cap.ROUND
    }

    private val rectF = RectF()

    override fun onBoundsChange(bounds: Rect) {
        super.onBoundsChange(bounds)
        // Definiera området ormen ska ritas i, med hänsyn till tjockleken
        val padding = paint.strokeWidth / 2
        rectF.set(
            bounds.left + padding,
            bounds.top + padding,
            bounds.right - padding,
            bounds.bottom - padding
        )

        // --- OPTIMERAD GRADIENT FÖR RUND LEDANDE NOS (utan rött lock) ---
        // Vi tonar in från genomskinlig (vid 10deg) till mörkorange, men vi håller
        // mörkorange helt solid ända fram till nosens absoluta spets (190deg).
        // Det runda hörnet ritas därefter och får full färg.
        paint.shader = SweepGradient(
            rectF.centerX(),
            rectF.centerY(),
            intArrayOf(Color.TRANSPARENT, snakeColor, snakeColor), // Håller färgen solid på slutet
            floatArrayOf(0.027f, 0.47f, 0.52f) // Justerade stopp för att matcha den nya vinkeln
        )
    }

    override fun draw(canvas: Canvas) {
        // Rotera canvasen -90 grader så att ormen börjar längst upp kl 12:00
        canvas.save()
        canvas.rotate(-90f, rectF.centerX(), rectF.centerY())

        // --- HÄR ÄR DEN KRITISKA FIXEN! ---
        // Vi börjar rita vid 10 grader (startAngle) istället för 0.
        // Vi ritar 180 grader (sweepAngle) totalt. Nosen hamnar vid 190 grader.
        // Det tar bort det "rödaktiga" locket i början eftersom vi flyttar bort
        // svansen från den "farliga" 0-graders startpunkten.
        canvas.drawArc(rectF, 10f, 180f, false, paint)

        canvas.restore()
    }

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
    }

    @Deprecated("Deprecated in Java", ReplaceWith("PixelFormat.TRANSLUCENT", "android.graphics.PixelFormat"))
    override fun getOpacity(): Int {
        return PixelFormat.TRANSLUCENT
    }
}