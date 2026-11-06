package com.rickyboy.appsweep

import android.graphics.drawable.Drawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView

data class RunningApp(
    val packageName: String,
    val label: String,
    val icon: Drawable?,
    val memoryKb: Long,
    val cpuPercent: Float,
    val cacheBytes: Long,          // -1 = unknown
    val isSystem: Boolean,
    val isProtected: Boolean,
    val backgroundBlocked: Boolean
)

class AppAdapter(
    private val onTap: (RunningApp) -> Unit,
    private val onHold: (RunningApp) -> Unit
) : RecyclerView.Adapter<AppAdapter.Holder>() {

    init {
        setHasStableIds(true) // keeps the selected row selected during live updates
    }

    override fun getItemId(position: Int): Long = items[position].packageName.hashCode().toLong()

    var items: List<RunningApp> = emptyList()
        set(value) {
            field = value
            notifyDataSetChanged()
        }

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val icon: ImageView = view.findViewById(R.id.appIcon)
        val name: TextView = view.findViewById(R.id.appName)
        val detail: TextView = view.findViewById(R.id.appDetail)
        val badges: TextView = view.findViewById(R.id.appBadges)
        val action: TextView = view.findViewById(R.id.appAction)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_app, parent, false)
        return Holder(view)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val app = items[position]
        val ctx = holder.itemView.context
        holder.icon.setImageDrawable(app.icon)
        holder.name.text = app.label

        val parts = mutableListOf("${formatKb(app.memoryKb)} RAM")
        parts.add(String.format("%.1f%% CPU", app.cpuPercent))
        if (app.cacheBytes >= 0) parts.add("${formatBytes(app.cacheBytes)} cache")
        holder.detail.text = parts.joinToString("  ·  ")

        val badges = mutableListOf<String>()
        if (app.isSystem) badges.add("SYSTEM")
        if (app.isProtected) badges.add("PROTECTED")
        if (app.backgroundBlocked) badges.add("BACKGROUND BLOCKED")
        if (app.cpuPercent >= 10f) badges.add("HIGH CPU")
        holder.badges.visibility = if (badges.isEmpty()) View.GONE else View.VISIBLE
        holder.badges.text = badges.joinToString("  ")

        if (app.isProtected) {
            holder.action.text = "🔒" // lock
            holder.action.setTextColor(ContextCompat.getColor(ctx, R.color.neon_cyan))
        } else {
            holder.action.text = "✕" // X
            holder.action.setTextColor(ContextCompat.getColor(ctx, R.color.neon_pink))
        }
        holder.itemView.setOnClickListener { onTap(app) }
        holder.itemView.setOnLongClickListener { onHold(app); true }
    }

    companion object {
        fun formatKb(kb: Long): String = formatBytes(kb * 1024)

        fun formatBytes(bytes: Long): String = when {
            bytes >= 1024L * 1024 * 1024 -> String.format("%.1f GB", bytes / 1024.0 / 1024.0 / 1024.0)
            bytes >= 1024L * 1024 -> "${bytes / 1024 / 1024} MB"
            bytes >= 1024 -> "${bytes / 1024} KB"
            else -> "$bytes B"
        }
    }
}
