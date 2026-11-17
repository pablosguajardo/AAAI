package com.aaai.app

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.MenuItem
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.lifecycle.lifecycleScope
import com.aaai.app.data.isGithubRelease
import com.aaai.app.services.AppMetadataService
import kotlinx.coroutines.launch

/**
 * About screen: project link, disclaimer and a dynamic list of the GitHub repositories
 * of every catalog app whose sourceType is GITHUB_RELEASE.
 */
class AboutActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "AboutActivity"
        private const val GITHUB_BASE = "https://github.com/"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_about)

        val toolbar = findViewById<Toolbar>(R.id.aboutToolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.title = getString(R.string.title_activity_about)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        val projectUrl = getString(R.string.project_url)
        findViewById<TextView>(R.id.tvProjectLink).apply {
            text = projectUrl
            setOnClickListener { openUrl(projectUrl) }
        }

        loadRepos()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    /** Builds the list from the catalog, so new GITHUB_RELEASE apps show up automatically. */
    private fun loadRepos() {
        val container = findViewById<LinearLayout>(R.id.reposContainer)
        lifecycleScope.launch {
            val service = AppMetadataService(this@AboutActivity)
            val repos = try {
                service.initializeAppData()
                service.getAllApps()
                    .filter { it.isGithubRelease() }
                    .mapNotNull { app -> app.githubRepo?.let { repo -> repo to app.appName } }
                    .distinctBy { it.first.lowercase() }
                    .sortedBy { it.first.lowercase() }
            } catch (e: Exception) {
                Log.e(TAG, "Could not load repositories: ${e.message}", e)
                emptyList()
            }

            container.removeAllViews()
            if (repos.isEmpty()) {
                container.addView(buildText(getString(R.string.about_no_repos), false))
                return@launch
            }
            repos.forEach { (repo, appName) ->
                val url = GITHUB_BASE + repo
                container.addView(buildText("$appName\n$repo", true).apply {
                    setOnClickListener { openUrl(url) }
                })
            }
        }
    }

    private fun buildText(text: String, isLink: Boolean): TextView {
        return TextView(this).apply {
            this.text = text
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            minHeight = (48 * resources.displayMetrics.density).toInt()
            gravity = Gravity.CENTER_VERTICAL
            val tv = TypedValue()
            val attr = if (isLink) android.R.attr.textColorLink else android.R.attr.textColorSecondary
            if (theme.resolveAttribute(attr, tv, true)) {
                setTextColor(if (tv.resourceId != 0) getColor(tv.resourceId) else tv.data)
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
    }

    private fun openUrl(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, "No app found to open the link", Toast.LENGTH_SHORT).show()
        }
    }
}
