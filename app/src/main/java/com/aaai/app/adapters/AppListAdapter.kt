package com.aaai.app.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.signature.ObjectKey
import com.aaai.app.utils.IconCache
import com.aaai.app.R
import com.aaai.app.data.AppMetadata
import com.aaai.app.data.AppSourceType

class AppListAdapter(
    private val onInstallClick: (AppMetadata) -> Unit,
    private val onOpenClick: (AppMetadata) -> Unit
) : RecyclerView.Adapter<AppListAdapter.ViewHolder>() {

    private val items = mutableListOf<AppMetadata>()

    fun submitList(list: List<AppMetadata>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_app, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount() = items.size

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val ivIcon: ImageView = view.findViewById(R.id.ivIcon)
        private val tvAppName: TextView = view.findViewById(R.id.tvAppName)
        private val tvVersion: TextView = view.findViewById(R.id.tvVersion)
        private val tvCategory: TextView = view.findViewById(R.id.tvCategory)
        private val tvStatus: TextView = view.findViewById(R.id.tvStatus)
        private val btnAction: Button = view.findViewById(R.id.btnAction)

        fun bind(app: AppMetadata) {
            tvAppName.text = app.appName
            // FILE apps: catalog version. GITHUB_RELEASE apps: nothing before installing,
            // then the version that was actually installed.
            val versionText = when {
                app.sourceType != AppSourceType.GITHUB_RELEASE -> app.version.takeIf { it.isNotEmpty() }
                app.isInstalled -> app.installedVersion?.takeIf { it.isNotEmpty() }
                else -> null
            }
            if (versionText != null) {
                tvVersion.text = if (versionText.startsWith("v", ignoreCase = true)) versionText else "v$versionText"
                tvVersion.visibility = View.VISIBLE
            } else {
                tvVersion.visibility = View.GONE
            }
            tvCategory.text = app.category.name

            val defaultIcon = android.R.drawable.sym_def_app_icon
            if (app.iconUrl.isNotEmpty()) {
                // Icon is served from Glide's disk cache for 30 days; after that it is fetched again
                // (new signature) and replaced. If the refresh fails, the previous cached copy is
                // shown, and if there is none, the default Android icon.
                val ctx = itemView.context
                val stamp = IconCache.stampFor(ctx, app.iconUrl)
                var request = Glide.with(ctx)
                    .load(app.iconUrl)
                    .signature(ObjectKey(stamp.current))
                    .placeholder(defaultIcon)
                    .error(defaultIcon)
                stamp.previous?.let { prev ->
                    request = request.error(
                        Glide.with(ctx)
                            .load(app.iconUrl)
                            .signature(ObjectKey(prev))
                            .onlyRetrieveFromCache(true)
                            .error(defaultIcon)
                    )
                }
                request.into(ivIcon)
            } else {
                // No icon URL (also resets recycled views)
                Glide.with(itemView.context).clear(ivIcon)
                ivIcon.setImageResource(defaultIcon)
            }

            if (app.isInstalled) {
                tvStatus.visibility = View.VISIBLE
                tvStatus.text = if (app.hasUpdate) "Update available" else "Installed"
                tvStatus.setTextColor(if (app.hasUpdate) 0xFFFF9800.toInt() else 0xFF4CAF50.toInt())
                btnAction.text = if (app.hasUpdate) "Update" else "Open"
            } else {
                tvStatus.visibility = View.GONE
                btnAction.text = "Install"
            }

            btnAction.setOnClickListener {
                // Installed and up to date -> "Open"; otherwise install / update
                if (app.isInstalled && !app.hasUpdate) onOpenClick(app) else onInstallClick(app)
            }
        }
    }
}
