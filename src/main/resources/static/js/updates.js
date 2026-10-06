/*
 * Creator CRM: Updates, What's New and Help.
 * Part of the dashboard; index.html loads these files in order and they share one scope (see core.js).
 */
"use strict";

// ---------- updates, What's New, Help ----------
// The app checks GitHub for a new release; in the Windows and Mac apps "Update now" backs up, installs and reopens.

let updateStatus = null;
let updateTimer = null;

function dismissedVersion() {
  try { return localStorage.getItem("crm.updateLater"); } catch (e) { return null; }
}

async function refreshUpdate() {
  try {
    updateStatus = await api("GET", "/api/updates");
  } catch (e) { return; }
  showUpdateBanner();
  if (updateStatus.installing) watchInstall();
}

function showUpdateBanner() {
  const b = clear(document.getElementById("update-banner"));
  const s = updateStatus;
  const show = s && (s.installing || s.installState || (s.updateAvailable && dismissedVersion() !== s.latestVersion));
  b.classList.toggle("hidden", !show);
  b.classList.toggle("error", Boolean(s && !s.installing && /failed/i.test(s.installState || "")));
  if (!show) return;
  if (s.installing || !s.updateAvailable) {
    b.appendChild(el("span", {}, s.installState || "Updating…"));
    return;
  }
  b.appendChild(el("span", {}, el("strong", {}, "Version " + s.latestVersion + " is ready. "),
    s.installState || (s.releaseName || "").replace(/^Creator CRM\s+[\d.]+\s*[:·-]?\s*/, "")));
  b.appendChild(el("span", { class: "spacer" }));
  b.appendChild(s.canInstall
    ? el("button", { class: "primary small", onclick: installUpdate }, "Update now")
    : el("a", { class: "btn small", href: s.releaseUrl, target: "_blank", rel: "noopener noreferrer" }, "Download"));
  b.appendChild(el("button", { class: "small", onclick: () => {
    try { localStorage.setItem("crm.updateLater", s.latestVersion); } catch (e) { /* private window */ }
    showUpdateBanner();
  } }, "Later"));
}

const installUpdate = action(async () => {
  const s = updateStatus;
  if (!confirm("Creator CRM will back up your data, install version " + s.latestVersion
    + " and open again by itself. It takes about a minute, and you may need to sign in again. Continue?")) return;
  updateStatus = await api("POST", "/api/updates/install");
  showUpdateBanner();
  watchInstall();
});

// While installing: follow progress, then wait for the new version to come up and reload into it.
function watchInstall() {
  clearTimeout(updateTimer);
  updateTimer = setTimeout(async () => {
    try {
      const r = await fetch("/api/updates", { credentials: "same-origin" });
      if (r.status === 401) { location.replace("/login.html"); return; }
      updateStatus = await r.json();
      showUpdateBanner();
      if (updateStatus.installing) watchInstall();
    } catch (e) {
      updateStatus = Object.assign({}, updateStatus, { installState: "Installing… this page reloads by itself when Creator CRM is back." });
      showUpdateBanner();
      waitForRestart();
    }
  }, 2000);
}

function waitForRestart() {
  clearTimeout(updateTimer);
  updateTimer = setTimeout(async () => {
    try {
      const r = await fetch("/api/setup/status", { cache: "no-store" });
      if (r.ok) { location.hash = "#whatsnew"; location.reload(); return; }
    } catch (e) { /* still installing */ }
    waitForRestart();
  }, 3000);
}

function updatesCard(root) {
  const s = updateStatus || {};
  const line = !s.enabled ? "Update checks are turned off for this install."
    : s.updateAvailable ? "Version " + s.latestVersion + " is available."
    : s.checkError ? s.checkError
    : s.checkedAt ? "You're up to date (checked " + fmtDateTime(s.checkedAt) + ")."
    : "Not checked yet.";
  const auto = el("input", { type: "checkbox", checked: s.autoCheck !== false, onchange: action(async (e) => {
    updateStatus = await api("PUT", "/api/updates/auto-check", { enabled: e.target.checked });
  }) });
  return card("Updates",
    el("p", {}, "You're on version " + (s.currentVersion || "?") + ". ", el("span", { class: "muted" }, line)),
    s.installHint ? el("p", { class: "small muted" }, s.installHint) : null,
    el("div", { class: "row" },
      el("button", { class: "small", disabled: !s.enabled, onclick: action(async () => {
        updateStatus = await api("POST", "/api/updates/check");
        showUpdateBanner();
        renderSettings(root);
      }) }, "Check now"),
      s.updateAvailable && s.canInstall ? el("button", { class: "primary small", onclick: installUpdate }, "Update now") : null,
      s.updateAvailable && !s.canInstall && s.releaseUrl
        ? el("a", { class: "btn small", href: s.releaseUrl, target: "_blank", rel: "noopener noreferrer" }, "Download") : null,
      el("a", { class: "btn small", href: "#whatsnew" }, "What's new")),
    el("label", { class: "row check" }, auto, "Check for updates automatically (every few hours)"),
    el("p", { class: "small muted" }, "Nothing is installed without your click. Before installing, a copy of your data is saved in the "
      + "backups folder next to your data."));
}

