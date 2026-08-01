package com.quandzv23.gamespace

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.widget.SwitchCompat
import androidx.recyclerview.widget.RecyclerView

class AppToggleAdapter(
    private val packageManager: PackageManager,
    private var apps: List<ApplicationInfo>,
    private val isOn: (String) -> Boolean,
    private val onToggle: (ApplicationInfo, Boolean) -> Unit
) : RecyclerView.Adapter<AppToggleAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val icon: ImageView = view.findViewById(R.id.toggle_item_icon)
        val name: TextView = view.findViewById(R.id.toggle_item_name)
        val switch: SwitchCompat = view.findViewById(R.id.toggle_item_switch)
    }

    fun submit(newApps: List<ApplicationInfo>) {
        apps = newApps
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_app_toggle, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val app = apps[position]
        holder.icon.setImageDrawable(packageManager.getApplicationIcon(app))
        holder.name.text = packageManager.getApplicationLabel(app).toString()
        holder.switch.setOnCheckedChangeListener(null)
        holder.switch.isChecked = isOn(app.packageName)
        holder.switch.setOnCheckedChangeListener { _, checked -> onToggle(app, checked) }
    }

    override fun getItemCount(): Int = apps.size
}
