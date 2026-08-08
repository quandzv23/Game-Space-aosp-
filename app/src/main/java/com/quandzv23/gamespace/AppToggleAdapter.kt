package com.quandzv23.gamespace

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.widget.SwitchCompat
import androidx.recyclerview.widget.DiffUtil
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

    /**
     * Dùng DiffUtil thay vì notifyDataSetChanged() thô — chỉ cập nhật ĐÚNG những dòng thực
     * sự thay đổi (thêm/bớt 1 app), thay vì rebind toàn bộ danh sách đang hiển thị. Đây là
     * nguyên nhân gốc của bug "thêm app xong bấm thoát bị mất app": notifyDataSetChanged()
     * buộc RecyclerView tái sử dụng lại TẤT CẢ view đang hiện trên màn hình ngay lập tức,
     * kể cả những dòng chẳng liên quan gì tới thao tác vừa bấm — nếu người dùng chạm/thả tay
     * đúng lúc đó, sự kiện chạm có thể rơi vào 1 view vừa bị đổi nội dung sang app khác.
     * DiffUtil chỉ động vào đúng vị trí thay đổi, các dòng khác giữ nguyên view cũ, loại bỏ
     * hẳn khả năng này.
     */
    fun submit(newApps: List<ApplicationInfo>) {
        val oldApps = apps
        val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize() = oldApps.size
            override fun getNewListSize() = newApps.size
            override fun areItemsTheSame(oldPos: Int, newPos: Int) =
                oldApps[oldPos].packageName == newApps[newPos].packageName
            override fun areContentsTheSame(oldPos: Int, newPos: Int) =
                oldApps[oldPos].packageName == newApps[newPos].packageName &&
                    isOn(oldApps[oldPos].packageName) == isOn(newApps[newPos].packageName)
        })
        apps = newApps
        diff.dispatchUpdatesTo(this)
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
