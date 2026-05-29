package com.dinatid.arbetslogg.ui

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.widget.SwitchCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import com.dinatid.arbetslogg.R
import com.dinatid.arbetslogg.data.AppEvent
import com.dinatid.arbetslogg.data.TimeRepository
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class DashboardFragment : Fragment() {

    private val viewModel: MainViewModel by activityViewModels()

    private var selectedDateOffset = 0
    private var wifiCountdownSeconds = -1

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_dashboard, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val btnPrevDay = view.findViewById<ImageView>(R.id.btnPrevDay)
        val btnNextDay = view.findViewById<ImageView>(R.id.btnNextDay)
        val txtCurrentDate = view.findViewById<TextView>(R.id.txtCurrentDate)

        val txtCenterTime = view.findViewById<TextView>(R.id.txtCenterTime)
        val txtCurrentSsid = view.findViewById<TextView>(R.id.txtCurrentSsid)
        val txtCurrentWorkplace = view.findViewById<TextView>(R.id.txtCurrentWorkplace)

        val txtInTimeCircle = view.findViewById<TextView>(R.id.txtInTimeCircle)
        val txtCountdownLabel = view.findViewById<TextView>(R.id.txtCountdownLabel)

        val imgSpinningSnake = view.findViewById<ImageView>(R.id.imgSpinningSnake)

        val txtStatusMessage = view.findViewById<TextView>(R.id.txtStatusMessage)
        val switchManualToggle = view.findViewById<SwitchCompat>(R.id.switchManualToggle)
        val txtToggleStatus = view.findViewById<TextView>(R.id.txtToggleStatus)

        val lblMonthTitle = view.findViewById<TextView>(R.id.lblMonthTitle)
        val txtMonthBalance = view.findViewById<TextView>(R.id.txtMonthBalance)
        val progressActualMonth = view.findViewById<ProgressBar>(R.id.progressActualMonth)
        val monthCard = view.findViewById<View>(R.id.monthCard)
        val goalLeftSpace = view.findViewById<View>(R.id.goalLeftSpace)
        val goalRightSpace = view.findViewById<View>(R.id.goalRightSpace)

        val cardPermissionWarning = view.findViewById<View>(R.id.cardPermissionWarning)
        val txtPermissionWarningMsg = view.findViewById<TextView>(R.id.txtPermissionWarningMsg)
        val btnFixPermissions = view.findViewById<Button>(R.id.btnFixPermissions)
        
        val clockCircle = view.findViewById<View>(R.id.clockCircle)

        // TEMA-ANPASSAD ORM (SNAKE)
        val repo = TimeRepository.getInstance(requireActivity().applicationContext)
        val snakeColor = if (repo.getAppTheme() == 1) {
            ContextCompat.getColor(requireContext(), R.color.modern_accent_orange)
        } else {
            Color.parseColor("#B72B08") // Classic mörkorange
        }
        val snakeDrawable = FadeTailDrawable(snakeColor)
        imgSpinningSnake.setImageDrawable(snakeDrawable)
        
        if (repo.getAppTheme() == 1) {
            clockCircle.setBackgroundResource(R.drawable.bg_clock_ring_modern)
        } else {
            clockCircle.setBackgroundResource(R.drawable.bg_clock_ring)
        }

        ObjectAnimator.ofFloat(imgSpinningSnake, "rotation", 0f, 360f).apply {
            duration = 60000
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            start()
        }

        btnFixPermissions.setOnClickListener {
            val intent = Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = android.net.Uri.fromParts("package", requireContext().packageName, null)
            }
            startActivity(intent)
        }

        btnPrevDay.setOnClickListener {
            selectedDateOffset--
            viewModel.refreshData(selectedDateOffset, wifiCountdownSeconds)
        }

        btnNextDay.setOnClickListener {
            if (selectedDateOffset < 0) {
                selectedDateOffset++
                viewModel.refreshData(selectedDateOffset, wifiCountdownSeconds)
            }
        }

        switchManualToggle.setOnClickListener {
            val prefsShared = requireContext().getSharedPreferences("arbetslogg_prefs", Context.MODE_PRIVATE)
            val savedData = prefsShared.getString("workplaces_structure", "") ?: ""

            if (savedData.isEmpty()) {
                switchManualToggle.isChecked = false
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle(getString(R.string.error_no_workplace_title))
                    .setMessage(getString(R.string.error_no_workplace_msg))
                    .setPositiveButton("OK") { dialog, _ -> dialog.dismiss() }
                    .show()
                return@setOnClickListener
            }

            val isCheckingIn = switchManualToggle.isChecked

            if (isCheckingIn) {
                val workplaceNames = mutableListOf<String>()
                if (savedData.isNotEmpty()) {
                    savedData.split("|").forEach { block ->
                        val parts = block.split(":")
                        if (parts.size == 2 && parts[0].isNotEmpty()) {
                            workplaceNames.add(parts[0])
                        }
                    }
                }
                workplaceNames.add("Övrigt / Hemifrån")

                val items = workplaceNames.toTypedArray()
                var selectedWorkplace = items[0]

                MaterialAlertDialogBuilder(requireContext())
                    .setTitle("Välj arbetsplats / kund")
                    .setSingleChoiceItems(items, 0) { _, which ->
                        selectedWorkplace = items[which]
                    }
                    .setPositiveButton("CHECKA IN") { dialog, _ ->
                        val finalChoice = if (selectedWorkplace == "Övrigt / Hemifrån") "Övrigt" else selectedWorkplace
                        viewModel.handleManualToggle(finalChoice)
                        viewModel.refreshData(selectedDateOffset, wifiCountdownSeconds)
                        dialog.dismiss()
                    }
                    .setNegativeButton("AVBRYT") { dialog, _ ->
                        switchManualToggle.isChecked = false
                        dialog.dismiss()
                    }
                    .setOnCancelListener { switchManualToggle.isChecked = false }
                    .show()

            } else {
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle("Checka ut?")
                    .setMessage("Vill du stämpla ut manuellt?")
                    .setPositiveButton("JA") { dialog, _ ->
                        viewModel.handleManualToggle(null)
                        viewModel.refreshData(selectedDateOffset, wifiCountdownSeconds)
                        dialog.dismiss()
                    }
                    .setNegativeButton("NEJ") { dialog, _ ->
                        switchManualToggle.isChecked = true
                        dialog.dismiss()
                    }
                    .setOnCancelListener { switchManualToggle.isChecked = true }
                    .show()
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            repo.events.collect { event ->
                when (event) {
                    is AppEvent.RefreshData -> {
                        viewModel.refreshData(selectedDateOffset, wifiCountdownSeconds)
                    }
                    is AppEvent.CountdownUpdate -> {
                        wifiCountdownSeconds = event.secondsLeft
                        viewModel.refreshData(selectedDateOffset, wifiCountdownSeconds)
                    }
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.uiState.collectLatest { state ->
                val typedValue = android.util.TypedValue()
                
                requireContext().theme.resolveAttribute(R.attr.titleTextColor, typedValue, true)
                val titleColor = typedValue.data
                
                requireContext().theme.resolveAttribute(R.attr.arrowTintColor, typedValue, true)
                val arrowColor = typedValue.data
                
                requireContext().theme.resolveAttribute(R.attr.secondaryTextColorCustom, typedValue, true)
                val secondaryTextColor = typedValue.data

                txtCurrentDate.text = state.currentDate
                txtCurrentDate.setTextColor(titleColor)
                btnPrevDay.imageTintList = ColorStateList.valueOf(arrowColor)
                btnNextDay.imageTintList = ColorStateList.valueOf(arrowColor)
                btnNextDay.isClickable = state.isNextDayClickable
                btnNextDay.alpha = state.nextDayAlpha

                txtCenterTime.text = state.centerTime
                txtCenterTime.setTextColor(state.centerTimeColor)

                txtCountdownLabel.visibility = if (state.isCountdownVisible) View.VISIBLE else View.GONE
                txtCountdownLabel.setTextColor(Color.parseColor("#F44336"))

                txtCurrentSsid.text = state.currentSsid
                txtCurrentSsid.setTextColor(secondaryTextColor)

                if (state.currentWorkplace.isNotEmpty()) {
                    txtCurrentWorkplace.text = state.currentWorkplace
                    txtCurrentWorkplace.setTextColor(secondaryTextColor)
                    txtCurrentWorkplace.visibility = View.VISIBLE
                } else {
                    txtCurrentWorkplace.visibility = View.GONE
                }

                txtInTimeCircle.text = state.inTimeCircle
                txtInTimeCircle.setTextColor(secondaryTextColor)
                txtInTimeCircle.visibility = View.VISIBLE

                txtStatusMessage.text = state.statusMessage
                txtStatusMessage.setTextColor(state.statusColor)

                txtToggleStatus.text = state.toggleStatusText

                val prefsShared = requireContext().getSharedPreferences("arbetslogg_prefs", Context.MODE_PRIVATE)
                val savedData = prefsShared.getString("workplaces_structure", "") ?: ""
                val hasWorkplace = savedData.isNotEmpty()

                if (!hasWorkplace) {
                    txtToggleStatus.setTextColor(Color.GRAY)
                    switchManualToggle.alpha = 0.5f
                } else {
                    txtToggleStatus.setTextColor(secondaryTextColor)
                    switchManualToggle.alpha = 1.0f
                }

                switchManualToggle.isChecked = state.isCurrentlyIn

                txtMonthBalance.text = state.monthBalance
                txtMonthBalance.setTextColor(secondaryTextColor)

                progressActualMonth.progress = state.monthProgress
                progressActualMonth.progressTintList = ColorStateList.valueOf(state.monthProgressColor)
                
                // NYTT: Bakgrundsfärg på månads-kortet bör följa temat (colorSurface)
                requireContext().theme.resolveAttribute(com.google.android.material.R.attr.colorSurface, typedValue, true)
                val surfaceColor = typedValue.data
                monthCard.backgroundTintList = ColorStateList.valueOf(surfaceColor)
                
                // Uppdatera typsnitt dynamiskt för monthCard om de inte redan styrs av XML (vissa element skapas/ändras i kod)
                val typedValueFontBold = android.util.TypedValue()
                requireContext().theme.resolveAttribute(R.attr.fontBold, typedValueFontBold, true)
                val fontBold = try { ResourcesCompat.getFont(requireContext(), typedValueFontBold.resourceId) } catch(e: Exception) { null }
                
                lblMonthTitle.typeface = fontBold
                txtMonthBalance.typeface = fontBold
                
                requireContext().theme.resolveAttribute(R.attr.spinnerTextColor, typedValue, true)
                val spinnerTextColorValue = typedValue.data
                
                lblMonthTitle.setTextColor(spinnerTextColorValue)
                txtMonthBalance.setTextColor(spinnerTextColorValue)

                val leftParams = goalLeftSpace.layoutParams as LinearLayout.LayoutParams
                leftParams.weight = state.goalLeftWeight
                goalLeftSpace.layoutParams = leftParams

                val rightParams = goalRightSpace.layoutParams as LinearLayout.LayoutParams
                rightParams.weight = state.goalRightWeight
                goalRightSpace.layoutParams = rightParams

                // --- BEHÖRIGHETSKONTROLL (VISUELL) ---
                if (state.isLocationPermissionMissing || state.isNotificationPermissionMissing) {
                    cardPermissionWarning.visibility = View.VISIBLE
                    val msg = when {
                        state.isLocationPermissionMissing && state.isNotificationPermissionMissing -> getString(R.string.warning_permission_both)
                        state.isLocationPermissionMissing -> getString(R.string.warning_permission_location)
                        else -> getString(R.string.warning_permission_notifications)
                    }
                    txtPermissionWarningMsg.text = msg
                } else {
                    cardPermissionWarning.visibility = View.GONE
                }

                val smartMode = prefsShared.getInt("smart_help_mode", 1)

                if (smartMode == 2) {
                    lblMonthTitle.text = "MÅNADSMÅL (KONSULT)"
                } else {
                    lblMonthTitle.text = "DENNA MÅNADEN"
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        wifiCountdownSeconds = -1
        viewModel.refreshData(selectedDateOffset, wifiCountdownSeconds)
    }

    override fun onPause() {
        super.onPause()
    }
}