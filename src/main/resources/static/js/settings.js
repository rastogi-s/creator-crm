/*
 * Creator CRM: Settings, Claude spending, backups and plain-words errors.
 * Part of the dashboard; index.html loads these files in order and they share one scope (see core.js).
 */
"use strict";

// ---------- Settings ----------

// Deal dates on a "Creator CRM" calendar in her Google account
async function calendarStep(root, c) {
  const cal = await api("GET", "/api/calendar");
  const li = el("li", { class: cal.state === "READY" && cal.on ? "done" : "", id: "settings-calendar" }, el("h3", {}, "Google Calendar"),
    el("p", { class: "small muted" }, "Contracts to sign, content due, posting days and payments go on a calendar called Creator CRM in your Google account, "
      + "so they show on your phone. Dates move when a deal changes and disappear when they're done. The app can't see your other calendars."));
  if (cal.state === "NO_GOOGLE") {
    li.appendChild(el("p", { class: "small" }, "Connect Gmail first (step 2). The calendar uses the same Google sign-in."));
    return li;
  }
  if (cal.state === "NEEDS_RECONNECT") {
    li.appendChild(el("p", { class: "small" }, "Press Reconnect Gmail once and allow calendar access on Google's screen."));
    if (c.GOOGLE_CLIENT_ID && c.GOOGLE_CLIENT_SECRET) li.appendChild(el("p", {}, el("button", { class: "primary small", onclick: action(async () => {
      const r = await api("POST", "/oauth/google/start"); location.href = r.url;
    }) }, "Reconnect Gmail")));
    return li;
  }
  const on = el("input", { type: "checkbox", checked: cal.on, onchange: action(async (e) => {
    await api("POST", "/api/calendar", { on: e.target.checked });
    renderSettings(root);
  }, "Saved") });
  li.appendChild(el("label", { class: "check" }, on, " Put deal dates on my Google Calendar"));
  li.appendChild(el("p", { class: "small" + (cal.error ? " warn" : "") }, cal.error ? cal.error
    : cal.on ? cal.events + (cal.events === 1 ? " date" : " dates") + " on your Creator CRM calendar" + (cal.lastRun ? ", updated " + fmtDateTime(cal.lastRun) : "") + "."
      : "Off. Turning it off removed the app's events."));
  if (cal.on) li.appendChild(el("p", {}, el("button", { class: "small", onclick: action(async () => {
    const r = await api("POST", "/api/calendar/sync");
    if (r.error) throw new Error(r.error);
    renderSettings(root);
  }, "Calendar updated") }, "Update now")));
  return li;
}

// Settings is split into tabs so the everyday choices aren't buried among developer ones. The open tab lives in
// the address (#settings?tab=you), so a Save that re-renders the page, or a link from elsewhere, lands on it.
const SETTINGS_TABS = [
  ["accounts", "Accounts", "Claude, Gmail, Google Calendar, Instagram and contact finders"],
  ["you", "You", "Your name, voice, rates and to-do rules"],
  ["deals", "Deals & money", "Follow-ups, invoices, rates, contracts, rebooking"],
  ["app", "App", "Updates, backups, Claude spending, password"],
  ["advanced", "Advanced", "Claude models, extra connections, error reports, restoring a backup"],
];
// Older links (and the OAuth return pages) name a card, not a tab.
const SETTINGS_TAB_OF = { spend: "app", backup: "app", gmail: "accounts", instagram: "accounts", facebook: "advanced", finders: "accounts" };

function settingsTab() {
  const q = new URLSearchParams(location.hash.split("?")[1] || "");
  if (SETTINGS_TABS.some(([id]) => id === q.get("tab"))) return q.get("tab");
  for (const k of q.keys()) if (SETTINGS_TAB_OF[k]) return SETTINGS_TAB_OF[k];
  return "accounts";
}

function settingsSection(id, title, ...children) {
  const c = card(title, ...children);
  if (id) c.id = id;
  return c;
}

