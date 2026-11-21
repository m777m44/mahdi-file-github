package io.github.mahdi.uploader

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.Switch
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    override fun attachBaseContext(newBase: android.content.Context) {
        val config = android.content.res.Configuration(newBase.resources.configuration)
        config.setLocale(java.util.Locale("ar"))
        super.attachBaseContext(newBase.createConfigurationContext(config))
    }

    private val scope = CoroutineScope(Dispatchers.Main + Job())

    private var api: GitHubClient? = null
    private var repos: MutableList<org.json.JSONObject> = mutableListOf()
    private var adapter: RepoAdapter? = null

    private lateinit var loginSection: View
    private lateinit var mainSection: View
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

    private enum class Kind { FILE, FOLDER, ZIP }
    private data class Pending(val uri: Uri, val kind: Kind, val relPath: String, val size: Long)

    private var pendingDocs = mutableListOf<Pending>()
    private var zipAsIs = false

    // ------------------------------------------------------------ launchers

    private val pickFiles = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNullOrEmpty()) return@registerForActivityResult
        scope.launch {
            val list = withContext(Dispatchers.IO) {
                uris.mapNotNull { uri -> docInfo(uri)?.let { (name, size) -> Pending(uri, Kind.FILE, name, size) } }
            }
            pendingDocs.removeAll { it.kind == Kind.FILE }
            pendingDocs.addAll(list)
            refreshPickedSummary()
        }
    }

    private val pickFolder = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        scope.launch {
            val list = withContext(Dispatchers.IO) { walkTree(uri) }
            pendingDocs.removeAll { it.kind != Kind.FILE || it.relPath.contains('/') }
            pendingDocs.addAll(list)
            refreshPickedSummary()
        }
    }

    private val pickZip = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNullOrEmpty()) return@registerForActivityResult
        scope.launch {
            val list = withContext(Dispatchers.IO) {
                uris.mapNotNull { uri ->
                    docInfo(uri)?.let { (name, size) ->
                        Pending(uri, Kind.ZIP, name, size)
                    }
                }
            }
            pendingDocs.removeAll { it.kind == Kind.ZIP }
            pendingDocs.addAll(list)
            refreshPickedSummary()
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
        mainSection = findViewById(R.id.mainSection)
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
        repoSearch.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                adapter?.filter(s?.toString().orEmpty())
            }
        })
        fabCreateRepo.setOnClickListener { showCreateRepoDialog() }
    }

    // ------------------------------------------------------------ login

    private fun showLogin() {
        loginSection.isVisible = true
        mainSection.isVisible = false
        deviceCodeCard.isVisible = false
        loginStatus.text = ""
    }

    private fun showMain() {
        loginSection.isVisible = false
        mainSection.isVisible = true
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
                Toast.makeText(this@MainActivity, getString(R.string.welcome, user.optString("login")),
                    Toast.LENGTH_SHORT).show()
                showMain()
                loadRepos()
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
                    val code = withContext(Dispatchers.IO) { apiOrNew().requestDeviceCode(clientId) }
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

    private fun apiOrNew(): GitHubClient = api ?: GitHubClient("").also { }

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
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, getString(R.string.load_failed, e.message ?: ""),
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

    private fun showCreateRepoDialog(prefillName: String = "") {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_create_repo, null)
        val name = view.findViewById<EditText>(R.id.repoNameInput)
        val desc = view.findViewById<EditText>(R.id.repoDescInput)
        val priv = view.findViewById<Switch>(R.id.repoPrivateSwitch)
        name.setText(prefillName)
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
                val repo = withContext(Dispatchers.IO) { client.createRepo(name, private, description) }
                Toast.makeText(this@MainActivity,
                    getString(R.string.repo_created, repo.optString("full_name")),
                    Toast.LENGTH_SHORT).show()
                loadRepos()
                openUploadSheet(repo)
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, getString(R.string.repo_create_failed, e.message ?: ""),
                    Toast.LENGTH_LONG).show()
            } finally {
                reposProgress.isVisible = false
            }
        }
    }

    // ------------------------------------------------------------ upload

    private fun docInfo(uri: Uri): Pair<String, Long>? {
        return try {
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIdx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                val sizeIdx = cursor.getColumnIndex(android.provider.OpenableColumns.SIZE)
                if (cursor.moveToFirst()) {
                    val name = if (nameIdx >= 0) cursor.getString(nameIdx) else uri.toString().substringAfterLast('/')
                    val size = if (sizeIdx >= 0 && !cursor.isNull(sizeIdx)) cursor.getLong(sizeIdx) else 0L
                    Pair(UploadEngine.cleanPath(name).substringAfterLast('/'), size)
                } else null
            } ?: Pair(UploadEngine.cleanPath(uri.toString()).substringAfterLast('/'), 0L)
        } catch (_: Exception) {
            null
        }
    }

    private fun walkTree(treeUri: Uri): List<Pending> {
        val root = DocumentFile.fromTreeUri(this, treeUri) ?: return emptyList()
        val out = mutableListOf<Pending>()
        fun walk(dir: DocumentFile, prefix: String) {
            for (child in dir.listFiles()) {
                val childName = child.name ?: continue
                val path = if (prefix.isEmpty()) childName else "$prefix/$childName"
                if (child.isDirectory) walk(child, path)
                else out.add(Pending(child.uri, Kind.FILE, path, child.length()))
            }
        }
        val rootName = root.name ?: ""
        val children = mutableListOf<Pending>()
        for (child in root.listFiles()) {
            val childName = child.name ?: continue
            val path = "$rootName/$childName"
            if (child.isDirectory) walk(child, path)
            else children.add(Pending(child.uri, Kind.FILE, path, child.length()))
        }
        // keep a sticky folder marker so the picker caches what was chosen
        if (children.isEmpty() && root.listFiles().isEmpty()) return emptyList()
        return children
    }

    private fun refreshPickedSummary() {
        val sheet = uploadSheet ?: return
        val summary = sheet.findViewById<TextView>(R.id.pickedSummary) ?: return
        val totalSize = pendingDocs.sumOf { it.size }
        val names = pendingDocs.take(12).joinToString("\n") {
            "• ${it.relPath} (${Formatter.formatShortFileSize(this, it.size)})"
        }
        summary.text = if (pendingDocs.isEmpty()) getString(R.string.nothing_picked)
        else getString(R.string.picked_summary, pendingDocs.size, names,
            Formatter.formatShortFileSize(this, totalSize))
    }

    private fun openUploadSheet(repo: org.json.JSONObject) {
        pendingDocs.clear()
        zipAsIs = false
        val view = LayoutInflater.from(this).inflate(R.layout.sheet_upload, null)
        val dialog = BottomSheetDialog(this)
        dialog.setContentView(view)
        uploadSheet = dialog

        val repoInput = view.findViewById<EditText>(R.id.uploadRepoInput)
        val branchInput = view.findViewById<EditText>(R.id.uploadBranchInput)
        val prefixInput = view.findViewById<EditText>(R.id.uploadPrefixInput)
        val messageInput = view.findViewById<EditText>(R.id.uploadMessageInput)
        val zipModeGroup = view.findViewById<android.widget.RadioGroup>(R.id.zipModeGroup)
        val zipModeRow = view.findViewById<View>(R.id.zipModeRow)
        val progress = view.findViewById<LinearProgressIndicator>(R.id.uploadProgress)
        val progressLabel = view.findViewById<TextView>(R.id.progressLabel)
        val pickedSummary = view.findViewById<TextView>(R.id.pickedSummary)
        val uploadBtn = view.findViewById<MaterialButton>(R.id.btnUpload)
        val allInputs = listOf(repoInput, branchInput, prefixInput, messageInput)

        repoInput.setText(repo.optString("full_name"))
        branchInput.hint = getString(R.string.default_branch_hint, repo.optString("default_branch"))

        fun updateZipRow() {
            val hasZip = pendingDocs.any { it.kind == Kind.ZIP }
            zipModeRow.isVisible = hasZip
        }
        updateZipRow()

        view.findViewById<MaterialButton>(R.id.btnPickFiles).setOnClickListener {
            pickFiles.launch(arrayOf("*/*"))
        }
        view.findViewById<MaterialButton>(R.id.btnPickFolder).setOnClickListener {
            pickFolder.launch(null)
        }
        view.findViewById<MaterialButton>(R.id.btnPickZip).setOnClickListener {
            pickZip.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream"))
        }
        zipModeGroup.setOnCheckedChangeListener { _, _ ->
            zipAsIs = view.findViewById<android.widget.RadioButton>(R.id.zipAsIs).isChecked
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

            uploadBtn.isEnabled = false
            allInputs.forEach { it.isEnabled = false }
            progress.isVisible = true

            scope.launch {
                try {
                    val items = buildUploadTasks(prefix)

                    val actualRepo = withContext(Dispatchers.IO) { client.getRepo(repoFull) }
                    val targetBranch = branch.ifEmpty { actualRepo.optString("default_branch") }

                    val finalResult = UploadEngine.upload(
                        client, repoFull, targetBranch, items, message,
                    ) { done, total, current ->
                        scope.launch {
                            progress.progress = (done * 100 / total.coerceAtLeast(1))
                            progressLabel.text = getString(R.string.uploading_current, current)
                        }
                    }

                    val summary = buildString {
                        append(getString(R.string.upload_done, finalResult.uploaded.size, repoFull, targetBranch))
                        if (finalResult.commits > 0) append("\n" + getString(R.string.commits_count, finalResult.commits))
                        if (finalResult.failed.isNotEmpty()) {
                            append("\n\n" + getString(R.string.failed_files, finalResult.failed.size))
                            finalResult.failed.take(10).forEach { append("\n• ${it.first} — ${it.second}") }
                        }
                    }
                    MaterialAlertDialogBuilder(this@MainActivity)
                        .setTitle(getString(R.string.upload_result_title))
                        .setMessage(summary)
                        .setPositiveButton(getString(R.string.open_repo)) { _, _ ->
                            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(actualRepo.optString("html_url"))))
                        }
                        .setNeutralButton(getString(R.string.close), null)
                        .show()
                    dialog.dismiss()
                    loadRepos()
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

    private suspend fun buildUploadTasks(prefix: String): List<UploadEngine.Item> =
        withContext(Dispatchers.IO) {
            val client = api ?: return@withContext emptyList()
            val items = mutableListOf<UploadEngine.Item>()
            for (doc in pendingDocs) {
                when (doc.kind) {
                    Kind.FILE -> {
                        val path = if (prefix.isEmpty()) doc.relPath else "$prefix/${doc.relPath}"
                        val uri = doc.uri
                        items.add(UploadEngine.Item(path, doc.size) { UploadEngine.readBytes(applicationContext, uri) })
                    }
                    Kind.ZIP -> {
                        if (zipAsIs) {
                            val path = if (prefix.isEmpty()) doc.relPath else "$prefix/${doc.relPath}"
                            val uri = doc.uri
                            items.add(UploadEngine.Item(path, doc.size) { UploadEngine.readBytes(applicationContext, uri) })
                        } else {
                            val extracted = UploadEngine.extractZip(applicationContext, doc.uri, doc.relPath)
                            for (item in extracted) {
                                items.add(UploadEngine.Item(
                                    if (prefix.isEmpty()) item.path else "$prefix/${item.path}",
                                    item.size,
                                    item.read,
                                ))
                            }
                        }
                    }
                    Kind.FOLDER -> {}
                }
            }
            items
        }
}

class RepoAdapter(
    private var source: List<org.json.JSONObject>,
    private val onClick: (org.json.JSONObject) -> Unit,
) : RecyclerView.Adapter<RepoAdapter.Holder>() {

    private var shown: List<org.json.JSONObject> = source
    private val dateFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    class Holder(val root: View) : RecyclerView.ViewHolder(root) {
        val name: TextView = root.findViewById(R.id.repoName)
        val badge: TextView = root.findViewById(R.id.repoBadge)
        val desc: TextView = root.findViewById(R.id.repoDesc)
        val meta: TextView = root.findViewById(R.id.repoMeta)
        val upload: MaterialButton = root.findViewById(R.id.btnUploadSmall)
    }

    override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): Holder {
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
        holder.meta.text = "الفرع: ${repo.optString("default_branch")}  •  آخر تحديث: $updated"
        holder.root.setOnClickListener { onClick(repo) }
        holder.upload.setOnClickListener { onClick(repo) }
    }

    fun filter(query: String) {
        val q = query.trim().lowercase(Locale.ROOT)
        shown = if (q.isEmpty()) source
        else source.filter { it.optString("full_name").lowercase(Locale.ROOT).contains(q) ||
                it.optString("description").lowercase(Locale.ROOT).contains(q) }
        notifyDataSetChanged()
    }

    fun replace(newList: List<org.json.JSONObject>) {
        source = newList
        shown = newList
        notifyDataSetChanged()
    }
}
