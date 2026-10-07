/*
 * Creator CRM: More, practice mode and the Everything working? card.
 * Part of the dashboard; index.html loads these files in order and they share one scope (see core.js).
 */
"use strict";

// ---------- More ----------
// Everything that isn't daily work, one tap from the tab bar.

async function signOut() {
  await fetch("/logout", { method: "POST", credentials: "same-origin", headers: { "X-XSRF-TOKEN": csrf() } });
  location.replace("/login.html?logout");
}

const syncNow = action(async () => {
  await api("POST", "/api/sync");
  setTimeout(pollStatus, 1000);
}, "Checking… new items appear as they're read");

// ---------- Practice mode ----------
// A separate copy of the app with made-up brands (see PracticeMode.java). Her real deals are never in it.

let practiceInside = false;
let practiceTimer = null;

async function leavePractice() {
  const r = await api("POST", "/api/practice/leave");
  location.href = r.returnUrl;
}

function practiceCard() {
  const card = el("div", { class: "card practice-card" },
    el("h2", {}, "Practice with sample brands"),
    el("p", { class: "muted" }, "Try anything with made-up brands like Bloomleaf Tea and Juniper Juice: reply to emails, "
      + "send invoices, move deals along. Nothing you do there touches your real deals, and nothing is really sent. "
      + "Each time you start, the sample brands begin fresh."));
  const body = el("div", { class: "row" });
  card.appendChild(body);
  if (practiceInside) {
    body.appendChild(el("span", {}, "You're practising now."));
    body.appendChild(el("button", { class: "primary", onclick: action(leavePractice) }, "Leave practice"));
    return card;
  }
  if (!/^(localhost|127\.0\.0\.1|\[::1\])$/.test(location.hostname)) {
    body.appendChild(el("span", { class: "muted" }, "Practice mode opens on the laptop where the app runs."));
    return card;
  }
  const show = (s) => {
    clearTimeout(practiceTimer);
    clear(body);
    if (s.state === "READY") {
      body.appendChild(el("a", { class: "btn primary", href: s.url }, "Open practice"));
      body.appendChild(el("button", { onclick: action(async () => show(await api("POST", "/api/practice/stop"))) }, "Close practice"));
    } else if (s.state === "STARTING") {
      body.appendChild(el("button", { class: "primary", disabled: true }, "Getting your sample brands ready…"));
      practiceTimer = setTimeout(async () => {
        if (!card.isConnected) return;
        try {
          const next = await api("GET", "/api/practice");
          if (next.state === "READY") location.href = next.url; else show(next);
        } catch (e) { show({ state: "FAILED", error: e.message }); }
      }, 1500);
    } else {
      body.appendChild(el("button", { class: "primary", onclick: action(async () => show(await api("POST", "/api/practice/start"))) },
        "Start practice"));
      if (s.error) body.appendChild(el("span", { class: "alert error" }, s.error));
    }
  };
  api("GET", "/api/practice").then(show).catch(() => show({ state: "OFF" }));
  return card;
}

// Inside the practice copy: a banner on every page, so it's never mistaken for the real thing.
async function checkPractice() {
  try {
    const p = await api("GET", "/api/practice");
    if (!p.inside) return;
    practiceInside = true;
    document.body.classList.add("practice");
    document.title = "Practice · Creator CRM";
    const banner = el("div", { id: "practice-banner", class: "update-banner warn practice-banner", role: "status" },
      el("span", {}, el("strong", {}, "Practice mode. "), "These are sample brands. Nothing here is real, and nothing is sent."),
      el("span", { class: "spacer" }),
      el("button", { class: "small primary", onclick: action(leavePractice) }, "Leave practice"));
    document.querySelector(".topbar").appendChild(banner);
  } catch (e) { /* not important */ }
}

async function renderMore(root) {
  clear(root);
  root.appendChild(el("h1", {}, "More"));
  root.appendChild(healthCard());
  const link = (tab, title, detail) => el("a", { class: "more-link", href: "#" + tab, "data-tab": tab },
    el("span", { class: "title" }, title), el("span", { class: "detail" }, detail));
  root.appendChild(el("div", { class: "card more-list" },
    link("summary", "Day summary", "What you got done today and what's lined up for tomorrow"),
    link("contacts", "Brand contacts", "Everyone at every brand, best person to pitch first"),
    link("directory", "Brand directory", "A list of brand inboxes you can share or sell, with no personal details"),
    link("links", "My links", "Your Instagram, TikTok, website and media kit, used in drafts"),
    link("settings", "Settings", "Accounts, your rates and voice, follow-ups, backups"),
    link("setup", "Setup guide", "Connect Claude and Gmail one step at a time"),
    link("help", "Help", "Short videos for every feature, and Report a problem"),
    link("whatsnew", "What's new", "The latest changes to the app")));
  root.appendChild(practiceCard());
  root.appendChild(el("div", { class: "row" },
    el("button", { onclick: syncNow, title: "New emails and DMs are also checked by themselves every 30 minutes" }, "Check for new messages now"),
    el("div", { class: "spacer" }),
    practiceInside ? null : el("button", { class: "danger", onclick: signOut }, "Sign out")));
}