async function renderSettings(root) {
  // A second render (a sync finishing, a Save) can start while this one waits on the server. Only the newest
  // one may add cards, or the page ends up with two of some cards.
  const gen = String(Number(root.dataset.renderGen || 0) + 1);
  root.dataset.renderGen = gen;
  const stale = () => root.dataset.renderGen !== gen;
  const s = await api("GET", "/api/settings");
  updateStatus = await api("GET", "/api/updates").catch(() => updateStatus);
  if (stale()) return;
  const c = s.credentials;
  const p = s.preferences;
  clear(root);
  root.appendChild(el("h1", {}, "Settings"));
  root.appendChild(el("p", { class: "muted" }, "Everything here is stored encrypted in your own database. Saved credentials are never shown again — leave a field blank to keep the current value."));

  const open = settingsTab();
  const panes = {};
  const tabBar = el("div", { class: "settings-tabs", role: "tablist", "aria-label": "Settings sections" });
  for (const [id, label, detail] of SETTINGS_TABS) {
    panes[id] = el("div", { class: "settings-pane" + (id === open ? "" : " hidden"), role: "tabpanel", id: "settings-pane-" + id,
      "aria-labelledby": "settings-tab-" + id });
    tabBar.appendChild(el("button", { type: "button", class: "settings-tab" + (id === open ? " active" : ""), role: "tab",
      id: "settings-tab-" + id, "data-settings-tab": id, title: detail, "aria-selected": String(id === open),
      "aria-controls": "settings-pane-" + id, onclick: () => {
        history.replaceState(null, "", "#settings?tab=" + id);
        tabBar.querySelectorAll(".settings-tab").forEach((b) => {
          b.classList.toggle("active", b.dataset.settingsTab === id);
          b.setAttribute("aria-selected", String(b.dataset.settingsTab === id));
        });
        Object.entries(panes).forEach(([k, pane]) => pane.classList.toggle("hidden", k !== id));
      } }, label));
  }
  root.appendChild(tabBar);
  Object.values(panes).forEach((pane) => root.appendChild(pane));

  const secretField = (name, label, placeholder) => {
    const input = el("input", Object.assign({ type: "password", placeholder: c[name] ? "•••••••• (saved)" : placeholder || "" }, NOT_A_LOGIN));
    input.dataset.name = name;
    return el("div", {}, el("label", {}, label), input);
  };
  const saveSecrets = (container, extra) => action(async () => {
    const body = {};
    container.querySelectorAll("input[data-name]").forEach((i) => { if (i.value.trim()) body[i.dataset.name] = i.value.trim(); });
    if (!Object.keys(body).length) throw new Error("Nothing to save");
    await api("PUT", "/api/settings/credentials", body);
    if (extra) await extra();
    renderSettings(root);
  }, "Saved");
  const savePrefsButton = (fields, label, primary) => el("p", {}, el("button", { class: (primary === false ? "" : "primary ") + "small", onclick: action(async () => {
    const body = {};
    for (const [k, v] of Object.entries(fields)) body[k] = v.value;
    await api("PUT", "/api/settings/preferences", body);
    renderSettings(root);
  }, "Saved") }, label || "Save"));
  const channel = s.channels;

  // ----- Accounts -----
  const done = c.ANTHROPIC_API_KEY && channel.EMAIL.connected;
  panes.accounts.appendChild(el("div", { class: "setup-callout" + (done ? " quiet" : "") },
    el("span", {}, done ? "Need to connect a new computer or account? The setup guide walks through it one step at a time."
      : "New here? The setup guide connects Claude and Gmail one step at a time, with what to click at each step."),
    el("a", { class: "btn small" + (done ? "" : " primary"), href: "#setup" }, "Open the setup guide")));
  const steps = el("ol", { class: "steps" });
  panes.accounts.appendChild(card(null, steps));

  // 1. Claude
  const claudeBox = el("div", {}, secretField("ANTHROPIC_API_KEY", "Claude API key", "sk-ant-…"));
  steps.appendChild(el("li", { class: c.ANTHROPIC_API_KEY ? "done" : "" },
    el("h3", {}, "Connect Claude"),
    el("p", { class: "small muted" }, "Claude reads your brand messages and writes your drafts. Create a key at console.anthropic.com → API keys, or use the setup guide for step-by-step help."),
    claudeBox,
    el("div", { class: "row" }, el("button", { class: "primary small", onclick: saveSecrets(claudeBox) }, "Save"),
      c.ANTHROPIC_API_KEY ? el("button", { class: "small", onclick: action(async () => {
        const r = await api("POST", "/api/settings/test-anthropic");
        if (!r.ok) throw new Error(r.error);
      }, "Claude API key works ✓") }, "Test") : null)));

  // 2. Gmail
  const gmailBox = el("div", {}, secretField("GOOGLE_CLIENT_ID", "Google OAuth client ID"), secretField("GOOGLE_CLIENT_SECRET", "Google OAuth client secret"));
  steps.appendChild(el("li", { class: channel.EMAIL.connected ? "done" : "" },
    el("h3", {}, "Connect Gmail"),
    el("p", { class: "small muted" }, "In Google Cloud Console: enable the Gmail API and the Google Calendar API, create an OAuth client of type “Web application”, and add this authorized redirect URI. ",
      el("a", { href: "#setup?step=gmail" }, "Step-by-step help")),
    el("div", { class: "code" }, s.googleRedirectUri),
    gmailBox,
    el("div", { class: "row" },
      el("button", { class: "small", onclick: saveSecrets(gmailBox) }, "Save client"),
      c.GOOGLE_CLIENT_ID && c.GOOGLE_CLIENT_SECRET ? el("button", { class: "primary small", onclick: action(async () => {
        const r = await api("POST", "/oauth/google/start"); location.href = r.url;
      }) }, channel.EMAIL.connected ? "Reconnect Gmail" : "Connect Gmail") : null,
      channel.EMAIL.connected ? el("button", { class: "small danger", onclick: action(async () => { await api("POST", "/oauth/google/disconnect"); renderSettings(root); }, "Disconnected") }, "Disconnect") : null),
    channelStatus(channel.EMAIL, "Gmail"),
    channel.EMAIL.connected ? importHistory() : null,
    el("p", { class: "small muted" }, "Permissions requested: read mail + create/send drafts, and a Creator CRM calendar for deal dates. The app cannot delete or change existing mail or see your other calendars.")));

  // 3. Google Calendar
  const calendar = await calendarStep(root, c);
  if (stale()) return;
  steps.appendChild(calendar);

  // 4. Instagram (the extras, like pasting a token or real-time webhooks, are under Advanced)
  const igBox = el("div", {}, secretField("INSTAGRAM_APP_ID", "Instagram app ID"), secretField("INSTAGRAM_APP_SECRET", "Instagram app secret"));
  steps.appendChild(el("li", { class: channel.INSTAGRAM.connected ? "done" : "" },
    el("h3", {}, "Connect Instagram (optional)"),
    el("p", { class: "small muted" }, "Needs an Instagram Business or Creator account and a Meta app using “Instagram API with Instagram login” with the instagram_business_basic and instagram_business_manage_messages permissions (add instagram_business_manage_insights to show your reach, and instagram_business_manage_comments so brands commenting on your posts show up under Deals, Pitching brands). Add yourself as a tester; App Review is only needed if other people's accounts will use your app. Added a permission? Press Reconnect Instagram once."),
    el("p", { class: "small muted" }, "OAuth redirect URI (Meta requires https — deploy the app or use a tunnel):"),
    el("div", { class: "code" }, s.instagramRedirectUri),
    igBox,
    el("div", { class: "row" },
      el("button", { class: "small", onclick: saveSecrets(igBox) }, "Save app"),
      c.INSTAGRAM_APP_ID && c.INSTAGRAM_APP_SECRET ? el("button", { class: "primary small", onclick: action(async () => {
        const r = await api("POST", "/oauth/instagram/start"); location.href = r.url;
      }) }, channel.INSTAGRAM.connected ? "Reconnect Instagram" : "Connect Instagram") : null,
      channel.INSTAGRAM.connected ? el("button", { class: "small danger", onclick: action(async () => { await api("POST", "/oauth/instagram/disconnect"); renderSettings(root); }, "Disconnected") }, "Disconnect") : null),
    channelStatus(channel.INSTAGRAM, "Instagram"),
    channel.INSTAGRAM.connected ? instagramStatsLine(s.instagramStats, root) : null,
    s.instagramTokenExpiresAt ? el("p", { class: "small muted" }, "Token renews automatically; current expiry " + fmtDate(s.instagramTokenExpiresAt) + ".") : null));

  // ----- You -----
  const pf = {
    creatorName: el("input", { value: p.creatorName, maxlength: "200" }),
    creatorProfile: el("textarea", { class: "tall", maxlength: "20000" }),
    timezone: el("input", { value: p.timezone }),
    taskRules: el("textarea", { maxlength: "2000", id: "task-rules",
      placeholder: "e.g. For application forms, list what the form asks for.\nGifted-only offers are low priority.\nAlways say if usage rights aren't mentioned." }),
  };
  pf.creatorProfile.value = p.creatorProfile;
  pf.taskRules.value = p.taskRules || "";
  panes.you.appendChild(settingsSection("settings-about", "About you",
    el("label", {}, "Your name (used in sign-offs)"), pf.creatorName,
    el("label", {}, "Voice, rates & rules — drafts only quote rates written here"), pf.creatorProfile,
    el("label", {}, "Time zone"), pf.timezone,
    el("label", { for: "task-rules" }, "Your rules for to-dos (Claude follows these when it reads a new email and writes the to-do and its summary)"), pf.taskRules,
    savePrefsButton(pf)));

  // ----- Deals & money -----
  // Follow-ups
  const fu = {
    followupCadenceDays: el("input", { value: p.followupCadenceDays, pattern: "[0-9, ]+" }),
    followupTime: el("input", { type: "time", value: p.followupTime }),
    followupAutoSend: el("input", { type: "checkbox", id: "followup-auto-send" }),
  };
  fu.followupAutoSend.checked = p.followupAutoSend === "true";
  panes.deals.appendChild(settingsSection("settings-followups", "Follow-ups",
    el("p", { class: "small muted" }, "Each day at this time the app checks for new messages and drafts every follow-up that's due. Brands that reply drop out automatically."),
    el("div", { class: "grid" },
      el("div", {}, el("label", {}, "Days to wait before follow-up #1, #2, …"), fu.followupCadenceDays),
      el("div", {}, el("label", {}, "Daily follow-up time (your time zone)"), fu.followupTime)),
    el("label", { class: "check", for: "followup-auto-send" }, fu.followupAutoSend,
      " Send email follow-ups automatically at that time"),
    el("p", { class: "small muted" }, "Off: follow-ups wait in Drafts for you to press Send. On: email follow-ups go out by themselves; replies, rates and anything else still need your approval. Instagram follow-ups always wait, because Meta only allows replies within 24 hours."),
    el("p", {}, el("button", { class: "primary small", onclick: action(async () => {
      if (fu.followupAutoSend.checked && p.followupAutoSend !== "true"
          && !confirm("Email follow-ups will be sent without asking you first. Turn this on?")) return;
      await api("PUT", "/api/settings/preferences", { followupCadenceDays: fu.followupCadenceDays.value,
        followupTime: fu.followupTime.value, followupAutoSend: String(fu.followupAutoSend.checked) });
      renderSettings(root);
    }, "Saved") }, "Save"))));

  // Invoices
  const iv = {
    invoiceBusinessName: el("input", { value: p.invoiceBusinessName, maxlength: "200", placeholder: p.creatorName }),
    invoiceAddress: el("textarea", { maxlength: "2000", placeholder: "Street\nCity, postcode\nCountry" }),
    invoiceTaxId: el("input", { value: p.invoiceTaxId, maxlength: "100" }),
    invoicePaymentDetails: el("textarea", { maxlength: "2000", placeholder: "Bank name, account holder, account number / IBAN, or PayPal email" }),
    invoicePrefix: el("input", { value: p.invoicePrefix, maxlength: "10" }),
    invoiceTermsDays: el("input", { type: "number", min: "0", max: "365", value: p.invoiceTermsDays }),
    paymentReminderDays: el("input", { value: p.paymentReminderDays, pattern: "[0-9, ]*", placeholder: "Off" }),
  };
  iv.invoiceAddress.value = p.invoiceAddress;
  iv.invoicePaymentDetails.value = p.invoicePaymentDetails;
  panes.deals.appendChild(settingsSection("settings-invoices", "Invoices",
    el("p", { class: "small muted" }, "Printed on every invoice. Payment details only appear in the PDF; they're never shown to Claude."),
    el("div", { class: "grid" },
      el("div", {}, el("label", {}, "Business name"), iv.invoiceBusinessName),
      el("div", {}, el("label", {}, "Tax ID (optional)"), iv.invoiceTaxId)),
    el("label", {}, "Address"), iv.invoiceAddress,
    el("label", {}, "How brands pay you"), iv.invoicePaymentDetails,
    el("div", { class: "grid" },
      el("div", {}, el("label", {}, "Invoice number prefix (" + (p.invoicePrefix || "INV") + "-" + new Date().getFullYear() + "-001)"), iv.invoicePrefix),
      el("div", {}, el("label", {}, "Payment due after (days)"), iv.invoiceTermsDays)),
    el("label", {}, "Payment reminders: days after the due date"), iv.paymentReminderDays,
    el("p", { class: "small muted" }, "On each of these days a polite reminder with the invoice attached is drafted for an unpaid invoice. "
      + "Reminders always wait in Drafts for you, stop as soon as you mark the invoice paid, and pause while you're checking a payment "
      + "the brand says it sent. Leave blank to turn them off."),
    savePrefsButton(iv)));

  // Rate advisor
  const ra = {
    rateUsagePercentPerMonth: el("input", { type: "number", min: "0", max: "999", value: p.rateUsagePercentPerMonth }),
    rateExclusivityPercent: el("input", { type: "number", min: "0", max: "999", value: p.rateExclusivityPercent }),
  };
  panes.deals.appendChild(settingsSection("settings-rates", "Rate advisor",
    el("p", { class: "small muted" }, "On a deal you're still deciding, the app suggests what to ask for. Your price per Reel, Story, "
      + "TikTok, post or UGC video comes from your last paid deals once you have five, and until then from the rates in About you. "
      + "Paid usage and exclusivity add these percentages. The number only goes to a brand if you put it in a counter-offer and send it."),
    el("div", { class: "grid" },
      el("div", {}, el("label", {}, "Paid usage: add % per month"), ra.rateUsagePercentPerMonth),
      el("div", {}, el("label", {}, "Exclusivity: add %"), ra.rateExclusivityPercent)),
    savePrefsButton(ra)));

  // Contract check
  const ck = {
    contractMaxPaymentDays: el("input", { type: "number", min: "0", max: "999", value: p.contractMaxPaymentDays }),
    contractFreeUsageMonths: el("input", { type: "number", min: "0", max: "999", value: p.contractFreeUsageMonths }),
    contractRevisionsIncluded: el("input", { type: "number", min: "0", max: "99", value: p.contractRevisionsIncluded }),
  };
  panes.deals.appendChild(settingsSection("settings-contracts", "Contract check",
    el("p", { class: "small muted" }, "When a brand emails a contract as a PDF, Claude reads its terms and the app flags anything outside these limits. "
      + "Contracts on DocuSign and similar sites can't be opened by the app; paste their text on the deal instead. Each contract costs one Claude call."),
    el("div", { class: "grid" },
      el("div", {}, el("label", {}, "Get paid within (days)"), ck.contractMaxPaymentDays),
      el("div", {}, el("label", {}, "Paid usage your fee includes (months)"), ck.contractFreeUsageMonths),
      el("div", {}, el("label", {}, "Revision rounds you include"), ck.contractRevisionsIncluded)),
    savePrefsButton(ck)));

  // Rebooking past brands
  const wb = {
    winBackQuietDays: el("input", { type: "number", min: "14", max: "999", value: p.winBackQuietDays }),
    winBackWeeklyLimit: el("input", { type: "number", min: "0", max: "20", value: p.winBackWeeklyLimit }),
  };
  panes.deals.appendChild(settingsSection("settings-rebook", "Rebooking past brands",
    el("p", { class: "small muted" }, "Each week the app drafts a few re-pitches to brands that paid you and have gone quiet, and to brands "
      + "whose gifted collab went up at least two weeks ago. Brands with an open deal or a recent pitch are skipped. "
      + "Re-pitches wait in Drafts and show on Today; nothing is sent until you approve it."),
    el("div", { class: "grid" },
      el("div", {}, el("label", {}, "Quiet for at least (days)"), wb.winBackQuietDays),
      el("div", {}, el("label", {}, "Re-pitches per week (0 = off)"), wb.winBackWeeklyLimit)),
    savePrefsButton(wb)));

  // ----- Advanced -----
  const adv = {
    classifierModel: modelSelect(p.classifierModel, [
      ["claude-sonnet-5-5", "Sonnet 5.5: cheaper, under 1¢ per message (recommended)"],
      ["claude-opus-5-5", "Opus 5.5: most careful, about 2× the cost"]]),
    writerModel: modelSelect(p.writerModel, [
      ["claude-opus-5-5", "Opus 5.5: best writing, about 2–4¢ per draft (recommended)"],
      ["claude-sonnet-5-5", "Sonnet 5.5: about half the cost, plainer drafts"]]),
    brandKeywords: el("textarea", { maxlength: "2000" }),
  };
  adv.brandKeywords.value = p.brandKeywords;
  panes.advanced.appendChild(el("p", { class: "muted small" }, "You don't need anything on this tab for everyday use. The defaults are fine."));
  panes.advanced.appendChild(settingsSection("settings-claude", "Claude models and email filtering",
    el("div", { class: "grid" },
      el("div", {}, el("label", {}, "Claude for reading messages"), adv.classifierModel),
      el("div", {}, el("label", {}, "Claude for writing drafts and finding brands"), adv.writerModel)),
    el("p", { class: "small muted" }, "Costs are rough. Settings → App → Claude spending shows what you actually spend."),
    el("label", {}, "Brand keywords (emails without these in bulk/automated mail are skipped before AI)"), adv.brandKeywords,
    savePrefsButton(adv)));

  // Instagram extras
  const igTokenBox = el("div", {}, secretField("INSTAGRAM_ACCESS_TOKEN", "Paste an access token from the Meta App Dashboard"));
  const webhookOut = el("div", { class: "code hidden" });
  panes.advanced.appendChild(settingsSection("settings-instagram-extras", "Instagram: token and real-time DMs",
    el("p", { class: "small muted" }, "Instead of Connect Instagram on the Accounts tab, you can paste an access token here."),
    igTokenBox,
    el("div", { class: "row" }, el("button", { class: "small", onclick: saveSecrets(igTokenBox) }, "Save token")),
    el("p", { class: "small muted" }, "Real-time DMs (optional): in the Meta dashboard set the webhook callback URL to the address below, subscribe to “messages” (and “comments” and “mentions” for brands engaging with you), and use a verify token generated here. Without webhooks, DMs and comments are fetched on each sync."),
    el("div", { class: "code" }, s.instagramWebhookUrl),
    el("div", { class: "row" }, el("button", { class: "small", onclick: action(async () => {
      const r = await api("POST", "/api/settings/instagram-webhook-token");
      webhookOut.textContent = "Verify token (shown once): " + r.verifyToken;
      webhookOut.classList.remove("hidden");
    }) }, c.INSTAGRAM_WEBHOOK_VERIFY_TOKEN ? "Regenerate verify token" : "Generate verify token")),
    webhookOut));

  // Facebook (optional, for brand lookups)
  const fb = s.facebook || {};
  const fbBox = el("div", {}, secretField("FACEBOOK_APP_ID", "Meta app ID (Facebook Login)"), secretField("FACEBOOK_APP_SECRET", "Meta app secret"));
  panes.advanced.appendChild(settingsSection("settings-facebook", "Facebook for brand lookups (optional)",
    el("p", { class: "small muted" }, "Lets Outreach look up any brand's Instagram account by handle (followers, bio, the creators it tags in sponsored posts, an email in its bio), hide fans among accounts engaging with you, and see posts you're tagged in. Your DMs and stats keep using the Instagram connection on the Accounts tab."),
    el("p", { class: "small muted" }, "Needs your Instagram account linked to a Facebook Page you manage (Instagram → Settings → Accounts Center), and the “Instagram API with Facebook Login” use case in your Meta app with instagram_basic, instagram_manage_comments, instagram_manage_insights, pages_show_list, pages_read_engagement and business_management. While the app is in development mode, give your Facebook account a role on it. Redirect URI:"),
    el("div", { class: "code" }, s.facebookRedirectUri),
    fbBox,
    el("div", { class: "row" },
      el("button", { class: "small", onclick: saveSecrets(fbBox) }, "Save app"),
      c.FACEBOOK_APP_ID && c.FACEBOOK_APP_SECRET ? el("button", { class: "primary small", onclick: action(async () => {
        const r = await api("POST", "/oauth/facebook/start"); location.href = r.url;
      }) }, fb.connected ? "Reconnect Facebook" : "Connect Facebook") : null,
      fb.connected ? el("button", { class: "small danger", onclick: action(async () => { await api("POST", "/oauth/facebook/disconnect"); renderSettings(root); }, "Disconnected") }, "Disconnect") : null),
    fb.connected ? el("p", { class: "small" }, icon("checkCircle"), " Connected through the Facebook Page “" + fb.page + "”.") : null));

  // MCP
  const keyOut = el("div", { class: "code hidden" });
  panes.advanced.appendChild(settingsSection("settings-mcp", "Use it from Claude (MCP, optional)",
    el("p", { class: "small muted" }, "Lets Claude Desktop, Claude Code or Cowork read your plan and update the CRM. MCP endpoint:"),
    el("div", { class: "code" }, s.mcpUrl),
    el("div", { class: "row" },
      el("button", { class: "small", onclick: action(async () => {
        if (c.MCP_API_KEY_HASH && !confirm("This replaces the existing key. Continue?")) return;
        const r = await api("POST", "/api/settings/mcp-key");
        keyOut.textContent = "API key (copy it now, it won't be shown again):\n" + r.apiKey +
          "\n\nClaude Code:\nclaude mcp add --transport http creator-crm " + s.mcpUrl + " --header \"Authorization: Bearer " + r.apiKey + "\"";
        keyOut.classList.remove("hidden");
      }) }, c.MCP_API_KEY_HASH ? "Regenerate key" : "Generate key"),
      c.MCP_API_KEY_HASH ? el("button", { class: "small danger", onclick: action(async () => { await api("POST", "/api/settings/mcp-key/revoke"); renderSettings(root); }, "Key revoked") }, "Revoke") : null),
    keyOut,
    el("p", { class: "small muted" }, s.mcpAllowSend ? "Sending via MCP is turned on." : "MCP can draft but not send; you approve sends here.")));

  const [spend, learning, startup, errorReports, autoBackup, finders] = await Promise.all([claudeSpendCard(root), learningCard(root),
    startWithWindowsCard(), errorReportsCard(root, c), autoBackupCard(root), contactFindersCard(root, c, secretField, saveSecrets)]);
  if (stale()) return;
  panes.accounts.appendChild(finders);
  panes.you.appendChild(learning);
  panes.advanced.appendChild(errorReports);

  // ----- App -----
  panes.app.appendChild(updatesCard(root));
  if (startup) panes.app.appendChild(startup);
  panes.app.appendChild(spend);
  panes.app.appendChild(autoBackup);
  panes.advanced.appendChild(backupCard(root));

  // Password
  const cur = el("input", { type: "password", autocomplete: "current-password" });
  const nw = el("input", { type: "password", autocomplete: "new-password", minlength: "12" });
  panes.app.appendChild(passwordForm(card("Change password", el("div", { class: "grid" },
    el("div", {}, el("label", {}, "Current password"), cur), el("div", {}, el("label", {}, "New password (12+ characters)"), nw)),
    el("p", {}, el("button", { class: "small", onclick: action(async () => {
      await api("POST", "/api/settings/password", { currentPassword: cur.value, newPassword: nw.value });
      cur.value = ""; nw.value = "";
    }, "Password changed") }, "Change password")))));

  if (s.pendingAnalysis > 0) {
    panes.accounts.appendChild(el("p", { class: "muted small" }, s.pendingAnalysis + (s.pendingAnalysis === 1 ? " message is" : " messages are") + " waiting for Claude to read them" + (c.ANTHROPIC_API_KEY ? "." : ". Add your Claude key above.")));
  }
}

