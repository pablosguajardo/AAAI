package com.aaai.app.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.aaai.app.R
import com.aaai.app.data.GithubRepo

class GithubRepoAdapter(
    private val onRepoClick: (GithubRepo) -> Unit,
    private val onActionClick: ((GithubRepo) -> Unit)? = null
) : ListAdapter<GithubRepo, GithubRepoAdapter.ViewHolder>(DIFF) {

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<GithubRepo>() {
            override fun areItemsTheSame(a: GithubRepo, b: GithubRepo) = a.id == b.id
            override fun areContentsTheSame(a: GithubRepo, b: GithubRepo) = a == b
        }
        private const val MAX_TOPICS = 3
    }

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val icon: ImageView        = view.findViewById(R.id.ivRepoIcon)
        val name: TextView         = view.findViewById(R.id.tvRepoName)
        val badge: TextView        = view.findViewById(R.id.tvGroupBadge)
        val stars: TextView        = view.findViewById(R.id.tvStars)
        val lastTag: TextView      = view.findViewById(R.id.tvLastTag)
        val description: TextView  = view.findViewById(R.id.tvRepoDescription)
        val topicsGroup: ChipGroup = view.findViewById(R.id.chipGroupTopics)
        val btnAction: Button      = view.findViewById(R.id.btnRepoAction)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_github_repo, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val repo = getItem(position)
        val ctx = holder.itemView.context

        holder.name.text = repo.displayName

        // Badge: Verified > AA > Android
        when {
            repo.isVerified -> {
                holder.badge.visibility = View.VISIBLE
                holder.badge.text = "Android Auto Verified"
                holder.badge.backgroundTintList =
                    ContextCompat.getColorStateList(ctx, android.R.color.holo_green_dark)
                holder.badge.setBackgroundResource(R.drawable.badge_android_auto)
            }
            repo.isAndroidAuto -> {
                holder.badge.visibility = View.VISIBLE
                holder.badge.text = "Android Auto "
                holder.badge.backgroundTintList =
                    ContextCompat.getColorStateList(ctx, android.R.color.holo_orange_dark)
                holder.badge.setBackgroundResource(R.drawable.badge_android_auto)
            }
            else -> {
                //holder.badge.visibility = View.GONE
                holder.badge.visibility = View.VISIBLE
                holder.badge.text = "NOT FOR Android Auto "
                holder.badge.backgroundTintList =
                    ContextCompat.getColorStateList(ctx, android.R.color.holo_red_dark)
                holder.badge.setBackgroundResource(R.drawable.badge_android_auto)
           
            }
        }

        // Stars (file apps show no stars)
        if (repo.isFileSource) {
            holder.stars.text = "📦 Included"
            holder.stars.setTextColor(ContextCompat.getColor(ctx, android.R.color.darker_gray))
        } else {
            holder.stars.text = "⭐ ${repo.stars.formatStars()}"
            holder.stars.setTextColor(0xFFFFD700.toInt())
        }

        // Last tag
        holder.lastTag.text = repo.lastTag ?: ""
        holder.lastTag.visibility = if (repo.lastTag.isNullOrBlank()) View.GONE else View.VISIBLE

        // Description
        if (!repo.description.isNullOrBlank()) {
            holder.description.visibility = View.VISIBLE
            holder.description.text = repo.description
        } else {
            holder.description.visibility = View.GONE
        }

        // Topics chips
        holder.topicsGroup.removeAllViews()
        repo.topics.take(MAX_TOPICS).forEach { topic ->
            val chip = Chip(ctx).apply {
                text = topic
                textSize = 10f
                isClickable = false
                isFocusable = false
                chipMinHeight = 24f
                chipStartPadding = 6f
                chipEndPadding = 6f
            }
            holder.topicsGroup.addView(chip)
        }

        // Icon
        if (!repo.avatarUrl.isNullOrBlank()) {
            Glide.with(ctx).load(repo.avatarUrl)
                .placeholder(android.R.drawable.sym_def_app_icon)
                .error(android.R.drawable.sym_def_app_icon)
                .into(holder.icon)
        } else {
            holder.icon.setImageResource(android.R.drawable.sym_def_app_icon)
        }

        // Action button (only shown when onActionClick is provided = main list mode)
        if (onActionClick != null) {
            holder.btnAction.visibility = View.VISIBLE
            bindActionButton(holder.btnAction, repo, ctx)
            holder.btnAction.setOnClickListener { onActionClick.invoke(repo) }
        } else {
            holder.btnAction.visibility = View.GONE
        }

        holder.itemView.setOnClickListener { onRepoClick(repo) }
    }

    private fun bindActionButton(btn: Button, repo: GithubRepo, ctx: android.content.Context) {
        when {
            repo.signatureMismatch -> {
                btn.text = "Reinstall"
                btn.backgroundTintList =
                    ContextCompat.getColorStateList(ctx, android.R.color.holo_orange_dark)
            }
            repo.hasUpdate -> {
                btn.text = "Update"
                btn.backgroundTintList =
                    ContextCompat.getColorStateList(ctx, android.R.color.holo_blue_dark)
            }
            repo.isInstalled -> {
                btn.text = "Open"
                btn.backgroundTintList =
                    ContextCompat.getColorStateList(ctx, android.R.color.holo_green_dark)
            }
            else -> {
                btn.text = "Install"
                btn.backgroundTintList = null  // default blue from theme
            }
        }
    }

    private fun Int.formatStars(): String = when {
        this >= 1_000 -> "${"%.1f".format(this / 1_000.0)}k"
        else -> this.toString()
    }
}
