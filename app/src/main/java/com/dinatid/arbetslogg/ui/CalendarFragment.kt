package com.dinatid.arbetslogg.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.speech.RecognizerIntent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import com.dinatid.arbetslogg.R
import com.dinatid.arbetslogg.data.TimeRepository
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import java.text.SimpleDateFormat
import java.util.*

class CalendarFragment : Fragment() {

    private val viewModel: HistoryViewModel by viewModels()

    private lateinit var speechLauncher: ActivityResultLauncher<Intent>
    private var activeEditText: EditText? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        speechLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                val matches = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                if (!matches.isNullOrEmpty()) {
                    val spokenText = matches[0]
                    val currentText = activeEditText?.text?.toString() ?: ""
                    val newText = if (currentText.isEmpty()) spokenText else "$currentText $spokenText"

                    activeEditText?.setText(newText)
                    activeEditText?.setSelection(newText.length)
                }
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_calendar, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val btnPrevMonth = view.findViewById<ImageView>(R.id.btnPrevMonth)
        val btnNextMonth = view.findViewById<ImageView>(R.id.btnNextMonth)
        val txtCurrentMonth = view.findViewById<TextView>(R.id.txtCurrentMonth)
        val rvCalendar = view.findViewById<RecyclerView>(R.id.rvCalendar)