function instagramStatsLine(st, root) {
  const fmt = (n) => Number(n).toLocaleString();
  const text = st
    ? fmt(st.followers) + " followers · " + (st.postsSampled ? st.engagementRatePct + "% engagement over " + st.postsSampled + " posts" : "no recent posts")
      + (st.reach28d != null ? " · " + fmt(st.reach28d) + " accounts reached in 28 days" : "") + " · updated " + fmtDate(st.updatedAt)
    : "Your follower and engagement numbers haven't been read yet.";
  return el("div", { class: "row" }, el("span", { class: "small" }, icon("chart"), " " + text), el("button", { class: "small", onclick: action(async () => {
    await api("POST", "/api/settings/instagram-stats/refresh"); renderSettings(root);
  }, "Instagram stats updated") }, "Refresh stats"));
}

// A model picker with rough costs; keeps a model typed in by hand on an older version.
function modelSelect(current, options) {
  const s = el("select", {}, options.map(([v, label]) => el("option", { value: v }, label)));
  if (current && !options.some(([v]) => v === current)) s.appendChild(el("option", { value: current }, current));
  s.value = current || options[0][0];
  return s;
}

async function learningCard(root) {
  const { stats, recent } = await api("GET", "/api/learning");
  const toggle = el("input", { type: "checkbox", id: "learn-toggle" });
  toggle.checked = stats.enabled;
  toggle.addEventListener("change", action(async () => {
    await api("PUT", "/api/settings/preferences", { learnFromHistory: String(toggle.checked) });
  }, "Saved"));
  const kinds = { FOLLOW_UP: "Follow-up", RATES: "Rates", PITCH: "Pitch", DECLINE: "Decline", REPLY: "Reply" };
  const rows = recent.map((e) => {
    const detail = el("div", { class: "hidden" },
      e.edited && e.aiBody ? el("div", { class: "msg" }, el("div", { class: "meta" }, "Claude's draft"), el("div", { class: "text" }, e.aiBody)) : null,
      el("div", { class: "msg out" }, el("div", { class: "meta" }, e.edited ? "What you sent instead" : "What you sent"), el("div", { class: "text" }, e.sentBody)));
    const exclude = el("button", { class: "small" + (e.excluded ? "" : " danger"), onclick: action(async () => {
      await api("POST", "/api/learning/examples/" + e.id + "/excluded?excluded=" + !e.excluded);
      renderSettings(root);
    }, e.excluded ? "Claude will learn from this again" : "Claude won't use this one") }, e.excluded ? "Use again" : "Don't learn from this");
    return el("div", { class: "item" + (e.excluded ? " muted" : "") },
      el("div", { class: "body" },
        el("div", { class: "row" }, el("strong", {}, e.brandName || "—"), el("span", { class: "badge" }, kinds[e.kind] || pretty(e.kind)),
          e.source === "WRITTEN" ? el("span", { class: "badge" }, "Written by you") : null,
          e.edited ? el("span", { class: "badge accent" }, "You edited it") : null,
          e.gotReply ? el("span", { class: "badge ok" }, "Brand replied") : null,
          e.excluded ? el("span", { class: "badge" }, "Not used") : null,
          el("span", { class: "small muted" }, fmtDate(e.sentAt))),
        detail),
      el("div", { class: "actions" },
        el("button", { class: "small", onclick: (ev) => { detail.classList.toggle("hidden"); ev.currentTarget.textContent = detail.classList.contains("hidden") ? "Show" : "Hide"; } }, "Show"),
        exclude));
  });
  return card("Learning from your writing",
    el("p", { class: "small muted" }, "Every message you send is saved with Claude's original draft and whether the brand replied. New drafts get your closest past examples, favouring the ones you edited and the ones that got answers. Messages you write yourself in Gmail or Instagram count too."),
    el("label", { class: "check", for: "learn-toggle" }, toggle, " Use my past messages when writing drafts"),
    el("div", { class: "stats" }, stat(stats.examples, "messages to learn from"), stat(stats.edited, "drafts you edited"), stat(stats.gotReply, "got a reply")),
    recent.length ? el("div", {}, rows) : emptyLine("Nothing yet. Send a draft or write to a brand and it will show up here."));
}