// Only shown in the installed Windows app, the one place the setting can work.
async function startWithWindowsCard() {
  const st = await api("GET", "/api/desktop/start-with-windows").catch(() => null);
  if (!st || !st.supported) return null;
  const box = el("input", { type: "checkbox", checked: st.enabled, onchange: action(async (e) => {
    try {
      const r = await api("PUT", "/api/desktop/start-with-windows", { enabled: e.target.checked });
      e.target.checked = r.enabled;
    } catch (err) {
      e.target.checked = !e.target.checked;
      throw err;
    }
  }, "Saved") });
  return card("Start with Windows",
    el("label", { class: "row check" }, box, "Start Creator CRM when Windows starts"),
    el("p", { class: "small muted" }, "Creator CRM opens quietly in the tray when you sign in to this laptop, so follow-ups, email checks "
      + "and your phone keep working after a restart. Click the tray icon or the desktop shortcut to open it."));
}

// "Something isn't working": her note plus recent (redacted) log lines go to the developer as a GitHub issue.
function reportProblemCard() {
  const note = el("textarea", { maxlength: "4000", placeholder: "What were you doing, and what went wrong?" });
  return card("Something not working?",
    el("p", { class: "small muted" }, "Tell us what happened. Your note and the app's recent activity log are sent so it can be fixed. "
      + "Email addresses, phone numbers, passwords and keys are removed first."),
    note,
    el("div", { class: "row" },
      el("button", { class: "primary small", onclick: action(async () => {
        if (!note.value.trim()) throw new Error("Please describe the problem first");
        await api("POST", "/api/diagnostics/report", { note: note.value });
        note.value = "";
      }, "Sent. Thank you!") }, "Report a problem"),
      el("a", { class: "btn small", href: "/api/diagnostics/log" }, "Save log file")));
}

async function errorReportsCard(root, c) {
  const d = await api("GET", "/api/diagnostics").catch(() => null);
  if (!d) return el("div");
  const tokenBox = el("div", {}, (() => {
    const input = el("input", Object.assign({ type: "password", placeholder: c.ERROR_REPORT_TOKEN ? "•••••••• (saved)" : "github_pat_…" }, NOT_A_LOGIN));
    input.dataset.name = "ERROR_REPORT_TOKEN";
    return el("div", {}, el("label", {}, "Error-report token"), input);
  })());
  const auto = el("input", { type: "checkbox", checked: d.autoReport, onchange: action(async (e) => {
    await api("PUT", "/api/diagnostics/auto-report", { enabled: e.target.checked });
  }, "Saved") });
  const where = [d.github ? "GitHub" : null, d.emailTo && d.gmailConnected ? "email to " + d.emailTo : null].filter(Boolean).join(" and ");
  const emailInput = el("input", { type: "email", value: d.emailTo || "", placeholder: "you@example.com", maxlength: "254" });
  const line = !d.configured ? "Off: add an email address or GitHub token below."
    : !d.autoReport ? "Automatic reports are paused. Report a problem still works."
    : d.lastError ? d.lastError
    : d.lastSentAt ? "On (" + where + "). Last report sent " + fmtDateTime(d.lastSentAt) + "."
    : "On (" + where + "). Nothing has needed reporting since the app started.";
  const recent = d.recent.length ? el("ul", { class: "small" }, ...d.recent.slice(0, 5).map((r) => el("li", {},
    r.title.replace(/^\[auto-report\] /, "") + " (" + r.count + "×, " + fmtDateTime(r.lastSeen) + ")",
    r.issueUrl ? el("span", {}, " · ", el("a", { href: r.issueUrl, target: "_blank", rel: "noopener noreferrer" }, "report")) : null))) : null;
  return card("Error reports",
    el("p", {}, el("span", { class: "muted" }, line)),
    el("p", { class: "small muted" }, "When something goes wrong, the app sends a short report to the developer (by email and/or a GitHub issue on "
      + d.repo + ") so it can be fixed in the next update. Repeats of the same error are grouped. Emails, phone numbers, Instagram handles, "
      + "passwords and keys are removed before anything is sent."),
    recent ? el("p", { class: "small" }, "Errors since the app started:") : null, recent,
    el("label", { class: "row check" }, auto, "Send error reports automatically"),
    el("details", { open: !d.configured }, el("summary", { class: "small" }, "Set up (for the developer)"),
      el("label", {}, "Email reports to"), emailInput,
      el("p", { class: "small muted" }, d.gmailConnected
        ? "Sent from the connected Gmail account, so a copy also appears in its Sent folder. Leave blank to turn email reports off."
        : "Needs Gmail connected (Settings, Accounts), because reports are sent from that account."),
      el("div", { class: "row" }, el("button", { class: "small", onclick: action(async () => {
        await api("PUT", "/api/diagnostics/email", { address: emailInput.value.trim() });
        renderSettings(root);
      }, "Saved") }, "Save email")),
      el("p", { class: "small muted" }, "Optional, GitHub issues: create a fine-grained GitHub token with access to only " + d.repo
        + " and the permission Issues: Read and write (nothing else), then paste it here."),
      tokenBox,
      el("div", { class: "row" },
        el("button", { class: "small", onclick: action(async () => {
          const v = tokenBox.querySelector("input").value.trim();
          if (!v) throw new Error("Nothing to save");
          await api("PUT", "/api/settings/credentials", { ERROR_REPORT_TOKEN: v });
          renderSettings(root);
        }, "Saved") }, "Save token"),
        c.ERROR_REPORT_TOKEN ? el("button", { class: "small danger", onclick: action(async () => {
          await api("PUT", "/api/settings/credentials", { ERROR_REPORT_TOKEN: "" });
          renderSettings(root);
        }, "Removed") }, "Remove") : null)),
    el("div", { class: "row" },
      el("a", { class: "btn small", href: "#help" }, "Report a problem"),
      el("a", { class: "btn small", href: "/api/diagnostics/log" }, "Save log file")));
}

