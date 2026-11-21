"""GitHub Uploader — تطبيق ويب محلي للدخول إلى GitHub ورفع أي ملفات أو مجلدات.

Usage:
    pip install -r requirements.txt
    python app.py
"""

import json
import os
import secrets
import webbrowser

from flask import (
    Flask, flash, jsonify, redirect, render_template, request, session, url_for,
)

from github_api import (
    GitHubClient, GitHubError, FileEntry, clean_rel_path,
    entries_from_zip_bounded, ensure_commit_base, upload_entries,
)

BASE_DIR = os.path.dirname(os.path.abspath(__file__))
CONFIG_PATH = os.path.join(BASE_DIR, "config.json")

app = Flask(__name__)


def load_config():
    if os.path.exists(CONFIG_PATH):
        with open(CONFIG_PATH, encoding="utf-8") as fh:
            return json.load(fh)
    return {}


def save_config(cfg):
    with open(CONFIG_PATH, "w", encoding="utf-8") as fh:
        json.dump(cfg, fh, ensure_ascii=False, indent=2)
    os.chmod(CONFIG_PATH, 0o600)


CONFIG = load_config()
CONFIG.setdefault("client_id", "")
CONFIG.setdefault("client_secret", "")
CONFIG.setdefault("token", "")
if "secret_key" not in CONFIG:
    CONFIG["secret_key"] = secrets.token_hex(32)
    save_config(CONFIG)
app.secret_key = CONFIG["secret_key"]

OAUTH_SCOPES = "repo read:user"


def client():
    token = session.get("token") or CONFIG.get("token")
    return GitHubClient(token) if token else None


def current_user():
    gh = client()
    if not gh:
        return None, None
    try:
        return gh, gh.get_user()
    except GitHubError:
        return gh, None


@app.context_processor
def inject_globals():
    user = session.get("user")
    return {"user": user}


@app.route("/")
def index():
    if not CONFIG.get("client_id") or not CONFIG.get("client_secret"):
        return render_template("index.html", needs_setup=True,
                               client_id=CONFIG.get("client_id", ""))
    gh, user = current_user()
    if user:
        session["user"] = {"login": user["login"], "avatar": user["avatar_url"]}
        return redirect(url_for("dashboard"))
    return render_template("index.html", needs_setup=False,
                           client_id=CONFIG.get("client_id", ""))


@app.route("/setup", methods=["POST"])
def setup():
    CONFIG["client_id"] = request.form.get("client_id", "").strip()
    CONFIG["client_secret"] = request.form.get("client_secret", "").strip()
    save_config(CONFIG)
    return redirect(url_for("index"))


@app.route("/login")
def login():
    if not CONFIG.get("client_id"):
        return redirect(url_for("index"))
    from urllib.parse import quote
    redirect_uri = url_for("callback", _external=True).replace("http://", "http://")
    return redirect(
        "https://github.com/login/oauth/authorize"
        f"?client_id={quote(CONFIG['client_id'])}"
        f"&redirect_uri={quote(redirect_uri)}"
        f"&scope={quote(OAUTH_SCOPES)}"
    )


@app.route("/callback")
def callback():
    code = request.args.get("code")
    error = request.args.get("error")
    if error or not code:
        flash("تم إلغاء تسجيل الدخول أو فشل الطلب من GitHub.", "error")
        return redirect(url_for("index"))
    import requests as rq
    resp = rq.post(
        "https://github.com/login/oauth/access_token",
        json={
            "client_id": CONFIG["client_id"],
            "client_secret": CONFIG["client_secret"],
            "code": code,
        },
        headers={"Accept": "application/json"},
        timeout=60,
    )
    data = resp.json()
    token = data.get("access_token")
    if not token:
        flash(f"فشل الحصول على التوكن من GitHub: {data.get('error_description', data.get('error', 'خطأ غير معروف'))}", "error")
        return redirect(url_for("index"))
    CONFIG["token"] = token
    save_config(CONFIG)
    session["token"] = token
    session.pop("user", None)
    return redirect(url_for("index"))


@app.route("/logout")
def logout():
    CONFIG["token"] = ""
    save_config(CONFIG)
    session.pop("token", None)
    session.pop("user", None)
    return redirect(url_for("index"))


@app.route("/dashboard")
def dashboard():
    gh, user = current_user()
    if not gh:
        return redirect(url_for("index"))
    if not user:
        return render_template("error.html",
                               message="انتهت صلاحية الجلسة أو التوكن غير صالح. سجّل الدخول من جديد.")
    session["user"] = {"login": user["login"], "avatar": user["avatar_url"]}
    try:
        repos = gh.list_repos()
    except GitHubError as exc:
        return render_template("error.html", message=str(exc))
    repos.sort(key=lambda r: r["updated_at"], reverse=True)
    return render_template("dashboard.html", user=user, repos=repos)