// ---------- Claude spending and credits ----------
// The API can't report the credit balance, so the app adds up an estimate of each call's cost and counts down
// from the balance last typed in here.

const usd = (n) => "$" + Number(n || 0).toLocaleString(undefined, { minimumFractionDigits: 2, maximumFractionDigits: 2 });

function showCreditBanner(sp) {
  const b = clear(document.getElementById("credit-banner"));
  const a = sp && sp.alert;
  b.classList.toggle("hidden", !a);
  if (!a) return;
  b.classList.toggle("error", a.level === "OUT");
  b.classList.toggle("warn", a.level !== "OUT");
  b.appendChild(el("span", {}, el("strong", {}, a.level === "OUT" ? "Out of Claude credits. " : "Claude credits low. "),
    a.message.replace(/^Claude credits[^.:]*[.:]\s*/, "")));
  b.appendChild(el("span", { class: "spacer" }));
  b.appendChild(el("a", { class: "btn small", href: "#settings?spend" }, "Details"));
}

async function refreshCredits() {
  try { showCreditBanner(await api("GET", "/api/claude-spend")); } catch (e) { /* signed out or offline */ }
}

async function claudeSpendCard(root) {
  const sp = await api("GET", "/api/claude-spend").catch(() => null);
  if (!sp) return el("div");
  showCreditBanner(sp);
  const names = { CLASSIFY: "Reading messages", DRAFT: "Writing drafts", REVISE: "Changing drafts", RESEARCH: "Finding brands", CONTRACT: "Checking contracts", CONTACTS: "Reading contact pictures" };
  const balance = el("input", { type: "number", min: "0", step: "0.01", placeholder: "e.g. 25.00",
    value: sp.balanceUsd != null ? sp.balanceUsd.toFixed(2) : null });
  const before = el("input", { type: "number", min: "0", step: "0.01", value: sp.beforeUsd ? sp.beforeUsd.toFixed(2) : null });
  const save = (path, input) => action(async () => {
    const v = input.value.trim();
    await api("PUT", "/api/claude-spend/" + path, { usd: v === "" ? null : Number(v) });
    renderSettings(root);
  }, "Saved");
  const since = sp.trackedSince ? "since " + fmtDate(sp.trackedSince) + (sp.beforeUsd ? ", plus " + usd(sp.beforeUsd) + " from before" : "") : "nothing yet";
  const left = sp.outOfCreditsSince ? el("p", { class: "alert error" }, "Out of credits since " + fmtDateTime(sp.outOfCreditsSince)
      + ". Add credits in the Claude Console, then enter the new balance below.")
    : sp.remainingUsd != null ? el("p", { class: sp.alert ? "alert error" : "" }, "About " + usd(sp.remainingUsd) + " of credit left (you entered "
      + usd(sp.balanceUsd) + " on " + fmtDate(sp.balanceAt) + ").")
    : el("p", { class: "small muted" }, "Enter your balance below to get a warning before the credits run out.");
  const card_ = card("Claude spending",
    el("div", { class: "stats" },
      stat(usd(sp.totalUsd), "spent in total (" + since + ")"),
      stat(usd(sp.thisMonthUsd), "this month"),
      stat(sp.calls.toLocaleString(), "Claude requests")),
    el("ul", { class: "small" }, Object.entries(sp.byFeature).map(([k, v]) => el("li", {}, (names[k] || pretty(k)) + ": " + usd(v))),
      sp.months.length > 1 ? sp.months.map((m) => el("li", { class: "muted" }, m.month + ": " + usd(m.usd))) : null),
    left,
    el("div", { class: "grid" },
      el("div", {}, el("label", {}, "Credit balance right now ($)"), balance,
        el("div", { class: "row" }, el("button", { class: "primary small", onclick: save("balance", balance) }, "Save balance"))),
      el("div", {}, el("label", {}, "Spent before this app started counting ($)"), before,
        el("div", { class: "row" }, el("button", { class: "small", onclick: save("spent-before", before) }, "Save")))),
    el("p", { class: "small muted" }, "Find both at console.anthropic.com: Billing shows the balance, Usage/Cost shows what was spent. "
      + "Enter the balance again whenever you top up. You'll see a warning at the top when about 20% is left, and a red one if "
      + "the credits run out. The totals are estimates from Claude's token counts and published prices, and may differ a little "
      + "from the bill."));
  card_.id = "spend";
  return card_;
}

