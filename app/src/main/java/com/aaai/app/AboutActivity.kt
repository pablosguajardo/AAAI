package com.aaai.app

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.MenuItem
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar

/**
 * About screen: project link, disclaimer and app responsibility notice.
 * The third-party repo list was removed — each app's repo link is accessible from the main list.
 */
class AboutActivity : AppCompatActivity() {

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
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) { finish(); return true }
        return super.onOptionsItemSelected(item)
    }

    private fun openUrl(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, "No app found to open the link", Toast.LENGTH_SHORT).show()
        }
    }
}
