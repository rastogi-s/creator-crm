/*
 * Creator CRM: Routing between pages, header search, live sync status, and start-up. Loads last.
 * Part of the dashboard; index.html loads these files in order and they share one scope (see core.js).
 */
"use strict";

// ---------- routing ----------

const views = { today: renderToday, pipeline: renderPipeline, money: renderMoney, outreach: renderOutreach, links: renderLinks, contacts: renderContacts, campaigns: renderCampaigns,
                drafts: renderDrafts, summary: renderSummary, settings: renderSettings,
                help: renderHelp, whatsnew: renderWhatsNew, more: renderMore, setup: renderSetup, directory: renderDirectory };
// Five places in the tab bar; the other pages live under Deals or More, and keep their own addresses.
const NAV_OF = { outreach: "pipeline", links: "more", contacts: "more", campaigns: "more", summary: "more", settings: "more", help: "more", whatsnew: "more", setup: "more", directory: "more" };
let statuses = {};

function currentTab() {
  const h = location.hash.replace(/^#/, "").split("?")[0];
  return views[h] ? h : "today";
}

// Lights up the open place. On a laptop the sidebar has its own entry for pages under More, so that lights up instead.
function markNav() {
  const tab = currentTab();
  const own = [...document.querySelectorAll(".tab")].some((b) => b.dataset.tab === tab && b.getClientRects().length);
  const nav = own ? tab : NAV_OF[tab] || tab;
  document.querySelectorAll(".tab").forEach((b) => {
    b.classList.toggle("active", b.dataset.tab === nav);
    if (b.dataset.tab === nav) b.setAttribute("aria-current", "page"); else b.removeAttribute("aria-current");
  });
}
matchMedia("(min-width: 1024px)").addEventListener("change", markNav);

// On a phone the tab bar is pinned to the bottom of the screen. It sits outside the sticky header there, because
// iPhone Safari moves a pinned bar that lives inside a sticky header up and down while the page scrolls.
const PHONE = matchMedia("(max-width: 640px)");
function placeNav() {
  const nav = document.querySelector("nav.tabs"), header = document.querySelector(".topbar");
  if (PHONE.matches) { if (nav.parentElement !== document.body) header.after(nav); }
  else if (nav.parentElement !== header) header.insertBefore(nav, header.querySelector(".global-search"));
}
placeNav();
PHONE.addEventListener("change", placeNav);

async function route() {
  const tab = currentTab();
  markNav();
  document.querySelectorAll(".view").forEach((v) => v.classList.toggle("hidden", v.id !== "view-" + tab));
  const q = new URLSearchParams(location.hash.split("?")[1] || "");
  if (tab === "settings" && q.has("gmail") && oauthReturnsToSetup()) {
    location.replace("#setup?step=gmail&gmail=" + encodeURIComponent(q.get("gmail")));
    return;
  }
  const notes = {
    "gmail=connected": "Gmail connected.", "gmail=denied": "Gmail access was not granted.",
    "gmail=failed": "Google didn't accept the sign-in. Check the Client ID, Client secret and the address you added in Google Cloud (the setup guide's Gmail step lists each one).",
    "gmail=no-refresh-token": "Google didn't finish connecting. Open myaccount.google.com/permissions, remove Creator CRM, then press Connect Gmail again.",
    "gmail=state-mismatch": "The Gmail sign-in took too long. Please press Connect Gmail again.",
    "instagram=connected": "Instagram connected.", "instagram=denied": "Instagram access was not granted.",
    "instagram=failed": "Instagram didn't accept the sign-in. Check the app ID, app secret and the address you added in the Meta dashboard.",
    "instagram=state-mismatch": "The Instagram sign-in took too long. Please press Connect Instagram again.",
    "facebook=connected": "Facebook connected. You can now look up brands on Instagram.",
    "facebook=denied": "Facebook access was not granted.",
    "facebook=failed": "Facebook didn't accept the sign-in. Check the app ID, app secret and the address you added in the Meta dashboard.",
    "facebook=no-page": "No Facebook Page with a linked Instagram account was found. Link your Instagram to a Facebook Page and try again.",
    "facebook=state-mismatch": "The Facebook sign-in took too long. Please press Connect Facebook again.",
  };
  for (const [k, v] of q.entries()) {
    const msg = notes[k + "=" + v];
    if (msg) toast(msg, /failed|denied|no-refresh|mismatch|no-page/.test(v));
  }
  try {
    await views[tab](document.getElementById("view-" + tab));
    for (const id of ["spend", "backup"]) {
      if (q.has(id)) { const c = document.getElementById(id); if (c) c.scrollIntoView({ block: "start" }); }
    }
  } catch (err) {
    toast(err.message, true);
  }
  refreshDraftCount();
}

async function refreshDraftCount() {
  try {
    const d = await api("GET", "/api/drafts");
    const b = document.getElementById("drafts-count");
    b.textContent = d.length;
    b.classList.toggle("hidden", d.length === 0);
  } catch (e) { /* ignore */ }
}

// ---------- boot ----------

document.querySelectorAll(".tab").forEach((b) => b.addEventListener("click", () => { location.hash = "#" + b.dataset.tab; }));
document.getElementById("drawer-backdrop").addEventListener("click", closeDrawer);
document.addEventListener("keydown", (e) => { if (e.key === "Escape") closeDrawer(); });
document.getElementById("sync-btn").addEventListener("click", syncNow);

// ---------- header search ----------
// Finds deals by brand or contact, past emails and DMs, and invoices. "/" jumps to it from anywhere.

(function globalSearch() {
  const input = document.getElementById("global-search");
  const box = document.getElementById("global-results");
  const groups = [["DEAL", "Deals"], ["MESSAGE", "Emails and DMs"], ["INVOICE", "Invoices"]];
  let hits = [], active = -1, timer, seq = 0;

  function close() { box.classList.add("hidden"); input.setAttribute("aria-expanded", "false"); active = -1; }
  function open(hit) {
    close();
    input.blur();
    if (hit.kind === "INVOICE" && hit.invoiceId) openInvoice(hit.invoiceId); else if (hit.opportunityId) openDeal(hit.opportunityId);
  }
  function paint() {
    clear(box);
    if (!hits.length) { box.appendChild(emptyLine("Nothing found for “" + input.value.trim() + "”.")); }
    let i = 0;
    for (const [kind, label] of groups) {
      const mine = hits.filter((h) => h.kind === kind);
      if (!mine.length) continue;
      box.appendChild(el("div", { class: "group" }, label));
      for (const h of mine) {
        const idx = i++;
        box.appendChild(el("button", { class: "hit" + (idx === active ? " active" : ""), type: "button",
          onmousedown: (e) => e.preventDefault(), onclick: () => open(h) },
          el("div", { class: "title" }, h.title), h.detail ? el("div", { class: "detail" }, h.detail) : null));
      }
    }
    box.classList.remove("hidden");
    input.setAttribute("aria-expanded", "true");
  }
  async function run() {
    const q = input.value.trim();
    if (q.length < 2) { hits = []; close(); return; }
    const mine = ++seq;
    try {
      const found = await api("GET", "/api/search?q=" + encodeURIComponent(q));
      if (mine !== seq) return; // a newer search already went out
      // Keyboard order follows the groups, so arrow keys move down the list as shown.
      hits = groups.flatMap(([kind]) => found.filter((h) => h.kind === kind)); active = -1; paint();
    } catch (err) { toast(err.message, true); }
  }
  input.addEventListener("input", () => { clearTimeout(timer); timer = setTimeout(run, 250); });
  input.addEventListener("focus", () => { if (hits.length && input.value.trim().length >= 2) paint(); });
  input.addEventListener("blur", () => setTimeout(close, 150));
  input.addEventListener("keydown", (e) => {
    if (e.key === "Escape") { input.value = ""; hits = []; close(); input.blur(); e.stopPropagation(); return; }
    if (!hits.length) return;
    if (e.key === "ArrowDown" || e.key === "ArrowUp") {
      e.preventDefault();
      active = (active + (e.key === "ArrowDown" ? 1 : -1) + hits.length) % hits.length;
      paint();
      const a = box.querySelector(".hit.active");
      if (a) a.scrollIntoView({ block: "nearest" });
    } else if (e.key === "Enter") {
      e.preventDefault();
      open(hits[Math.max(active, 0)]);
    }
  });
  document.addEventListener("keydown", (e) => {
    const t = e.target;
    const typing = t && (t.tagName === "INPUT" || t.tagName === "TEXTAREA" || t.tagName === "SELECT" || t.isContentEditable);
    if (e.key === "/" && !typing && !e.ctrlKey && !e.metaKey && !e.altKey) { e.preventDefault(); input.focus(); input.select(); }
  });
})();

// ---------- live sync status ----------
// Polls quickly while a sync or AI analysis is running, slowly otherwise. When stored data changes, the
// open page re-renders, unless the user is mid-edit (focused field or open deal drawer).

let seenVersion = null;
let staleView = false;
let statusTimer = null;

function userIsEditing() {
  const a = document.activeElement;
  const typing = a && /^(INPUT|TEXTAREA|SELECT)$/.test(a.tagName) && a.closest("main, #drawer");
  return Boolean(typing) || !document.getElementById("drawer").classList.contains("hidden");
}

function showStatus(s) {
  const n = s.waitingForAi;
  const msgs = n + " message" + (n === 1 ? "" : "s");
  let text = "", warn = false, title = "";
  const since = s.importingSince ? fmtDate(s.importingSince.slice(0, 10)) : null;
  if (s.analyzing) text = "Claude is reading " + msgs + "…";
  else if (s.syncing) text = since ? "Bringing in email since " + since + "…" : "Checking for new messages…";
  else if (since) text = "Bringing in email since " + since + " continues next time the app checks";
  else if (n > 0 && !s.aiConfigured) {
    text = msgs + " waiting: add your Claude key in Settings, Accounts"; warn = true;
  } else if (s.inBatch > 0 && !s.aiError) {
    text = s.inBatch + " older message" + (s.inBatch === 1 ? "" : "s") + " being read at half price"
      + (n > s.inBatch ? " (" + n + " waiting in all)" : "") + ". Results come in over the next few hours.";
  } else if (n > 0 && s.aiError) {
    const reason = s.aiError.replace(/^\S+\s+/, "");
    text = msgs + " waiting: " + (/\(401\)/.test(reason) ? "Claude didn't accept your key. Check it in Settings, Accounts"
      : /\(429\)/.test(reason) ? "Claude asked the app to slow down; it tries again shortly"
      : plainError(reason, "Claude") || reason);
    warn = true; title = s.aiError;
  } else if (n > 0) text = msgs + " waiting for Claude to read them";
  const chip = document.getElementById("sync-status");
  chip.textContent = text;
  chip.title = title;
  chip.className = "sync-status" + (warn ? " warn" : "");
  document.getElementById("sync-status-row").classList.toggle("hidden", !text);
  showChannelBanner(s.channelProblems || []);
}

// An account that keeps failing gets a banner on every page: otherwise Today looks calm while no new mail arrives.
function showChannelBanner(problems) {
  const b = clear(document.getElementById("channel-banner"));
  b.classList.toggle("hidden", !problems.length);
  for (const p of problems) {
    const name = p.channel === "EMAIL" ? "Gmail" : "Instagram";
    const when = fmtDate(p.since.slice(0, 10));
    const row = el("div", { class: "row", title: p.detail },
      el("span", {}, el("strong", {}, name + " stopped connecting on " + when + ". "),
        p.signIn ? "New messages aren't coming in until you reconnect it."
          : "New messages aren't coming in. Check the internet connection, or reconnect it."),
      el("span", { class: "spacer" }),
      p.channel === "EMAIL"
        ? el("button", { class: "small primary", onclick: action(async () => {
            const r = await api("POST", "/oauth/google/start"); location.href = r.url;
          }) }, "Reconnect Gmail")
        : el("a", { class: "btn small", href: "#settings" }, "Reconnect Instagram"));
    b.appendChild(row);
  }
}

async function pollStatus() {
  clearTimeout(statusTimer);
  let busy = false;
  try {
    const s = await api("GET", "/api/sync/status");
    busy = s.syncing || s.analyzing;
    showStatus(s);
    if (seenVersion !== null && s.version !== seenVersion) staleView = true;
    seenVersion = s.version;
    if (staleView && !userIsEditing() && !document.hidden) {
      staleView = false;
      await route();
    }
  } catch (e) { /* signed out or offline: try again later */ }
  statusTimer = setTimeout(pollStatus, busy ? 4000 : 60000);
}
document.addEventListener("visibilitychange", () => { if (!document.hidden) pollStatus(); });
window.addEventListener("hashchange", route);
pollStatus();
refreshCredits();
setInterval(refreshCredits, 5 * 60 * 1000);

refreshUpdate();
setInterval(refreshUpdate, 30 * 60 * 1000);
checkPractice().then(maybeShowWhatsNew).then(maybeShowSetup).then(() => api("GET", "/api/statuses")).then((s) => { statuses = s; route(); });
