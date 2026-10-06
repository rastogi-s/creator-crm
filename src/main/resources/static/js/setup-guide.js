/*
 * Creator CRM: The first-run setup guide.
 * Part of the dashboard; index.html loads these files in order and they share one scope (see core.js).
 */
"use strict";

// ---------- Setup guide ----------
// First run: one task per screen, why the app needs it, and exactly what to click. It opens by itself until
// Claude and Gmail are connected (or she chooses to set up later); Settings, Accounts links back to it.

const SETUP_STEPS = [["welcome", "Welcome"], ["claude", "Claude"], ["gmail", "Gmail"], ["voice", "Your rates"], ["done", "Done"]];

// OAuth sign-ins come back to Settings; one started from the guide should come back to the guide.
function oauthReturnsToSetup() {
  try {
    const v = sessionStorage.getItem("crm.oauthReturn");
    sessionStorage.removeItem("crm.oauthReturn");
    return v === "setup";
  } catch (e) { return false; }
}

async function maybeShowSetup() {
  if (location.hash.replace(/^#/, "") || practiceInside) return; // a link or bookmark to a page wins
  try {
    const s = await api("GET", "/api/settings");
    if (!s.preferences.setupGuide && !(s.credentials.ANTHROPIC_API_KEY && s.channels.EMAIL.connected)) location.hash = "#setup";
  } catch (e) { /* not important */ }
}

function copyButton(text) {
  return el("button", { type: "button", class: "small", onclick: action(async () => {
    await navigator.clipboard.writeText(text);
  }, "Copied") }, "Copy");
}

function extLink(href, text) {
  return el("a", { href, target: "_blank", rel: "noopener noreferrer" }, text);
}

async function renderSetup(root) {
  const s = await api("GET", "/api/settings");
  const c = s.credentials;
  const p = s.preferences;
  const gmail = s.channels.EMAIL;
  const q = new URLSearchParams(location.hash.split("?")[1] || "");
  const step = SETUP_STEPS.some(([id]) => id === q.get("step")) ? q.get("step") : "welcome";
  const at = SETUP_STEPS.findIndex(([id]) => id === step);
  const go = (id) => { location.hash = "#setup?step=" + id; };
  const next = () => go(SETUP_STEPS[at + 1][0]);
  const later = action(async () => {
    await api("PUT", "/api/settings/preferences", { setupGuide: "skipped" });
    location.hash = "#today";
  }, "You can finish setting up any time from Settings, Accounts.");

  clear(root);
  root.appendChild(el("ol", { class: "setup-progress", "aria-label": "Setup steps" }, SETUP_STEPS.map(([id, label], i) =>
    el("li", { class: i < at ? "past" : i === at ? "now" : "", "aria-current": i === at ? "step" : null },
      el("a", { href: "#setup?step=" + id }, label)))));
  const box = el("div", { class: "card setup-step", "data-step": step });
  root.appendChild(box);
  const put = (...nodes) => nodes.forEach((n) => { if (n) box.appendChild(n); });
  const why = (text) => el("p", { class: "setup-why" }, el("strong", {}, "Why: "), text);
  const nav = (...right) => el("div", { class: "setup-nav" },
    at > 0 ? el("button", { type: "button", onclick: () => go(SETUP_STEPS[at - 1][0]) }, "Back") : null,
    el("span", { class: "spacer" }),
    step !== "done" ? el("button", { type: "button", class: "link", onclick: later }, "Set up later") : null,
    ...right);

  if (step === "welcome") {
    const fresh = p.creatorName === "Creator";
    let zone = p.timezone;
    try { if (fresh) zone = Intl.DateTimeFormat().resolvedOptions().timeZone || zone; } catch (e) { /* keep the saved one */ }
    const name = el("input", { value: fresh ? "" : p.creatorName, maxlength: "200", placeholder: "e.g. Priya", id: "setup-name" });
    const tz = el("input", { value: zone, id: "setup-tz" });
    put(
      el("h1", {}, "Let's get Creator CRM ready"),
      el("p", { class: "lede" }, "Three short steps: connect Claude, connect Gmail, and tell the app your rates. "
        + "You can stop at any point and pick up again from Settings."),
      el("label", { for: "setup-name" }, "Your name, as you sign your emails"), name,
      el("label", { for: "setup-tz" }, "Your time zone"), tz,
      el("p", { class: "small muted" }, "Filled in from this computer. Follow-ups and reminders use it."),
      nav(el("button", { class: "primary", onclick: action(async () => {
        if (!name.value.trim()) throw new Error("Please type your name");
        await api("PUT", "/api/settings/preferences", { creatorName: name.value.trim(), timezone: tz.value.trim() });
        next();
      }) }, "Start")));
  }

  if (step === "claude") {
    const key = el("input", Object.assign({ type: "password", id: "setup-claude-key",
      placeholder: c.ANTHROPIC_API_KEY ? "•••••••• (saved, paste a new one to replace it)" : "sk-ant-…" }, NOT_A_LOGIN));
    put(
      el("h1", {}, "Connect Claude"),
      why("Claude reads each brand email, works out what the brand wants, and writes your reply drafts. You pay Anthropic "
        + "directly for what you use, usually a few dollars a month."),
      c.ANTHROPIC_API_KEY ? el("p", { class: "setup-ok" }, "✓ A Claude key is saved. Paste a new one only if you want to replace it.") : null,
      el("ol", { class: "setup-howto" },
        el("li", {}, "Open ", extLink("https://console.anthropic.com/", "console.anthropic.com"), " and sign up with your email."),
        el("li", {}, "Go to ", el("strong", {}, "Billing"), " and add credits. $10 is a good start; Settings, App, Claude spending shows what you use."),
        el("li", {}, "Go to ", el("strong", {}, "API keys"), ", press ", el("strong", {}, "Create key"), ", name it Creator CRM, and copy it."),
        el("li", {}, "Paste it here and press ", el("strong", {}, "Save and test"), ".")),
      passwordForm(el("div", {}, el("label", { for: "setup-claude-key" }, "Claude API key"), key)),
      nav(
        c.ANTHROPIC_API_KEY ? el("button", { type: "button", onclick: next }, "Next") : el("button", { type: "button", onclick: next }, "Skip for now"),
        el("button", { class: "primary", onclick: action(async () => {
          if (key.value.trim()) await api("PUT", "/api/settings/credentials", { ANTHROPIC_API_KEY: key.value.trim() });
          else if (!c.ANTHROPIC_API_KEY) throw new Error("Paste your Claude API key first");
          const r = await api("POST", "/api/settings/test-anthropic");
          if (!r.ok) throw new Error(r.error);
          next();
        }, "Claude is connected ✓") }, "Save and test")));
  }

  if (step === "gmail") {
    const id = el("input", Object.assign({ type: "password", id: "setup-google-id",
      placeholder: c.GOOGLE_CLIENT_ID ? "•••••••• (saved)" : "….apps.googleusercontent.com" }, NOT_A_LOGIN));
    const secret = el("input", Object.assign({ type: "password", id: "setup-google-secret",
      placeholder: c.GOOGLE_CLIENT_SECRET ? "•••••••• (saved)" : "GOCSPX-…" }, NOT_A_LOGIN));
    const connect = action(async () => {
      const body = {};
      if (id.value.trim()) body.GOOGLE_CLIENT_ID = id.value.trim();
      if (secret.value.trim()) body.GOOGLE_CLIENT_SECRET = secret.value.trim();
      if (Object.keys(body).length) await api("PUT", "/api/settings/credentials", body);
      else if (!(c.GOOGLE_CLIENT_ID && c.GOOGLE_CLIENT_SECRET)) throw new Error("Paste the Client ID and Client secret first");
      const r = await api("POST", "/oauth/google/start");
      try { sessionStorage.setItem("crm.oauthReturn", "setup"); } catch (e) { /* comes back to Settings instead */ }
      location.href = r.url;
    });
    put(
      el("h1", {}, "Connect Gmail"),
      why("So the app can read brand emails as they arrive, save your replies in Gmail Drafts, and put deal dates on a "
        + "Creator CRM calendar. It can't delete or change your existing mail."),
      gmail.connected ? el("p", { class: "setup-ok" }, "✓ Gmail is connected" + (gmail.account ? " as " + gmail.account : "") + ".") : null,
      gmail.connected ? null : el("p", { class: "small muted" }, "This is the one technical step, done once. Google needs you to create "
        + "your own private sign-in for the app. If someone is helping you set up, this is the step to hand them."),
      gmail.connected ? null : el("ol", { class: "setup-howto" },
        el("li", {}, "Open ", extLink("https://console.cloud.google.com/projectcreate", "Google Cloud"), ", sign in with your Gmail, and create a project called Creator CRM."),
        el("li", {}, "Turn on ", extLink("https://console.cloud.google.com/apis/library/gmail.googleapis.com", "the Gmail API"), " and ",
          extLink("https://console.cloud.google.com/apis/library/calendar-json.googleapis.com", "the Google Calendar API"), ": press ", el("strong", {}, "Enable"), " on each."),
        el("li", {}, "Open ", extLink("https://console.cloud.google.com/auth/overview", "Google Auth Platform"), " and press ", el("strong", {}, "Get started"),
          ". App name: Creator CRM. Audience: ", el("strong", {}, "External"), ". Then under ", el("strong", {}, "Audience"),
          " press ", el("strong", {}, "Publish app"), ", so the connection doesn't stop every 7 days."),
        el("li", {}, "Under ", el("strong", {}, "Clients"), " press ", el("strong", {}, "Create client"), ", choose ", el("strong", {}, "Web application"),
          ", and under Authorized redirect URIs add this address:",
          el("div", { class: "row copy-row" }, el("code", { class: "code" }, s.googleRedirectUri), copyButton(s.googleRedirectUri))),
        el("li", {}, "Google shows a Client ID and a Client secret. Paste them below and press ", el("strong", {}, "Connect Gmail"), "."),
        el("li", {}, "On Google's screen pick your account. If it says Google hasn't verified this app, press ", el("strong", {}, "Advanced"),
          ", then ", el("strong", {}, "Go to Creator CRM"), ". That's expected: it's your own private app.")),
      gmail.connected ? null : passwordForm(el("div", { class: "grid" },
        el("div", {}, el("label", { for: "setup-google-id" }, "Client ID"), id),
        el("div", {}, el("label", { for: "setup-google-secret" }, "Client secret"), secret))),
      gmail.lastError ? channelError(gmail.lastError, "Gmail") : null,
      nav(
        el("button", { type: "button", class: gmail.connected ? "primary" : "", onclick: next }, gmail.connected ? "Next" : "Skip for now"),
        gmail.connected ? null : el("button", { class: "primary", onclick: connect }, "Connect Gmail")));
  }

  if (step === "voice") {
    const profile = el("textarea", { class: "tall", maxlength: "20000", id: "setup-profile" });
    profile.value = p.creatorProfile;
    put(
      el("h1", {}, "Your rates and how you write"),
      why("Drafts only ever quote rates written here. Anything missing becomes a blank like [RATE FOR 1 REEL] that you fill in, "
        + "and the app won't send a draft until every blank is filled."),
      el("p", { class: "small muted" }, "Fill in the parts you know: your rates, what you will and won't promote, and a few words on how you "
        + "like to sound. You can change this any time in Settings, You."),
      el("label", { for: "setup-profile" }, "About you, your rates and your rules"), profile,
      nav(
        el("button", { type: "button", onclick: next }, "Skip for now"),
        el("button", { class: "primary", onclick: action(async () => {
          await api("PUT", "/api/settings/preferences", { creatorProfile: profile.value });
          next();
        }, "Saved") }, "Save and continue")));
  }

  if (step === "done") {
    const check = (ok, text, fix) => el("li", { class: ok ? "ok" : "" }, el("span", { class: "mark" }, ok ? "✓" : "○"), " ", text,
      ok || !fix ? null : el("span", {}, " · ", el("a", { href: fix }, "Do it now")));
    const ready = c.ANTHROPIC_API_KEY && gmail.connected;
    put(
      el("h1", {}, ready ? "You're all set" : "Almost there"),
      el("p", { class: "lede" }, ready
        ? "New brand emails are read as they arrive, and replies wait in Drafts for you to check. Nothing is sent without your click."
        : "The app works once Claude and Gmail are both connected. You can come back to the guide any time from Settings, Accounts."),
      el("ul", { class: "setup-checklist" },
        check(c.ANTHROPIC_API_KEY, "Claude connected", "#setup?step=claude"),
        check(gmail.connected, "Gmail connected", "#setup?step=gmail"),
        check(p.creatorName !== "Creator", "Your name: " + p.creatorName, "#setup?step=welcome")),
      el("h3", {}, "When you're ready (optional)"),
      el("ul", { class: "setup-extras" },
        el("li", {}, el("a", { href: "#settings?tab=accounts" }, "Connect Instagram"), " to see brand DMs and your follower numbers."),
        el("li", {}, el("a", { href: "#settings?tab=deals" }, "Add your invoice details"), ": your address and how brands pay you."),
        el("li", {}, el("a", { href: "#settings?tab=app" }, "Turn on nightly backups"), " so a lost laptop doesn't lose your deals."),
        gmail.connected ? el("li", {}, el("a", { href: "#settings?tab=accounts" }, "Import older email"), " to bring in past deals.") : null),
      nav(el("button", { class: "primary", onclick: action(async () => {
        await api("PUT", "/api/settings/preferences", { setupGuide: ready ? "done" : "skipped" });
        location.hash = "#today";
      }) }, "Go to Today")));
  }
}
