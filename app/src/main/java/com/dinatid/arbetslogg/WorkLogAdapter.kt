package com.dinatid.arbetslogg

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

// --- FIX: Vy-modellen bor nu här istället för i en egen fil. 100% säkert för HistoryActivity! ---
data class WorkShift(
    val day: String,
    val timeRange: String,
    val duration: String
)

// Föräldraklass för de två radtyperna (Rubrik och Arbetspass)
sealed class HistoryItem {
    data class Header(val date: String) : HistoryItem()
    data class Shift(val workShift: WorkShift) : HistoryItem()
}

class WorkLogAdapter(
    private var items: List<HistoryItem>,
    private val onItemClick: (WorkShift) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private val TYPE_HEADER = 0
    private val TYPE_SHIFT = 1

    override fun getItemViewType(position: Int): Int = when (items[position]) {
        is HistoryItem.Header -> TYPE_HEADER
        is HistoryItem.Shift -> TYPE_SHIFT
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_HEADER) {
            HeaderViewHolder(inflater.inflate(R.layout.item_history_header, parent, false))
        } else {
            ShiftViewHolder(inflater.inflate(R.layout.item_work_shift, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val item = items[position]

        if (holder is HeaderViewHolder && item is HistoryItem.Header) {
            holder.txtHeader.text = item.date

        } else if (holder is ShiftViewHolder && item is HistoryItem.Shift) {
            val shift = item.workShift

            // Här sätter vi all data på raden
            holder.txtDate.text = shift.day
            holder.txtTime.text = shift.timeRange
            holder.txtDuration.text = shift.duration

            // Gör hela raden klickbar
            holder.itemView.setOnClickListener { onItemClick(shift) }
        }
    }

    override fun getItemCount() = items.size

    fun updateData(newItems: List<HistoryItem>) {
        items = newItems
        notifyDataSetChanged()
    }

    // ViewHolder för datumrubriken (t.ex. "MÅNDAG 12 MAJ")
    class HeaderViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val txtHeader: TextView = view.findViewById(R.id.txtHistoryDateHeader)
    }

    // ViewHolder för själva arbetspasset
    class ShiftViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val txtDate: TextView = view.findViewById(R.id.txtShiftDate)
        val txtTime: TextView = view.findViewById(R.id.txtShiftTime)
        val txtDuration: TextView = view.findViewById(R.id.txtShiftDuration)
    }
}