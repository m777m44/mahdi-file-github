"""Thin wrapper around the official GitHub REST API v3 used by the app."""

import concurrent.futures
import threading

import requests

API = "https://api.github.com"


class GitHubError(Exception):
    def __init__(self, message, status=None):
        super().__init__(message)
        self.status = status


def _quote_path(path):
    from urllib.parse import quote
    return quote(path, safe="/")


class GitHubClient:
    def __init__(self, token):
        self.token = token
        self._local = threading.local()

    def _headers(self, raw=False):
        headers = {
            "Authorization": f"Bearer {self.token}",
            "Accept": "application/vnd.github+json",
            "X-GitHub-Api-Version": "2022-11-28",
        }
        if raw:
            headers["Content-Type"] = "application/octet-stream"
        else:
            headers["Content-Type"] = "application/json"
        return headers

    def _session(self):
        if not hasattr(self._local, "session"):
            self._local.session = requests.Session()
        return self._local.session

    def request(self, method, path, *, json_body=None, data=None, raw=False,
                params=None, expected=(200, 201, 204)):
        url = path if path.startswith("http") else API + path
        try:
            resp = self._session().request(
                method, url, headers=self._headers(raw=raw), json=json_body,
                data=data, params=params, timeout=300,
            )
        except requests.RequestException as exc:
            raise GitHubError(f"تعذر الاتصال بـ GitHub: {exc}") from exc
        if resp.status_code not in expected:
            try:
                msg = resp.json().get("message", resp.text[:300])
            except ValueError:
                msg = resp.text[:300]
            raise GitHubError(f"GitHub ({resp.status_code}): {msg}", status=resp.status_code)
        return resp

    # ---------------------------------------------------------------- user

    def get_user(self):
        return self.request("GET", "/user").json()

    # -------------------------------------------------------------- repos

    def list_repos(self):
        repos = []
        page = 1
        while True:
            batch = self.request(
                "GET", "/user/repos",
                params={
                    "affiliation": "owner,collaborator,organization_member",
                    "per_page": 100, "page": page, "sort": "updated",
                },
            ).json()
            repos.extend(batch)
            if len(batch) < 100:
                return repos
            page += 1

    def create_repo(self, name, private, description="", auto_init=True):
        return self.request(
            "POST", "/user/repos",
            json_body={
                "name": name, "private": private,
                "description": description or "", "auto_init": auto_init,
            },
        ).json()

    def get_repo(self, full_name):
        return self.request("GET", f"/repos/{full_name}").json()

    # ------------------------------------------------------- git plumbing

    def get_ref(self, full_name, branch):
        return self.request(
            "GET",
            f"/repos/{full_name}/git/ref/heads/{_quote_path(branch)}",
        ).json()

    def create_ref(self, full_name, branch, sha):
        return self.request(
            "POST", f"/repos/{full_name}/git/refs",
            json_body={"ref": f"refs/heads/{branch}", "sha": sha},
        ).json()

    def get_commit(self, full_name, sha):
        return self.request("GET", f"/repos/{full_name}/git/commits/{sha}").json()

    def create_blob(self, full_name, content_bytes):
        import base64
        payload = {
            "content": base64.b64encode(content_bytes).decode("ascii"),
            "encoding": "base64",
        }
        return self.request(
            "POST", f"/repos/{full_name}/git/blobs", json_body=payload,
        ).json()["sha"]

    def create_tree(self, full_name, base_tree, entries):
        return self.request(
            "POST", f"/repos/{full_name}/git/trees",
            json_body={"base_tree": base_tree, "tree": entries},
        ).json()

    def create_commit(self, full_name, message, tree_sha, parent_shas):
        return self.request(
            "POST", f"/repos/{full_name}/git/commits",
            json_body={"message": message, "tree": tree_sha, "parents": parent_shas},
        ).json()

    def update_ref(self, full_name, branch, sha):
        self.request(
            "PATCH", f"/repos/{full_name}/git/refs/heads/{_quote_path(branch)}",
            json_body={"sha": sha, "force": False},
            expected=(200,),
        )

    def put_file(self, full_name, path, content, message, branch):
        return self.request(
            "PUT", f"/repos/{full_name}/contents/{_quote_path(path)}",
            json_body={
                "message": message,
                "content": __import__("base64").b64encode(content).decode("ascii"),
                "branch": branch,
            },
        ).json()


def ensure_commit_base(client, full_name, branch):
    """Return (head_commit_sha, tree_sha) creating an initial commit when the
    repository is completely empty."""
    try:
        ref = client.get_ref(full_name, branch)
        commit = client.get_commit(full_name, ref["object"]["sha"])
        return commit["sha"], commit["tree"]["sha"]
    except GitHubError as exc:
        if exc.status not in (404, 409):
            raise
    result = client.put_file(
        full_name, "README.md",
        f"# {full_name.split('/', 1)[1]}\n\nتم تهيئة هذا المستودع عبر GitHub Uploader.\n".encode("utf-8"),
        "التهيئة الأولى للمستودع", branch,
    )
    commit_sha = result["commit"]["sha"]
    return commit_sha, result["commit"]["tree"]["sha"]


