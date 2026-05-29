package com.dinatid.arbetslogg.ui

import android.annotation.SuppressLint
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator
import androidx.core.content.res.ResourcesCompat
import com.dinatid.arbetslogg.R
import java.util.Locale

data class BarData(val label: String, val value: Float, val subLabel: String = "")

@SuppressLint("NewApi")
class WorkBarChart @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var data: List<BarData> = emptyList()
    private var goalValue: Float = 0f
    private var animationProgress = 0f
    
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val goalLinePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val subLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    
    private val barRect = RectF()
    private val cornerRadius = 12f * resources.displayMetrics.density
    
    private var barColor = Color.GRAY
    private var goalColor = Color.RED
    private var labelColor = Color.WHITE
    private var subLabelColor = Color.LTGRAY
    
    init {
        // Hämta färger från temat
        val typedValue = android.util.TypedValue()
        
        context.theme.resolveAttribute(R.attr.titleTextColor, typedValue, true)
        barColor = typedValue.data
        
        context.theme.resolveAttribute(R.attr.spinnerTextColor, typedValue, true)
        labelColor = typedValue.data
        
        context.theme.resolveAttribute(R.attr.secondaryTextColorCustom, typedValue, true)
        subLabelColor = typedValue.data
        
        // Mål-linjen kan vara en dämpad variant av accentfärgen eller en gråblå
        goalColor = Color.parseColor("#40FFFFFF") // 25% vit som standard i modern

        barPaint.color = barColor
        
        goalLinePaint.apply {
            color = goalColor
            style = Paint.Style.STROKE
            strokeWidth = 1f * resources.displayMetrics.density
            pathEffect = DashPathEffect(floatArrayOf(10f, 10f), 0f)
        }
        
        // Hämta typsnitt
        val typedValueFont = android.util.TypedValue()
        context.theme.resolveAttribute(R.attr.fontBold, typedValueFont, true)
        val fontBold = try { ResourcesCompat.getFont(context, typedValueFont.resourceId) } catch(e: Exception) { Typeface.DEFAULT_BOLD }
        
        context.theme.resolveAttribute(R.attr.fontRegular, typedValueFont, true)
        val fontRegular = try { ResourcesCompat.getFont(context, typedValueFont.resourceId) } catch(e: Exception) { Typeface.DEFAULT }

        labelPaint.apply {
            color = labelColor
            textSize = 12f * resources.displayMetrics.density
            typeface = fontBold
            textAlign = Paint.Align.CENTER
        }
        
        subLabelPaint.apply {
            color = subLabelColor
            textSize = 10f * resources.displayMetrics.density
            typeface = fontRegular
            textAlign = Paint.Align.CENTER
        }
        
        valuePaint.apply {
            color = labelColor
            textSize = 11f * resources.displayMetrics.density
            typeface = fontBold
            textAlign = Paint.Align.CENTER
        }
    }

    fun setData(newData: List<BarData>, goal: Float = 0f) {
        this.goalValue = goal
        if (this.data == newData) {
            // Om bara målet ändrats, rita om direkt
            invalidate()
            return
        }
        this.data = newData
        startAnimation()
    }

    private fun startAnimation() {
        animationProgress = 0f
        val animator = ValueAnimator.ofFloat(0f, 1f)
        animator.duration = 1200 // Lite långsammare för mer premium-känsla
        animator.interpolator = DecelerateInterpolator(1.5f)
        animator.addUpdateListener { animation ->
            animationProgress = animation.animatedValue as Float
            invalidate()
        }
        animator.start()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (data.isEmpty()) return

        val w = width.toFloat()
        val h = height.toFloat()
        
        val bottomPadding = 45f * resources.displayMetrics.density
        val topPadding = 30f * resources.displayMetrics.density
        val sidePadding = 20f * resources.displayMetrics.density
        
        val chartHeight = h - bottomPadding - topPadding
        val barWidth = (w - (sidePadding * 2)) / (data.size * 2 - 1)
        
        val maxValue = maxOf(data.maxOf { it.value }, goalValue, 1f)

        // Rita mållinje
        if (goalValue > 0) {
            val goalY = h - bottomPadding - (goalValue / maxValue) * chartHeight
            canvas.drawLine(sidePadding, goalY, w - sidePadding, goalY, goalLinePaint)
        }

        data.forEachIndexed { index, barData ->
            val left = sidePadding + (index * 2 * barWidth)
            val right = left + barWidth
            
            // Beräkna animerad höjd
            val targetHeight = (barData.value / maxValue) * chartHeight
            val currentHeight = targetHeight * animationProgress
            
            val top = h - bottomPadding - currentHeight
            val bottom = h - bottomPadding
            
            // Rita stapel
            barRect.set(left, top, right, bottom)
            canvas.drawRoundRect(barRect, cornerRadius, cornerRadius, barPaint)
            
            // Rita extra rektangel längst ner för att täcka rundningen vid basen
            canvas.drawRect(left, bottom - cornerRadius, right, bottom, barPaint)
            
            // Rita värde ovanpå stapeln (endast om animationen är nästan klar eller för tydlighet)
            if (animationProgress > 0.5f) {
                val valueText = String.format(Locale.getDefault(), "%.1fh", barData.value)
                canvas.drawText(valueText, left + barWidth/2, top - 8f * resources.displayMetrics.density, valuePaint)
            }

            // Rita etiketter (vecka)
            canvas.drawText(barData.label, left + barWidth/2, h - 25f * resources.displayMetrics.density, labelPaint)
            
            // Rita underetikett (datumintervall)
            canvas.drawText(barData.subLabel, left + barWidth/2, h - 8f * resources.displayMetrics.density, subLabelPaint)
        }
    }
}
