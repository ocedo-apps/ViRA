package com.dinatid.arbetslogg.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.transition.TransitionManager
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.*
import android.util.Log
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.viewpager2.widget.ViewPager2
import com.dinatid.arbetslogg.R
import com.dinatid.arbetslogg.data.TimeRepository
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.core.content.IntentCompat
import androidx.core.content.PackageManagerCompat
import androidx.core.content.UnusedAppRestrictionsConstants
import com.google.common.util.concurrent.FutureCallback
import com.google.common.util.concurrent.Futures
import java.text.SimpleDateFormat
import java.util.*

data class Workplace(val name: String, val ssids: MutableList<String> = mutableListOf())

class SettingsFragment : Fragment() {

    private lateinit var repository: TimeRepository
    private val workplaces = mutableListOf<Workplace>()
    private lateinit var llWorkplacesContainer: LinearLayout
    private lateinit var backupLauncher: ActivityResultLauncher<Intent>
    private lateinit var importLauncher: ActivityResultLauncher<Intent>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        backupLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                result.data?.data?.let { uri ->
                    saveDatabaseToUri(uri)
                }
            }
        }
        importLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                result.data?.data?.let { uri ->
                    confirmImportDatabase(uri)
                }
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_settings, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        repository = TimeRepository.getInstance(requireActivity().application)

        val toggleGroupSmartHelp = view.findViewById<MaterialButtonToggleGroup>(R.id.toggleGroupSmartHelp)
        val txtSmartHelpDescription = view.findViewById<TextView>(R.id.txtSmartHelpDescription)
        val cardConsultantGoal = view.findViewById<LinearLayout>(R.id.cardConsultantGoal)
        val etConsultantMonthlyGoal = view.findViewById<EditText>(R.id.etConsultantMonthlyGoal)
        val txtWorkplacesHeading = view.findViewById<TextView>(R.id.txtWorkplacesHeading)
        val etWorkplaceName = view.findViewById<EditText>(R.id.etWorkplaceName)
        val btnAddWorkplace = view.findViewById<Button>(R.id.btnAddWorkplace)
        llWorkplacesContainer = view.findViewById(R.id.llWorkplacesContainer)
        val etDailyGoal = view.findViewById<EditText>(R.id.etDailyGoal)
        val etLunchMinutes = view.findViewById<EditText>(R.id.etLunchMinutes)
        val spRounding = view.findViewById<Spinner>(R.id.spRounding)
        val btnSaveSettings = view.findViewById<Button>(R.id.btnSaveSettings)
        val switchCalendarIntegration = view.findViewById<androidx.appcompat.widget.SwitchCompat>(R.id.switchCalendarIntegration)
        
        val toggleGroupTheme = view.findViewById<MaterialButtonToggleGroup>(R.id.toggleGroupTheme)
        val btnThemeClassic = view.findViewById<MaterialButton>(R.id.btnThemeClassic)
        val btnThemeModern = view.findViewById<MaterialButton>(R.id.btnThemeModern)

        val cardAutoRevokeWarning = view.findViewById<LinearLayout>(R.id.cardAutoRevokeWarning)
        val btnFixAutoRevoke = view.findViewById<Button>(R.id.btnFixAutoRevoke)
        val btnExportBackup = view.findViewById<Button>(R.id.btnExportBackup)
        val btnImportBackup = view.findViewById<Button>(R.id.btnImportBackup)

        val btnModeOff = view.findViewById<MaterialButton>(R.id.btnModeOff)
        val btnModeEmployee = view.findViewById<MaterialButton>(R.id.btnModeEmployee)
        val btnModeConsultant = view.findViewById<MaterialButton>(R.id.btnModeConsultant)

        // --- TEMA-HANTERING ---
        val currentTheme = repository.getAppTheme()
        toggleGroupTheme.check(if (currentTheme == 1) R.id.btnThemeModern else R.id.btnThemeClassic)
        
        fun updateThemeButtonTint(theme: Int) {
            val typedValueOrange = android.util.TypedValue()
            requireContext().theme.resolveAttribute(com.google.android.material.R.attr.colorPrimary, typedValueOrange, true)
            val orangeColor = typedValueOrange.data

            val typedValueActive = android.util.TypedValue()
            requireContext().theme.resolveAttribute(R.attr.buttonTextActiveColor, typedValueActive, true)
            val textPrimaryColor = typedValueActive.data

            val typedValueInactive = android.util.TypedValue()
            requireContext().theme.resolveAttribute(R.attr.buttonTextInactiveColor, typedValueInactive, true)
            val textSecondaryColor = typedValueInactive.data
            
            btnThemeClassic.backgroundTintList = ColorStateList.valueOf(if (theme == 0) orangeColor else Color.TRANSPARENT)
            btnThemeClassic.setTextColor(if (theme == 0) textPrimaryColor else textSecondaryColor)
            btnThemeClassic.strokeColor = ColorStateList.valueOf(if (theme == 0) orangeColor else textSecondaryColor)
            
            btnThemeModern.backgroundTintList = ColorStateList.valueOf(if (theme == 1) orangeColor else Color.TRANSPARENT)
            btnThemeModern.setTextColor(if (theme == 1) textPrimaryColor else textSecondaryColor)
            btnThemeModern.strokeColor = ColorStateList.valueOf(if (theme == 1) orangeColor else textSecondaryColor)
        }
        updateThemeButtonTint(currentTheme)

        toggleGroupTheme.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                val newTheme = if (checkedId == R.id.btnThemeModern) 1 else 0
                updateThemeButtonTint(newTheme)
            }
        }

        // --- KOLLA AUTO-REVOKE STATUS ---
        checkAutoRevokeStatus(cardAutoRevokeWarning)

        btnFixAutoRevoke.setOnClickListener {
            val intent = IntentCompat.createManageUnusedAppRestrictionsIntent(requireContext(), requireContext().packageName)
            startActivity(intent)
        }

        btnExportBackup.setOnClickListener {
            val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "application/x-sqlite3"
                putExtra(Intent.EXTRA_TITLE, "arbetslogg_backup_${SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(Date())}.db")
            }
            backupLauncher.launch(intent)
        }

        btnImportBackup.setOnClickListener {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
            }
            importLauncher.launch(intent)
        }

        switchCalendarIntegration.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                switchCalendarIntegration.thumbTintList = ColorStateList.valueOf(Color.parseColor("#FFFFFF"))
                switchCalendarIntegration.trackTintList = ColorStateList.valueOf(Color.parseColor("#DD8900"))
            } else {
                switchCalendarIntegration.thumbTintList = ColorStateList.valueOf(Color.parseColor("#D6D6D6"))
                switchCalendarIntegration.trackTintList = ColorStateList.valueOf(Color.parseColor("#9E9E9E"))
            }
        }

        val options = listOf("Ingen avrundning", "Närmaste 5 minuter", "Närmaste kvart (15 min)")
        val adapter = ArrayAdapter(requireContext(), R.layout.spinner_item, options)
        adapter.setDropDownViewResource(R.layout.spinner_dropdown_item)
        spRounding.adapter = adapter

        fun updateButtonTint(selectedMode: Int) {
            val typedValueOrange = android.util.TypedValue()
            requireContext().theme.resolveAttribute(com.google.android.material.R.attr.colorPrimary, typedValueOrange, true)
            val orangeColor = typedValueOrange.data

            val typedValueActive = android.util.TypedValue()
            requireContext().theme.resolveAttribute(R.attr.buttonTextActiveColor, typedValueActive, true)
            val textPrimaryColor = typedValueActive.data

            val typedValueInactive = android.util.TypedValue()
            requireContext().theme.resolveAttribute(R.attr.buttonTextInactiveColor, typedValueInactive, true)
            val textSecondaryColor = typedValueInactive.data

            val transparentColor = Color.TRANSPARENT

            listOf(btnModeOff, btnModeEmployee, btnModeConsultant).forEach { button ->
                button.backgroundTintList = ColorStateList.valueOf(transparentColor)
                button.setTextColor(textSecondaryColor)
                button.strokeColor = ColorStateList.valueOf(textSecondaryColor)
            }

            val selectedButton = when (selectedMode) {
                0 -> btnModeOff
                2 -> btnModeConsultant
                else -> btnModeEmployee
            }
            selectedButton.backgroundTintList = ColorStateList.valueOf(orangeColor)
            selectedButton.setTextColor(textPrimaryColor)
            selectedButton.strokeColor = ColorStateList.valueOf(orangeColor)
        }

        fun applySmartHelpEffects(mode: Int) {
            val rootView = view.findViewById<View>(R.id.settingsScrollView) as? android.view.ViewGroup
            if (rootView != null) {
                TransitionManager.beginDelayedTransition(rootView)
            }

            when (mode) {
                0 -> {
                    txtSmartHelpDescription.text = "Helt manuellt läge. Appen give inga förslag eller smarta frågor om dina tider."
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

        val prefs = requireContext().getSharedPreferences("arbetslogg_prefs", Context.MODE_PRIVATE)
        val savedMode = prefs.getInt("smart_help_mode", 1)
        switchCalendarIntegration.isChecked = prefs.getBoolean("use_calendar_integration", false)

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

        viewLifecycleOwner.lifecycleScope.launch {
            loadWorkplacesData()
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

        etWorkplaceName.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) {
                val hasText = !s.isNullOrBlank()
                btnAddWorkplace.isEnabled = hasText
                btnAddWorkplace.alpha = if (hasText) 1.0f else 0.3f
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })

        btnAddWorkplace.setOnClickListener {
            val name = etWorkplaceName.text.toString().trim()
            if (name.isEmpty()) {
                Toast.makeText(requireContext(), "Skriv ett namn på arbetsplatsen!", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (workplaces.any { it.name.equals(name, ignoreCase = true) }) {
                Toast.makeText(requireContext(), "Arbetsplatsen finns redan!", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(etWorkplaceName.windowToken, 0)

            workplaces.add(Workplace(name))
            etWorkplaceName.text.clear()
            updateWorkplacesUi()
        }

        btnSaveSettings.setOnClickListener {
            val goalText = etDailyGoal.text.toString().trim()
            val lunchText = etLunchMinutes.text.toString().trim()
            val consultantGoalText = etConsultantMonthlyGoal.text.toString().trim()

            if (goalText.isEmpty() || lunchText.isEmpty() || consultantGoalText.isEmpty()) {
                Toast.makeText(requireContext(), "Vänligen fyll i alla målfält!", Toast.LENGTH_SHORT).show()
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

            val finalTheme = if (toggleGroupTheme.checkedButtonId == R.id.btnThemeModern) 1 else 0

            viewLifecycleOwner.lifecycleScope.launch {
                saveWorkplacesData()

                prefs.edit()
                    .putInt("smart_help_mode", finalMode)
                    .putInt("consultant_monthly_goal", consultantMonthlyGoal)
                    .putBoolean("use_calendar_integration", switchCalendarIntegration.isChecked)
                    .apply()

                repository.setDailyGoalMinutes(goalMinutes)
                repository.setLunchMinutes(lunchMinutes)
                repository.setRoundingInterval(roundingMinutes)
                
                if (repository.getAppTheme() != finalTheme) {
                    repository.setAppTheme(finalTheme)
                    // Starta om aktiviteten för att tillämpa nya temat
                    requireActivity().recreate()
                }

                Toast.makeText(requireContext(), "Inställningar sparade!", Toast.LENGTH_SHORT).show()

                // NYTT: Istället för att ladda om hela appen, rulla smidigt tillbaka till första fliken!
                val viewPager = requireActivity().findViewById<ViewPager2>(R.id.mainViewPager)
                viewPager?.setCurrentItem(0, true)
            }
        }
    }

    private fun updateWorkplacesUi() {
        llWorkplacesContainer.removeAllViews()

        val typedValueOrange = android.util.TypedValue()
        requireContext().theme.resolveAttribute(R.attr.titleTextColor, typedValueOrange, true)
        val orangeColor = typedValueOrange.data

        if (workplaces.isEmpty()) {
            val tvEmpty = TextView(requireContext()).apply {
                text = "Inga arbetsplatser tillagda än."
                
                val typedValueTheme = android.util.TypedValue()
                requireContext().theme.resolveAttribute(R.attr.spinnerTextColor, typedValueTheme, true)
                setTextColor(typedValueTheme.data)

                textSize = 16f
                
                val typedValue = android.util.TypedValue()
                requireContext().theme.resolveAttribute(R.attr.fontRegular, typedValue, true)
                try { typeface = ResourcesCompat.getFont(context, typedValue.resourceId) } catch(e: Exception){}
                
                setPadding(0, 16, 0, 16)
            }
            llWorkplacesContainer.addView(tvEmpty)
            return
        }

        val typedValueFontBold = android.util.TypedValue()
        requireContext().theme.resolveAttribute(R.attr.fontBold, typedValueFontBold, true)
        val fontBoldResId = typedValueFontBold.resourceId

        val typedValueFontRegular = android.util.TypedValue()
        requireContext().theme.resolveAttribute(R.attr.fontRegular, typedValueFontRegular, true)
        val fontRegularResId = typedValueFontRegular.resourceId

        val typedValueFontSemiBold = android.util.TypedValue()
        requireContext().theme.resolveAttribute(R.attr.fontSemiBold, typedValueFontSemiBold, true)
        val fontSemiBoldResId = typedValueFontSemiBold.resourceId

        for (workplace in workplaces) {
            val row = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, 8, 0, 8)
            }

            val tvName = TextView(requireContext()).apply {
                text = workplace.name
                
                val typedValueTheme = android.util.TypedValue()
                requireContext().theme.resolveAttribute(R.attr.spinnerTextColor, typedValueTheme, true)
                setTextColor(typedValueTheme.data)

                textSize = 16f
                try { typeface = ResourcesCompat.getFont(context, fontSemiBoldResId) } catch(e: Exception){}
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }

            val dpDensity = resources.displayMetrics.density
            val dp4 = (4 * dpDensity).toInt()
            val dp12 = (12 * dpDensity).toInt()
            val dp30 = (30 * dpDensity).toInt()
            val dp15 = (15 * dpDensity).toInt()

            val btnWifi = TextView(requireContext()).apply {
                text = "Wi-Fi (${workplace.ssids.size})"
                setTextColor(ContextCompat.getColor(context, R.color.modern_text_primary))
                textSize = 12f
                try { typeface = ResourcesCompat.getFont(context, fontBoldResId) } catch(e: Exception){}

                gravity = Gravity.CENTER
                includeFontPadding = false

                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dp15.toFloat()
                    setColor(orangeColor)
                }

                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp30).apply {
                    setMargins(dp12, 0, dp12, 0)
                }
                setPadding(dp12, 0, dp12, 0)

                setOnClickListener { showWifiDialog(workplace) }
            }

            val btnDelete = TextView(requireContext()).apply {
                text = "X"
                setTextColor(ContextCompat.getColor(context, R.color.sunset_text_main))
                textSize = 14f
                try { typeface = ResourcesCompat.getFont(context, fontBoldResId) } catch(e: Exception){}

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
                    val dialog = MaterialAlertDialogBuilder(requireContext())
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
                        val redColor = ContextCompat.getColor(requireContext(), R.color.warning_red)
                        val cremeColor = ContextCompat.getColor(requireContext(), R.color.sunset_creme)
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
        val typedValueOrange = android.util.TypedValue()
        requireContext().theme.resolveAttribute(com.google.android.material.R.attr.colorPrimary, typedValueOrange, true)
        val orangeColor = typedValueOrange.data

        val builder = MaterialAlertDialogBuilder(requireContext())
        builder.setTitle("WI-FI FÖR ${workplace.name.uppercase()}")

        val input = EditText(requireContext()).apply {
            setText(workplace.ssids.joinToString(", "))
            
            val typedValueColor = android.util.TypedValue()
            requireContext().theme.resolveAttribute(R.attr.spinnerTextColor, typedValueColor, true)
            setTextColor(typedValueColor.data)

            hint = "T.ex. Kontoret-GUEST, Butiken-WiFi"
            setHintTextColor(ContextCompat.getColor(context, R.color.modern_text_secondary))
            textSize = 14f
            backgroundTintList = ColorStateList.valueOf(orangeColor)
            
            val typedValue = android.util.TypedValue()
            requireContext().theme.resolveAttribute(R.attr.fontRegular, typedValue, true)
            try { typeface = ResourcesCompat.getFont(context, typedValue.resourceId) } catch(e: Exception){}
        }

        val container = LinearLayout(requireContext()).apply {
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
        val prefs = requireContext().getSharedPreferences("arbetslogg_prefs", Context.MODE_PRIVATE)
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
        requireContext().getSharedPreferences("arbetslogg_prefs", Context.MODE_PRIVATE)
            .edit()
            .putString("workplaces_structure", serialized)
            .apply()

        val allSsids = workplaces.flatMap { it.ssids }.joinToString(",")
        repository.setTargetSsid(allSsids)
    }

    private fun checkAutoRevokeStatus(card: View) {
        val future = PackageManagerCompat.getUnusedAppRestrictionsStatus(requireContext())
        Futures.addCallback(future, object : FutureCallback<Int> {
            override fun onSuccess(status: Int?) {
                // Om statusen inte är DISABLED betyder det att funktionen är aktiv (eller stöds)
                val isRestrictionActive = status != null && 
                                         status != UnusedAppRestrictionsConstants.DISABLED && 
                                         status != UnusedAppRestrictionsConstants.ERROR
                
                requireActivity().runOnUiThread {
                    card.visibility = if (isRestrictionActive) View.VISIBLE else View.GONE
                }
            }

            override fun onFailure(t: Throwable) {
                requireActivity().runOnUiThread {
                    card.visibility = View.GONE
                }
            }
        }, ContextCompat.getMainExecutor(requireContext()))
    }

    private fun confirmImportDatabase(uri: android.net.Uri) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Importera backup?")
            .setMessage("Varning: Detta kommer att ersätta all din nuvarande historik med innehållet i backup-filen. Detta går inte att ångra.")
            .setPositiveButton("IMPORTERA") { _, _ ->
                importDatabaseFromUri(uri)
            }
            .setNegativeButton("AVBRYT", null)
            .show()
    }

    private fun importDatabaseFromUri(uri: android.net.Uri) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                // Stäng databasen först för säkerhets skull
                com.dinatid.arbetslogg.AppDatabase.getDatabase(requireContext()).close()
                
                val dbFile = requireContext().getDatabasePath("work_log_database")
                requireContext().contentResolver.openInputStream(uri)?.use { inputStream ->
                    dbFile.outputStream().use { outputStream ->
                        inputStream.copyTo(outputStream)
                    }
                }
                
                withContext(Dispatchers.Main) {
                    Toast.makeText(requireContext(), "Backup inläst! Startar om appen...", Toast.LENGTH_LONG).show()
                    delay(1500)
                    requireActivity().recreate()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Log.e("ImportError", "Fel vid import", e)
                    Toast.makeText(requireContext(), "Fel vid import: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun saveDatabaseToUri(uri: android.net.Uri) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val dbFile = requireContext().getDatabasePath("work_log_database")
                if (!dbFile.exists()) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(requireContext(), "Ingen data att säkerhetskopiera!", Toast.LENGTH_SHORT).show()
                    }
                    return@launch
                }

                requireContext().contentResolver.openOutputStream(uri)?.use { outputStream ->
                    dbFile.inputStream().use { inputStream ->
                        inputStream.copyTo(outputStream)
                    }
                }
                
                withContext(Dispatchers.Main) {
                    Toast.makeText(requireContext(), "Backup sparad! ✅", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Log.e("BackupError", "Fel vid backup", e)
                    Toast.makeText(requireContext(), "Fel vid backup: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
}