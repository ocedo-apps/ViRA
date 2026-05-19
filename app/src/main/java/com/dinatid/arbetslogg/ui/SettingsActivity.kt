package com.dinatid.arbetslogg.ui

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.lifecycle.lifecycleScope
import com.dinatid.arbetslogg.R
import com.dinatid.arbetslogg.data.TimeRepository
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch
import android.transition.TransitionManager

data class Workplace(val name: String, val ssids: MutableList<String> = mutableListOf())

class SettingsActivity : AppCompatActivity() {

    private lateinit var repository: TimeRepository
    private val workplaces = mutableListOf<Workplace>()
    private lateinit var llWorkplacesContainer: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        repository = TimeRepository(application)

        // --- 1. HÄMTA ALLA VYER ---
        val toggleGroupSmartHelp = findViewById<MaterialButtonToggleGroup>(R.id.toggleGroupSmartHelp)
        val txtSmartHelpDescription = findViewById<TextView>(R.id.txtSmartHelpDescription)

        // NYA KONSULTVYER
        val cardConsultantGoal = findViewById<LinearLayout>(R.id.cardConsultantGoal)
        val etConsultantMonthlyGoal = findViewById<EditText>(R.id.etConsultantMonthlyGoal)

        val txtWorkplacesHeading = findViewById<TextView>(R.id.txtWorkplacesHeading)
        val rowAddWorkplace = findViewById<LinearLayout>(R.id.rowAddWorkplace)

        val etWorkplaceName = findViewById<EditText>(R.id.etWorkplaceName)
        val btnAddWorkplace = findViewById<Button>(R.id.btnAddWorkplace)
        llWorkplacesContainer = findViewById(R.id.llWorkplacesContainer)

        val etDailyGoal = findViewById<EditText>(R.id.etDailyGoal)
        val etLunchMinutes = findViewById<EditText>(R.id.etLunchMinutes)
        val spRounding = findViewById<Spinner>(R.id.spRounding)
        val btnSaveSettings = findViewById<Button>(R.id.btnSaveSettings)