// Nightly encrypted backups into a folder she picks (OneDrive by default), so a lost laptop doesn't lose deals.
async function autoBackupCard(root) {
  const b = await api("GET", "/api/backup/auto");
  const kb = (n) => n >= 1048576 ? (n / 1048576).toFixed(1) + " MB" : Math.max(1, Math.round(n / 1024)) + " KB";
  const status = b.lastError
    ? channelError(b.lastError)
    : b.lastBackupAt
      ? el("p", {}, el("span", { class: "status-dot on" }), "Last backup: " + fmtDateTime(b.lastBackupAt) + ", " + kb(b.lastSizeBytes))
      : el("p", { class: "muted" }, b.enabled ? "No backup yet. The first one is made within a few minutes." : "Not set up yet.");
  const folder = el("input", { value: b.folder, placeholder: b.defaultFolder, maxlength: "1000" });
  const pw = el("input", { type: "password", autocomplete: "current-password" });
  const pass = el("input", { type: "password", autocomplete: "new-password", minlength: "12" });
  const pass2 = el("input", { type: "password", autocomplete: "new-password", minlength: "12" });
  const on = el("input", { type: "checkbox", checked: b.enabled, disabled: !b.passphraseSet, onchange: action(async (e) => {
    await api("PUT", "/api/backup/auto", { enabled: e.target.checked });
    renderSettings(root);
  }, "Saved") });
  const save = action(async () => {
    const body = { folder: folder.value };
    if (pass.value || !b.passphraseSet) {
      if (pass.value.length < 12) throw new Error("Backup passphrase must be at least 12 characters");
      if (pass.value !== pass2.value) throw new Error("Passphrases don't match");
      if (!confirm("Write this passphrase down and keep it somewhere safe, away from this computer. "
        + "Without it the backups can't be opened, and it can't be recovered.")) return;
      body.passphrase = pass.value;
      body.currentPassword = pw.value;
    }
    await api("PUT", "/api/backup/auto", body);
    renderSettings(root);
  }, "Saved");
  const c = card("Automatic backups",
    el("p", { class: "small muted" }, "Every night the app saves an encrypted backup into this folder and keeps the newest " + b.keep
      + ". If the computer is off at night, it backs up soon after you open the app. A folder inside OneDrive (the default when you "
      + "have it) means a copy is safe even if this laptop is lost."),
    status,
    el("label", { class: "row check" }, on, "Back up automatically every night"),
    el("label", {}, "Folder"), folder,
    el("h3", {}, b.passphraseSet ? "Change the backup passphrase" : "Choose a backup passphrase"),
    el("div", { class: "grid" },
      el("div", {}, el("label", {}, "Your current password"), pw),
      el("div", {}, el("label", {}, "Backup passphrase (12+ characters)"), pass),
      el("div", {}, el("label", {}, "Confirm passphrase"), pass2)),
    el("div", { class: "row" },
      el("button", { class: "primary small", onclick: save }, b.passphraseSet ? "Save" : "Save and turn on"),
      b.passphraseSet ? el("button", { class: "small", onclick: action(async () => {
        const r = await api("POST", "/api/backup/auto/run");
        if (r.lastError) throw new Error(r.lastError);
        renderSettings(root);
      }, "Backup saved") }, "Back up now") : null),
    el("p", { class: "small muted" }, "To restore one, use Backup & restore on the Advanced tab with the same passphrase."));
  c.id = "backup";
  return passwordForm(c);
}

