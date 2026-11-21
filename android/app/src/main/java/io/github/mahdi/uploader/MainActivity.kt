package io.github.mahdi.uploader

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.Editable
import android.text.TextWatcher
import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.documentfile.provider.DocumentFile
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale

class MainActivity : AppCompatActivity() {

    override fun attachBaseContext(newBase: Context) {
        val config = android.content.res.Configuration(newBase.resources.configuration)
        config.setLocale(java.util.Locale("ar"))
        super.attachBaseContext(newBase.createConfigurationContext(config))
    }

    private val scope = CoroutineScope(Dispatchers.Main + Job())

    private var api: GitHubClient? = null
    private var repos: MutableList<org.json.JSONObject> = mutableListOf()
    private var adapter: RepoAdapter? = null

    private lateinit var loginSection: View
    private lateinit var deviceCodeCard: View
    private lateinit var userCodeText: TextView
    private lateinit var loginStatus: TextView
    private lateinit var patInput: EditText
    private lateinit var btnOpenBrowser: MaterialButton
    private lateinit var btnLoginGitHub: MaterialButton
    private lateinit var btnPatLogin: MaterialButton

    private lateinit var headerLogin: TextView
    private lateinit var btnSettings: ImageButton
    private lateinit var btnLogout: ImageButton
    private lateinit var repoSearch: EditText
    private lateinit var reposList: RecyclerView
    private lateinit var reposProgress: ProgressBar
    private lateinit var fabCreateRepo: com.google.android.material.floatingactionbutton.FloatingActionButton

    private var uploadSheet: BottomSheetDialog? = null
    private var stagingDir: File? = null

    private enum class Kind { FILE, FOLDER, ZIP }

    private data class Pending(
        val stagedFile: File,
        val kind: Kind,
        val relPath: String,
        val size: Long,
    )

    private var pendingDocs = mutableListOf<Pending>()
    private var zipAsIs = false
    private var pickErrors = mutableListOf<Pair<String, String>>()

    private fun newStagingDir(): File {
        stagingDir?.deleteRecursively()
        val dir = File(cacheDir, "upload_staging_${System.currentTimeMillis()}").apply { mkdirs() }
        stagingDir = dir
        return dir
    }

