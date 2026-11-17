package com.aaai.app

import android.app.Activity
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.Spinner
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.aaai.app.adapters.AppListAdapter
import com.aaai.app.autoconv.AutoMode
import com.aaai.app.data.AppMetadata
import com.aaai.app.data.isGithubRelease
import com.aaai.app.services.AppMetadataService
import com.aaai.app.services.DownloadAndInstallService
import com.aaai.app.services.GitHubReleaseService
import com.aaai.app.services.UpdateChecker
import com.aaai.app.utils.InstallAdCounter
import com.aaai.app.utils.InstalledVersionTracker
import com.aaai.app.utils.NetworkErrors
import com.startapp.sdk.adsbase.Ad
import com.startapp.sdk.adsbase.StartAppAd
import com.startapp.sdk.adsbase.adlisteners.AdDisplayListener
import com.startapp.sdk.adsbase.StartAppSDK
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var progressBar: ProgressBar
    private lateinit var tvEmpty: TextView
    private lateinit var adapter: AppListAdapter
    private lateinit var appMetadataService: AppMetadataService
    private lateinit var downloadService: DownloadAndInstallService

    private var installDialog: AlertDialog? = null
    private var installDialogMessage: TextView? = null
    private var installDialogProgress: ProgressBar? = null
    private var currentInstallApp: AppMetadata? = null

    private lateinit var etApkUrl: EditText
    private lateinit var spAutoMode: Spinner

    /** Mode picked in the spinner; applies to "Install URL" and "Choose APK" only. */
    private fun selectedAutoMode(): AutoMode = AutoMode.fromIndex(spAutoMode.selectedItemPosition)

    // Preloaded full-screen ad, shown before every InstallAdCounter.INSTALLS_PER_AD-th successful install
    private var interstitial: StartAppAd? = null

    // System file picker (SAF). Any type is allowed here; the content is validated afterwards.
    private val pickApkLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) startInstallFromFile(uri)
    }

    // Refreshes Install/Open buttons as soon as the system reports a package change
    private val packageChangeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            Log.d(TAG, "Package event: ${intent.action} ${intent.data}")
            refreshInstallStates()
        }
    }

    companion object {
        private const val TAG = "com.aaai.app-MainActivity"
        private const val REQUEST_INSTALL = 1001
        private const val REQUEST_SELF_UPDATE = 1002
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Start.io: explicit init (returnAds disabled), test ads only in debug builds
        StartAppSDK.setTestAdsEnabled(BuildConfig.DEBUG)
        StartAppSDK.init(this, "210536031", false)
        interstitial = StartAppAd(this).also { it.loadAd() }

        setContentView(R.layout.activity_main)

        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.title = getString(R.string.app_name)

        recyclerView = findViewById(R.id.recyclerView)
        progressBar = findViewById(R.id.progressBar)
        tvEmpty = findViewById(R.id.tvEmpty)

        appMetadataService = AppMetadataService(this)
        downloadService = DownloadAndInstallService(this)

        adapter = AppListAdapter(
            onInstallClick = { app -> onInstallClick(app) },
            onOpenClick = { app -> openApp(app) }
        )
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter

        etApkUrl = findViewById(R.id.etApkUrl)
        spAutoMode = findViewById(R.id.spAutoMode)
        spAutoMode.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item, AutoMode.selectable.map { it.label }
        )
        findViewById<Button>(R.id.btnInstallUrl).setOnClickListener { onInstallUrlClick() }
        findViewById<Button>(R.id.btnPickApk).setOnClickListener { onPickApkClick() }
        etApkUrl.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO) {
                onInstallUrlClick()
                true
            } else false
        }

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addDataScheme("package")
        }
        ContextCompat.registerReceiver(this, packageChangeReceiver, filter, ContextCompat.RECEIVER_EXPORTED)

        loadApps()
        checkForUpdate()
    }

    override fun onResume() {
        super.onResume()
        refreshInstallStates()
        // A mandatory update must stay on screen: show it again if the user came back without updating
        val pending = pendingUpdate
        if (pending != null && pending.mandatory &&
            updateDialog?.isShowing != true && installDialog?.isShowing != true &&
            updateErrorDialog?.isShowing != true
        ) {
            showUpdateDialog(pending)
        }
    }

    override fun onDestroy() {
        try { unregisterReceiver(packageChangeReceiver) } catch (_: Exception) {}
        super.onDestroy()
    }

    /** Re-checks which catalog apps are installed (incl. renamed packages) and updates the list. */
    private fun refreshInstallStates() {
        lifecycleScope.launch {
            appMetadataService.updateInstallationStates()
            val apps = appMetadataService.getAllApps()
            if (apps.isNotEmpty()) adapter.submitList(apps)

            // Check GitHub for newer releases of installed apps (throttled to once per 24h per app)
            if (appMetadataService.refreshLatestGithubTags()) {
                val updated = appMetadataService.getAllApps()
                if (updated.isNotEmpty()) adapter.submitList(updated)
            }
            appMetadataService.lastRefreshWarning?.let {
                Toast.makeText(this@MainActivity, "Could not check for updates. $it", Toast.LENGTH_LONG).show()
            }
        }
    }

    /** Opens the installed app using its renamed package (falls back to the original one). */
    private fun openApp(app: AppMetadata) {
        val installedPkg = appMetadataService.resolveInstalledPackage(app.packageName)
        if (installedPkg == null) {
            Toast.makeText(this, "${app.appName} is not installed", Toast.LENGTH_SHORT).show()
            refreshInstallStates()
            return
        }
        val launchIntent = packageManager.getLaunchIntentForPackage(installedPkg)
        if (launchIntent == null) {
            Log.w(TAG, "No launch intent for $installedPkg")
            Toast.makeText(
                this,
                "${app.appName} has no launcher; open it from Android Auto",
                Toast.LENGTH_LONG
            ).show()
            return
        }
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(launchIntent)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu, menu)
        menu.findItem(R.id.version)?.title = "v${BuildConfig.VERSION_NAME}"
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.infos -> {
                startActivity(Intent(this, AboutActivity::class.java))
                true
            }
            R.id.show_intro -> {
                startActivity(Intent(this, SetupActivity::class.java))
                true
            }
            R.id.check_update -> {
                checkForUpdateManually()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun loadApps() {
        progressBar.visibility = View.VISIBLE
        tvEmpty.visibility = View.GONE

        lifecycleScope.launch {
            try {
                appMetadataService.initializeAppData()
                val apps = appMetadataService.getAllApps()
                Log.d(TAG, "Apps loaded: ${apps.size}")
                runOnUiThread {
                    progressBar.visibility = View.GONE
                    if (apps.isEmpty()) {
                        tvEmpty.text = "No apps found"
                        tvEmpty.visibility = View.VISIBLE
                    } else {
                        adapter.submitList(apps)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error loading apps", e)
                runOnUiThread {
                    progressBar.visibility = View.GONE
                    tvEmpty.text = "Error loading apps: ${e.message}"
                    tvEmpty.visibility = View.VISIBLE
                }
            }
        }
    }

    private var pendingUpdate: UpdateChecker.UpdateInfo? = null
    private var updateDialog: AlertDialog? = null
    private var updateErrorDialog: AlertDialog? = null

    /** Looks for a newer AAAI release on GitHub. A failed check (offline...) never blocks the app. */
    private fun checkForUpdate() {
        lifecycleScope.launch {
            val info = UpdateChecker(this@MainActivity).check() ?: return@launch
            pendingUpdate = info
            showUpdateDialog(info)
        }
    }

    /** Formats a duration like "2 h 45 min" / "12 min" / "less than a minute". */
    private fun formatWait(ms: Long): String {
        val totalMin = (ms + 59_999) / 60_000
        if (totalMin <= 0) return "less than a minute"
        val h = totalMin / 60
        val m = totalMin % 60
        return when {
            h > 0 && m > 0 -> "$h h $m min"
            h > 0 -> "$h h"
            else -> "$m min"
        }
    }

    /**
     * Menu option: uses its own cache (UpdateChecker.MANUAL_CHECK_INTERVAL_MS), separate from the
     * automatic check. The first press (or the first one after that interval) queries GitHub; presses
     * in between reuse the saved result and tell the user how long to wait. Always reports a result.
     */
    private fun checkForUpdateManually() {
        val checker = UpdateChecker(this)
        val waitMs = checker.manualCooldownRemainingMs()
        val waitText = if (waitMs > 0) {
            "You can check GitHub again in ${formatWait(waitMs)}."
        } else null
        if (waitText == null) {
            Toast.makeText(this, "Checking for updates...", Toast.LENGTH_SHORT).show()
        }
        lifecycleScope.launch {
            val info = checker.check(manual = true)
            if (info != null) {
                if (waitText != null) Toast.makeText(this@MainActivity, waitText, Toast.LENGTH_LONG).show()
                pendingUpdate = info
                showUpdateDialog(info)
                return@launch
            }
            val error = checker.lastError
            if (error != null) {
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("Check for updates")
                    .setMessage("Could not check for updates. $error")
                    .setPositiveButton("OK", null)
                    .show()
            } else {
                pendingUpdate = null
                val base = "AAAI is up to date (v${BuildConfig.VERSION_NAME})."
                Toast.makeText(
                    this@MainActivity,
                    if (waitText != null) "$base $waitText" else base,
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun showUpdateDialog(info: UpdateChecker.UpdateInfo) {
        if (isFinishing) return
        val daysLeft = (UpdateChecker.MAX_DAYS_WITHOUT_UPDATE - info.daysSincePublished).coerceAtLeast(0)
        val builder = AlertDialog.Builder(this).setTitle("Update available: ${info.tag}")
        if (info.mandatory) {
            builder.setMessage(
                "This version is no longer supported. You must update AAAI to keep using it."
            )
                .setCancelable(false)
                .setPositiveButton("Update") { _, _ -> startSelfUpdate(info) }
                .setNegativeButton("Exit") { _, _ -> finishAffinity() }
        } else {
            builder.setMessage(
                "A new version of AAAI is available. The update will become required in $daysLeft day(s)."
            )
                .setPositiveButton("Update") { _, _ -> startSelfUpdate(info) }
                .setNegativeButton("Later", null)
        }
        updateDialog = builder.show()
    }

    private fun startSelfUpdate(info: UpdateChecker.UpdateInfo) {
        if (!canInstallPackages()) {
            checkAndRequestInstallPermission()
            return
        }
        if (downloadService.hasSelfUpdateDownloaded(info.tag)) {
            // Same idea as the catalog apps: reuse the downloaded APK or download it again
            AlertDialog.Builder(this)
                .setTitle("AAAI ${info.tag}")
                .setMessage("This version was already downloaded. Install it or download it again?")
                .setCancelable(false)
                .setPositiveButton("Install downloaded") { _, _ -> runSelfUpdate(info, false) }
                .setNeutralButton("Download again") { _, _ -> runSelfUpdate(info, true) }
                .setNegativeButton("Cancel") { _, _ ->
                    if (info.mandatory) showUpdateDialog(info)
                }
                .show()
            return
        }
        runSelfUpdate(info, false)
    }

    private fun runSelfUpdate(info: UpdateChecker.UpdateInfo, forceDownload: Boolean) {
        showProgressDialog("Updating AAAI ${info.tag}")
        lifecycleScope.launch {
            downloadService.installSelfUpdate(
                info.tag,
                info.assetUrl,
                forceDownload = forceDownload,
                onProgress = ::handleProgress,
                onReadyToInstall = { apk ->
                    runOnUiThread {
                        installDialog?.dismiss()
                        startActivityForResult(downloadService.buildInstallIntent(apk), REQUEST_SELF_UPDATE)
                    }
                },
                onError = { msg ->
                    runOnUiThread {
                        installDialog?.dismiss()
                        AlertDialog.Builder(this@MainActivity)
                            .setTitle("Update error")
                            .setMessage(msg)
                            .setPositiveButton("OK", null)
                            .show()
                    }
                }
            )
        }
    }

    private fun canInstallPackages(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            packageManager.canRequestPackageInstalls()
        } else {
            true
        }
    }

    private fun checkAndRequestInstallPermission() {
        if (canInstallPackages()) {
            Toast.makeText(this, "Install permission already granted", Toast.LENGTH_SHORT).show()
        } else {
            AlertDialog.Builder(this)
                .setTitle("Install permission required")
                .setMessage("AAAI needs permission to install apps from unknown sources.")
                .setPositiveButton("Open settings") { _, _ ->
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        startActivity(
                            Intent(
                                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                Uri.parse("package:$packageName")
                            )
                        )
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    private fun onInstallClick(app: AppMetadata) {
        // Only sourceType == GITHUB_RELEASE uses the GitHub versions list; everything else uses downloadUrl
        if (app.isGithubRelease()) {
            showGithubVersions(app)
            return
        }

        if (app.downloadUrl.isEmpty()) {
            Toast.makeText(this, "No download URL for ${app.appName}", Toast.LENGTH_SHORT).show()
            return
        }

        if (!canInstallPackages()) {
            checkAndRequestInstallPermission()
            return
        }

        // Ad gate first (every N attempts), then the cache/download popup
        runAfterAdGate { showInstallDialog(app) }
    }

    private fun showInstallDialog(app: AppMetadata) {
        val cachedApk = java.io.File(cacheDir, "${app.appId}.apk")
        val hasCached = !DownloadAndInstallService.FORCE_REDOWNLOAD &&
                cachedApk.exists() && cachedApk.length() > 0

        val dialog = AlertDialog.Builder(this)
            .setTitle("Install ${app.appName}")

        if (hasCached) {
            val sizeMb = "%.1f MB".format(cachedApk.length() / 1_048_576.0)
            dialog.setMessage("v${app.version} — cached ($sizeMb)\nUse cached APK or re-download?")
            dialog.setPositiveButton("Install (cached)") { _, _ ->
                startInstall(app)
            }
            dialog.setNeutralButton("Clear Cache & Re-download") { _, _ ->
                cachedApk.delete()
                Log.d(TAG, "Cache cleared: ${cachedApk.name}")
                startInstall(app)
            }
        } else {
            dialog.setMessage("Download and install v${app.version}?")
            dialog.setPositiveButton("Install") { _, _ ->
                startInstall(app)
            }
        }

        dialog.setNegativeButton("Cancel", null)
        dialog.show()
    }

    /** Lists up to 10 GitHub releases (newest first) so the user can choose which one to install. */
    private fun showGithubVersions(app: AppMetadata) {
        val repo = app.githubRepo ?: return
        if (!canInstallPackages()) {
            checkAndRequestInstallPermission()
            return
        }

        runAfterAdGate { fetchGithubVersions(app, repo) }
    }

    private fun fetchGithubVersions(app: AppMetadata, repo: String) {
        showProgressDialog("Fetching versions")
        installDialogMessage?.text = "Querying GitHub releases..."
        installDialogProgress?.isIndeterminate = true

        lifecycleScope.launch {
            val releaseService = GitHubReleaseService(this@MainActivity)
            val releases = try {
                releaseService.getReleases(repo)
            } catch (e: Exception) {
                Log.e(TAG, "GitHub releases error for $repo", e)
                installDialog?.dismiss()
                handleInstallError("Could not load versions. ${NetworkErrors.describe(e)}")
                return@launch
            }
            installDialog?.dismiss()

            // GitHub could not be reached but an older saved copy was used: let the user know
            releaseService.lastWarning?.let {
                Toast.makeText(this@MainActivity, it, Toast.LENGTH_LONG).show()
            }

            if (releases.isEmpty()) {
                Toast.makeText(this@MainActivity, "No APK releases found for ${app.appName}", Toast.LENGTH_LONG).show()
                return@launch
            }

            // Remember the newest tag so the list can flag "update available" later
            if (app.isInstalled) {
                InstalledVersionTracker.setLatestTag(this@MainActivity, app.appId, releases[0].tag)
                appMetadataService.updateInstallationStates()
                adapter.submitList(appMetadataService.getAllApps())
            }

            val installedTag = InstalledVersionTracker.getInstalledTag(this@MainActivity, app.appId)
            val dateFmt = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
            val labels = releases.mapIndexed { i, r ->
                buildString {
                    append(r.tag)
                    if (r.publishedAt > 0) append("  •  ").append(dateFmt.format(Date(r.publishedAt)))
                    if (i == 0) append("  (latest)")
                    if (r.prerelease) append("  [pre-release]")
                    if (app.isInstalled && r.tag == installedTag) append("  ✓ installed")
                }
            }.toTypedArray()

            AlertDialog.Builder(this@MainActivity)
                .setTitle("${app.appName} — choose version")
                .setItems(labels) { _, which -> onGithubVersionSelected(app, releases, which, installedTag) }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    private fun onGithubVersionSelected(
        app: AppMetadata,
        releases: List<GitHubReleaseService.Release>,
        which: Int,
        installedTag: String?
    ) {
        val release = releases[which]
        val asset = release.bestAsset()
        if (asset == null) {
            Toast.makeText(this, "No compatible APK in ${release.tag}", Toast.LENGTH_LONG).show()
            return
        }

        // Releases are sorted newest -> oldest, so a higher index than the installed one means older
        val installedIdx = if (installedTag != null) releases.indexOfFirst { it.tag == installedTag } else -1
        if (app.isInstalled && installedIdx in 0 until which) {
            AlertDialog.Builder(this)
                .setTitle("Older version")
                .setMessage(
                    "${app.appName} $installedTag is installed. Android does not allow installing an older " +
                            "version (${release.tag}) over a newer one.\n\n" +
                            "Uninstall it first (its data will be removed), then install ${release.tag}."
                )
                .setPositiveButton("Uninstall") { _, _ ->
                    val pkg = appMetadataService.resolveInstalledPackage(app.packageName)
                    if (pkg != null) {
                        InstalledVersionTracker.clearInstalled(this, app.appId)
                        startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:$pkg")))
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
            return
        }

        startGithubInstall(app, release.tag, asset.downloadUrl)
    }

    private fun startGithubInstall(app: AppMetadata, tag: String, assetUrl: String) {
        currentInstallApp = app
        showProgressDialog("Installing ${app.appName} $tag")
        lifecycleScope.launch {
            downloadService.installGithubRelease(
                app,
                tag,
                assetUrl,
                onProgress = ::handleProgress,
                onReadyToInstall = ::handleReadyToInstall,
                onError = ::handleInstallError
            )
        }
    }

    private fun onInstallUrlClick() {
        val url = etApkUrl.text.toString().trim()
        if (url.isEmpty()) {
            Toast.makeText(this, "Enter an APK URL", Toast.LENGTH_SHORT).show()
            return
        }
        if (!url.startsWith("http://", true) && !url.startsWith("https://", true)) {
            Toast.makeText(this, "The URL must start with http:// or https://", Toast.LENGTH_LONG).show()
            return
        }
        if (!canInstallPackages()) {
            checkAndRequestInstallPermission()
            return
        }
        (getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
            ?.hideSoftInputFromWindow(etApkUrl.windowToken, 0)

        runAfterAdGate { startInstallFromUrl(url) }
    }

    private fun startInstallFromUrl(url: String) {
        currentInstallApp = null
        showProgressDialog("Installing from URL")
        lifecycleScope.launch {
            downloadService.installFromUrl(
                url,
                onProgress = ::handleProgress,
                onReadyToInstall = ::handleReadyToInstall,
                onError = ::handleInstallError,
                autoMode = selectedAutoMode()
            )
        }
    }

    /**
     * When the install about to start is due for an ad (every InstallAdCounter.INSTALLS_PER_AD
     * successful installs), shows the full-screen ad first. [action] runs when the ad is closed,
     * or right away if no ad is due or none is ready (the user is never blocked).
     * The counter only increases when an install finishes OK (see onActivityResult).
     */
    private fun runAfterAdGate(action: () -> Unit) {
        if (!InstallAdCounter.shouldShowAdNow(this)) {
            action()
            return
        }
        val ad = interstitial
        if (ad == null || !ad.isReady) {
            // Ad not loaded yet: don't block the user, just try to load one for next time
            interstitial = StartAppAd(this).also { it.loadAd() }
            action()
            return
        }

        var done = false
        InstallAdCounter.markAdShown(this)
        val proceed = {
            if (!done) {
                done = true
                interstitial = StartAppAd(this).also { it.loadAd() }
                action()
            }
        }
        val shown = ad.showAd(object : AdDisplayListener {
            override fun adHidden(ad: Ad?) { proceed() }
            override fun adDisplayed(ad: Ad?) {}
            override fun adClicked(ad: Ad?) {}
            override fun adNotDisplayed(ad: Ad?) { proceed() }
        })
        if (!shown) proceed()
    }

    private fun onPickApkClick() {
        if (!canInstallPackages()) {
            checkAndRequestInstallPermission()
            return
        }
        runAfterAdGate { pickApkLauncher.launch(arrayOf("*/*")) }
    }

    private fun startInstallFromFile(uri: Uri) {
        currentInstallApp = null
        showProgressDialog("Installing from file")
        lifecycleScope.launch {
            downloadService.installFromFile(
                uri,
                onProgress = ::handleProgress,
                onReadyToInstall = ::handleReadyToInstall,
                onError = ::handleInstallError,
                autoMode = selectedAutoMode()
            )
        }
    }

    private fun handleProgress(progress: Int, message: String) {
        runOnUiThread {
            installDialogMessage?.text = message
            installDialogProgress?.progress = progress
        }
    }

    private fun handleReadyToInstall(apkFile: File) {
        runOnUiThread {
            installDialog?.dismiss()
            val intent = downloadService.buildInstallIntent(apkFile)
            startActivityForResult(intent, REQUEST_INSTALL)
        }
    }

    private fun handleInstallError(error: String) {
        runOnUiThread {
            installDialog?.dismiss()
            AlertDialog.Builder(this@MainActivity)
                .setTitle("Installation error")
                .setMessage(error)
                .setPositiveButton("OK", null)
                .show()
        }
    }

    private fun startInstall(app: AppMetadata) {
        currentInstallApp = app
        showProgressDialog("Installing ${app.appName}")
        lifecycleScope.launch {
            downloadService.downloadAndInstall(
                app,
                onProgress = ::handleProgress,
                onReadyToInstall = ::handleReadyToInstall,
                onError = ::handleInstallError
            )
        }
    }

    private fun showProgressDialog(title: String) {
        installDialogMessage = TextView(this).apply {
            text = "Starting..."
            setPadding(48, 32, 48, 8)
        }
        installDialogProgress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = false
            max = 100
            progress = 0
            setPadding(48, 8, 48, 32)
        }

        val layout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            addView(installDialogMessage)
            addView(installDialogProgress)
        }

        installDialog = AlertDialog.Builder(this)
            .setTitle(title)
            .setView(layout)
            .setCancelable(false)
            .create()
        installDialog?.show()
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == REQUEST_SELF_UPDATE) {
            handleSelfUpdateResult(resultCode, data)
            return
        }
        if (requestCode != REQUEST_INSTALL) return

        val appName = currentInstallApp?.appName ?: "App"
        currentInstallApp = null

        Log.d(TAG, "onActivityResult: appName=$appName resultCode=$resultCode data=${data?.extras}")

        // Always re-check: the real package state is the source of truth, not the result code
        refreshInstallStates()

        when (resultCode) {
            Activity.RESULT_OK -> {
                Log.d(TAG, "Install OK: $appName")
                Toast.makeText(this, "$appName installed successfully!", Toast.LENGTH_LONG).show()
                // Only successful installs count toward the next ad
                InstallAdCounter.registerSuccessfulInstall(this)
            }
            Activity.RESULT_CANCELED -> {
                Log.w(TAG, "Install CANCELED: $appName")
                Toast.makeText(this, "Installation cancelled (code: $resultCode)", Toast.LENGTH_SHORT).show()
            }
            Activity.RESULT_FIRST_USER -> {
                // Installation failed - get error details
                val extraStatus = data?.getIntExtra("android.intent.extra.INSTALL_RESULT", -1) ?: -1
                val errorMsg = installResultToMessage(extraStatus)
                Log.e(TAG, "Install FAILED for $appName: resultCode=$resultCode extraStatus=$extraStatus msg=$errorMsg")
                AlertDialog.Builder(this)
                    .setTitle("Installation failed: $appName")
                    .setMessage("$errorMsg\n(code: $extraStatus)")
                    .setPositiveButton("OK", null)
                    .show()
            }
            else -> {
                Log.w(TAG, "Unknown install result: $resultCode for $appName")
                Toast.makeText(this, "Install result: $resultCode", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /** On success Android restarts the app, so this normally only runs when the update failed. */
    private fun handleSelfUpdateResult(resultCode: Int, data: Intent?) {
        Log.d(TAG, "Self-update result: resultCode=$resultCode extras=${data?.extras}")
        if (resultCode == Activity.RESULT_OK) return

        val status = data?.getIntExtra("android.intent.extra.INSTALL_RESULT", 0) ?: 0
        val msg = if (resultCode == Activity.RESULT_CANCELED && status == 0) {
            "The update was cancelled or not installed."
        } else {
            "The update could not be installed (code $status).\n\n" +
                    "If the installed AAAI was signed with a different key than this version " +
                    "(for example a debug build), Android refuses to update it. " +
                    "Uninstall AAAI from Settings > Apps and install the new version again. " +
                    "The app's saved data will be lost."
        }
        val mandatory = pendingUpdate?.mandatory == true
        updateErrorDialog = AlertDialog.Builder(this)
            .setTitle("AAAI update")
            .setMessage(msg)
            .setCancelable(!mandatory)
            .setPositiveButton("OK") { _, _ ->
                val pending = pendingUpdate
                if (pending != null && pending.mandatory) showUpdateDialog(pending)
            }
            .show()
    }

    private fun installResultToMessage(code: Int): String {
        return when (code) {
            -1 -> "Installation failed (unknown error)"
            1 -> "Success"
            -2 -> "Already installed with a different signature.\n\nGo to Settings → Apps → find 'AAStream' (may appear as renamed) → Uninstall it, then try again."
            -3 -> "Insufficient storage space"
            -4 -> "Failed to delete an existing package"
            -5 -> "Package failed to verify"
            -6 -> "Package is not compatible with this device (wrong architecture or SDK)"
            -7 -> "Package failed signature verification"
            -8 -> "Package UID has changed"
            -9 -> "Package manager service failure"
            -10 -> "Malformed package"
            -11 -> "Package not signed"
            -12 -> "Provider conflict with installed app"
            -13 -> "Shared user mismatch"
            -14 -> "Package failed: installation request downgrade"
            -15 -> "New package code is older than installed package code"
            -16 -> "Package permissions mismatch"
            -17 -> "Package has restricted permissions"
            -18 -> "No matching ABIs (device architecture not supported by this APK)"
            -103 -> "Permission conflict with the original app.\n\n" +
                    "The Android system still has permissions registered from a previous installation.\n\n" +
                    "Fix: Restart the device and try again. If it persists, go to Settings → Apps → show all → search 'AAStream' and uninstall any remnant."
            -112 -> "Insufficient storage space"
            -124 -> "Package failed verification or is blocked by device policy"
            else -> "Installation failed (code: $code)"
        }
    }
}