        val switchCalendarIntegration = findViewById<androidx.appcompat.widget.SwitchCompat>(R.id.switchCalendarIntegration)
        switchCalendarIntegration.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                // --- PÅ-LÄGE ---
                // Ploppen (thumb) blir helt vit
                switchCalendarIntegration.thumbTintList = ColorStateList.valueOf(Color.parseColor("#FFFFFF"))
                // Spåret (track) blir din rödbruna accent (sunset_orange)
                switchCalendarIntegration.trackTintList = ColorStateList.valueOf(Color.parseColor("#DD8900"))
            } else {
                // --- AV-LÄGE ---
                // Ploppen (thumb) blir dämpad grå
                switchCalendarIntegration.thumbTintList = ColorStateList.valueOf(Color.parseColor("#D6D6D6"))
                // Spåret (track) blir mörkare grått
                switchCalendarIntegration.trackTintList = ColorStateList.valueOf(Color.parseColor("#9E9E9E"))
            }
        }
        val btnModeOff = findViewById<MaterialButton>(R.id.btnModeOff)
        val btnModeEmployee = findViewById<MaterialButton>(R.id.btnModeEmployee)
        val btnModeConsultant = findViewById<MaterialButton>(R.id.btnModeConsultant)

        val options = listOf("Ingen avrundning", "Närmaste 5 minuter", "Närmaste kvart (15 min)")
        val adapter = ArrayAdapter(this, R.layout.spinner_item, options)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spRounding.adapter = adapter

        // --- 2. LOKALA HJÄLPFUNKTIONER ---
        fun updateButtonTint(selectedMode: Int) {
            val orangeColor = ContextCompat.getColor(this, R.color.sunset_orange)
            val textMainColor = ContextCompat.getColor(this, R.color.sunset_text_main)
            val cremeColor = ContextCompat.getColor(this, R.color.sunset_creme)
            val transparentColor = Color.TRANSPARENT

            listOf(btnModeOff, btnModeEmployee, btnModeConsultant).forEach { button ->
                button.backgroundTintList = ColorStateList.valueOf(transparentColor)
                button.setTextColor(textMainColor)
                button.strokeColor = ColorStateList.valueOf(textMainColor)
            }

            val selectedButton = when (selectedMode) {
                0 -> btnModeOff
                2 -> btnModeConsultant
                else -> btnModeEmployee
            }
            selectedButton.backgroundTintList = ColorStateList.valueOf(orangeColor)
            selectedButton.setTextColor(cremeColor)
            selectedButton.strokeColor = ColorStateList.valueOf(orangeColor)
        }

        fun applySmartHelpEffects(mode: Int) {
            // Trollspöt: Säger åt Android att animera allt som växer/krymper i huvudvyn
            val rootView = findViewById<View>(android.R.id.content) as? android.view.ViewGroup
            if (rootView != null) {
                TransitionManager.beginDelayedTransition(rootView)
            }

            when (mode) {
                0 -> {
                    txtSmartHelpDescription.text = "Helt manuellt läge. Appen ger inga förslag eller smarta frågor om dina tider."
                    txtWorkplacesHeading.text = "MINA ARBETSPLATSER"
                    cardConsultantGoal.visibility = View.GONE
                }
                2 -> {
                    txtSmartHelpDescription.text = "Automatisk detektering av kundbyten baserat på SSID. Inga frågor ställs under dagen."
                    txtWorkplacesHeading.text = "Arbetsplatser & Kunder"
                    cardConsultantGoal.visibility = View.VISIBLE
                }
                else -> {
                    txtSmartHelpDescription.text = "Appen är tyst under dagen men frågar vid korta avbrott ifall det t.ex. var lunch."
                    txtWorkplacesHeading.text = "MINA ARBETSPLATSER"
                    cardConsultantGoal.visibility = View.GONE
                }
            }
        }

        // --- 3. LOGIK OCH LYSSNARE ---
        val prefs = getSharedPreferences("arbetslogg_prefs", Context.MODE_PRIVATE)
        val savedMode = prefs.getInt("smart_help_mode", 1)
        switchCalendarIntegration.isChecked = prefs.getBoolean("use_calendar_integration", false)

        // Hämta och fyll i sparat konsultmål (standard 160 timmar)
        val savedConsultantGoal = prefs.getInt("consultant_monthly_goal", 160)
        etConsultantMonthlyGoal.setText(savedConsultantGoal.toString())

        val activeCheckId = when (savedMode) {
            0 -> R.id.btnModeOff
            2 -> R.id.btnModeConsultant
            else -> R.id.btnModeEmployee
        }
        toggleGroupSmartHelp.check(activeCheckId)
        applySmartHelpEffects(savedMode)
        updateButtonTint(savedMode)

        toggleGroupSmartHelp.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                val currentMode = when (checkedId) {
                    R.id.btnModeOff -> 0
                    R.id.btnModeConsultant -> 2
                    else -> 1
                }
                applySmartHelpEffects(currentMode)
                updateButtonTint(currentMode)
            }
        }

        lifecycleScope.launch {
            loadWorkplacesData()

            if (workplaces.isEmpty()) {
                workplaces.add(Workplace("Min Arbetsplats"))
            }

            updateWorkplacesUi()

            val savedGoalMinutes = repository.getDailyGoalMinutes()
            if (savedGoalMinutes > 0) etDailyGoal.setText((savedGoalMinutes / 60).toString())

            val savedLunchMinutes = repository.getLunchMinutes()
            if (savedLunchMinutes > 0) etLunchMinutes.setText(savedLunchMinutes.toString())

            when (repository.getRoundingInterval()) {
                5 -> spRounding.setSelection(1)
                15 -> spRounding.setSelection(2)
                else -> spRounding.setSelection(0)
            }
        }

        btnAddWorkplace.setOnClickListener {
            val name = etWorkplaceName.text.toString().trim()
            if (name.isEmpty()) {
                Toast.makeText(this, "Skriv ett namn på arbetsplatsen!", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (workplaces.any { it.name.equals(name, ignoreCase = true) }) {
                Toast.makeText(this, "Arbetsplatsen finns redan!", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(etWorkplaceName.windowToken, 0)

            workplaces.add(Workplace(name))
            etWorkplaceName.text.clear()
            updateWorkplacesUi()
        }

        btnSaveSettings.setOnClickListener {
            val goalText = etDailyGoal.text.toString().trim()
            val lunchText = etLunchMinutes.text.toString().trim()

            // Hämta inmatat konsultmål
            val consultantGoalText = etConsultantMonthlyGoal.text.toString().trim()

            if (goalText.isEmpty() || lunchText.isEmpty() || consultantGoalText.isEmpty()) {
                Toast.makeText(this, "Vänligen fyll i alla målfält!", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val goalMinutes = goalText.toIntOrNull()?.times(60) ?: 480
            val lunchMinutes = lunchText.toIntOrNull() ?: 30
            val consultantMonthlyGoal = consultantGoalText.toIntOrNull() ?: 160

            val roundingMinutes = when (spRounding.selectedItemPosition) {
                1 -> 5
                2 -> 15
                else -> 0
            }

            val finalMode = when (toggleGroupSmartHelp.checkedButtonId) {
                R.id.btnModeOff -> 0
                R.id.btnModeConsultant -> 2
                else -> 1
            }

            lifecycleScope.launch {
                saveWorkplacesData()

                // Spara inställningar till SharedPreferences
                prefs.edit()
                    .putInt("smart_help_mode", finalMode)
                    .putInt("consultant_monthly_goal", consultantMonthlyGoal)
                    .putBoolean("use_calendar_integration", switchCalendarIntegration.isChecked)
                    .apply()

                // Spara tidsmål till databasen/repository
                repository.setDailyGoalMinutes(goalMinutes)
                repository.setLunchMinutes(lunchMinutes)
                repository.setRoundingInterval(roundingMinutes)

                Toast.makeText(this@SettingsActivity, "Inställningar sparade!", Toast.LENGTH_SHORT).show()

                val intent = Intent(this@SettingsActivity, MainActivity::class.java)
                intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP
                startActivity(intent)
                overridePendingTransition(0, 0)
                finish()
            }
        }

        val btnNavDashboard = findViewById<LinearLayout>(R.id.btnNavDashboard)
        val btnNavHistory = findViewById<LinearLayout>(R.id.btnNavHistory)
        val btnNavReports = findViewById<LinearLayout>(R.id.btnNavReports)

        btnNavDashboard.setOnClickListener {
            val intent = Intent(this, MainActivity::class.java)
            intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP
            startActivity(intent)
            overridePendingTransition(0, 0)
            finish()
        }
        btnNavHistory.setOnClickListener {
            startActivity(Intent(this, HistoryActivity::class.java))
            overridePendingTransition(0, 0)
            finish()
        }
        btnNavReports.setOnClickListener {
            startActivity(Intent(this, ReportActivity::class.java))
            overridePendingTransition(0, 0)
            finish()
        }

        val themeColor = Color.parseColor("#FCDEBB")
        val inactiveColor = Color.parseColor("#80FCDEBB")

        (btnNavDashboard.getChildAt(0) as? ImageView)?.imageTintList = ColorStateList.valueOf(inactiveColor)
        (btnNavDashboard.getChildAt(1) as? TextView)?.setTextColor(inactiveColor)
        (btnNavHistory.getChildAt(0) as? ImageView)?.imageTintList = ColorStateList.valueOf(inactiveColor)
        (btnNavHistory.getChildAt(1) as? TextView)?.setTextColor(inactiveColor)
        (btnNavReports.getChildAt(0) as? ImageView)?.imageTintList = ColorStateList.valueOf(inactiveColor)
        (btnNavReports.getChildAt(1) as? TextView)?.setTextColor(inactiveColor)

        val btnNavSettings = findViewById<LinearLayout>(R.id.btnNavSettings)
        (btnNavSettings.getChildAt(0) as? ImageView)?.imageTintList = ColorStateList.valueOf(themeColor)
        (btnNavSettings.getChildAt(1) as? TextView)?.setTextColor(themeColor)
    }

    private fun updateWorkplacesUi() {
        llWorkplacesContainer.removeAllViews()

        if (workplaces.isEmpty()) {
            val tvEmpty = TextView(this).apply {
                text = "Inga arbetsplatser tillagda än."
                setTextColor(ContextCompat.getColor(context, R.color.sunset_creme_inactive))
                textSize = 14f
                try { typeface = ResourcesCompat.getFont(context, R.font.asap_light) } catch(e: Exception){}
                setPadding(0, 16, 0, 16)
            }
            llWorkplacesContainer.addView(tvEmpty)
            return
        }

        for (workplace in workplaces) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, 8, 0, 8)
            }

            val tvName = TextView(this).apply {
                text = workplace.name
                setTextColor(ContextCompat.getColor(context, R.color.text_main))
                textSize = 16f
                try { typeface = ResourcesCompat.getFont(context, R.font.asap_semibold) } catch(e: Exception){}
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }

            val dpDensity = resources.displayMetrics.density
            val dp4 = (4 * dpDensity).toInt()
            val dp12 = (12 * dpDensity).toInt()
            val dp30 = (30 * dpDensity).toInt()
            val dp15 = (15 * dpDensity).toInt()

            val btnWifi = TextView(this).apply {
                text = "Wi-Fi (${workplace.ssids.size})"
                setTextColor(ContextCompat.getColor(context, R.color.sunset_text_main))
                textSize = 12f
                try { typeface = ResourcesCompat.getFont(context, R.font.asap_black) } catch(e: Exception){}

                gravity = Gravity.CENTER
                includeFontPadding = false

                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dp15.toFloat()
                    setColor(ContextCompat.getColor(context, R.color.sunset_orange))
                }

                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp30).apply {
                    setMargins(dp12, 0, dp12, 0)
                }
                setPadding(dp12, 0, dp12, 0)

                setOnClickListener { showWifiDialog(workplace) }
            }

            val btnDelete = TextView(this).apply {
                text = "X"
                setTextColor(ContextCompat.getColor(context, R.color.sunset_text_main))
                textSize = 14f
                try { typeface = ResourcesCompat.getFont(context, R.font.asap_black) } catch(e: Exception){}

                gravity = Gravity.CENTER
                includeFontPadding = false

                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dp15.toFloat()
                    setColor(ContextCompat.getColor(context, R.color.warning_red))
                }

                layoutParams = LinearLayout.LayoutParams(dp30, dp30).apply {
                    setMargins(dp4, 0, 0, 0)
                }

                setOnClickListener {
                    val dialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(this@SettingsActivity, R.style.JobbzonDialogTheme)
                        .setTitle("Ta bort?")
                        .setMessage("Arbetsplatsen ${workplace.name} med relaterade SSID kommer att tas bort. Är du säker?")
                        .setPositiveButton("JA") { dialogInterface, _ ->
                            workplaces.remove(workplace)
                            updateWorkplacesUi()
                            dialogInterface.dismiss()
                        }
                        .setNegativeButton("NEJ") { dialogInterface, _ ->
                            dialogInterface.dismiss()
                        }
                        .show()

                    val posButton = dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE)
                    if (posButton != null) {
                        val redColor = ContextCompat.getColor(this@SettingsActivity, R.color.warning_red)
                        val cremeColor = ContextCompat.getColor(this@SettingsActivity, R.color.sunset_creme)
                        posButton.backgroundTintList = ColorStateList.valueOf(redColor)
                        posButton.setTextColor(cremeColor)
                        posButton.setPadding(dp12, 0, dp12, 0)
                    }
                }
            }

            row.addView(tvName)
            row.addView(btnWifi)
            row.addView(btnDelete)

            llWorkplacesContainer.addView(row)
        }
    }

    private fun showWifiDialog(workplace: Workplace) {
        val builder = MaterialAlertDialogBuilder(this, R.style.JobbzonDialogTheme)
        builder.setTitle("WI-FI FÖR ${workplace.name.uppercase()}")

        val input = EditText(this).apply {
            setText(workplace.ssids.joinToString(", "))
            setTextColor(ContextCompat.getColor(context, R.color.dialog_input_text))
            hint = "T.ex. Jarven-INT, Jarven-GUEST"
            setHintTextColor(ContextCompat.getColor(context, R.color.dialog_hint_dark))
            backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(context, R.color.sunset_orange))
            try { typeface = ResourcesCompat.getFont(context, R.font.asap_light) } catch(e: Exception){}
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(45, 20, 45, 20)
            addView(input)
        }
        builder.setView(container)

        builder.setPositiveButton("SPARA") { dialog, _ ->
            val text = input.text.toString()
            workplace.ssids.clear()
            text.split(",").map { it.trim() }.filter { it.isNotEmpty() }.forEach {
                workplace.ssids.add(it)
            }

            updateWorkplacesUi()
            dialog.dismiss()
        }
        builder.setNegativeButton("AVBRYT") { dialog, _ -> dialog.cancel() }
        builder.show()
    }

    private fun loadWorkplacesData() {
        val prefs = getSharedPreferences("arbetslogg_prefs", Context.MODE_PRIVATE)
        val savedData = prefs.getString("workplaces_structure", "")

        workplaces.clear()

        if (savedData.isNullOrEmpty()) {
            val oldSsid = repository.getTargetSsid()
            if (oldSsid.isNotEmpty()) {
                val oldNetworks = oldSsid.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toMutableList()
                workplaces.add(Workplace("Arbetsplats 1", oldNetworks))
            }
        } else {
            savedData.split("|").forEach { block ->
                val parts = block.split(":")
                if (parts.size == 2) {
                    val name = parts[0]
                    val ssids = parts[1].split(",").map { it.trim() }.filter { it.isNotEmpty() }.toMutableList()
                    workplaces.add(Workplace(name, ssids))
                }
            }
        }
    }

    private suspend fun saveWorkplacesData() {
        val serialized = workplaces.joinToString("|") { "${it.name}:${it.ssids.joinToString(",")}" }
        getSharedPreferences("arbetslogg_prefs", Context.MODE_PRIVATE)
            .edit()
            .putString("workplaces_structure", serialized)
            .apply()

        val allSsids = workplaces.flatMap { it.ssids }.joinToString(",")
        repository.setTargetSsid(allSsids)
    }
}