@app.route("/api/repos/create", methods=["POST"])
def api_create_repo():
    gh = client()
    if not gh:
        return jsonify({"ok": False, "error": "غير مسجل الدخول"}), 401
    name = request.form.get("name", "").strip()
    private = request.form.get("private", "") in ("true", "1", "on")
    description = request.form.get("description", "").strip()
    if not name:
        return jsonify({"ok": False, "error": "اسم المستودع مطلوب"}), 400
    try:
        repo = gh.create_repo(name, private, description)
    except GitHubError as exc:
        return jsonify({"ok": False, "error": str(exc)}), 400
    return jsonify({
        "ok": True,
        "full_name": repo["full_name"],
        "html_url": repo["html_url"],
        "default_branch": repo["default_branch"],
    })


@app.route("/api/repo/branch", methods=["GET"])
def api_repo_branch():
    gh = client()
    if not gh:
        return jsonify({"ok": False, "error": "غير مسجل الدخول"}), 401
    full_name = request.args.get("repo", "")
    try:
        repo = gh.get_repo(full_name)
    except GitHubError as exc:
        return jsonify({"ok": False, "error": str(exc)}), 400
    return jsonify({"ok": True,
                    "default_branch": repo["default_branch"],
                    "private": repo["private"],
                    "html_url": repo["html_url"]})


@app.route("/upload", methods=["POST"])
def upload():
    gh = client()
    if not gh:
        return jsonify({"ok": False, "error": "غير مسجل الدخول"}), 401

    full_name = request.form.get("repo", "").strip()
    branch = request.form.get("branch", "").strip()
    prefix = clean_rel_path(request.form.get("prefix", ""))
    message = request.form.get("message", "").strip()
    zip_mode = request.form.get("zip_mode", "extract")

    if not full_name or "/" not in full_name:
        return jsonify({"ok": False, "error": "يجب اختيار مستودع صحيح"}), 400

    try:
        repo = gh.get_repo(full_name)
        if not branch:
            branch = repo["default_branch"]
        else:
            try:
                gh.get_ref(full_name, repo["default_branch"])
            except GitHubError as exc:
                if exc.status in (404, 409):
                    return jsonify({"ok": False, "error": f"المستودع فارغ أو الفرع {repo['default_branch']} غير موجود"}), 400
                raise
            try:
                gh.get_ref(full_name, branch)
            except GitHubError as exc:
                if exc.status not in (404, 409):
                    raise
                default_ref = gh.get_ref(full_name, repo["default_branch"])
                gh.create_ref(full_name, branch, default_ref["object"]["sha"])

        entries = []
        seen = set()

        def add_entry(entry):
            if entry.path and entry.path not in seen:
                seen.add(entry.path)
                entries.append(entry)

        for f in request.files.getlist("files"):
            if not f or not f.filename:
                continue
            rel = clean_rel_path(f.filename)
            if not rel:
                continue
            target = f"{prefix}/{rel}" if prefix else rel
            if f.filename.lower().endswith(".zip") and zip_mode == "extract":
                extracted = entries_from_zip_bounded(f.stream, rel)
                for entry in extracted:
                    add_entry(FileEntry(
                        f"{prefix}/{entry.path}" if prefix else entry.path,
                        entry.size, entry.loader))
            else:
                size = f.content_length
                if size is None:
                    f.stream.seek(0, 2)
                    size = f.stream.tell()
                    f.stream.seek(0)
                add_entry(FileEntry(target, size, f.read))

        if not entries:
            return jsonify({"ok": False, "error": "لم يتم اختيار أي ملفات لرفعها"}), 400

        report = upload_entries(gh, full_name, branch, entries, message)
        report["ok"] = True
        report["repo"] = full_name
        report["branch"] = branch
        report["count"] = len(report["uploaded"])
        return jsonify(report)
    except GitHubError as exc:
        return jsonify({"ok": False, "error": str(exc)}), 400


if __name__ == "__main__":
    port = int(os.environ.get("PORT", 5000))
    url = f"http://localhost:{port}"
    print("\n  GitHub Uploader يعمل الآن على: " + url)
    print("  أوقف التطبيق بالضغط على Ctrl+C\n")
    if os.environ.get("NO_BROWSER") != "1":
        import threading
        threading.Timer(1.2, lambda: webbrowser.open(url)).start()
    app.run(host="127.0.0.1", port=port, debug=False)