def _make_blob(client, full_name, entry, failures):
    try:
        sha = client.create_blob(full_name, entry.loader())
        return {"path": entry.path, "mode": "100644", "type": "blob", "sha": sha}
    except GitHubError as exc:
        failures.append({"path": entry.path, "error": str(exc)})
        return None


# GitHub hard-limits files at 100 MB; warn before hitting it.
HARD_LIMIT = 100 * 1024 * 1024


def upload_entries(client, full_name, branch, entries, message, progress_cb=None):
    """Upload entries (list of FileEntry) as batched commits on `branch`.

    Returns a report dict with uploaded/skipped/errors lists.
    """
    report = {"uploaded": [], "failed": [], "commits": 0, "too_big": []}

    valid = []
    for entry in entries:
        if entry.size > HARD_LIMIT:
            report["too_big"].append(entry.path)
        else:
            valid.append(entry)

    default_msg = message or "رفع ملفات عبر GitHub Uploader"
    head_sha, tree_sha = ensure_commit_base(client, full_name, branch)

    BATCH_BYTES = int(20 * 1024 * 1024)
    BATCH_FILES = 150

    batch, batch_bytes = [], 0

    def flush():
        nonlocal batch, batch_bytes, head_sha, tree_sha, report
        if not batch:
            return
        failures = []
        with concurrent.futures.ThreadPoolExecutor(max_workers=8) as pool:
            futures = [
                pool.submit(_make_blob, client, full_name, entry, failures)
                for entry in batch
            ]
            tree_entries = [f.result() for f in futures if f.result()]
        report["failed"].extend(failures)
        report["uploaded"].extend(e.path for e in batch if e.path not in {f["path"] for f in failures})
        if tree_entries:
            tree = client.create_tree(full_name, tree_sha, tree_entries)
            commit = client.create_commit(
                full_name,
                f"{default_msg} ({len(tree_entries)} ملف)",
                tree["sha"], [head_sha],
            )
            client.update_ref(full_name, branch, commit["sha"])
            head_sha, tree_sha = commit["sha"], tree["sha"]
            report["commits"] += 1
        batch, batch_bytes = [], 0
        if progress_cb:
            progress_cb(report)

    for entry in valid:
        batch.append(entry)
        batch_bytes += entry.size
        if len(batch) >= BATCH_FILES or batch_bytes >= BATCH_BYTES:
            flush()
    flush()

    if report["too_big"]:
        report["failed"].extend(
            {"path": p, "error": "حجم الملف يتجاوز حد GitHub البالغ 100 ميجابايت"}
            for p in report["too_big"]
        )
    return report


class FileEntry:
    """A single file to upload: repo-relative path plus a lazy loader."""

    __slots__ = ("path", "size", "loader")

    def __init__(self, path, size, loader):
        self.path = path
        self.size = size
        self.loader = loader


IGNORED_NAMES = {".DS_Store", "Thumbs.db", "desktop.ini"}


def clean_rel_path(raw):
    """Normalize a client-provided relative path and block traversal."""
    import os
    parts = [p for p in raw.replace("\\", "/").split("/") if p not in ("", ".", "..")]
    return "/".join(parts)


def entries_from_zip(zip_fileobj, zip_name):
    """Build FileEntry objects for the extracted contents of a zip file.
    The underlying archive must stay open until the loaders are consumed."""
    import posixpath
    from zipfile import ZipFile

    base = clean_rel_path(zip_name)
    if base.lower().endswith(".zip"):
        base = base[:-4]
    else:
        base = ""

    archive = ZipFile(zip_fileobj)
    entries = []
    for info in archive.infolist():
        if info.is_dir():
            continue
        rel = clean_rel_path(info.filename)
        parts = rel.split("/") if rel else []
        if (not rel
                or any(part == ".git" or part == "__MACOSX" for part in parts[:-1])
                or parts[0] in (".git", "__MACOSX")
                or any(p in IGNORED_NAMES for p in parts)):
            continue
        target = posixpath.join(base, rel) if base else rel
        entries.append(FileEntry(
            target, info.file_size,
            lambda i=info: archive.read(i),
        ))
    return entries


def entries_from_zip_bounded(zip_fileobj, zip_name, max_total=800 * 1024 * 1024):
    entries = entries_from_zip(zip_fileobj, zip_name)
    total = sum(e.size for e in entries)
    if total > max_total:
        raise GitHubError(
            f"محتوى ملفات الـ zip ({total // (1024 * 1024)} ميجابايت) أكبر من الحد المسموح محلياً"
        )
    return entries