        btnPrevMonth.setOnClickListener { viewModel.changeMonth(-1) }
        btnNextMonth.setOnClickListener { viewModel.changeMonth(1) }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.currentMonthText.collectLatest { monthText ->
                txtCurrentMonth.text = monthText
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.calendarDays.collectLatest { days ->
                val adapter = CalendarAdapter(days) { clickedDay ->
                    showDayDetailsPopup(clickedDay)
                }
                rvCalendar.adapter = adapter
            }
        }
    }

    private fun showDayDetailsPopup(day: CalendarDay) {
        if (day.date == null) return

        viewLifecycleOwner.lifecycleScope.launch {
            val note = viewModel.getNoteForDay(day.date)
            val dateFormatter = SimpleDateFormat("EEEE d MMMM", Locale("sv", "SE"))
            val dateString = dateFormatter.format(day.date).uppercase()

            val dialogView = layoutInflater.inflate(R.layout.layout_dialog_details, null)
            val txtDialogMessage = dialogView.findViewById<TextView>(R.id.txtDialogMessage)
            dialogView.findViewById<TextView>(R.id.txtDialogTitle).text = dateString

            fun buildMessage(animStep: Int): android.text.Spanned {
                var msg = ""
                if (day.isHoliday) {
                    msg += "🎈 ${day.holidayName}\n\n"
                }

                if (day.externalEvents.isNotEmpty()) {
                    val eventsStr = day.externalEvents.joinToString(", ").uppercase()
                    msg += "📅 $eventsStr\n\n"
                }

                if (day.loggedMinutes > 0 || day.isOngoing) {
                    if (day.loggedMinutes > 0) {
                        msg += getString(R.string.msg_logged_time_format, day.loggedMinutes / 60, day.loggedMinutes % 60)
                    }

                    val timeFormatter = SimpleDateFormat("HH:mm", Locale.getDefault())

                    var lunchOutIndex = -1
                    var lunchInIndex = -1
                    var lastOutTs = 0L
                    var lastOutIdx = -1

                    for (idx in day.dayLogs.indices) {
                        val log = day.dayLogs[idx]
                        if (log.type.startsWith(com.dinatid.arbetslogg.WorkLog.TYPE_OUT)) {
                            lastOutTs = log.timestamp
                            lastOutIdx = idx
                        } else if (log.type == com.dinatid.arbetslogg.WorkLog.TYPE_IN && lastOutTs != 0L) {
                            val gapMin = (log.timestamp - lastOutTs) / 60000
                            val calTmp = Calendar.getInstance().apply { timeInMillis = lastOutTs }
                            val hourOfDay = calTmp.get(Calendar.HOUR_OF_DAY)

                            if (gapMin in 25..90 && hourOfDay in 10..13) {
                                lunchOutIndex = lastOutIdx
                                lunchInIndex = idx
                                break
                            }
                        }
                    }

                    for (idx in day.dayLogs.indices) {
                        val log = day.dayLogs[idx]
                        val time = timeFormatter.format(Date(log.timestamp))
                        var type = if (log.type == com.dinatid.arbetslogg.WorkLog.TYPE_IN) "IN " else "UT "

                        if (idx == lunchOutIndex || idx == lunchInIndex) {
                            val lunchLabel = getString(R.string.calendar_lunch)
                            type = "$lunchLabel $type"
                        }

                        val ssid = log.ssid.takeIf { it.isNotEmpty() } ?: getString(R.string.calendar_unknown_ssid)
                        val editMarker = if (log.isManuallyEdited) " <font color='#808080' size='10'>${getString(R.string.edit_log_edited_marker)}</font>" else ""
                        
                        msg += "• $type kl $time ($ssid)$editMarker"
                        if (!log.comment.isNullOrBlank()) {
                            msg += " <font color='#FF9800'>[${log.comment}]</font>"
                        }
                        msg += "\n"
                    }

                    if (day.isOngoing) {
                        val dots = when (animStep % 4) {
                            1 -> ".<font color='#00000000'>..</font>"
                            2 -> "..<font color='#00000000'>.</font>"
                            3 -> "..."
                            else -> "<font color='#00000000'>...</font>"
                        }
                        val ongoingText = getString(R.string.calendar_ongoing).uppercase()
                        msg += "• $ongoingText $dots\n"
                    }
                } else if (day.isHoliday) {
                    val now = Calendar.getInstance().apply {
                        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
                    }
                    val checkDate = Calendar.getInstance().apply {
                        time = day.date
                        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
                    }

                    if (checkDate.after(now)) {
                        msg += getString(R.string.msg_ledig_day_future)
                    } else {
                        msg += getString(R.string.msg_ledig_day)
                    }
                } else {
                    msg += getString(R.string.msg_no_time_logged)
                }

                if (!note.isNullOrBlank()) {
                    msg += getString(R.string.msg_your_note, note)
                }

                return android.text.Html.fromHtml(msg.replace("\n", "<br/>"), android.text.Html.FROM_HTML_MODE_LEGACY)
            }

            txtDialogMessage.text = buildMessage(0)

            val dialog = MaterialAlertDialogBuilder(requireContext())
                .setView(dialogView)
                .setCancelable(true)
                .create()

            var animJob: Job? = null
            if (day.isOngoing) {
                animJob = viewLifecycleOwner.lifecycleScope.launch {
                    var currentStep = 0
                    while (isActive) {
                        delay(600)
                        currentStep++
                        txtDialogMessage.text = buildMessage(currentStep)
                    }
                }
            }

            dialog.setOnDismissListener {
                animJob?.cancel()
            }

            dialogView.findViewById<ImageView>(R.id.btnClose).setOnClickListener {
                dialog.dismiss()
            }

            dialogView.findViewById<Button>(R.id.btnDagsanteckning).setOnClickListener {
                dialog.dismiss()
                showCommentPopup(day, note ?: "")
            }

            txtDialogMessage.setOnClickListener {
                showLogSelectionForEdit(day)
                dialog.dismiss()
            }

            dialog.show()
        }
    }

    private fun showLogSelectionForEdit(day: CalendarDay) {
        val options = day.dayLogs.map { log ->
            val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(log.timestamp))
            val type = if (log.type == com.dinatid.arbetslogg.WorkLog.TYPE_IN) "IN" else "UT"
            "$type kl $time (${log.ssid})"
        }.toTypedArray()

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.edit_log_title))
            .setItems(options) { _, which ->
                showEditLogDialog(day.dayLogs[which])
            }
            .setNeutralButton(getString(R.string.btn_add_manual)) { _, _ ->
                showAddManualLogDialog(day)
            }
            .setNegativeButton(getString(R.string.btn_close), null)
            .show()
    }

    private fun showEditLogDialog(log: com.dinatid.arbetslogg.WorkLog) {
        val dialogView = layoutInflater.inflate(R.layout.layout_dialog_edit_log, null)
        val txtTime = dialogView.findViewById<TextView>(R.id.txtEditTime)
        val inputComment = dialogView.findViewById<EditText>(R.id.inputComment)
        val btnSave = dialogView.findViewById<Button>(R.id.btnSaveEdit)
        val btnDelete = dialogView.findViewById<ImageView>(R.id.btnDeleteLog)
        val btnClose = dialogView.findViewById<ImageView>(R.id.btnClose)

        val sdf = SimpleDateFormat("HH:mm", Locale.getDefault())
        txtTime.text = sdf.format(Date(log.timestamp))
        inputComment.setText(log.comment ?: "")

        val cal = Calendar.getInstance().apply { timeInMillis = log.timestamp }
        
        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setView(dialogView)
            .setCancelable(true)
            .create()

        txtTime.setOnClickListener {
            android.app.TimePickerDialog(requireContext(), R.style.ViRATimePickerTheme, { _, h, m ->
                cal.set(Calendar.HOUR_OF_DAY, h)
                cal.set(Calendar.MINUTE, m)
                txtTime.text = String.format(Locale.getDefault(), "%02d:%02d", h, m)
            }, cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE), true).show()
        }

        btnSave.setOnClickListener {
            val updatedLog = log.copy(
                timestamp = cal.timeInMillis,
                comment = inputComment.text.toString().trim().takeIf { it.isNotEmpty() },
                isManuallyEdited = true
            )
            viewLifecycleOwner.lifecycleScope.launch {
                TimeRepository.getInstance(requireActivity().application).updateLog(updatedLog)
                dialog.dismiss()
                viewModel.refresh()
            }
        }

        btnDelete.setOnClickListener {
            MaterialAlertDialogBuilder(requireContext())
                .setMessage(getString(R.string.edit_log_delete_confirm))
                .setPositiveButton(getString(R.string.edit_log_btn_delete)) { _, _ ->
                    viewLifecycleOwner.lifecycleScope.launch {
                        TimeRepository.getInstance(requireActivity().application).deleteLog(log)
                        dialog.dismiss()
                        viewModel.refresh()
                    }
                }
                .setNegativeButton(getString(R.string.setup_workplace_no), null)
                .show()
        }

        btnClose.setOnClickListener { dialog.dismiss() }
        dialog.show()
    }

    private fun showAddManualLogDialog(day: CalendarDay) {
        val cal = Calendar.getInstance().apply { 
            time = day.date!!
            set(Calendar.HOUR_OF_DAY, 8)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        
        val dialogView = layoutInflater.inflate(R.layout.layout_dialog_edit_log, null)
        val txtTime = dialogView.findViewById<TextView>(R.id.txtEditTime)
        val inputComment = dialogView.findViewById<EditText>(R.id.inputComment)
        val btnSave = dialogView.findViewById<Button>(R.id.btnSaveEdit)
        val btnDelete = dialogView.findViewById<ImageView>(R.id.btnDeleteLog)
        val btnClose = dialogView.findViewById<ImageView>(R.id.btnClose)
        
        dialogView.findViewById<TextView>(R.id.txtEditTitle).text = getString(R.string.btn_add_manual)
        btnDelete.visibility = View.GONE
        txtTime.text = "08:00"
        
        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setView(dialogView)
            .create()

        btnClose.setOnClickListener { dialog.dismiss() }

        txtTime.setOnClickListener {
            android.app.TimePickerDialog(requireContext(), R.style.ViRATimePickerTheme, { _, h, m ->
                cal.set(Calendar.HOUR_OF_DAY, h)
                cal.set(Calendar.MINUTE, m)
                txtTime.text = String.format(Locale.getDefault(), "%02d:%02d", h, m)
            }, cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE), true).show()
        }

        btnSave.setOnClickListener {
            val note = inputComment.text.toString().trim().takeIf { it.isNotEmpty() }
            val newLog = com.dinatid.arbetslogg.WorkLog(
                type = com.dinatid.arbetslogg.WorkLog.TYPE_IN,
                timestamp = cal.timeInMillis,
                ssid = "Manuell",
                comment = note,
                isManuallyEdited = true
            )
            viewLifecycleOwner.lifecycleScope.launch {
                TimeRepository.getInstance(requireActivity().application).insertLog(newLog)
                dialog.dismiss()
                viewModel.refresh()
            }
        }
        
        dialog.show()
    }

    private fun showCommentPopup(day: CalendarDay, currentNote: String) {
        val date = day.date ?: return

        val dialogView = layoutInflater.inflate(R.layout.layout_dialog_comment, null)
        val inputNote = dialogView.findViewById<EditText>(R.id.inputNote)
        val micBtn = dialogView.findViewById<ImageView>(R.id.micBtn)
        val btnSpara = dialogView.findViewById<Button>(R.id.btnSpara)
        val btnClose = dialogView.findViewById<ImageView>(R.id.btnClose)

        activeEditText = inputNote
        inputNote.setText(currentNote)
        inputNote.setSelection(currentNote.length)

        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setView(dialogView)
            .setCancelable(true)
            .create()

        btnClose.setOnClickListener {
            dialog.dismiss()
            showDayDetailsPopup(day)
        }

        micBtn.setOnClickListener {
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, Locale.getDefault())
                putExtra(RecognizerIntent.EXTRA_PROMPT, getString(R.string.prompt_speak))
            }
            try {
                speechLauncher.launch(intent)
            } catch (e: Exception) {
                Toast.makeText(requireContext(), getString(R.string.error_mic_not_supported), Toast.LENGTH_SHORT).show()
            }
        }

        btnSpara.setOnClickListener {
            viewModel.saveNoteForDay(date, inputNote.text.toString())
            dialog.dismiss()

            viewLifecycleOwner.lifecycleScope.launch {
                delay(100)
                showDayDetailsPopup(day)
            }
        }

        dialog.show()
    }

    override fun onResume() {
        super.onResume()
        viewModel.refresh()
    }

    override fun onPause() {
        super.onPause()
    }
}
