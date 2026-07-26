package com.quandzv23.gamespace

import android.content.Context
import android.content.pm.PackageManager
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.RecyclerView

class GameAdapter(
    private val context: Context,
    private val packageManager: PackageManager,
    private var packages: List<String>,
    private val onRemove: (String) -> Unit
) : RecyclerView.Adapter<GameAdapter.GameViewHolder>() {

    class GameViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val icon: ImageView = view.findViewById(R.id.app_icon)
        val name: TextView = view.findViewById(R.id.app_name)
        val playBtn: TextView = view.findViewById(R.id.btn_play)
        val removeBtn: TextView = view.findViewById(R.id.btn_remove)
    }

    fun submit(newPackages: List<String>) {
        packages = newPackages
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): GameViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_game, parent, false)
        return GameViewHolder(view)
    }

    override fun onBindViewHolder(holder: GameViewHolder, position: Int) {
        val pkg = packages[position]
        try {
            val appInfo = packageManager.getApplicationInfo(pkg, 0)
            holder.icon.setImageDrawable(packageManager.getApplicationIcon(appInfo))
            holder.name.text = packageManager.getApplicationLabel(appInfo).toString()
        } catch (e: PackageManager.NameNotFoundException) {
            holder.icon.setImageDrawable(null)
            holder.name.text = pkg
        }
        holder.removeBtn.setOnClickListener { onRemove(pkg) }
        holder.playBtn.setOnClickListener {
            val launchIntent = packageManager.getLaunchIntentForPackage(pkg)
            if (launchIntent != null) {
                context.startActivity(launchIntent)
            } else {
                Toast.makeText(context, "Không mở được app này", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun getItemCount(): Int = packages.size
}