function backupCard() {
  // Export
  const exPw = el("input", { type: "password", autocomplete: "current-password" });
  const exPass = el("input", { type: "password", autocomplete: "new-password", minlength: "12" });
  const exPass2 = el("input", { type: "password", autocomplete: "new-password", minlength: "12" });
  const doExport = action(async () => {
    if (exPass.value.length < 12) throw new Error("Backup passphrase must be at least 12 characters");
    if (exPass.value !== exPass2.value) throw new Error("Passphrases don't match");
    const res = await fetch("/api/backup/export", {
      method: "POST", credentials: "same-origin",
      headers: { "Content-Type": "application/json", "X-XSRF-TOKEN": csrf() },
      body: JSON.stringify({ currentPassword: exPw.value, passphrase: exPass.value }),
    });
    if (!res.ok) throw new Error(((await res.json().catch(() => ({}))).error) || "Backup failed");
    const blob = await res.blob();
    const name = (res.headers.get("Content-Disposition") || "").match(/filename="?([^"]+)"?/);
    const a = el("a", { href: URL.createObjectURL(blob), download: name ? name[1] : "creator-crm-backup.crmbak" });
    document.body.appendChild(a);
    a.click();
    a.remove();
    setTimeout(() => URL.revokeObjectURL(a.href), 10000);
    exPw.value = exPass.value = exPass2.value = "";
  }, "Backup downloaded. Keep the file and passphrase somewhere safe.");

  // Restore
  const file = el("input", { type: "file", accept: ".crmbak" });
  const rePass = el("input", Object.assign({ type: "password" }, NOT_A_LOGIN));
  const rePw = el("input", { type: "password", autocomplete: "current-password" });
  const restoreHeaders = () => ({ "Content-Type": "application/octet-stream", "X-XSRF-TOKEN": csrf(),
    "X-Backup-Passphrase": encodeURIComponent(rePass.value) });
  const doRestore = action(async () => {
    const f = file.files[0];
    if (!f) throw new Error("Choose a backup file");
    const bytes = await f.arrayBuffer();
    const info = await fetch("/api/backup/inspect", { method: "POST", credentials: "same-origin", headers: restoreHeaders(), body: bytes });
    const summary = await info.json().catch(() => ({}));
    if (!info.ok) throw new Error(summary.error || "Could not read backup");
    const r = summary.rows || {};
    const msg = "Backup from " + fmtDateTime(summary.createdAt) + ":\n" + (r.opportunities || 0) + " deals, " + (r.messages || 0)
      + " messages, " + (r.tasks || 0) + " tasks, " + summary.credentials + " credentials.\n\n"
      + "This REPLACES everything in this install, including user accounts. Everyone will be signed out. Continue?";
    if (!confirm(msg)) return;
    const res = await fetch("/api/backup/restore", { method: "POST", credentials: "same-origin",
      headers: Object.assign(restoreHeaders(), { "X-Current-Password": encodeURIComponent(rePw.value) }), body: bytes });
    if (!res.ok) throw new Error(((await res.json().catch(() => ({}))).error) || "Restore failed");
    alert("Restore complete. Sign in with the account from the backup.");
    location.replace("/login.html");
  });

  return passwordForm(card("Backup & restore",
    el("p", { class: "small muted" }, "A backup is one encrypted file with all your deals, messages, settings and connected-account credentials. "
      + "It can be restored on any Creator CRM install (embedded database or PostgreSQL). Without the passphrase it can't be opened — and it can't be recovered if you forget it."),
    el("div", { class: "grid" },
      el("div", {}, el("label", {}, "Your current password"), exPw),
      el("div", {}, el("label", {}, "Backup passphrase (12+ characters)"), exPass),
      el("div", {}, el("label", {}, "Confirm passphrase"), exPass2)),
    el("p", {}, el("button", { class: "primary small", onclick: doExport }, "Download backup")),
    el("h3", {}, "Restore from a backup"),
    el("div", { class: "grid" },
      el("div", {}, el("label", {}, "Backup file"), file),
      el("div", {}, el("label", {}, "Backup passphrase"), rePass),
      el("div", {}, el("label", {}, "Your current password"), rePw)),
    el("p", {}, el("button", { class: "danger small", onclick: doRestore }, "Restore…"))));
}