    private fun persistPermission(uri: Uri) {
        try {
            contentResolver.takePersistableUriPermission(
                uri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                    or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        } catch (_: Exception) {
        }
    }

    /** Copy a picked document into app-private storage immediately so uploads
        never depend on the original provider being reachable later. */
    private fun stageDocument(uri: Uri, relPath: String): File {
        val dir = stagingDir ?: newStagingDir()
        val safe = UploadEngine.stableName(relPath)
        val ext = if (safe.substringAfterLast('.', "").isEmpty()) "bin" else safe.substringAfterLast(".")
        val stem = safe.substringBeforeLast('.', safe)
        var target = File(dir, safe)
        var n = 1
        while (target.exists()) {
            target = File(dir, stem + "_" + n + "." + ext)
            n++
        }
        val input = openReadably(uri)
        input.use { stream -> target.outputStream().use { output -> stream.copyTo(output) } }
        if (target.length() == 0L) {
            target.delete()
            throw Exception(getString(R.string.empty_or_unreadable, relPath))
        }
        return target
    }

    /** Tries several reading strategies with retries: a plain stream, a file
        descriptor, and (last resort) the raw path reported by the provider,
        so flaky providers or freshly moved files do not break staging. */
    private fun openReadably(uri: Uri): java.io.InputStream {
        var lastError: Exception? = null
        repeat(3) { attempt ->
            try {
                contentResolver.openInputStream(uri)?.let { return it }
            } catch (e: Exception) {
                lastError = e
            } catch (t: Throwable) {
                lastError = Exception(t.message, t)
            }
            try {
                val pfd = contentResolver.openFileDescriptor(uri, "r")
                if (pfd != null) {
                    return android.os.ParcelFileDescriptor.AutoCloseInputStream(pfd)
                }
            } catch (e: Exception) {
                lastError = e
            } catch (t: Throwable) {
                lastError = Exception(t.message, t)
            }
            try {
                contentResolver.query(uri, null, null, null, null)?.use { c ->
                    val dataIdx = c.getColumnIndex("_data")
                    if (dataIdx >= 0 && c.moveToFirst()) {
                        val path = c.getString(dataIdx)
                        if (!path.isNullOrBlank() && android.os.Build.VERSION.SDK_INT < 30) {
                            val f = File(path)
                            if (f.exists() && f.canRead()) return f.inputStream()
                        }
                    }
                }
            } catch (e: Exception) {
                lastError = e
            }
            Thread.sleep(400L * (attempt + 1))
        }
        throw Exception(getString(R.string.cannot_read_file, lastError?.message ?: uri.toString()))
    }

    private fun displayName(uri: Uri): String {
        try {
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && cursor.moveToFirst()) {
                    val name = cursor.getString(idx)
                    if (!name.isNullOrBlank()) return name
                }
            }
        } catch (_: Exception) {
        }
        return uri.lastPathSegment ?: "file"
    }

    // ------------------------------------------------------------ launchers

    private val pickFiles = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNullOrEmpty()) return@registerForActivityResult
        scope.launch {
            val errors = mutableListOf<Pair<String, String>>()
            val staged = mutableListOf<Pending>()
            withContext(Dispatchers.IO) {
                for (uri in uris) {
                    persistPermission(uri)
                    val name = displayName(uri)
                    try {
                        val file = stageDocument(uri, name)
                        synchronized(staged) {
                            staged.add(Pending(file, Kind.FILE, name, file.length()))
                        }
                    } catch (e: Exception) {
                        errors.add(name to (e.message ?: "تعذر النسخ"))
                    }
                }
            }
            pendingDocs.removeAll { it.kind == Kind.FILE }
            pendingDocs.addAll(staged)
            pickErrors = errors
            refreshPickedSummary()
        }
    }

    private val pickFolder = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        persistPermission(uri)
        scope.launch {
            val errors = mutableListOf<Pair<String, String>>()
            val staged = mutableListOf<Pending>()
            withContext(Dispatchers.IO) {
                val root = DocumentFile.fromTreeUri(this@MainActivity, uri)
                    ?: return@withContext
                val rootName = root.name ?: ""
                for (child in root.listFiles()) {
                    val childName = child.name ?: continue
                    val path = "${rootName}/${childName}"
                    if (child.isDirectory) {
                        walkAndStage(child, path, dirStaging(), staged, errors)
                    } else {
                        try {
                            val file = stageDocument(child.uri, path)
                            synchronized(staged) {
                                staged.add(Pending(file, Kind.FILE, path, file.length()))
                            }
                        } catch (e: Exception) {
                            errors.add(path to (e.message ?: "تعذر النسخ"))
                        }
                    }
                }
            }
            pendingDocs.removeAll { it.relPath.contains('/') || it.kind == Kind.ZIP }
            pendingDocs.addAll(staged)
            pickErrors = errors
            refreshPickedSummary()
        }
    }

    private val pickZip = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNullOrEmpty()) return@registerForActivityResult
        scope.launch {
            val errors = mutableListOf<Pair<String, String>>()
            val staged = mutableListOf<Pending>()
            withContext(Dispatchers.IO) {
                for (uri in uris) {
                    persistPermission(uri)
                    val name = displayName(uri)
                    try {
                        val file = stageDocument(uri, name)
                        synchronized(staged) {
                            staged.add(Pending(file, Kind.ZIP, name, file.length()))
                        }
                    } catch (e: Exception) {
                        errors.add(name to (e.message ?: "تعذر النسخ"))
                    }
                }
            }
            pendingDocs.removeAll { it.kind == Kind.ZIP }
            pendingDocs.addAll(staged)
            pickErrors = errors
            refreshPickedSummary()
        }
    }

    private fun dirStaging(): File = stagingDir ?: newStagingDir()

    private fun walkAndStage(
        dir: DocumentFile,
        prefix: String,
        staging: File,
        out: MutableList<Pending>,
        errors: MutableList<Pair<String, String>>,
    ) {
        for (child in dir.listFiles()) {
            val name = child.name ?: continue
            val path = "${prefix}/${name}"
            if (child.isDirectory) {
                walkAndStage(child, path, staging, out, errors)
            } else {
                try {
                    val file = stageDocument(child.uri, path)
                    synchronized(out) {
                        out.add(Pending(file, Kind.FILE, path, file.length()))
                    }
                } catch (e: Exception) {
                    errors.add(path to (e.message ?: "تعذر النسخ"))
                }
            }
        }
    }

    // ------------------------------------------------------------ lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        bindViews()
        setupListeners()

        val token = Store.token(this)
        if (token.isNotEmpty()) {
            api = GitHubClient(token)
            showMain()
            loadRepos()
        } else {
            showLogin()
        }
    }

    private fun bindViews() {
        loginSection = findViewById(R.id.loginSection)
        deviceCodeCard = findViewById(R.id.deviceCodeCard)
        userCodeText = findViewById(R.id.userCodeText)
        loginStatus = findViewById(R.id.loginStatus)
        patInput = findViewById(R.id.patInput)
        btnOpenBrowser = findViewById(R.id.btnOpenBrowser)
        btnLoginGitHub = findViewById(R.id.btnLoginGitHub)
        btnPatLogin = findViewById(R.id.btnPatLogin)
        headerLogin = findViewById(R.id.headerLogin)
        btnSettings = findViewById(R.id.btnSettings)
        btnLogout = findViewById(R.id.btnLogout)
        repoSearch = findViewById(R.id.repoSearch)
        reposList = findViewById(R.id.reposList)
        reposProgress = findViewById(R.id.reposProgress)
        fabCreateRepo = findViewById(R.id.fabCreateRepo)
    }

    private fun setupListeners() {
        btnLoginGitHub.setOnClickListener { startDeviceFlow() }
        btnOpenBrowser.setOnClickListener {
            val url = btnOpenBrowser.tag as? String
            if (url != null) startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }
        btnPatLogin.setOnClickListener { loginWithPat() }
        btnSettings.setOnClickListener { showSettingsDialog() }
        btnLogout.setOnClickListener {
            Store.setToken(this, "")
            api = null
            repos.clear()
            showLogin()
        }
        repoSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                adapter?.filter(s?.toString().orEmpty())
            }
        })
        fabCreateRepo.setOnClickListener { showCreateRepoDialog() }
    }

    // ------------------------------------------------------------ login

    private fun showLogin() {
        loginSection.isVisible = true
        mainSectionHidden()
        deviceCodeCard.isVisible = false
        loginStatus.text = ""
    }

    private fun mainSectionHidden() {
        findViewById<View>(R.id.mainSection).isVisible = false
    }

    private fun showMain() {
        loginSection.isVisible = false
        findViewById<View>(R.id.mainSection).isVisible = true
        headerLogin.text = Store.login(this).ifEmpty { getString(R.string.app_name) }
    }

    private fun ensureClientId(then: (String) -> Unit) {
        val existing = Store.clientId(this)
        if (existing.isNotEmpty()) {
            then(existing)
            return
        }
        showSettingsDialog { then(it) }
    }

    private fun showSettingsDialog(onSaved: (String) -> Unit = {}) {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_settings, null)
        val input = view.findViewById<EditText>(R.id.clientIdInput)
        input.setText(Store.clientId(this))
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.settings_title))
            .setView(view)
            .setPositiveButton(getString(R.string.save)) { _, _ ->
                val id = input.text.toString().trim()
                if (id.isNotEmpty()) {
                    Store.setClientId(this, id)
                    onSaved(id)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun loginWithPat() {
        val token = patInput.text.toString().trim()
        if (token.isEmpty()) {
            Toast.makeText(this, getString(R.string.enter_pat), Toast.LENGTH_SHORT).show()
            return
        }
        loginStatus.text = getString(R.string.checking_token)
        scope.launch {
            try {
                val client = GitHubClient(token)
                val user = withContext(Dispatchers.IO) { client.getUser() }
                Store.setToken(this@MainActivity, token)
                Store.setLogin(this@MainActivity, user.optString("login"))
                api = client
                Toast.makeText(this@MainActivity,
                    getString(R.string.welcome, user.optString("login")),
                    Toast.LENGTH_SHORT).show()
                showMain()
                loadRepos()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                loginStatus.text = getString(R.string.login_failed, e.message ?: "")
            }
        }
    }

    private fun startDeviceFlow() {
        ensureClientId { clientId ->
            scope.launch {
                try {
                    loginStatus.text = getString(R.string.requesting_code)
                    val code = withContext(Dispatchers.IO) {
                        (api ?: GitHubClient("")).requestDeviceCode(clientId)
                    }
                    val userCode = code.getString("user_code")
                    val uri = code.getString("verification_uri")
                    val interval = code.optInt("interval", 5).coerceAtLeast(3)
                    deviceCodeCard.isVisible = true
                    userCodeText.text = userCode
                    btnOpenBrowser.tag = uri
                    loginStatus.text = getString(R.string.enter_code_at)
                    pollDeviceFlow(clientId, code.getString("device_code"), interval)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    loginStatus.text = getString(R.string.login_failed, e.message ?: "")
                }
            }
        }
    }

    private fun pollDeviceFlow(clientId: String, deviceCode: String, interval: Int) {
        scope.launch {
            var currentInterval = interval
            while (isActive) {
                delay(currentInterval * 1000L)
                try {
                    val result = withContext(Dispatchers.IO) {
                        GitHubClient("").pollForToken(clientId, deviceCode)
                    }
                    val token = result.optString("access_token")
                    val error = result.optString("error")
                    when {
                        token.isNotEmpty() -> {
                            val client = GitHubClient(token)
                            val user = withContext(Dispatchers.IO) { client.getUser() }
                            Store.setToken(this@MainActivity, token)
                            Store.setLogin(this@MainActivity, user.optString("login"))
                            api = client
                            Toast.makeText(this@MainActivity,
                                getString(R.string.welcome, user.optString("login")),
                                Toast.LENGTH_SHORT).show()
                            showMain()
                            loadRepos()
                            return@launch
                        }
                        error == "authorization_pending" -> continue
                        error == "slow_down" -> { currentInterval += 5; continue }
                        else -> {
                            loginStatus.text = getString(
                                R.string.login_failed,
                                result.optString("error_description", error),
                            )
                            return@launch
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    loginStatus.text = getString(R.string.login_failed, e.message ?: "")
                    return@launch
                }
            }
        }
    }

    // ------------------------------------------------------------ repos

    private fun loadRepos() {
        val client = api ?: return
        reposProgress.isVisible = true
        scope.launch {
            try {
                val result = client.listRepos()
                repos = (0 until result.length()).map { result.getJSONObject(it) }.toMutableList()
                repos.sortByDescending { it.optString("updated_at") }
                setupAdapter()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity,
                    getString(R.string.load_failed, e.message ?: ""),
                    Toast.LENGTH_LONG).show()
            } finally {
                reposProgress.isVisible = false
            }
        }
    }

    private fun setupAdapter() {
        if (adapter == null) {
            adapter = RepoAdapter(repos) { repo -> openUploadSheet(repo) }
            reposList.layoutManager = LinearLayoutManager(this)
            reposList.adapter = adapter
        } else {
            adapter!!.replace(repos)
        }
        adapter!!.filter(repoSearch.text?.toString().orEmpty())
    }

    private fun showCreateRepoDialog() {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_create_repo, null)
        val name = view.findViewById<EditText>(R.id.repoNameInput)
        val desc = view.findViewById<EditText>(R.id.repoDescInput)
        val priv = view.findViewById<android.widget.Switch>(R.id.repoPrivateSwitch)
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.create_repo_title))
            .setView(view)
            .setPositiveButton(getString(R.string.create)) { _, _ ->
                val repoName = name.text.toString().trim()
                if (repoName.isEmpty()) return@setPositiveButton
                createRepo(repoName, priv.isChecked, desc.text.toString().trim())
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun createRepo(name: String, private: Boolean, description: String) {
        val client = api ?: return
        reposProgress.isVisible = true
        scope.launch {
            try {
                val repo = withContext(Dispatchers.IO) {
                    client.createRepo(name, private, description)
                }
                Toast.makeText(this@MainActivity,
                    getString(R.string.repo_created, repo.optString("full_name")),
                    Toast.LENGTH_SHORT).show()
                loadRepos()
                openUploadSheet(repo)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity,
                    getString(R.string.repo_create_failed, e.message ?: ""),
                    Toast.LENGTH_LONG).show()
            } finally {
                reposProgress.isVisible = false
            }
        }
    }

    // ------------------------------------------------------------ upload

    private fun refreshPickedSummary() {
        val sheet = uploadSheet ?: return
        val summary = sheet.findViewById<TextView>(R.id.pickedSummary) ?: return
        val sb = StringBuilder()
        if (pendingDocs.isEmpty()) {
            sb.append(getString(R.string.nothing_picked))
        } else {
            val totalSize = pendingDocs.sumOf { it.size }
            sb.append(getString(
                R.string.picked_summary_short,
                pendingDocs.size,
                Formatter.formatShortFileSize(this, totalSize),
            ))
            sb.append('\n')
            pendingDocs.take(15).forEach {
                sb.append("• ").append(it.relPath)
                    .append(" (").append(Formatter.formatShortFileSize(this, it.size)).append(")\n")
            }
            if (pendingDocs.size > 15) {
                sb.append(getString(R.string.more_files, pendingDocs.size - 15)).append('\n')
            }
        }
        if (pickErrors.isNotEmpty()) {
            sb.append('\n').append(getString(R.string.stage_errors, pickErrors.size)).append('\n')
            pickErrors.forEach { (name, err) -> sb.append("⚠ ").append(name).append(": ").append(err).append('\n') }
        }
        summary.text = sb.toString()
    }

    private fun openUploadSheet(repo: org.json.JSONObject) {
        pendingDocs.clear()
        pickErrors.clear()
        zipAsIs = false
        newStagingDir()

        val view = LayoutInflater.from(this).inflate(R.layout.sheet_upload, null)
        val dialog = BottomSheetDialog(this)
        dialog.setContentView(view)
        uploadSheet = dialog

        val repoInput = view.findViewById<EditText>(R.id.uploadRepoInput)
        val branchInput = view.findViewById<EditText>(R.id.uploadBranchInput)
        val prefixInput = view.findViewById<EditText>(R.id.uploadPrefixInput)
        val messageInput = view.findViewById<EditText>(R.id.uploadMessageInput)
        val zipModeGroup = view.findViewById<RadioGroup>(R.id.zipModeGroup)
        val zipModeRow = view.findViewById<View>(R.id.zipModeRow)
        val progress = view.findViewById<LinearProgressIndicator>(R.id.uploadProgress)
        val progressLabel = view.findViewById<TextView>(R.id.progressLabel)
        val uploadBtn = view.findViewById<MaterialButton>(R.id.btnUpload)
        val allInputs = listOf(repoInput, branchInput, prefixInput, messageInput)

        repoInput.setText(repo.optString("full_name"))
        branchInput.hint = getString(R.string.default_branch_hint, repo.optString("default_branch"))

        fun updateZipRow() {
            zipModeRow.isVisible = pendingDocs.any { it.kind == Kind.ZIP }
        }
        updateZipRow()

        view.findViewById<MaterialButton>(R.id.btnPickFiles).setOnClickListener {
            pickFiles.launch(arrayOf("*/*"))
        }
        view.findViewById<MaterialButton>(R.id.btnPickFolder).setOnClickListener {
            pickFolder.launch(null)
        }
        view.findViewById<MaterialButton>(R.id.btnPickZip).setOnClickListener {
            pickZip.launch(arrayOf("application/zip", "application/x-zip-compressed"))
        }
        view.findViewById<MaterialButton>(R.id.btnAdvancedToggle).setOnClickListener { btn ->
            val box = view.findViewById<View>(R.id.advancedBox)
            box.isVisible = !box.isVisible
        }
        zipModeGroup.setOnCheckedChangeListener { _, _ ->
            zipAsIs = view.findViewById<RadioButton>(R.id.zipAsIs).isChecked
        }

        uploadBtn.setOnClickListener {
            val repoFull = repoInput.text.toString().trim()
            if (repoFull.isEmpty() || '/' !in repoFull) {
                Toast.makeText(this, getString(R.string.pick_repo_first), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (pendingDocs.isEmpty()) {
                Toast.makeText(this, getString(R.string.pick_files_first), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val client = api ?: return@setOnClickListener
            val branch = branchInput.text.toString().trim()
            val prefix = UploadEngine.cleanPath(prefixInput.text.toString().trim())
            val message = messageInput.text.toString().trim()
            val snapshot = pendingDocs.toList()

            uploadBtn.isEnabled = false
            allInputs.forEach { it.isEnabled = false }
            progress.isVisible = true

            scope.launch {
                try {
                    val actualRepo = withContext(Dispatchers.IO) { client.getRepo(repoFull) }
                    val targetBranch = branch.ifEmpty { actualRepo.optString("default_branch") }

                    val items = mutableListOf<UploadEngine.Item>()
                    for (doc in snapshot) {
                        when (doc.kind) {
                            Kind.FILE -> {
                                val path = if (prefix.isEmpty()) doc.relPath else "${prefix}/${doc.relPath}"
                                val f = doc.stagedFile
                                items.add(UploadEngine.Item(path, f.length()) { UploadEngine.readBytesFile(f) })
                            }
                            Kind.ZIP -> {
                                if (zipAsIs) {
                                    val path = if (prefix.isEmpty()) doc.relPath else "${prefix}/${doc.relPath}"
                                    val f = doc.stagedFile
                                    items.add(UploadEngine.Item(path, f.length()) { UploadEngine.readBytesFile(f) })
                                } else {
                                    val workDir = File(dirStaging(), "zipwork_${System.nanoTime()}").apply { mkdirs() }
                                    val extracted = UploadEngine.extractZipFile(
                                        doc.stagedFile, doc.relPath, workDir,
                                    )
                                    for (item in extracted) {
                                        items.add(UploadEngine.Item(
                                            if (prefix.isEmpty()) item.path else "${prefix}/${item.path}",
                                            item.size,
                                            item.read,
                                        ))
                                    }
                                }
                            }
                            Kind.FOLDER -> {}
                        }
                    }

                    val result = UploadEngine.upload(
                        client, repoFull, targetBranch, items, message,
                    ) { done, total, current ->
                        scope.launch {
                            progress.progress = (done * 100 / total.coerceAtLeast(1))
                            progressLabel.text = getString(R.string.uploading_current, current)
                        }
                    }

                    val summary = buildString {
                        append(getString(R.string.upload_done, result.uploaded.size, repoFull, targetBranch))
                        if (result.commits > 0) append("\n").append(getString(R.string.commits_count, result.commits))
                        if (result.failed.isNotEmpty()) {
                            append("\n\n").append(getString(R.string.failed_files, result.failed.size))
                            result.failed.take(10).forEach { (path, err) ->
                                append("\n• ").append(path).append(" — ").append(err)
                            }
                        }
                    }
                    MaterialAlertDialogBuilder(this@MainActivity)
                        .setTitle(getString(R.string.upload_result_title))
                        .setMessage(summary)
                        .setPositiveButton(getString(R.string.open_repo)) { _, _ ->
                            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(actualRepo.optString("html_url"))))
                        }
                        .setNegativeButton(getString(R.string.close), null)
                        .show()
                    dialog.dismiss()
                    loadRepos()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    MaterialAlertDialogBuilder(this@MainActivity)
                        .setTitle(getString(R.string.upload_failed_title))
                        .setMessage(e.message ?: "")
                        .setPositiveButton(getString(R.string.close), null)
                        .show()
                } finally {
                    uploadBtn.isEnabled = true
                    allInputs.forEach { it.isEnabled = true }
                    progress.isVisible = false
                    progressLabel.text = ""
                }
            }
        }

        dialog.setOnDismissListener {
            uploadSheet = null
        }
        dialog.show()
        refreshPickedSummary()
    }
}

class RepoAdapter(
    private var source: List<org.json.JSONObject>,
    private val onClick: (org.json.JSONObject) -> Unit,
) : RecyclerView.Adapter<RepoAdapter.Holder>() {

    private var shown: List<org.json.JSONObject> = source

    class Holder(val root: View) : RecyclerView.ViewHolder(root) {
        val name: TextView = root.findViewById(R.id.repoName)
        val badge: TextView = root.findViewById(R.id.repoBadge)
        val desc: TextView = root.findViewById(R.id.repoDesc)
        val meta: TextView = root.findViewById(R.id.repoMeta)
        val upload: MaterialButton = root.findViewById(R.id.btnUploadSmall)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_repo, parent, false)
        return Holder(view)
    }

    override fun getItemCount(): Int = shown.size

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val repo = shown[position]
        holder.name.text = repo.optString("full_name")
        holder.badge.text = if (repo.optBoolean("private"))
            holder.root.context.getString(R.string.badge_private)
        else holder.root.context.getString(R.string.badge_public)
        holder.desc.text = repo.optString("description")
            .ifEmpty { holder.root.context.getString(R.string.no_desc) }
        val updated = repo.optString("updated_at").take(10)
        holder.meta.text = holder.root.context.getString(
            R.string.repo_meta, repo.optString("default_branch"), updated,
        )
        holder.root.setOnClickListener { onClick(repo) }
        holder.upload.setOnClickListener { onClick(repo) }
    }

    fun filter(query: String) {
        val q = query.trim().lowercase(Locale.ROOT)
        shown = if (q.isEmpty()) source
        else source.filter {
            it.optString("full_name").lowercase(Locale.ROOT).contains(q) ||
                it.optString("description").lowercase(Locale.ROOT).contains(q)
        }
        notifyDataSetChanged()
    }

    fun replace(newList: List<org.json.JSONObject>) {
        source = newList
        shown = newList
        notifyDataSetChanged()
    }
}
