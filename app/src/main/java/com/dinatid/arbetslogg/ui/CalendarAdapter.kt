package com.dinatid.arbetslogg.ui

import android.graphics.Color
import android.text.Html
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.dinatid.arbetslogg.R
import java.util.Date
import kotlinx.coroutines.*

// 1. Lådan som håller all data för EN enda dag i kalendern
data class CalendarDay(
    val date: Date?,
    val dayNumber: String,
    val loggedMinutes: Int,
    val isHoliday: Boolean,
    val holidayName: String? = null,
    val isToday: Boolean,
    val isWeekend: Boolean, // <-- NY: Håller koll på lördag/söndag
    val isOngoing: Boolean = false, // <-- NY: Håller koll på om ett pass pågår just nu
    val dayLogs: List<com.dinatid.arbetslogg.WorkLog> = emptyList(),
    val lunchText: String? = null, // <-- NY: Text för lunchrast
    val externalEvents: List<String> = emptyList() // <-- NY: SJUK, VAB etc från Outlook/Google
)

// 2. Själva Adaptern som ritar upp rutorna
class CalendarAdapter(
    private val days: List<CalendarDay>,
    private val onDayClick: (CalendarDay) -> Unit
) : RecyclerView.Adapter<CalendarAdapter.DayViewHolder>() {

    private val adapterScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var animationJob: Job? = null
    private var animationStep = 0

    init {
        startAnimationLoop()
    }

    private fun startAnimationLoop() {
        animationJob?.cancel()
        animationJob = adapterScope.launch {
            while (isActive) {
                animationStep = (animationStep + 1) % 4
                // Vi uppdaterar bara de rader som faktiskt visas och har "isOngoing"
                notifyDataSetChanged() 
                delay(600)
            }
        }
    }

    private fun getAnimatedProgressText(context: android.content.Context): String {
        val dots = when (animationStep) {
            1 -> ".<font color='#00000000'>..</font>"
            2 -> "..<font color='#00000000'>.</font>"
            3 -> "..."
            else -> "<font color='#00000000'>...</font>"
        }
        val text = context.getString(R.string.calendar_ongoing)
        return "$text<br/>$dots"
    }

    inner class DayViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val dayContainer: LinearLayout = view.findViewById(R.id.dayContainer)
        val txtDayNumber: TextView = view.findViewById(R.id.txtDayNumber)
        val txtLoggedTime: TextView = view.findViewById(R.id.txtLoggedTime)
        val txtLunch: TextView = view.findViewById(R.id.txtLunch)
        val txtExternalEvent: TextView = view.findViewById(R.id.txtExternalEvent)
    }

    // FIX: Övertydlig returtyp (CalendarAdapter.DayViewHolder) så Kotlin inte klagar
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CalendarAdapter.DayViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_calendar_day, parent, false)
        return DayViewHolder(view)
    }

    // FIX: Övertydlig parametertyp (CalendarAdapter.DayViewHolder)
    override fun onBindViewHolder(holder: CalendarAdapter.DayViewHolder, position: Int) {
        val day = days[position]
        val context = holder.itemView.context

        if (day.date == null) {
            // Det här är en "tom" ruta
            holder.txtDayNumber.text = ""
            holder.txtLoggedTime.text = ""
            holder.dayContainer.setOnClickListener(null)
            holder.dayContainer.setBackgroundColor(Color.TRANSPARENT)
            return
        }

        // --- RITA UPP EN RIKTIG DAG ---
        holder.txtDayNumber.text = day.dayNumber

        // Hämta färger från temat istället för hårdkodat
        val typedValue = android.util.TypedValue()
        
        context.theme.resolveAttribute(R.attr.spinnerTextColor, typedValue, true)
        val themeColor = typedValue.data

        context.theme.resolveAttribute(R.attr.loggedTimeColor, typedValue, true)
        val workColor = typedValue.data

        context.theme.resolveAttribute(com.google.android.material.R.attr.colorSurface, typedValue, true)
        val surfaceColor = typedValue.data

        context.theme.resolveAttribute(R.attr.mainBackground, typedValue, true)
        val pageBgColor = if (typedValue.type >= android.util.TypedValue.TYPE_FIRST_COLOR_INT && typedValue.type <= android.util.TypedValue.TYPE_LAST_COLOR_INT) {
            typedValue.data
        } else {
            android.graphics.Color.parseColor("#15000000") // Fallback för Classic-gradient
        }

        context.theme.resolveAttribute(android.R.attr.textColorSecondary, typedValue, true)
        val secondaryTextColor = typedValue.data

        val holidayTextColor = androidx.core.content.ContextCompat.getColor(context, R.color.warning_red)

        // --- BAKGRUNDSLOGIK (Idag > Röda dagar/Helg/Event > Arbetsdagar) ---
        if (day.isToday) {
            val alphaTodayColor = androidx.core.graphics.ColorUtils.setAlphaComponent(surfaceColor, 255)
            
            val background = android.graphics.drawable.GradientDrawable()
            background.setColor(alphaTodayColor)
            background.cornerRadius = android.util.TypedValue.applyDimension(
                android.util.TypedValue.COMPLEX_UNIT_DIP, 6f, context.resources.displayMetrics
            )
            background.setStroke(
                android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_DIP, 2f, context.resources.displayMetrics).toInt(),
                workColor
            )
            holder.dayContainer.background = background
            
        } else if (day.isHoliday || day.isWeekend || day.externalEvents.isNotEmpty()) {
            // Dessa dagar behåller nu den "grå som kalendern i stort har"
            holder.dayContainer.setBackgroundColor(android.graphics.Color.TRANSPARENT)
        } else {
            // Vanliga arbetsdagar blir nu mörka
            holder.dayContainer.setBackgroundColor(pageBgColor)
        }

        // --- Hantera texten och färgerna för siffran ---
        if (day.isHoliday) {
            holder.txtDayNumber.setTextColor(holidayTextColor)
        } else if (!day.isWeekend && day.externalEvents.isEmpty() && !day.isToday) {
            // För vanliga arbetsdagar (som nu är mörka), använd ljus grå/blå
            holder.txtDayNumber.setTextColor(secondaryTextColor)
        } else {
            // För övriga dagar (grå bakgrund), använd primär textfärg (t.ex. vit)
            holder.txtDayNumber.setTextColor(themeColor)
        }

        // --- Hantera texten för arbetad tid / ledighet ---
        if (day.isOngoing) {
            holder.txtLoggedTime.text = Html.fromHtml(getAnimatedProgressText(context), Html.FROM_HTML_MODE_LEGACY)
            holder.txtLoggedTime.setTextColor(workColor)
            holder.txtLoggedTime.visibility = View.VISIBLE
        } else if (day.loggedMinutes > 0) {
            val hours = day.loggedMinutes / 60
            val mins = day.loggedMinutes % 60
            holder.txtLoggedTime.text = if (mins > 0) "${hours}h ${mins}m" else "${hours}h"
            holder.txtLoggedTime.setTextColor(workColor)
            holder.txtLoggedTime.visibility = View.VISIBLE
        } else if (day.isHoliday) {
            holder.txtLoggedTime.text = context.getString(R.string.calendar_vacation)
            holder.txtLoggedTime.setTextColor(holidayTextColor)
            holder.txtLoggedTime.visibility = View.VISIBLE
        } else {
            holder.txtLoggedTime.text = ""
            holder.txtLoggedTime.visibility = View.GONE
        }

        // --- Hantera lunchtext ---
        if (!day.lunchText.isNullOrEmpty()) {
            holder.txtLunch.text = day.lunchText
            holder.txtLunch.visibility = View.VISIBLE
        } else {
            holder.txtLunch.visibility = View.GONE
        }

        // --- Hantera externa händelser (SJUK, VAB etc) ---
        if (day.externalEvents.isNotEmpty()) {
            val eventText = day.externalEvents.joinToString("\n").uppercase()
            holder.txtExternalEvent.text = eventText
            holder.txtExternalEvent.visibility = View.VISIBLE
            holder.txtExternalEvent.setTextColor(themeColor)
        } else {
            holder.txtExternalEvent.visibility = View.GONE
        }

        // Hantera klick på rutan
        holder.dayContainer.setOnClickListener {
            onDayClick(day)
        }
    }

    override fun getItemCount(): Int = days.size

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        super.onDetachedFromRecyclerView(recyclerView)
        animationJob?.cancel()
    }
}
