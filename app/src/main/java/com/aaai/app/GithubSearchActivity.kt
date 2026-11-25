package com.aaai.app

import android.app.Activity
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.google.android.material.chip.Chip
import com.aaai.app.adapters.GithubRepoAdapter
import com.aaai.app.auto.AutoUpdateNotifier
import com.aaai.app.autoconv.AutoMode
import com.aaai.app.data.GithubRepo
import com.aaai.app.data.GithubRepoDbHelper
import com.aaai.app.services.DownloadAndInstallService
import com.aaai.app.services.GitHubReleaseService
import com.aaai.app.services.UpdateChecker
import com.aaai.app.utils.InstalledVersionTracker
import com.aaai.app.utils.NetworkErrors
import com.aaai.app.utils.NotificationPermissionHelper
import com.aaai.app.utils.PackageNaming
import com.startapp.sdk.adsbase.StartAppSDK
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class GithubSearchActivity : AppCompatActivity() {

    // -------------------------------------------------------------------------
    // Views
    // -------------------------------------------------------------------------
    private lateinit var etSearch: EditText
    private lateinit var btnClearSearch: ImageButton
    private lateinit var chipVerified: Chip
    private lateinit var chipAndroidAuto: Chip
    private lateinit var chipAndroid: Chip
    private lateinit var spSort: Spinner
    private lateinit var spTopic: Spinner
    private lateinit var tvDisclaimer: TextView
    private lateinit var tvResultCount: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var tvEmpty: TextView
    private lateinit var recyclerView: RecyclerView
    private lateinit var btnLoadMore: Button
    // Manual install panel
    private lateinit var manualInstallHeader: LinearLayout
    private lateinit var tvManualToggle: TextView
    private lateinit var manualInstallContent: LinearLayout
    private lateinit var etApkUrl: EditText
    private lateinit var btnInstallUrl: Button
    private lateinit var spAutoMode: Spinner
    private lateinit var btnPickApk: Button
    private lateinit var cbManualRename: android.widget.CheckBox

    // -------------------------------------------------------------------------
    // State
    // -------------------------------------------------------------------------
    private lateinit var dbHelper: GithubRepoDbHelper
    private var db: SQLiteDatabase? = null
    private lateinit var adapter: GithubRepoAdapter
    private lateinit var downloadService: DownloadAndInstallService

    private var selectedGroup: String = GROUP_VERIFIED
    private var selectedTopicId: Long? = null
    private var sortBy: String = SORT_STARS
    private var currentPage = 0
    private var totalCount = 0
    private var allTopics: List<Pair<Long, String>> = emptyList()
    private var dbReady = false
    private var searchJob: Job? = null

    // Install state cache — invalidated on package changes and onResume
    private var cachedInstalledPkgs: Set<String>? = null
    private var cachedOwnCert: String? = null

    // Install state
    private var installDialog: AlertDialog? = null
    private var installDialogMessage: TextView? = null
    private var installDialogProgress: ProgressBar? = null
    private var currentInstallRepo: GithubRepo? = null
    private var currentInstallTag: String? = null
    private var githubBusy = false
    private var versionsDialog: AlertDialog? = null

    private val pickApkLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) startInstallFromFile(uri)
    }

    // Android 13+: request POST_NOTIFICATIONS (used from the menu item)
    private val requestNotificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            NotificationPermissionHelper.markAsked(this)
        }

    // Registered dynamically (not in the manifest) so it only receives while the app is alive —
    // a manifest-declared receiver for this action is silently dropped by Android when the app
    // is not running ("Background execution not allowed").
    private var carConnectionReceiverRegistered = false
    private val carConnectionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            Log.d(TAG, "CAR_CONNECTION_UPDATED received")
            checkAutoUpdatesIfConnected()
        }
    }

    companion object {
        private const val TAG = "com.aaai.app-GithubSearchActivity"
        const val GROUP_VERIFIED = "verified"
        const val GROUP_AUTO = "android-auto"
        const val GROUP_ANDROID = "android"
        private const val SORT_STARS = "stars"
        private const val SORT_NEWEST = "newest"
        private const val SEARCH_DEBOUNCE_MS = 400L
        private const val MIN_TOPIC_COUNT_VERIFIED = 1
        private const val MIN_TOPIC_COUNT_AUTO     = 6
        private const val MIN_TOPIC_COUNT_ANDROID  = 8
        // Computed based on selected group
        private const val MIN_TOPIC_COUNT = 1  // fallback, overridden per group
        private const val REQUEST_INSTALL = 2001
    }

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_github_search)

        StartAppSDK.setTestAdsEnabled(BuildConfig.DEBUG)
        StartAppSDK.init(this, "210536031", false)

        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.title = getString(R.string.app_name)

        bindViews()
        downloadService    = DownloadAndInstallService(this)
        dbHelper           = GithubRepoDbHelper(this)

        setupSortSpinner()
        setupGroupChips()
        setupSearchBar()
        setupAdapter()
        setupManualInstallPanel()

        btnLoadMore.setOnClickListener { loadPage(currentPage + 1, append = true) }

        lifecycleScope.launch { openDb() }

        // Register package change receiver to refresh install states
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addDataScheme("package")
        }
        ContextCompat.registerReceiver(this, packageReceiver, filter,
            ContextCompat.RECEIVER_EXPORTED)
    }

    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            invalidateInstallCache()
            refreshInstallStates()
        }
    }

    override fun onResume() {
        super.onResume()
        maybeAskNotificationPermission()
        // Invalidate cache on resume so reinstall/uninstall from outside is reflected
        invalidateInstallCache()
        if (dbReady) refreshInstallStates()
        checkAutoUpdatesIfConnected()
    }

    override fun onStart() {
        super.onStart()
        if (!carConnectionReceiverRegistered) {
            ContextCompat.registerReceiver(
                this,
                carConnectionReceiver,
                IntentFilter("androidx.car.app.connection.action.CAR_CONNECTION_UPDATED"),
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
            carConnectionReceiverRegistered = true
        }
    }

    override fun onStop() {
        if (carConnectionReceiverRegistered) {
            try { unregisterReceiver(carConnectionReceiver) } catch (_: Exception) {}
            carConnectionReceiverRegistered = false
        }
        super.onStop()
    }

    /**
     * If Android Auto is currently connected (checked via its content provider), runs the same
     * update check used by [AutoUpdateNotifier] so a notification appears while the app is open
     * (or right when it's reopened with Auto already connected).
     */
    private fun checkAutoUpdatesIfConnected() {
        lifecycleScope.launch(Dispatchers.IO) {
            val connected = try {
                contentResolver.query(
                    Uri.parse("content://androidx.car.app.connection"),
                    arrayOf("CarConnectionState"), null, null, null
                )?.use { c ->
                    if (c.moveToNext()) c.getInt(c.getColumnIndexOrThrow("CarConnectionState")) == 2 else false
                } ?: false
            } catch (e: Exception) {
                Log.w(TAG, "Could not read car connection state: ${e.message}")
                false
            }
            Log.d(TAG, "Android Auto connected=$connected")
            if (connected) {
                try {
                    AutoUpdateNotifier(this@GithubSearchActivity).checkAndNotify()
                } catch (e: Exception) {
                    Log.e(TAG, "Auto update check failed: ${e.message}", e)
                }
            }
        }
    }

    private fun maybeAskNotificationPermission() {
        if (!NotificationPermissionHelper.shouldAsk(this)) return
        AlertDialog.Builder(this)
            .setTitle("Android Auto alerts")
            .setMessage("Allow AAAI to send notifications so you get update alerts on the Android Auto display while driving.")
            .setPositiveButton("Enable") { _, _ ->
                NotificationPermissionHelper.markAsked(this)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    requestNotificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                }
            }
            .setNegativeButton("Not now") { _, _ -> NotificationPermissionHelper.markAsked(this) }
            .show()
    }

    private fun invalidateInstallCache() {
        cachedInstalledPkgs = null
        cachedOwnCert = null
    }

    override fun onDestroy() {
        try { unregisterReceiver(packageReceiver) } catch (_: Exception) {}
        db?.close()
        super.onDestroy()
    }

    private fun bindViews() {
        etSearch             = findViewById(R.id.etSearch)
        btnClearSearch       = findViewById(R.id.btnClearSearch)
        chipVerified         = findViewById(R.id.chipVerified)
        chipAndroidAuto      = findViewById(R.id.chipAndroidAuto)
        chipAndroid          = findViewById(R.id.chipAndroid)
        spSort               = findViewById(R.id.spSort)
        spTopic              = findViewById(R.id.spTopic)
        tvDisclaimer         = findViewById(R.id.tvDisclaimer)
        tvResultCount        = findViewById(R.id.tvResultCount)
        progressBar          = findViewById(R.id.progressBar)
        tvEmpty              = findViewById(R.id.tvEmpty)
        recyclerView         = findViewById(R.id.recyclerView)
        btnLoadMore          = findViewById(R.id.btnLoadMore)
        manualInstallHeader  = findViewById(R.id.manualInstallHeader)
        tvManualToggle       = findViewById(R.id.tvManualInstallToggle)
        manualInstallContent = findViewById(R.id.manualInstallContent)
        etApkUrl             = findViewById(R.id.etApkUrl)
        btnInstallUrl        = findViewById(R.id.btnInstallUrl)
        spAutoMode           = findViewById(R.id.spAutoMode)
        btnPickApk           = findViewById(R.id.btnPickApk)
        cbManualRename       = findViewById(R.id.cbManualRename)
    }

    // -------------------------------------------------------------------------
    // Menu (same as MainActivity)
    // -------------------------------------------------------------------------

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu, menu)
        menu.findItem(R.id.version)?.title = "v${BuildConfig.VERSION_NAME}"
        menu.findItem(R.id.search_github)?.isVisible = false // we're already here
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.infos        -> { startActivity(Intent(this, AboutActivity::class.java)); true }
            R.id.show_intro   -> { startActivity(Intent(this, SetupActivity::class.java)); true }
            R.id.auto_alerts  -> { handleAutoAlertsMenu(); true }
            R.id.check_update -> { checkForUpdate(); true }
            R.id.clear_apps_cache -> { clearAppsCache(); true }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun handleAutoAlertsMenu() {
        if (!NotificationPermissionHelper.isGranted(this)) {
            NotificationPermissionHelper.markAsked(this)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                requestNotificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            } else {
                Toast.makeText(this, "Notifications are enabled automatically on this Android version.", Toast.LENGTH_LONG).show()
            }
            return
        }
        AlertDialog.Builder(this)
            .setTitle("🔔 Android Auto alerts")
            .setMessage(
                "Notifications are enabled ✓\n\n" +
                "AAAI will notify you on the Android Auto display when app updates are available."
            )
            .setPositiveButton("OK", null)
            .show()
    }

    private fun checkForUpdate() {
        Toast.makeText(this, "Checking for updates...", Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            val info = UpdateChecker(this@GithubSearchActivity).check(manual = true)
            if (info != null) {
                AlertDialog.Builder(this@GithubSearchActivity)
                    .setTitle("Update available: ${info.tag}")
                    .setMessage("A new version of AAAI is available.")
                    .setPositiveButton("View") { _, _ ->
                        startActivity(Intent(Intent.ACTION_VIEW,
                            Uri.parse("https://github.com/pablosguajardo/AAAI/releases")))
                    }
                    .setNegativeButton("Later", null).show()
            } else {
                Toast.makeText(this@GithubSearchActivity,
                    "AAAI is up to date (v${BuildConfig.VERSION_NAME})", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun clearAppsCache() {
        val size = com.aaai.app.utils.ApkCache.measure(cacheDir)
        if (size.files == 0) {
            Toast.makeText(this, "No downloaded apps to clear", Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Clear apps cache")
            .setMessage("Delete ${size.files} file(s) (${com.aaai.app.utils.ApkCache.format(size.bytes)})?")
            .setPositiveButton("Clear") { _, _ ->
                lifecycleScope.launch {
                    val result = withContext(Dispatchers.IO) { com.aaai.app.utils.ApkCache.clearAll(cacheDir) }
                    Toast.makeText(this@GithubSearchActivity,
                        "Freed ${com.aaai.app.utils.ApkCache.format(result.bytes)} (${result.files} file(s))",
                        Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton("Cancel", null).show()
    }

    // -------------------------------------------------------------------------
    // DB
    // -------------------------------------------------------------------------

    private suspend fun openDb() {
        progressBar.visibility = View.VISIBLE
        tvEmpty.visibility = View.VISIBLE
        tvEmpty.text = "Loading apps…"
        val opened = withContext(Dispatchers.IO) { dbHelper.openReadable() }
        progressBar.visibility = View.GONE
        tvEmpty.visibility = View.GONE
        if (opened == null) {
            tvEmpty.visibility = View.VISIBLE
            tvEmpty.text = "App database not available yet.\nRun the indexer script first."
            return
        }
        db = opened
        allTopics = withContext(Dispatchers.IO) {
            dbHelper.allTopics(opened, GROUP_VERIFIED, MIN_TOPIC_COUNT_VERIFIED)
        }
        setupTopicSpinner()
        dbReady = true
        loadPage(0)
    }

    // -------------------------------------------------------------------------
    // Spinners / chips
    // -------------------------------------------------------------------------

    private fun setupSortSpinner() {
        spSort.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item,
            listOf("Most stars", "Most recent"))
        spSort.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                sortBy = if (pos == 0) SORT_STARS else SORT_NEWEST
                resetSearch()
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
    }

    private fun setupGroupChips() {
        fun selectGroup(group: String) {
            if (selectedGroup == group) return
            selectedGroup = group
            chipVerified.isChecked    = (group == GROUP_VERIFIED)
            chipAndroidAuto.isChecked = (group == GROUP_AUTO)
            chipAndroid.isChecked     = (group == GROUP_ANDROID)
            tvDisclaimer.visibility = if (group == GROUP_ANDROID) View.VISIBLE else View.GONE
            reloadTopicsAndSearch()
        }
        chipVerified.setOnClickListener    { selectGroup(GROUP_VERIFIED) }
        chipAndroidAuto.setOnClickListener { selectGroup(GROUP_AUTO) }
        chipAndroid.setOnClickListener     { selectGroup(GROUP_ANDROID) }
    }

    private fun reloadTopicsAndSearch() {
        val currentDb = db ?: return
        selectedTopicId = null
        dbReady = false
        lifecycleScope.launch {
            allTopics = withContext(Dispatchers.IO) {
                val minCount = when (selectedGroup) {
                    GROUP_ANDROID -> MIN_TOPIC_COUNT_ANDROID
                    GROUP_AUTO    -> MIN_TOPIC_COUNT_AUTO
                    else          -> MIN_TOPIC_COUNT_VERIFIED
                }
                dbHelper.allTopics(currentDb, selectedGroup, minCount)
            }
            setupTopicSpinner()
            dbReady = true
            resetSearch()
        }
    }

    private fun setupTopicSpinner() {
        val labels = mutableListOf("All topics") + allTopics.map { it.second }
        spTopic.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, labels)
        var skipFirst = true
        spTopic.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                if (skipFirst) { skipFirst = false; return }
                selectedTopicId = if (pos == 0) null else allTopics[pos - 1].first
                resetSearch()
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
    }

    private fun setupSearchBar() {
        etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {
                btnClearSearch.visibility = if (s.isNullOrEmpty()) View.GONE else View.VISIBLE
                searchJob?.cancel()
                searchJob = lifecycleScope.launch {
                    delay(SEARCH_DEBOUNCE_MS)
                    resetSearch()
                }
            }
            override fun afterTextChanged(s: Editable?) {}
        })
        etSearch.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) { searchJob?.cancel(); resetSearch(); true }
            else false
        }
        btnClearSearch.setOnClickListener { etSearch.setText(""); btnClearSearch.visibility = View.GONE }
    }

    private fun setupAdapter() {
        adapter = GithubRepoAdapter(
            onRepoClick    = { repo -> showRepoDetail(repo) },
            onActionClick  = { repo -> onActionButtonClick(repo) }
        )
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter
    }

    // -------------------------------------------------------------------------
    // Manual install panel
    // -------------------------------------------------------------------------

    private fun setupManualInstallPanel() {
        spAutoMode.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item,
            AutoMode.selectable.map { it.label })

        manualInstallHeader.setOnClickListener {
            val open = manualInstallContent.visibility == View.VISIBLE
            manualInstallContent.visibility = if (open) View.GONE else View.VISIBLE
            tvManualToggle.text = if (open) "▶  Manual installation" else "▼  Manual installation"
        }

        btnInstallUrl.setOnClickListener {
            val url = etApkUrl.text.toString().trim()
            if (url.isEmpty()) { Toast.makeText(this, "Enter an APK URL", Toast.LENGTH_SHORT).show(); return@setOnClickListener }
            if (!url.startsWith("http", ignoreCase = true)) { Toast.makeText(this, "URL must start with https://", Toast.LENGTH_SHORT).show(); return@setOnClickListener }
            if (!canInstallPackages()) { requestInstallPermission(); return@setOnClickListener }
            startInstallFromUrl(url)
        }

        btnPickApk.setOnClickListener {
            if (!canInstallPackages()) { requestInstallPermission(); return@setOnClickListener }
            pickApkLauncher.launch(arrayOf("*/*"))
        }
    }

    // -------------------------------------------------------------------------
    // Search
    // -------------------------------------------------------------------------

    private fun resetSearch() {
        if (!dbReady) return
        currentPage = 0
        adapter.submitList(emptyList())
        loadPage(0)
    }

    private fun loadPage(page: Int, append: Boolean = false) {
        val currentDb = db ?: return
        progressBar.visibility = View.VISIBLE
        btnLoadMore.visibility = View.GONE
        val query = etSearch.text.toString()
        val group = selectedGroup
        val topicId = selectedTopicId
        lifecycleScope.launch {
            val (results, count) = withContext(Dispatchers.IO) {
                val r = dbHelper.search(currentDb, query, group, topicId, sortBy, page)
                val c = if (page == 0) dbHelper.count(currentDb, query, group, topicId) else totalCount
                r to c
            }
            progressBar.visibility = View.GONE
            currentPage = page
            totalCount = count

            val newList = if (append) {
                // Only enrich the NEW items — existing ones are already enriched
                val existing = (0 until adapter.itemCount).map { adapter.currentList[it] }
                val enrichedNew = withContext(Dispatchers.IO) { enrichWithInstallState(results) }
                existing + enrichedNew
            } else {
                // Fresh load: invalidate cache so we get fresh install state
                invalidateInstallCache()
                withContext(Dispatchers.IO) { enrichWithInstallState(results) }
            }
            adapter.submitList(newList)
            tvResultCount.visibility = if (count > 0) View.VISIBLE else View.GONE
            tvResultCount.text = "$count result${if (count != 1) "s" else ""}"
            if (newList.isEmpty()) {
                tvEmpty.visibility = View.VISIBLE
                tvEmpty.text = if (query.isBlank() && topicId == null) "No apps found." else "No results."
            } else tvEmpty.visibility = View.GONE
            val shown = newList.size
            btnLoadMore.visibility = if (shown < count) View.VISIBLE else View.GONE
            if (shown < count) btnLoadMore.text = "Load more (${count - shown} remaining)"
        }
    }

    // -------------------------------------------------------------------------
    // Install state detection
    // -------------------------------------------------------------------------

    /**
     * Enriches repos with install state (isInstalled, hasUpdate, signatureMismatch).
     * Uses cached package list and own cert to avoid repeated expensive scans.
     * Must be called from an IO coroutine.
     */
    private fun enrichWithInstallState(repos: List<GithubRepo>): List<GithubRepo> {
        if (repos.isEmpty()) return repos
        // Use cached values; scan once per cache cycle
        val installedPkgs = cachedInstalledPkgs
            ?: PackageNaming.installedPackageNames(this).also { cachedInstalledPkgs = it }
        val ownCert = cachedOwnCert
            ?: com.aaai.app.utils.SigningCheck.ownCertHash(this).also { cachedOwnCert = it }

        return repos.map { repo ->
            if (repo.packageName.isBlank()) return@map repo
            val installedPkg = PackageNaming.resolveInstalled(this, repo.packageName, installedPkgs)
            // Package not installed: clear install state flags
                ?: return@map repo.copy(isInstalled = false, hasUpdate = false, signatureMismatch = false)
            // Use installed tag, fall back to pending tag (set right after a successful install)
            // Saved tag lost (app data cleared / AAAI reinstalled): recover it from the version stamped
            // inside the installed APK, same as AppMetadataService does. Otherwise "Update" shows as "Open".
            if (InstalledVersionTracker.getInstalledTag(this, repo.appId) == null) {
                val stamped = readStampedVersion(installedPkg)
                Log.d(TAG, "recover ${repo.appId} pkg=$installedPkg stamped=$stamped")
                stamped?.let { InstalledVersionTracker.recoverInstalled(this, repo.appId, it) }
            }
            val installedVersion = InstalledVersionTracker.getInstalledTag(this, repo.appId)
                ?: InstalledVersionTracker.getPendingTagForDisplay(this, repo.appId)
            // latestVersion: prefer what GitHub told us (set in startVersionPicker), fall back to DB
            val latestVersion = InstalledVersionTracker.getLatestTag(this, repo.appId) ?: repo.lastTag
            val hasUpdate = if (installedVersion != null && latestVersion != null && installedVersion != latestVersion) {
                // Use VersionCompare (same as AppMetadataService.isNewerTag) — handles GitHub tag formats
                com.aaai.app.utils.VersionCompare.compare(latestVersion, installedVersion)
                    ?.let { it > 0 } ?: (latestVersion != installedVersion)
            } else if (installedVersion == null && latestVersion != null) {
                // No tag known at all (installed before version tracking existed, no stamp either):
                // fall back to the installed APK's own versionName, ignoring pre-release suffixes
                // (same fallback AppMetadataService used to do).
                val apkVersionName = try { packageManager.getPackageInfo(installedPkg, 0).versionName } catch (e: Exception) { null }
                com.aaai.app.utils.VersionCompare.compare(latestVersion, apkVersionName, ignorePreRelease = true)
                    ?.let { it > 0 } ?: (apkVersionName != null && latestVersion != apkVersionName)
            } else false
            // Signature check: only compute if package is actually installed
            val mismatch = com.aaai.app.utils.SigningCheck.isMismatch(this, installedPkg, ownCert)
            repo.copy(isInstalled = true, installedVersion = installedVersion,
                      hasUpdate = hasUpdate, signatureMismatch = mismatch)
        }
    }

    /** Version / tag stamped into the installed APK's manifest by ApkPackageRenamer, or null if absent. */
    private fun readStampedVersion(installedPackage: String): String? = try {
        packageManager.getApplicationInfo(installedPackage, android.content.pm.PackageManager.GET_META_DATA)
            .metaData?.getString(com.aaai.app.utils.ApkPackageRenamer.META_CATALOG_VERSION)
            ?.takeIf { it.isNotBlank() }
    } catch (e: Exception) {
        null
    }

    private fun refreshInstallStates() {
        val currentList = (0 until adapter.itemCount).map { adapter.currentList[it] }
        if (currentList.isEmpty()) return
        lifecycleScope.launch {
            // Invalidate so we get fresh data, then re-enrich
            invalidateInstallCache()
            val enriched = withContext(Dispatchers.IO) { enrichWithInstallState(currentList) }
            adapter.submitList(enriched)
        }
    }

    // -------------------------------------------------------------------------
    // Action button click (Install/Open/Update/Reinstall)
    // -------------------------------------------------------------------------

    private fun onActionButtonClick(repo: GithubRepo) {
        when {
            repo.signatureMismatch -> showReinstallDialog(repo)
            repo.hasUpdate || !repo.isInstalled -> startInstallFlow(repo)
            repo.isInstalled -> openApp(repo)
        }
    }

    private fun openApp(repo: GithubRepo) {
        val installed = PackageNaming.resolveInstalled(this, repo.packageName)
        if (installed == null) { Toast.makeText(this, "Not installed", Toast.LENGTH_SHORT).show(); return }
        val intent = packageManager.getLaunchIntentForPackage(installed)
        if (intent != null) startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        else Toast.makeText(this, "${repo.displayName}: open from Android Auto", Toast.LENGTH_LONG).show()
    }

    private fun showReinstallDialog(repo: GithubRepo) {
        AlertDialog.Builder(this)
            .setTitle("Reinstall ${repo.displayName}")
            .setMessage("Signed with a different key. Uninstall first, then reinstall.")
            .setPositiveButton("Uninstall") { _, _ ->
                val pkg = PackageNaming.resolveInstalled(this, repo.packageName) ?: return@setPositiveButton
                InstalledVersionTracker.clearInstalled(this, repo.appId)
                startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:$pkg")))
            }
            .setNegativeButton("Cancel", null).show()
    }

    private fun startInstallFlow(repo: GithubRepo) {
        if (!canInstallPackages()) { requestInstallPermission(); return }
        showRepoDetail(repo)
    }

    // -------------------------------------------------------------------------
    // Repo detail dialog
    // -------------------------------------------------------------------------

    private fun showRepoDetail(repo: GithubRepo) {
        val view = layoutInflater.inflate(R.layout.dialog_github_repo_detail, null)
        val ivIcon   = view.findViewById<ImageView>(R.id.ivDetailIcon)
        val tvName   = view.findViewById<TextView>(R.id.tvDetailName)
        val tvFull   = view.findViewById<TextView>(R.id.tvDetailFullName)
        val tvStars  = view.findViewById<TextView>(R.id.tvDetailStars)
        val tvDesc   = view.findViewById<TextView>(R.id.tvDetailDescription)
        val tvTopics = view.findViewById<TextView>(R.id.tvDetailTopics)
        val tvTag    = view.findViewById<TextView>(R.id.tvDetailLastTag)
        val spMode   = view.findViewById<Spinner>(R.id.spDetailAutoMode)
        val cbRename = view.findViewById<CheckBox>(R.id.cbRenamePackage)
        val btnRepo  = view.findViewById<Button>(R.id.btnOpenRepo)

        tvName.text   = repo.displayName
        tvFull.text   = repo.fullName
        tvStars.text  = if (repo.isFileSource) "📦 Included in AAAI" else "⭐ ${repo.stars}"
        tvDesc.text   = repo.description ?: ""
        tvDesc.visibility = if (repo.description.isNullOrBlank()) View.GONE else View.VISIBLE
        tvTopics.text = repo.topics.joinToString(" • ")
        tvTopics.visibility = if (repo.topics.isEmpty()) View.GONE else View.VISIBLE
        tvTag.text    = repo.lastTag?.let { "Latest: $it" } ?: ""
        tvTag.visibility = if (repo.lastTag.isNullOrBlank()) View.GONE else View.VISIBLE

        if (!repo.avatarUrl.isNullOrBlank()) {
            Glide.with(this).load(repo.avatarUrl)
                .placeholder(android.R.drawable.sym_def_app_icon)
                .error(android.R.drawable.sym_def_app_icon).into(ivIcon)
        }

        spMode.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item,
            AutoMode.selectable.map { it.label })
        // Pre-select: app_action if set; otherwise by current group
        // android-auto/verified = SIGN_ONLY(0), android = GAME(1)
        val defaultMode = if (repo.appAction != null) repo.defaultAutoMode()
                          else if (selectedGroup == GROUP_ANDROID) AutoMode.GAME
                          else AutoMode.SIGN_ONLY
        spMode.setSelection((AutoMode.selectable.indexOf(defaultMode)).coerceAtLeast(0))

        // FILE apps: hide rename checkbox (rename doesn't apply)
        cbRename.visibility = if (repo.isFileSource) View.GONE else View.VISIBLE

        btnRepo.setOnClickListener {
            if (!repo.isFileSource) {
                startActivity(Intent(Intent.ACTION_VIEW,
                    Uri.parse("https://github.com/${repo.fullName}")))
            } else {
                btnRepo.isEnabled = false
                btnRepo.text = "🔒 Included app"
            }
        }
        if (repo.isFileSource) {
            btnRepo.text = "📦 Included in AAAI"
        }

        AlertDialog.Builder(this)
            .setTitle(if (repo.isInstalled && !repo.hasUpdate) repo.displayName else "Install from GitHub")
            .setView(view)
            .setPositiveButton("Choose version") { _, _ ->
                val mode = AutoMode.fromIndex(spMode.selectedItemPosition)
                val skipRename = !cbRename.isChecked
                if (repo.isFileSource) startFileInstall(repo, mode)
                else startVersionPicker(repo, mode, skipRename)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // -------------------------------------------------------------------------
    // Version picker (GitHub releases)
    // -------------------------------------------------------------------------

    private fun startVersionPicker(repo: GithubRepo, autoMode: AutoMode, skipRename: Boolean = false) {
        if (githubBusy || versionsDialog?.isShowing == true) return
        githubBusy = true
        showProgressDialog("Fetching versions")

        lifecycleScope.launch {
            val service = GitHubReleaseService(this@GithubSearchActivity)
            val releases = try {
                service.getReleases(repo.fullName)
            } catch (e: Exception) {
                githubBusy = false; installDialog?.dismiss()
                showError("Could not load versions. ${NetworkErrors.describe(e)}")
                return@launch
            }
            githubBusy = false; installDialog?.dismiss()
            service.lastWarning?.let { Toast.makeText(this@GithubSearchActivity, it, Toast.LENGTH_LONG).show() }

            if (releases.isEmpty()) {
                Toast.makeText(this@GithubSearchActivity, "No APK releases found", Toast.LENGTH_LONG).show()
                return@launch
            }
            InstalledVersionTracker.setLatestTag(this@GithubSearchActivity, repo.appId, releases[0].tag)

            val dateFmt = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
            val installedTag = InstalledVersionTracker.getInstalledTag(this@GithubSearchActivity, repo.appId)
            val labels = releases.mapIndexed { i, r ->
                buildString {
                    append(r.tag)
                    if (r.publishedAt > 0) append("  •  ").append(dateFmt.format(Date(r.publishedAt)))
                    if (i == 0) append("  (latest)")
                    if (r.prerelease) append("  [pre-release]")
                    if (r.tag == installedTag) append("  installed")
                }
            }.toTypedArray()

            versionsDialog = AlertDialog.Builder(this@GithubSearchActivity)
                .setTitle("${repo.displayName} — choose version")
                .setItems(labels) { _, which ->
                    val asset = releases[which].bestAsset()
                    if (asset == null) {
                        Toast.makeText(this@GithubSearchActivity, "No compatible APK", Toast.LENGTH_LONG).show()
                        return@setItems
                    }
                    startGithubInstall(repo, releases[which].tag, asset.downloadUrl, autoMode, skipRename)
                }
                .setNegativeButton("Cancel", null).show()
        }
    }

    // -------------------------------------------------------------------------
    // Install flows
    // -------------------------------------------------------------------------

    private fun startGithubInstall(repo: GithubRepo, tag: String, url: String,
                                   autoMode: AutoMode, skipRename: Boolean) {
        currentInstallRepo = repo; currentInstallTag = tag
        showProgressDialog("Installing ${repo.displayName} $tag")
        val actionCode: String? = when (autoMode) {
            AutoMode.GAME -> "g"; AutoMode.NAVIGATION -> "n"
            AutoMode.WEBVIEW -> "w"; AutoMode.MEDIA -> "m"; else -> null
        }
        lifecycleScope.launch {
            downloadService.installGithubRelease(
                repo.toAppMetadata(actionCode), tag, url, skipRename = skipRename,
                onProgress = { p, m -> runOnUiThread { installDialogMessage?.text = m; installDialogProgress?.progress = p } },
                onReadyToInstall = { f -> runOnUiThread { installDialog?.dismiss(); startActivityForResult(downloadService.buildInstallIntent(f), REQUEST_INSTALL) } },
                onError = { e -> runOnUiThread { installDialog?.dismiss(); showError(e) } }
            )
        }
    }

    private fun startFileInstall(repo: GithubRepo, autoMode: AutoMode) {
        currentInstallRepo = repo; currentInstallTag = repo.lastTag
        showProgressDialog("Installing ${repo.displayName}")
        val actionCode: String? = when (autoMode) {
            AutoMode.GAME -> "g"; AutoMode.NAVIGATION -> "n"
            AutoMode.WEBVIEW -> "w"; AutoMode.MEDIA -> "m"; else -> null
        }
        lifecycleScope.launch {
            downloadService.downloadAndInstall(
                repo.toAppMetadata(actionCode),
                onProgress = { p, m -> runOnUiThread { installDialogMessage?.text = m; installDialogProgress?.progress = p } },
                onReadyToInstall = { f -> runOnUiThread { installDialog?.dismiss(); startActivityForResult(downloadService.buildInstallIntent(f), REQUEST_INSTALL) } },
                onError = { e -> runOnUiThread { installDialog?.dismiss(); showError(e) } }
            )
        }
    }

    private fun startInstallFromUrl(url: String) {
        showProgressDialog("Installing from URL")
        val mode = AutoMode.fromIndex(spAutoMode.selectedItemPosition)
        val skipRename = !cbManualRename.isChecked
        lifecycleScope.launch {
            downloadService.installFromUrl(url,
                onProgress = { p, m -> runOnUiThread { installDialogMessage?.text = m; installDialogProgress?.progress = p } },
                onReadyToInstall = { f -> runOnUiThread { installDialog?.dismiss(); startActivityForResult(downloadService.buildInstallIntent(f), REQUEST_INSTALL) } },
                onError = { e -> runOnUiThread { installDialog?.dismiss(); showError(e) } },
                autoMode = mode,
                skipRename = skipRename
            )
        }
    }

    private fun startInstallFromFile(uri: android.net.Uri) {
        showProgressDialog("Installing from file")
        val mode = AutoMode.fromIndex(spAutoMode.selectedItemPosition)
        val skipRename = !cbManualRename.isChecked
        lifecycleScope.launch {
            downloadService.installFromFile(uri,
                onProgress = { p, m -> runOnUiThread { installDialogMessage?.text = m; installDialogProgress?.progress = p } },
                onReadyToInstall = { f -> runOnUiThread { installDialog?.dismiss(); startActivityForResult(downloadService.buildInstallIntent(f), REQUEST_INSTALL) } },
                onError = { e -> runOnUiThread { installDialog?.dismiss(); showError(e) } },
                autoMode = mode,
                skipRename = skipRename
            )
        }
    }

    private fun showProgressDialog(title: String) {
        installDialogMessage = TextView(this).apply { text = "Starting..."; setPadding(48, 32, 48, 8) }
        installDialogProgress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = false; max = 100; progress = 0; setPadding(48, 8, 48, 32)
        }
        val layout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            addView(installDialogMessage); addView(installDialogProgress)
        }
        installDialog = AlertDialog.Builder(this).setTitle(title).setView(layout).setCancelable(false).create()
        installDialog?.show()
    }

    private fun showError(msg: String) {
        AlertDialog.Builder(this).setTitle("Error").setMessage(msg).setPositiveButton("OK", null).show()
    }

    // -------------------------------------------------------------------------
    // Permissions
    // -------------------------------------------------------------------------

    private fun canInstallPackages(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) packageManager.canRequestPackageInstalls() else true

    private fun requestInstallPermission() {
        AlertDialog.Builder(this)
            .setTitle("Install permission required")
            .setMessage("AAAI needs permission to install apps from unknown sources.")
            .setPositiveButton("Open settings") { _, _ ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:$packageName")))
                }
            }
            .setNegativeButton("Cancel", null).show()
    }

    // -------------------------------------------------------------------------
    // onActivityResult
    // -------------------------------------------------------------------------

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_INSTALL) return
        val repo = currentInstallRepo; val tag = currentInstallTag
        currentInstallRepo = null; currentInstallTag = null
        when (resultCode) {
            Activity.RESULT_OK -> {
                Toast.makeText(this, "${repo?.displayName ?: "App"} installed!", Toast.LENGTH_LONG).show()
                if (repo != null && tag != null) {
                    // Mark as installed immediately (RESULT_OK is reliable here)
                    InstalledVersionTracker.forceInstalled(this, repo.appId, tag)
                    lifecycleScope.launch(Dispatchers.IO) {
                        com.aaai.app.utils.ApkCache.cleanupAfterInstall(cacheDir, repo.appId, tag)
                    }
                }
                refreshInstallStates()
            }
            Activity.RESULT_CANCELED ->
                Toast.makeText(this, "Installation cancelled", Toast.LENGTH_SHORT).show()
            else -> {
                val code = data?.getIntExtra("android.intent.extra.INSTALL_RESULT", -1) ?: -1
                showError("Installation failed (code: $code)")
            }
        }
    }
}