// ---------- Everything working? ----------
// One plain line each for Claude, Gmail, Instagram, backups and updates, from what the app last saw (nothing is
// fetched from the internet to draw it). Anything that needs her gets exactly one button that fixes it.
// More shows the whole card; Today shows one line, and only when something needs attention.

const HEALTH_ICON = { ok: "✓", warn: "!", problem: "!", off: "–" };
const HEALTH_WORD = { ok: "Working", warn: "Needs a look", problem: "Needs fixing", off: "Not set up" };
const HEALTH_NEEDS = { problem: 0, warn: 1 };

function healthFix(fix, state, done) {
  if (!fix) return null;
  const cls = "small" + (state === "problem" ? " primary" : "");
  if (fix.kind === "link") {
    const external = /^https?:/i.test(fix.target);
    return el("a", { class: "btn " + cls, href: fix.target, target: external ? "_blank" : null,
      rel: external ? "noopener noreferrer" : null }, fix.label);
  }
  return el("button", { class: cls, onclick: action(async () => {
    if (fix.kind === "oauth") {
      const r = await api("POST", fix.target);
      location.href = r.url;
      return;
    }
    if (fix.kind === "install") {
      updateStatus = await api("GET", "/api/updates");
      await installUpdate();
    } else {
      await api("POST", fix.target);
      if (fix.target === "/api/sync") { toast("Checking… new items appear as they're read"); setTimeout(pollStatus, 1000); }
    }
    if (done) done();
  }) }, fix.label);
}

function healthCard() {
  const box = el("div", { class: "card health", id: "health" },
    el("h3", {}, "Everything working?"), el("p", { class: "small muted" }, "Checking…"));
  const draw = (checks) => {
    const needs = checks.filter((c) => c.state in HEALTH_NEEDS).length;
    clear(box);
    box.appendChild(el("div", { class: "health-head" }, el("h3", {}, "Everything working?"),
      el("span", { class: "health-verdict " + (needs ? "warn" : "ok") },
        needs ? (needs === 1 ? "1 thing needs you" : needs + " things need you") : "Yes ✓")));
    box.appendChild(el("ul", { class: "health-list" }, checks.map((c) => el("li", { class: "health-row " + c.state },
      el("span", { class: "health-icon", role: "img", "aria-label": HEALTH_WORD[c.state], title: HEALTH_WORD[c.state] }, HEALTH_ICON[c.state]),
      el("div", { class: "body" }, el("div", { class: "title" }, c.name), el("div", { class: "detail" }, c.summary)),
      healthFix(c.fix, c.state, load)))));
    box.appendChild(el("div", { class: "row" },
      el("button", { class: "small", onclick: action(async () => {
        draw(await api("POST", "/api/health/recheck"));
        refreshUpdate();
      }, "Checked") }, "Check again")));
  };
  const load = () => api("GET", "/api/health").then(draw).catch(() => {
    clear(box).append(el("h3", {}, "Everything working?"), el("p", { class: "small muted" }, "Couldn't check right now."));
  });
  load();
  return box;
}

// Today: the most urgent thing and its fix on one line, or a quiet "Everything working ✓".
function healthStrip() {
  const strip = el("div", { class: "health-strip hidden", role: "status" });
  const load = () => api("GET", "/api/health").then((checks) => {
    const needs = checks.filter((c) => c.state in HEALTH_NEEDS)
      .sort((a, b) => HEALTH_NEEDS[a.state] - HEALTH_NEEDS[b.state]);
    clear(strip);
    if (!needs.length) {
      strip.className = "health-strip ok";
      strip.appendChild(el("a", { href: "#more" }, "Everything working ✓"));
      return;
    }
    const top = needs[0];
    strip.className = "health-strip " + top.state;
    strip.append(el("span", { class: "health-icon", "aria-hidden": "true" }, HEALTH_ICON[top.state]),
      el("span", { class: "text" }, top.headline),
      healthFix(top.fix, top.state, load) || "",
      needs.length > 1 ? el("a", { class: "more", href: "#more" }, "+" + (needs.length - 1) + " more") : "");
  }).catch(() => { /* offline: Today still works */ });
  load();
  return strip;
}
