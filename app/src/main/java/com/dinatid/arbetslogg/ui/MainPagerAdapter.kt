package com.dinatid.arbetslogg.ui

import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter

class MainPagerAdapter(activity: AppCompatActivity) : FragmentStateAdapter(activity) {

    // Vi har 4 flikar totalt i bottenmenyn
    override fun getItemCount(): Int = 4

    // Här bestämmer vi vilket "rum" (Fragment) som visas när man swipar
    override fun createFragment(position: Int): Fragment {
        return when (position) {
            0 -> DashboardFragment() // Idag-vyn
            1 -> CalendarFragment()   // Kalendern
            2 -> ReportFragment()    // Rapporter
            3 -> SettingsFragment()  // Inställningar
            else -> DashboardFragment()
        }
    }
}