function channelStatus(ch, name) {
  return el("div", {},
    el("p", { class: "small" }, el("span", { class: "status-dot" + (ch.connected ? " on" : "") }),
      ch.connected ? "Connected" + (ch.account ? " as " + ch.account : "") : "Not connected",
      ch.lastSync ? " · last checked " + fmtDateTime(ch.lastSync) : ""),
    ch.lastError ? channelError(ch.lastError, name) : null);
}

function importHistory() {
  const days = el("select", { "aria-label": "How far back" },
    [["30", "Last 30 days"], ["90", "Last 3 months"], ["180", "Last 6 months"], ["365", "Last year"]]
      .map(([v, label]) => el("option", { value: v }, label)));
  days.value = "90";
  return el("div", { class: "import-box" },
    el("h4", {}, "Import older email"),
    el("p", { class: "small muted" }, "Brings in past deals. Older email is analyzed oldest first so each deal's status builds up in order, and "
      + "reply drafts are only written for unanswered email from the last 2 months. Big imports are analyzed at half price "
      + "in the background, so deals fill in over a few hours. Already-imported email is skipped; "
      + "Gmail rate limits pause and resume it automatically."),
    el("div", { class: "row" }, days,
      el("button", { class: "small", onclick: action(async () => {
        await api("POST", "/api/sync/import", { days: Number(days.value) });
        setTimeout(pollStatus, 1000);
      }, "Import started. Progress shows at the top.") }, "Import")));
}

