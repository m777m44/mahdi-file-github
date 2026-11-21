/* GitHub Uploader — client side logic */

const $ = (sel) => document.querySelector(sel);
const qs = document.querySelectorAll.bind(document);

document.addEventListener("DOMContentLoaded", () => {
  const searchInput = $("#repo-search");
  if (searchInput) {
    searchInput.addEventListener("input", () => {
      const q = searchInput.value.trim().toLowerCase();
      qs(".repo-card").forEach((card) => {
        card.classList.toggle("hidden", q && !card.dataset.name.includes(q));
      });
    });
  }

  qs(".select-repo").forEach((btn) => {
    btn.addEventListener("click", () => {
      $("#repo-fullname").value = btn.dataset.fullname;
      $("#branch").value = "";
      $("#branch").placeholder = "افتراضي: " + btn.dataset.branch;
      $("#upload-section").scrollIntoView({ behavior: "smooth" });
    });
  });

  const createForm = $("#create-repo-form");
  if (createForm) {
    createForm.addEventListener("submit", async (e) => {
      e.preventDefault();
      const fd = new FormData(createForm);
      const btn = createForm.querySelector("button[type=submit]");
      btn.disabled = true;
      try {
        const res = await fetch("/api/repos/create", { method: "POST", body: fd });
        const data = await res.json();
        if (data.ok) {
          $("#repo-fullname").value = data.full_name;
          $("#branch").placeholder = "افتراضي: " + data.default_branch;
          showResult(`<b>تم إنشاء المستودع:</b> <a href="${data.html_url}" target="_blank" rel="noopener">${data.full_name}</a><br>يمكنك الآن اختيار الملفات ورفعها.`, "ok");
        } else {
          showResult(`فشل إنشاء المستودع: ${data.error}`, "fail");
        }
      } catch (err) {
        showResult("خطأ في الشبكة: " + err.message, "fail");
      } finally {
        btn.disabled = false;
      }
    });
  }

  const zipModes = $("#zip-modes");
  ["input-files", "input-folder", "input-zip"].forEach((id) => {
    const input = document.getElementById(id);
    if (!input) return;
    input.addEventListener("change", () => {
      if (["input-folder", "input-files"].includes(id) && input.files.length) {
        input.dataset.chosen = "1";
      } else if (id === "input-zip" && input.files.length) {
        input.dataset.chosen = "1";
        zipModes.hidden = false;
      }
      renderPicked();
    });
  });

  const uploadForm = $("#upload-form");
  if (uploadForm) {
    uploadForm.addEventListener("submit", (e) => {
      e.preventDefault();
      doUpload();
    });
  }
});

function entryPath(file, input) {
  if (input.id === "input-folder" && file.webkitRelativePath) {
    return file.webkitRelativePath;
  }
  return file.name;
}

function renderPicked() {
  const list = $("#picked-list");
  list.innerHTML = "";
  let total = 0, count = 0;
  ["input-files", "input-folder", "input-zip"].forEach((id) => {
    const input = document.getElementById(id);
    if (!input || !input.dataset.chosen) return;
    for (const f of input.files) {
      total += f.size;
      count++;
      const div = document.createElement("div");
      div.textContent = `📄 ${entryPath(f, input)} (${humanSize(f.size)})`;
      list.appendChild(div);
    }
  });
  if (count) {
    const head = document.createElement("div");
    head.innerHTML = `<b>${count} ملف — المجموع ${humanSize(total)}</b>`;
    list.prepend(head);
  }
}

function humanSize(n) {
  if (n > 1024 * 1024 * 1024) return (n / 1024 ** 3).toFixed(2) + " GB";
  if (n > 1024 * 1024) return (n / 1024 ** 2).toFixed(2) + " MB";
  if (n > 1024) return (n / 1024).toFixed(1) + " KB";
  return n + " B";
}

function doUpload() {
  const form = $("#upload-form");
  const repoInput = $("#repo-fullname");
  const btn = $("#upload-btn");
  const wrap = $("#progress-wrap");
  const bar = $("#progress-bar");
  const label = $("#progress-label");

  if (!repoInput.value.trim()) {
    showResult("اختر مستودعاً أولاً من القائمة أو أنشئ واحداً جديداً.", "fail");
    return;
  }

  const fd = new FormData();
  fd.append("repo", repoInput.value.trim());
  fd.append("branch", $("#branch").value.trim());
  fd.append("prefix", $("#prefix").value.trim());
  fd.append("message", $("#message").value.trim());
  const zipMode = form.querySelector('input[name="zip_mode"]:checked');
  fd.append("zip_mode", zipMode ? zipMode.value : "extract");

  let count = 0;
  ["input-files", "input-folder", "input-zip"].forEach((id) => {
    const input = document.getElementById(id);
    if (!input || !input.dataset.chosen) return;
    for (const f of input.files) {
      fd.append("files", f, entryPath(f, input));
      count++;
    }
  });

  if (!count) {
    showResult("لم يتم اختيار أي ملفات 📂", "fail");
    return;
  }

  btn.disabled = true;
  wrap.hidden = false;
  label.textContent = "جارٍ إرسال الملفات إلى الخادم المحلي...";

  const xhr = new XMLHttpRequest();
  xhr.open("POST", "/upload");
  xhr.upload.onprogress = (e) => {
    if (e.lengthComputable) {
      const pct = Math.round((e.loaded / e.total) * 100);
      bar.style.width = pct + "%";
      if (pct >= 100) label.textContent = "جارٍ رفع الملفات إلى GitHub... (قد يستغرق وقتاً حسب الحجم)";
    }
  };
  xhr.onload = () => {
    btn.disabled = false;
    let data;
    try { data = JSON.parse(xhr.responseText); }
    catch { showResult("رد غير متوقع من الخادم", "fail"); return; }
    if (data.ok) {
      bar.style.width = "100%";
      let html = `<b>✅ تم رفع ${data.count} ملف إلى ${data.repo}</b>
        (الفرع: ${data.branch} — ${data.commits} التزام)`;
      if (data.failed && data.failed.length) {
        html += `<br><span class="err">فشل رفع ${data.failed.length} ملف:</span><ul>`;
        data.failed.forEach((f) => { html += `<li class="err">${f.path} — ${f.error}</li>`; });
        html += "</ul>";
      }
      showResult(html, "ok");
    } else {
      showResult(`فشل الرفع: ${data.error || "خطأ غير معروف"}`, "fail");
    }
  };
  xhr.onerror = () => {
    btn.disabled = false;
    showResult("خطأ في الشبكة أثناء الرفع.", "fail");
  };
  xhr.send(fd);
}

function showResult(html, kind) {
  const box = $("#result");
  box.hidden = false;
  box.className = "result " + kind;
  box.innerHTML = html;
}
