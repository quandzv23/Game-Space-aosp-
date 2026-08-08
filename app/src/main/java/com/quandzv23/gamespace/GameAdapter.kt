package com.quandzv23.gamespace

import android.content.Context
import android.content.pm.PackageManager
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView

class GameAdapter(
    private val context: Context,
    private val packageManager: PackageManager,
    private var packages: List<String>,
    private val onRemove: (String) -> Unit,
    private val onSelect: (String) -> Unit
) : RecyclerView.Adapter<GameAdapter.ListViewHolder>() {

    class ListViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val icon: ImageView = view.findViewById(R.id.app_icon)
        val name: TextView = view.findViewById(R.id.app_name)
        val removeBtn: TextView = view.findViewById(R.id.btn_remove)
    }

    /** DiffUtil thay vì notifyDataSetChanged() thô — chỉ động vào đúng dòng thay đổi. */
    fun submit(newPackages: List<String>) {
        val oldPackages = packages
        val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize() = oldPackages.size
            override fun getNewListSize() = newPackages.size
            override fun areItemsTheSame(oldPos: Int, newPos: Int) =
                oldPackages[oldPos] == newPackages[newPos]
            override fun areContentsTheSame(oldPos: Int, newPos: Int) =
                oldPackages[oldPos] == newPackages[newPos]
        })
        packages = newPackages
        diff.dispatchUpdatesTo(this)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ListViewHolder {
        return ListViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.item_game, parent, false))
    }

    override fun onBindViewHolder(holder: ListViewHolder, position: Int) {
        val pkg = packages[position]
        var label: String = pkg
        var icon: android.graphics.drawable.Drawable? = null
        try {
            val appInfo = packageManager.getApplicationInfo(pkg, 0)
            icon = packageManager.getApplicationIcon(appInfo)
            label = packageManager.getApplicationLabel(appInfo).toString()
        } catch (e: PackageManager.NameNotFoundException) {
        }

        holder.icon.setImageDrawable(icon)
        holder.name.text = label
        holder.removeBtn.setOnClickListener { onRemove(pkg) }
        holder.itemView.setOnClickListener { onSelect(pkg) }
    }

    override fun getItemCount(): Int = packages.size
}
