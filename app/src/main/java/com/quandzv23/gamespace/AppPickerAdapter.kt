package com.quandzv23.gamespace

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class AppPickerAdapter(
    private val packageManager: PackageManager,
    private val allApps: List<ApplicationInfo>,
    private val onPick: (ApplicationInfo) -> Unit
) : RecyclerView.Adapter<AppPickerAdapter.ViewHolder>() {

    private var filtered: List<ApplicationInfo> = allApps

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val icon: ImageView = view.findViewById(R.id.picker_item_icon)
        val name: TextView = view.findViewById(R.id.picker_item_name)
        val pkg: TextView = view.findViewById(R.id.picker_item_package)
    }

    fun filter(query: String) {
        filtered = if (query.isBlank()) {
            allApps
        } else {
            allApps.filter {
                packageManager.getApplicationLabel(it).toString()
                    .contains(query, ignoreCase = true) ||
                    it.packageName.contains(query, ignoreCase = true)
            }
        }
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_app_picker, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val app = filtered[position]
        holder.icon.setImageDrawable(packageManager.getApplicationIcon(app))
        holder.name.text = packageManager.getApplicationLabel(app).toString()
        holder.pkg.text = app.packageName
        holder.itemView.setOnClickListener { onPick(app) }
    }

    override fun getItemCount(): Int = filtered.size
}