// Raw errors from Google, Meta and the network, in words she can act on. The original stays under Details.
const PLAIN_ERRORS = [
  [/invalid_grant|invalid_token|token has been|expired|revoked|unauthori[sz]ed|\b401\b|OAuthException/i,
    (n) => n + " needs you to sign in again. Press Reconnect " + n + "."],
  [/\b403\b|insufficient|permission|scope/i,
    (n) => n + " hasn't given the app permission for this. Press Reconnect " + n + " and allow everything on the sign-in screen."],
  [/\b429\b|rate.?limit|quota|too many requests/i, (n) => n + " asked the app to slow down. It carries on by itself shortly."],
  [/UnknownHost|timed? ?out|connection (refused|reset)|no route|network is unreachable|SocketException/i,
    (n) => "Couldn't reach " + n + ". Check this computer is online; the app tries again by itself."],
  [/\b5\d\d\b|backendError|service unavailable|internal server error|overloaded/i,
    (n) => n + " is having problems on its side. The app tries again by itself."],
];

function plainError(text, name) {
  const hit = name && PLAIN_ERRORS.find(([re]) => re.test(text));
  return hit ? hit[1](name) : null;
}

// Short first line always visible; anything longer folds into "Details" so the page never scrolls sideways.
function channelError(text, name) {
  text = text.replace(/^\d{4}-\d\d-\d\dT\S+\s+/, ""); // stored with the time it happened
  const first = plainError(text, name) || text.split("\n")[0];
  const summary = first.length > 160 ? first.slice(0, 160) + "…" : first;
  return el("div", { class: "alert error channel-error" },
    el("strong", {}, "Last error: "), summary,
    text !== summary ? el("details", {}, el("summary", {}, "Details"), el("pre", {}, text)) : null);
}
