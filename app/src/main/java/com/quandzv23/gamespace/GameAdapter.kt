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
    private var cardMode: Boolean = false,
    private val onRemove: (String) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    class ListViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val icon: ImageView = view.findViewById(R.id.app_icon)
        val name: TextView = view.findViewById(R.id.app_name)
        val playBtn: TextView = view.findViewById(R.id.btn_play)
        val removeBtn: TextView = view.findViewById(R.id.btn_remove)
    }

    class CardViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val icon: ImageView = view.findViewById(R.id.card_app_icon)
        val name: TextView = view.findViewById(R.id.card_app_name)
        val playBtn: TextView = view.findViewById(R.id.card_btn_play)
        val removeBtn: TextView = view.findViewById(R.id.card_btn_remove)
    }

    fun submit(newPackages: List<String>) {
        packages = newPackages
        notifyDataSetChanged()
    }

    fun setCardMode(enabled: Boolean) {
        cardMode = enabled
        notifyDataSetChanged()
    }

    override fun getItemViewType(position: Int): Int = if (cardMode) 1 else 0

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return if (viewType == 1) {
            CardViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.item_game_card, parent, false))
        } else {
            ListViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.item_game, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val pkg = packages[position]
        var label: String = pkg
        var icon: android.graphics.drawable.Drawable? = null 
        try {
            val appInfo = packageManager.getApplicationInfo(pkg, 0)
            icon = packageManager.getApplicationIcon(appInfo)
            label = packageManager.getApplicationLabel(appInfo).toString()
        } catch (e: PackageManager.NameNotFoundException) {
            
        }

        val launchAction = {
            val launchIntent = packageManager.getLaunchIntentForPackage(pkg)
            if (launchIntent != null) context.startActivity(launchIntent)
            else Toast.makeText(context, "Không mở được app này", Toast.LENGTH_SHORT).show()
        }

        when (holder) {
            is ListViewHolder -> {
                holder.icon.setImageDrawable(icon)
                holder.name.text = label
                holder.removeBtn.setOnClickListener { onRemove(pkg) }
                holder.playBtn.setOnClickListener { launchAction() }
            }
            is CardViewHolder -> {
                holder.icon.setImageDrawable(icon)
                holder.name.text = label
                holder.removeBtn.setOnClickListener { onRemove(pkg) }
                holder.playBtn.setOnClickListener { launchAction() }
            }
        }
    }

    override fun getItemCount(): Int = packages.size
}