function videoPlayer(version, file) {
  const fallback = el("p", { class: "small muted hidden" }, "The video couldn't load. It plays once Creator CRM can reach the internet.");
  const v = el("video", { controls: true, preload: "metadata", playsinline: true, src: "/api/videos/" + version + "/" + file });
  v.addEventListener("error", () => { v.classList.add("hidden"); fallback.classList.remove("hidden"); });
  return el("div", { class: "video" }, v, fallback);
}

function featureCard(entry, f) {
  return el("div", { class: "card feature" },
    el("h3", {}, f.title),
    el("p", {}, f.body),
    f.video ? videoPlayer(entry.version, f.video) : null,
    f.tryIt ? el("p", {}, el("a", { class: "btn small", href: f.tryIt }, "Try it")) : null);
}

async function renderWhatsNew(root) {
  const w = await api("GET", "/api/whats-new");
  const entries = w.unseen.length ? w.unseen : w.all.slice(0, 1);
  clear(root);
  root.appendChild(el("h1", {}, "What's new in Creator CRM " + w.currentVersion.replace(/-.*$/, "")));
  if (!entries.length) root.appendChild(emptyLine("Nothing new in this version."));
  for (const e of entries) {
    if (entries.length > 1 || e.title) root.appendChild(el("h2", {}, e.title || "Version " + e.version));
    e.features.forEach((f) => root.appendChild(featureCard(e, f)));
  }
  root.appendChild(el("p", { class: "row" }, el("a", { class: "btn small", href: "#today" }, "Back to Today"),
    el("a", { href: "#help" }, "All walkthrough videos")));
  if (w.unseen.length) api("POST", "/api/whats-new/seen").catch(() => {});
}

async function renderHelp(root) {
  const w = await api("GET", "/api/whats-new");
  clear(root);
  root.appendChild(el("h1", {}, "Help"));
  root.appendChild(el("p", { class: "muted" }, "Short videos of every feature, newest first. Each one has a Try it button that takes you there."));
  root.appendChild(reportProblemCard());
  if (!w.all.length) root.appendChild(emptyLine("No walkthroughs yet."));
  for (const e of w.all) {
    root.appendChild(el("h2", {}, (e.title || "Version " + e.version) + " ", el("span", { class: "badge" }, e.version)));
    e.features.forEach((f) => root.appendChild(featureCard(e, f)));
  }
}

// After an update, open What's New once.
async function maybeShowWhatsNew() {
  try {
    const w = await api("GET", "/api/whats-new");
    if (w.unseen.length && !location.hash.replace(/^#/, "")) location.hash = "#whatsnew";
  } catch (e) { /* not important */ }
}
