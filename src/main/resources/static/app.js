/*
 * Creator CRM dashboard. Vanilla JS, no build step.
 * All third-party text (emails, DMs, brand names) is inserted with textContent, never innerHTML.
 */
(function () {
  "use strict";

  // ---------- helpers ----------

  function csrf() {
    const m = document.cookie.match(/(?:^|;\s*)XSRF-TOKEN=([^;]+)/);
    return m ? decodeURIComponent(m[1]) : "";
  }

  async function api(method, path, body) {
    const opts = { method, credentials: "same-origin", headers: {} };
    if (method !== "GET") opts.headers["X-XSRF-TOKEN"] = csrf();
    if (body !== undefined) {
      opts.headers["Content-Type"] = "application/json";
      opts.body = JSON.stringify(body);
    }
    const res = await fetch(path, opts);
    if (res.status === 401) { location.replace("/login.html"); throw new Error("Signed out"); }
    const data = res.status === 204 ? null : await res.json().catch(() => null);
    if (!res.ok) throw new Error((data && data.error) || "Request failed (" + res.status + ")");
    return data;
  }

  /** el("div", {class: "x", onclick: fn}, "text", childNode, [more]) */
  function el(tag, attrs, ...children) {
    const node = document.createElement(tag);
    for (const [k, v] of Object.entries(attrs || {})) {
      if (v === null || v === undefined || v === false) continue;
      if (k.startsWith("on")) node.addEventListener(k.slice(2), v);
      else if (k === "class") node.className = v;
      else if (k === "value") node.value = v;
      else node.setAttribute(k, v === true ? "" : v);
    }
    for (const c of children.flat()) {
      if (c === null || c === undefined || c === false) continue;
      node.appendChild(typeof c === "string" || typeof c === "number" ? document.createTextNode(String(c)) : c);
    }
    return node;
  }

  function clear(node) { while (node.firstChild) node.removeChild(node.firstChild); return node; }

  let toastTimer;
  function toast(msg, isError) {
    const t = document.getElementById("toast");
    t.textContent = msg;
    t.className = "toast" + (isError ? " error" : "");
    clearTimeout(toastTimer);
    toastTimer = setTimeout(() => t.classList.add("hidden"), isError ? 7000 : 3500);
  }

  /** Wrap an async click handler: disable the button, report errors. */
  function action(fn, okMsg) {
    return async (e) => {
      const btn = e && e.currentTarget;
      if (btn) btn.disabled = true;
      try {
        await fn(e);
        if (okMsg) toast(okMsg);
      } catch (err) {
        toast(err.message, true);
      } finally {
        if (btn) btn.disabled = false;
      }
    };
  }

  // Same rule as Placeholders.java: capitals in square brackets, e.g. [RATE FOR 1 REEL] or [MEDIA KIT LINK].
  function blanksIn(...texts) {
    const found = new Set();
    for (const t of texts) for (const m of (t || "").matchAll(/\[[A-Z][A-Z0-9 &'\/+.,:#$%-]{2,80}\]/g)) found.add(m[0]);
    return [...found];
  }

  function pretty(s) {
    if (!s) return "";
    if (s === "REPITCH") return "Re-pitch";
    s = String(s).toLowerCase().replace(/_/g, " ");
    return s.charAt(0).toUpperCase() + s.slice(1);
  }

  function fmtDate(iso) {
    if (!iso) return "";
    const d = new Date(iso.length === 10 ? iso + "T00:00:00" : iso);
    return d.toLocaleDateString(undefined, { month: "short", day: "numeric" });
  }

  function fmtDateTime(iso) {
    return iso ? new Date(iso).toLocaleString(undefined, { month: "short", day: "numeric", hour: "numeric", minute: "2-digit" }) : "";
  }

  function card(title, ...children) {
    return el("div", { class: "card" }, title ? el("h3", {}, title) : null, ...children);
  }

  function emptyLine(text) { return el("div", { class: "empty" }, text); }

  // ---------- password managers ----------
  // Browsers treat every password box on a page as one big login form: they fill her saved password into
  // the first one (the Claude API key) and her username into whatever text box comes before it (a search
  // box). Keys and tokens are marked as not-a-login, and each card that asks for her password gets its own
  // form with a hidden username box, so the saved login never lands anywhere else.

  const NOT_A_LOGIN = { autocomplete: "new-password", "data-lpignore": "true", "data-1p-ignore": "true", "data-bwignore": "true" };

  function passwordForm(node) {
    // Buttons in a form submit it by default, and Enter in a field "clicks" the first one. Neither should run an action.
    node.querySelectorAll("button:not([type])").forEach((b) => { b.type = "button"; });
    return el("form", { class: "password-form", onsubmit: (e) => e.preventDefault() },
      el("input", { type: "text", name: "username", autocomplete: "username", hidden: true, tabindex: "-1", "aria-hidden": "true" }),
      node);
  }

  // ---------- list controls: search, filter chips, sortable columns ----------

  /** A view's filters and sort, kept in this browser so they survive a refresh. */
  function loadPrefs(view, defaults) {
    try {
      const saved = JSON.parse(localStorage.getItem("crm.view." + view));
      if (saved && typeof saved === "object") return Object.assign({}, defaults, saved);
    } catch (e) { /* private window or bad JSON */ }
    return Object.assign({}, defaults);
  }

  function savePrefs(view, prefs) {
    try { localStorage.setItem("crm.view." + view, JSON.stringify(prefs)); } catch (e) { /* ignore */ }
  }

  function searchBox(value, placeholder, onChange) {
    const input = el("input", { type: "search", class: "search", value: value || "", placeholder, "aria-label": placeholder, maxlength: "100",
      autocomplete: "off", name: "filter" });
    let timer;
    input.addEventListener("input", () => { clearTimeout(timer); timer = setTimeout(() => onChange(input.value), 150); });
    return input;
  }

  /** Every word of the query appears somewhere in the fields, ignoring case. */
  function matchesQuery(q, ...fields) {
    const words = String(q || "").toLowerCase().split(/\s+/).filter(Boolean);
    if (!words.length) return true;
    const hay = fields.filter((f) => f !== null && f !== undefined).join(" ").toLowerCase();
    return words.every((w) => hay.includes(w));
  }

  /** options: [[value, label], …]; picking the active chip again goes back to the first option ("All"). */
  function chipRow(options, selected, onPick, label) {
    return el("div", { class: "chips", role: "group", "aria-label": label || "Filter" }, options.map(([value, text]) =>
      el("button", { class: "chip" + (value === selected ? " active" : ""), "aria-pressed": String(value === selected),
        onclick: () => onPick(value === selected ? options[0][0] : value) }, text)));
  }

  /** keys: {column: row => comparable}. Blank values always sort last. */
  function sortRows(rows, sort, keys) {
    const key = keys[sort && sort.by];
    if (!key) return rows;
    const dir = sort.dir === "asc" ? 1 : -1;
    return rows.slice().sort((a, b) => {
      const x = key(a), y = key(b);
      const xb = x === null || x === undefined || x === "", yb = y === null || y === undefined || y === "";
      if (xb || yb) return xb === yb ? 0 : xb ? 1 : -1;
      if (typeof x === "string") return x.localeCompare(y, undefined, { sensitivity: "base", numeric: true }) * dir;
      return (x < y ? -1 : x > y ? 1 : 0) * dir;
    });
  }

  /** cols: [[label, sortKey or null, first direction]]. Clicking the sorted column again flips it. */
  function sortableHead(cols, sort, onSort) {
    return el("thead", {}, el("tr", {}, cols.map(([label, key, firstDir]) => {
      if (!key) return el("th", {}, label);
      const active = sort.by === key;
      return el("th", { "aria-sort": active ? (sort.dir === "asc" ? "ascending" : "descending") : "none" },
        el("button", { class: "th-sort", title: "Sort by " + label.toLowerCase(),
          onclick: () => onSort(active ? { by: key, dir: sort.dir === "asc" ? "desc" : "asc" } : { by: key, dir: firstDir || "asc" }) },
          label, el("span", { class: "sort-mark", "aria-hidden": "true" }, active ? (sort.dir === "asc" ? " ▲" : " ▼") : "")));
    })));
  }

  function shownLine(shown, total, noun, onClear) {
    if (shown === total) return null;
    return el("div", { class: "row small muted shown-line" }, "Showing " + shown + " of " + total + " " + noun + ".",
      onClear ? el("button", { class: "small", onclick: onClear }, "Clear filters") : null);
  }

  // ---------- routing ----------

  const views = { today: renderToday, pipeline: renderPipeline, money: renderMoney, outreach: renderOutreach, links: renderLinks,
                  drafts: renderDrafts, summary: renderSummary, settings: renderSettings,
                  help: renderHelp, whatsnew: renderWhatsNew, more: renderMore, setup: renderSetup };
  // Five places in the tab bar; the other pages live under Deals or More, and keep their own addresses.
  const NAV_OF = { outreach: "pipeline", links: "more", summary: "more", settings: "more", help: "more", whatsnew: "more", setup: "more" };
  let statuses = {};

  function currentTab() {
    const h = location.hash.replace(/^#/, "").split("?")[0];
    return views[h] ? h : "today";
  }

  async function route() {
    const tab = currentTab();
    const nav = NAV_OF[tab] || tab;
    document.querySelectorAll(".tab").forEach((b) => {
      b.classList.toggle("active", b.dataset.tab === nav);
      if (b.dataset.tab === nav) b.setAttribute("aria-current", "page"); else b.removeAttribute("aria-current");
    });
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

  // ---------- Today ----------

  async function renderToday(root) {
    const t = await api("GET", "/api/today");
    clear(root);
    root.appendChild(el("h1", {}, t.headline));
    root.appendChild(el("p", { class: "muted" }, new Date(t.date + "T00:00:00").toLocaleDateString(undefined,
      { weekday: "long", month: "long", day: "numeric" })));

    const pipelineTotal = Object.values(t.pipeline).reduce((a, b) => a + b, 0);
    root.appendChild(el("p", { class: "today-sums" }, pipelineTotal + " open deal" + (pipelineTotal === 1 ? "" : "s")
      + (t.openPipelineValue ? " worth $" + Number(t.openPipelineValue).toLocaleString() : "") + " · "
      + t.followUps.length + " follow-up" + (t.followUps.length === 1 ? "" : "s") + " due · ",
      el("a", { href: "#drafts" }, t.approvals.length + " draft" + (t.approvals.length === 1 ? "" : "s") + " to approve")));

    // The list is already ranked (money, lateness, priority). Three things are a plan; thirteen are a pile.
    const first = t.urgent.slice(0, 3);
    const rest = t.urgent.slice(3);
    root.appendChild(card("Do these first", itemList(first, "Nothing urgent. Nice.", true)));
    if (rest.length) {
      root.appendChild(el("details", { class: "card more-items" },
        el("summary", {}, el("h3", {}, "Everything else for today (" + rest.length + ")")),
        itemList(rest, "", false)));
    }
    root.appendChild(card("📌 Follow-ups", followUpList(t.followUps)));
    if (t.rebook && t.rebook.length) root.appendChild(rebookCard(t.rebook));

    if (t.approvals.length) {
      root.appendChild(card("✉️ Drafts awaiting your approval",
        el("ul", { class: "list" }, t.approvals.map((a) => el("li", { class: "item" },
          el("div", { class: "body" }, el("div", { class: "title" }, a.brand + " — " + pretty(a.type)),
            el("div", { class: "detail" }, a.preview)),
          el("div", { class: "actions" }, el("button", { class: "small primary", onclick: () => showDraft({ id: a.draftId }) }, "Review")))))));
    }

    const o = t.newOpportunities;
    root.appendChild(card("💰 New opportunities (last 24h)",
      el("p", {}, o.paid + " paid · " + o.gifted + " gifted · " + o.affiliate + " affiliate · " + o.other + " other"),
      o.items.length ? el("ul", {}, o.items.map((s) => el("li", {}, s))) : null));

    root.appendChild(card("📅 Upcoming", itemList(t.upcoming, "Nothing scheduled in the next two weeks.", false)));
  }

  // Win back past brands: re-pitches drafted for brands she worked with before, a few each week.
  function rebookCard(items) {
    const c = card("🔁 Rebook past brands",
      el("p", { class: "small muted" }, "Brands that paid you and have gone quiet, or gave you a gifted collab a few weeks ago. "
        + "A short re-pitch is ready for each; nothing is sent until you approve it."),
      el("ul", { class: "list" }, items.map((it) => el("li", { class: "item" },
        el("div", { class: "body" }, el("div", { class: "title" }, it.title), el("div", { class: "detail" }, it.detail)),
        el("div", { class: "actions" },
          el("button", { class: "small primary", onclick: () => showDraft({ id: it.refId }) }, "Review re-pitch"),
          el("button", { class: "small", title: "Skip this brand for now", onclick: action(async () => {
            await api("POST", "/api/drafts/" + it.refId + "/discard"); route();
          }, "Skipped") }, "Not now"),
          el("button", { class: "small", onclick: () => openDeal(it.opportunityId) }, "Open"))))));
    c.id = "rebook";
    return c;
  }

  // Lead score from LeadScoring: High / Medium / Low, with the reasons on hover.
  function leadBadge(level, why) {
    if (!level) return null;
    const cls = level === "HIGH" ? "badge ok" : level === "MEDIUM" ? "badge medium" : "badge";
    const text = level === "HIGH" ? "High value" : level === "MEDIUM" ? "Medium" : "Low value";
    return el("span", { class: cls + " lead", title: why || "" }, text);
  }

  function stat(v, l) { return el("div", { class: "stat" }, el("div", { class: "v" }, String(v)), el("div", { class: "l" }, l)); }

  function statButton(v, l, title, onclick) {
    return el("button", { class: "stat clickable", title, onclick }, el("div", { class: "v" }, String(v)), el("div", { class: "l" }, l));
  }

  // What a to-do is about: Claude's brief of the email and the links from it (forms, briefs, contracts).
  function taskAbout(brief, links) {
    const ok = (links || []).filter((l) => /^https?:\/\//i.test(l.url));
    if (!brief && !ok.length) return null;
    return el("div", { class: "task-about" },
      brief ? el("p", { class: "brief" }, brief) : null,
      ok.length ? el("div", { class: "chips links" }, ok.map((l) =>
        el("a", { class: "chip", href: l.url, target: "_blank", rel: "noopener noreferrer", title: l.url }, "🔗 " + (l.label || "Link")))) : null);
  }

  // One click to the email a to-do came from: in the app (works offline, for DMs too) and in Gmail.
  function emailButtons(opportunityId, info) {
    if (!info || !info.messageId) return [];
    return [
      opportunityId ? el("button", { class: "small", title: "Read the message this to-do came from",
        onclick: () => openDeal(opportunityId, info.messageId) }, "Read email") : null,
      info.gmailUrl ? el("a", { class: "btn small", href: info.gmailUrl, target: "_blank", rel: "noopener noreferrer",
        title: "Open this email in Gmail" }, "Open in Gmail") : null];
  }

  function itemList(items, emptyText, numbered) {
    if (!items.length) return emptyLine(emptyText);
    return el("ul", { class: "list" }, items.map((it, i) => el("li", { class: "item" },
      numbered ? el("span", { class: "num" }, (i + 1) + ".") : null,
      el("div", { class: "body" },
        el("div", { class: "title" }, it.title),
        el("div", { class: "detail" },
          it.overdueDays > 0 ? el("span", { class: "badge overdue" }, "Overdue " + it.overdueDays + "d") : null, " ",
          it.priority === "HIGH" && !it.overdueDays ? el("span", { class: "badge high" }, "High") : null, " ",
          it.lead ? leadBadge(it.lead, it.leadWhy) : null, it.lead ? " " : null,
          // The Overdue badge already says it; the text version is for emails and Claude.
          it.overdueDays > 0 ? (it.detail || "").replace(/\s*·?\s*⚠️ OVERDUE by \d+ days?/, "") : it.detail),
        it.task ? taskAbout(it.task.brief, it.task.links) : null),
      el("div", { class: "actions" },
        it.kind === "TASK" ? emailButtons(it.opportunityId, it.task) : null,
        it.kind === "TASK" ? el("button", { class: "small", onclick: action(async () => { await api("POST", "/api/tasks/" + it.refId + "/done"); route(); }, "Marked done") }, "Done") : null,
        it.kind === "DEADLINE" ? el("button", { class: "small", onclick: action(async () => { await api("POST", "/api/deadlines/" + it.refId + "/done"); route(); }, "Deadline cleared") }, "Done") : null,
        it.kind === "INVOICE" && /reminder ready/.test(it.detail) ? el("button", { class: "small primary", onclick: () => { location.hash = "#drafts?invoice=" + it.refId; } }, "Review reminder") : null,
        it.kind === "INVOICE" ? el("button", { class: "small", title: "The money arrived", onclick: action(async () => {
          if (!confirm("Mark this invoice as paid today?")) return;
          await api("POST", "/api/invoices/" + it.refId + "/paid"); route();
        }, "Marked paid 🎉") }, "Mark paid") : null,
        it.kind === "INVOICE" ? el("button", { class: "small", onclick: () => openInvoice(it.refId) }, "Open")
          : it.opportunityId ? el("button", { class: "small", onclick: () => openDeal(it.opportunityId) }, "Open") : null))));
  }

  function followUpList(items) {
    if (!items.length) return emptyLine("No follow-ups due today.");
    return el("ul", { class: "list" }, items.map((it) => el("li", { class: "item" },
      el("div", { class: "body" }, el("div", { class: "title" }, it.title),
        el("div", { class: "detail" }, it.overdueDays > 0 ? el("span", { class: "badge overdue" }, it.detail) : it.detail)),
      el("div", { class: "actions" },
        el("button", { class: "small", title: "Write a follow-up draft", onclick: action(async () => {
          const made = await api("POST", "/api/opportunities/" + it.opportunityId + "/drafts", { type: "FOLLOW_UP", instructions: it.title });
          showDraft(made);
        }, "Draft created") }, "Draft"),
        el("button", { class: "small", title: "I already sent it", onclick: action(async () => {
          await api("POST", "/api/opportunities/" + it.opportunityId + "/followups/sent"); route();
        }, "Follow-up recorded") }, "Sent"),
        el("button", { class: "small", onclick: () => openDeal(it.opportunityId) }, "Open")))));
  }

  // Deals has two halves: brands already talking to her, and brands she is pitching.
  function dealsSwitch(active) {
    return el("nav", { class: "segmented", "aria-label": "Deals" },
      [["pipeline", "Your deals"], ["outreach", "Pitching brands"]].map(([tab, label]) =>
        el("a", { href: "#" + tab, class: tab === active ? "on" : "", "aria-current": tab === active ? "page" : null }, label)));
  }

  // ---------- Pipeline ----------

  const PIPELINE_DEFAULTS = { q: "", status: "", comp: "", lead: "", closed: false, sort: { by: "updatedAt", dir: "desc" } };
  let pipelineFilter = loadPrefs("pipeline", PIPELINE_DEFAULTS);

  async function renderPipeline(root) {
    const f = pipelineFilter;
    const rows = await api("GET", "/api/pipeline?includeClosed=" + f.closed);
    clear(root);
    const save = () => savePrefs("pipeline", f);
    const statusOrder = Object.keys(statuses);
    const statusSelect = el("select", { "aria-label": "Status", onchange: (e) => { f.status = e.target.value; save(); draw(); } },
      el("option", { value: "" }, "All statuses"),
      Object.entries(statuses).map(([k, v]) => el("option", { value: k, selected: f.status === k }, v)));
    const compSelect = el("select", { "aria-label": "Deal type", onchange: (e) => { f.comp = e.target.value; save(); draw(); } },
      [["", "All deal types"], ["PAID", "Paid"], ["GIFTED", "Gifted"], ["AFFILIATE", "Affiliate"], ["UNKNOWN", "Not sure yet"]]
        .map(([v, l]) => el("option", { value: v, selected: f.comp === v }, l)));
    const closedBox = el("input", { type: "checkbox", checked: f.closed,
      onchange: (e) => { f.closed = e.target.checked; save(); renderPipeline(root); } });
    const leadSelect = el("select", { id: "lead-filter", "aria-label": "Leads", onchange: (e) => { f.lead = e.target.value; save(); draw(); } },
      [["", "All deals"], ["LEADS", "Leads only"], ["LOW", "Low-value leads"]].map(([v, t]) => el("option", { value: v, selected: f.lead === v }, t)));
    const search = searchBox(f.q, "Search brand, contact, campaign…", (v) => { f.q = v; save(); draw(); });
    root.appendChild(dealsSwitch("pipeline"));
    root.appendChild(el("div", { class: "row" }, el("h1", {}, "Deals"), el("div", { class: "spacer" }),
      el("label", { class: "check" }, closedBox, "Show closed")));
    root.appendChild(el("div", { class: "row filters" }, el("div", { class: "spacer" }, search), leadSelect, statusSelect, compSelect));
    const chips = el("div", { class: "row card" });
    const list = el("div", {});
    root.append(chips, list);

    // Batch decline: tick leads, then write a polite decline draft for each. Ticks survive filtering and sorting.
    const picked = new Set();
    const declineBtn = el("button", { class: "small", disabled: true, onclick: action(async () => {
      const n = picked.size;
      if (!confirm("Write a polite decline for " + n + (n === 1 ? " lead" : " leads") + "? They'll wait in Drafts for you to read and send.")) return;
      const r = await api("POST", "/api/opportunities/decline", { ids: [...picked] });
      toast(r.done + (r.done === 1 ? " decline" : " declines") + " drafted" + (r.skipped.length ? ". Skipped: " + r.skipped.join("; ") : ""), r.skipped.length > 0);
      location.hash = "#drafts";
    }) }, "Decline selected");
    const sync = () => { declineBtn.disabled = picked.size === 0; declineBtn.textContent = picked.size ? "Decline selected (" + picked.size + ")" : "Decline selected"; };
    const leadRank = { HIGH: 3, MEDIUM: 2, LOW: 1 };

    function draw() {
      // Status chips count every deal; clicking one filters to it, clicking again shows all.
      const counts = {};
      rows.forEach((r) => { counts[r.status] = (counts[r.status] || 0) + 1; });
      clear(chips).append(...Object.keys(counts).sort((a, b) => statusOrder.indexOf(a) - statusOrder.indexOf(b)).map((k) =>
        el("button", { class: "chip" + (f.status === k ? " active" : ""), "aria-pressed": String(f.status === k),
          title: "Show only " + (statuses[k] || pretty(k)),
          onclick: () => { f.status = f.status === k ? "" : k; statusSelect.value = f.status; save(); draw(); } },
          (statuses[k] || pretty(k)) + " · " + counts[k])));
      clear(list);
      if (!rows.length) { list.appendChild(card(null, emptyLine("No deals yet. They appear here as brand emails and DMs come in, or when you log a pitch."))); return; }
      const shown = sortRows(rows.filter((r) => (!f.status || r.status === f.status) && (!f.comp || r.compensation === f.comp)
        && (f.lead !== "LEADS" || r.lead) && (f.lead !== "LOW" || r.lead === "LOW")
        && matchesQuery(f.q, r.brand, r.contact, r.campaign, r.statusLabel, pretty(r.type), pretty(r.compensation), r.budget, r.deliverables, r.nextStep)),
      f.sort, {
        brand: (r) => r.brand, lead: (r) => leadRank[r.lead], status: (r) => statusOrder.indexOf(r.status), nextFollowUp: (r) => r.nextFollowUp,
        openTasks: (r) => r.openTasks, updatedAt: (r) => r.updatedAt,
      });
      const clearAll = () => { Object.assign(f, { q: "", status: "", comp: "", lead: "" }); search.value = ""; statusSelect.value = ""; compSelect.value = ""; leadSelect.value = ""; save(); draw(); };
      const line = shownLine(shown.length, rows.length, "deals", clearAll);
      if (line) list.appendChild(line);
      if (!shown.length) { list.appendChild(card(null, emptyLine(f.lead ? "No leads match this filter." : "No deals match. Try fewer words or another filter."))); return; }
      const leadRows = shown.filter((r) => r.lead);
      const pickAll = el("input", { type: "checkbox", title: "Select all leads shown", "aria-label": "Select all leads shown",
        checked: leadRows.length > 0 && leadRows.every((r) => picked.has(r.id)), onchange: (e) => {
          leadRows.forEach((r) => (e.target.checked ? picked.add(r.id) : picked.delete(r.id)));
          list.querySelectorAll("input.pick").forEach((b) => { b.checked = e.target.checked; });
          sync();
        } });
      if (leadRows.length) {
        list.appendChild(el("div", { class: "row card", id: "decline-bar" },
          el("span", { class: "small muted" }, "Not a fit? Tick the leads you'd like to turn down and press Decline selected. "
            + "A short, polite no-thanks is written for each; nothing is sent until you approve it."),
          el("div", { class: "spacer" }), declineBtn));
      }
      list.appendChild(el("div", { class: "card table-wrap" }, el("table", {},
        sortableHead([[leadRows.length ? pickAll : "", null], ["Brand", "brand"], ["Lead", "lead", "desc"], ["Status", "status"], ["Deal", null], ["Budget", null],
          ["Next follow-up", "nextFollowUp", "asc"], ["Open tasks", "openTasks", "desc"], ["Updated", "updatedAt", "desc"]],
        f.sort, (s) => { f.sort = s; save(); draw(); }),
        el("tbody", {}, shown.map((r) => el("tr", { class: "clickable", onclick: () => openDeal(r.id) },
          el("td", { onclick: (e) => e.stopPropagation() }, r.lead ? el("input", { type: "checkbox", class: "pick", checked: picked.has(r.id),
            "aria-label": "Pick " + r.brand, onchange: (e) => {
              if (e.target.checked) picked.add(r.id); else picked.delete(r.id);
              sync();
            } }) : null),
          el("td", {}, el("strong", {}, r.brand), r.campaign ? el("div", { class: "small muted" }, r.campaign) : null),
          el("td", {}, leadBadge(r.lead, r.leadWhy) || el("span", { class: "muted" }, "—")),
          el("td", {}, r.statusLabel),
          el("td", {}, pretty(r.type) + " · " + pretty(r.compensation)),
          el("td", {}, r.budget || "—"),
          el("td", {}, r.nextFollowUp ? "#" + r.nextFollowUpNumber + " · " + fmtDate(r.nextFollowUp) : "—"),
          el("td", {}, String(r.openTasks)),
          el("td", { class: "muted" }, fmtDate(r.updatedAt))))))));
    }
    draw();
  }

  // ---------- Money ----------

  let moneyYear = null;
  const moneyFilter = loadPrefs("money", { q: "", show: "all" });

  function fmtMoney(currency, amount) {
    try {
      return new Intl.NumberFormat(undefined, { style: "currency", currency }).format(Number(amount));
    } catch (e) {
      return currency + " " + Number(amount).toFixed(2);
    }
  }

  /** {"USD": 1200, "EUR": 300} -> "$1,200.00 · €300.00" */
  function moneyTotal(byCurrency) {
    const parts = Object.entries(byCurrency || {}).filter(([, v]) => Number(v) !== 0).map(([c, v]) => fmtMoney(c, v));
    return parts.length ? parts.join(" · ") : fmtMoney("USD", 0);
  }

  function invoiceBadge(inv) {
    if (inv.status === "SENT" && inv.daysOverdue > 0) return el("span", { class: "badge overdue" }, "Overdue " + inv.daysOverdue + "d");
    const cls = { DRAFT: "medium", SENT: "accent", PAID: "ok", VOID: "" }[inv.status];
    return el("span", { class: "badge " + cls }, { DRAFT: "Not sent yet", SENT: "Sent, unpaid", PAID: "Paid " + fmtDate(inv.paidDate), VOID: "Void" }[inv.status]);
  }

  async function renderMoney(root) {
    const m = await api("GET", "/api/money" + (moneyYear ? "?year=" + moneyYear : ""));
    clear(root);
    const yearSel = el("select", { class: "inline", onchange: (e) => { moneyYear = Number(e.target.value); renderMoney(root); } },
      m.years.map((y) => el("option", { value: String(y), selected: y === m.year }, String(y))));
    root.appendChild(el("div", { class: "row" }, el("h1", {}, "Money"), el("div", { class: "spacer" }), yearSel,
      el("a", { class: "btn small", href: "/api/money/invoices.csv?year=" + m.year, download: "invoices-" + m.year + ".csv",
        title: "Every invoice issued this year, for your taxes" }, "Export CSV")));
    if (m.businessDetailsMissing) {
      root.appendChild(el("div", { class: "alert info" }, "Your invoices still show placeholders for your address or payment details. ",
        el("a", { href: "#settings" }, "Add them in Settings, Invoices.")));
    }
    const f = moneyFilter;
    const save = () => savePrefs("money", { q: f.q, show: f.show });
    const ready = card("Ready to invoice", m.readyToInvoice.length ? el("ul", { class: "list" }, m.readyToInvoice.map((r) => el("li", { class: "item" },
      el("div", { class: "body" }, el("div", { class: "title" }, r.brand + (r.campaign ? " — " + r.campaign : "")),
        el("div", { class: "detail" }, r.amountText + " · " + r.status)),
      el("div", { class: "actions" },
        el("button", { class: "small primary", onclick: action(async () => {
          const inv = await api("POST", "/api/opportunities/" + r.opportunityId + "/invoices");
          openInvoice(inv.id);
        }) }, "Create invoice"),
        el("button", { class: "small", onclick: () => openDeal(r.opportunityId) }, "Open deal")))))
      : emptyLine("Every agreed paid deal has an invoice."));
    const list = el("div", {});
    const pick = (show) => { f.show = show; save(); draw(); list.scrollIntoView({ behavior: "smooth", block: "start" }); };
    // The totals double as shortcuts: each one shows the invoices behind it.
    root.appendChild(el("div", { class: "stats card" },
      statButton(moneyTotal(m.booked), "Booked, not invoiced yet", "Show deals ready to invoice", () => ready.scrollIntoView({ behavior: "smooth", block: "start" })),
      statButton(moneyTotal(m.outstanding), "Invoiced, waiting for payment", "Show unpaid invoices", () => pick("unpaid")),
      statButton(moneyTotal(m.paidThisMonth), "Paid this month", "Show paid invoices", () => pick("paid")),
      statButton(moneyTotal(m.overdue), "Overdue", "Show overdue invoices", () => pick("overdue"))));
    root.appendChild(ready);

    if (!m.months.length) { root.appendChild(card(null, emptyLine("No invoices in " + m.year + " yet."))); return; }
    root.appendChild(el("p", { class: "muted small" }, "Paid in " + m.year + ": " + moneyTotal(m.paidThisYear)));
    const search = searchBox(f.q, "Search brand or invoice number…", (v) => { f.q = v; save(); draw(); });
    const chips = el("div", {});
    root.appendChild(el("div", { class: "row filters" }, el("div", { class: "spacer" }, search), chips));
    root.appendChild(list);
    const all = m.months.flatMap((g) => g.invoices);
    const kinds = {
      all: () => true, draft: (i) => i.status === "DRAFT", unpaid: (i) => i.status === "SENT",
      overdue: (i) => i.status === "SENT" && i.daysOverdue > 0, paid: (i) => i.status === "PAID", void: (i) => i.status === "VOID",
    };

    function draw() {
      clear(chips).appendChild(chipRow([["all", "All"], ["draft", "Not sent"], ["unpaid", "Unpaid"], ["overdue", "Overdue"], ["paid", "Paid"], ["void", "Void"]],
        f.show, (v) => { f.show = v; save(); draw(); }, "Show invoices"));
      clear(list);
      const keep = (inv) => (kinds[f.show] || kinds.all)(inv) && matchesQuery(f.q, inv.number, inv.brand, inv.amountText);
      const shown = all.filter(keep).length;
      const line = shownLine(shown, all.length, "invoices", () => { f.q = ""; f.show = "all"; search.value = ""; save(); draw(); });
      if (line) list.appendChild(line);
      if (!shown) { list.appendChild(card(null, emptyLine("No invoices match."))); return; }
      for (const g of m.months) {
        const invoices = g.invoices.filter(keep);
        if (!invoices.length) continue;
        const label = new Date(g.month + "-01T00:00:00").toLocaleDateString(undefined, { month: "long", year: "numeric" });
        list.appendChild(el("div", { class: "card table-wrap" }, el("h3", {}, label), el("table", {},
          el("thead", {}, el("tr", {}, ["Invoice", "Brand", "Amount", "Issued", "Due", "Status"].map((h) => el("th", {}, h)))),
          el("tbody", {}, invoices.map((inv) => el("tr", { class: "clickable", onclick: () => openInvoice(inv.id) },
            el("td", {}, el("strong", {}, inv.number)),
            el("td", {}, inv.brand),
            el("td", {}, inv.amountText),
            el("td", { class: "muted" }, fmtDate(inv.issuedDate)),
            el("td", { class: "muted" }, fmtDate(inv.dueDate)),
            el("td", {}, invoiceBadge(inv))))))));
      }
    }
    draw();
  }

  async function openInvoice(id) {
    const inv = await api("GET", "/api/invoices/" + id);
    const drawer = clear(document.getElementById("drawer"));
    document.getElementById("drawer-backdrop").classList.remove("hidden");
    drawer.classList.remove("hidden");
    const refresh = () => { openInvoice(id); route(); };
    const pdfUrl = "/api/invoices/" + id + "/pdf";

    drawer.appendChild(el("div", { class: "row" }, el("h1", {}, "Invoice " + inv.number), invoiceBadge(inv), el("div", { class: "spacer" }),
      el("button", { class: "small", onclick: () => openDeal(inv.opportunityId) }, "Open deal"),
      el("button", { class: "small", onclick: closeDrawer }, "Close")));

    if (inv.status !== "DRAFT") {
      const paidOn = el("input", { type: "date", class: "inline", value: new Date().toLocaleDateString("en-CA") });
      drawer.appendChild(card(null,
        facts([["Brand", inv.brand], ["Bill to", inv.billTo && el("span", { style: "white-space: pre-line" }, inv.billTo)], ["Email", inv.billToEmail], ["Total", inv.amountText],
               ["Issued", fmtDate(inv.issuedDate)], ["Due", fmtDate(inv.dueDate)], ["Sent", fmtDate(inv.sentAt)],
               ["Paid", fmtDate(inv.paidDate)],
               ["Reminders", inv.remindersSent ? inv.remindersSent + " sent, last on " + fmtDate(inv.lastReminderOn) : null],
               ["Notes", inv.notes]]),
        el("ul", { class: "list" }, inv.lineItems.map((li) => el("li", { class: "item" },
          el("div", { class: "body" }, li.description), el("div", {}, fmtMoney(inv.currency, li.amount))))),
        el("div", { class: "row" },
          el("a", { class: "btn small", href: pdfUrl, target: "_blank", rel: "noopener" }, "View PDF"),
          el("a", { class: "btn small", href: pdfUrl + "?download=true" }, "Download PDF")),
        inv.status === "SENT" ? el("div", { class: "row" },
          el("label", { class: "row" }, "Paid on ", paidOn),
          el("button", { class: "primary small", onclick: action(async () => {
            await api("POST", "/api/invoices/" + id + "/paid", { paidDate: paidOn.value || null }); refresh();
          }, "Marked paid 🎉") }, "Mark paid"),
          el("button", { class: "small", title: "A polite payment reminder with the invoice attached, for you to check in Drafts", onclick: action(async () => {
            const made = await api("POST", "/api/invoices/" + id + "/reminder"); closeDrawer(); showDraft(made);
          }, "Payment reminder is in Drafts") }, "Write a reminder"),
          el("button", { class: "small", title: "Put the invoice email in Drafts again", onclick: action(async () => {
            const made = await api("POST", "/api/invoices/" + id + "/email"); closeDrawer(); showDraft(made);
          }, "Invoice email is in Drafts") }, "Email again"),
          el("div", { class: "spacer" }),
          el("button", { class: "small danger", onclick: action(async () => {
            if (!confirm("Void " + inv.number + "? Its number won't be reused.")) return;
            await api("POST", "/api/invoices/" + id + "/void"); refresh();
          }, "Invoice voided") }, "Void")) : null,
        inv.status === "SENT" ? el("p", { class: "small muted" }, "The app never marks an invoice paid by itself. Check your bank, then press Mark paid.") : null));
      return;
    }

    // Draft: editable
    const f = {
      billTo: el("textarea", { maxlength: "1000" }),
      billToEmail: el("input", { type: "email", value: inv.billToEmail || "", maxlength: "320", placeholder: "billing@brand.com" }),
      currency: el("input", { class: "short", value: inv.currency, maxlength: "3" }),
      issuedDate: el("input", { type: "date", value: inv.issuedDate }),
      dueDate: el("input", { type: "date", value: inv.dueDate }),
      notes: el("textarea", { maxlength: "2000", placeholder: "Optional, e.g. a PO number" }),
    };
    f.billTo.value = inv.billTo || "";
    f.notes.value = inv.notes || "";
    const lines = el("div", { class: "stack" });
    const totalOut = el("strong", {});
    const recalc = () => {
      const sum = [...lines.querySelectorAll("input[data-amount]")].reduce((a, i) => a + (Number(i.value) || 0), 0);
      totalOut.textContent = fmtMoney((f.currency.value || "USD").toUpperCase(), sum);
    };
    const addLine = (li) => {
      const desc = el("input", { class: "grow", value: li.description || "", maxlength: "300", placeholder: "e.g. 1 Instagram Reel, 30 days usage" });
      const amt = el("input", { class: "amount", type: "number", min: "0", step: "0.01", value: li.amount == null ? "" : String(li.amount), oninput: recalc });
      amt.dataset.amount = "1";
      desc.dataset.desc = "1";
      const row = el("div", { class: "row" }, desc, amt,
        el("button", { class: "small", title: "Remove line", onclick: () => { row.remove(); recalc(); } }, "✕"));
      lines.appendChild(row);
    };
    inv.lineItems.forEach(addLine);
    f.currency.addEventListener("input", recalc);
    recalc();
    const edits = () => ({
      billTo: f.billTo.value, billToEmail: f.billToEmail.value, currency: f.currency.value, notes: f.notes.value,
      issuedDate: f.issuedDate.value || null, dueDate: f.dueDate.value || null,
      lineItems: [...lines.children].map((r) => ({ description: r.querySelector("[data-desc]").value,
        amount: Number(r.querySelector("[data-amount]").value) || 0 })),
    });
    const save = () => api("PUT", "/api/invoices/" + id, edits());

    drawer.appendChild(card(null,
      el("div", { class: "grid" },
        el("div", {}, el("label", {}, "Bill to"), f.billTo),
        el("div", {}, el("label", {}, "Send to email"), f.billToEmail,
          el("div", { class: "grid" },
            el("div", {}, el("label", {}, "Invoice date"), f.issuedDate),
            el("div", {}, el("label", {}, "Due date"), f.dueDate)))),
      el("label", {}, "What you're billing for"), lines,
      el("div", { class: "row" }, el("button", { class: "small", onclick: () => { addLine({}); recalc(); } }, "Add line"),
        el("div", { class: "spacer" }), el("label", { class: "row" }, "Currency ", f.currency), el("span", {}, "Total "), totalOut),
      el("label", {}, "Notes"), f.notes,
      el("div", { class: "row" },
        el("button", { class: "primary", onclick: action(async () => {
          await save();
          const made = await api("POST", "/api/invoices/" + id + "/email");
          closeDrawer(); showDraft(made);
        }, "Invoice email is in Drafts. Check it, then press Send.") }, "Email invoice"),
        el("button", { onclick: action(async () => { await save(); window.open(pdfUrl, "_blank", "noopener"); }) }, "Preview PDF"),
        el("button", { onclick: action(async () => { await save(); refresh(); }, "Saved") }, "Save"),
        el("div", { class: "spacer" }),
        el("button", { title: "You sent the PDF some other way", onclick: action(async () => {
          await save(); await api("POST", "/api/invoices/" + id + "/sent"); refresh();
        }, "Marked as sent") }, "I sent it myself"),
        el("button", { class: "danger", onclick: action(async () => {
          if (!confirm("Void " + inv.number + "? Its number won't be reused.")) return;
          await api("POST", "/api/invoices/" + id + "/void"); refresh();
        }, "Invoice voided") }, "Void")),
      el("p", { class: "small muted" }, "Email invoice puts a short note with the PDF attached in Drafts. Nothing is sent until you press Send there.")));
  }

  // ---------- Deal drawer ----------

  function resultsCard(id, r, refresh) {
    const base = "/api/opportunities/" + id + "/results";
    const url = el("input", { type: "url", placeholder: "https://www.instagram.com/reel/…", value: (r && r.postUrl) || "", "aria-label": "Post link" });
    const fields = [["reach", "Accounts reached"], ["views", "Views"], ["likes", "Likes"], ["comments", "Comments"], ["saves", "Saves"], ["shares", "Shares"]];
    const inputs = {};
    for (const [k] of fields) inputs[k] = el("input", { type: "number", min: "0", step: "1", value: r && r[k] != null ? String(r[k]) : "" });
    const has = r && fields.some(([k]) => r[k] != null);
    const status = [];
    if (r && r.postedAt) status.push("Posted " + fmtDate(r.postedAt) + (r.postUrl ? " · " : ""));
    const lines = [];
    if (r && r.error) lines.push(el("p", { class: "small warn" }, r.error));
    else if (r && r.numbersDue) lines.push(el("p", { class: "small muted" }, "Instagram numbers arrive " + fmtDate(r.numbersDue) + ", a week after posting. A recap email to the brand is drafted then for you to check."));
    else if (r && r.source === "INSTAGRAM" && r.fetchedAt) lines.push(el("p", { class: "small muted" }, "From Instagram Insights, " + fmtDate(r.fetchedAt) + "."));
    else if (!r) lines.push(el("p", { class: "small muted" }, "Once it's live, link the post. Instagram posts get their numbers a week later; for TikTok or Stories, type them in."));
    return el("div", { class: "card", id: "results" }, el("h2", {}, "Campaign results"),
      r && r.postUrl ? el("p", { class: "small" }, status, el("a", { href: r.postUrl, target: "_blank", rel: "noopener" }, "View post")) : null,
      el("div", { class: "row" }, url,
        el("button", { class: "small", onclick: action(async () => { await api("POST", base + "/link", { url: url.value }); refresh(); }, "Post linked") }, "Save link"),
        !r || !r.mediaId ? el("button", { class: "small", onclick: action(async () => { await api("POST", base + "/find"); refresh(); }, "Found your post") }, "Find my post") : null),
      lines,
      el("div", { class: "grid numbers" }, fields.map(([k, label]) => el("div", {}, el("label", {}, label), inputs[k]))),
      r && r.engagementRate != null ? el("p", { class: "small" }, "Engagement rate: " + r.engagementRate + "% (likes, comments, saves and shares over accounts reached)") : null,
      el("div", { class: "row" },
        el("button", { class: "small", onclick: action(async () => {
          const body = {};
          for (const [k] of fields) body[k] = inputs[k].value === "" ? null : Number(inputs[k].value);
          await api("PUT", base + "/numbers", body); refresh();
        }, "Numbers saved") }, "Save numbers"),
        r && r.mediaId ? el("button", { class: "small", onclick: action(async () => { await api("POST", base + "/fetch"); refresh(); }, "Numbers updated from Instagram") }, "Get numbers now") : null,
        has ? el("button", { class: "small", onclick: () => window.open(base + "/pdf", "_blank", "noopener") }, "Preview PDF") : null,
        has ? el("button", { class: "primary small", onclick: action(async () => {
          const made = await api("POST", base + "/recap");
          closeDrawer(); showDraft(made);
        }, "Recap email is in Drafts. Check it, then press Send.") }, r.recapDraftedAt ? "Draft recap again" : "Draft recap email") : null),
      el("p", { class: "small muted" }, "The recap thanks the brand, shares these numbers with a one-page PDF attached, and suggests working together again. It waits in Drafts until you send it."));
  }

  function closeDrawer() {
    document.getElementById("drawer").classList.add("hidden");
    document.getElementById("drawer-backdrop").classList.add("hidden");
  }

  // Scrolls the open deal to one message and highlights it for a moment.
  // Web addresses in a message's text become links that open in a new window.
  function linkify(text) {
    const out = [];
    let last = 0;
    for (const m of text.matchAll(/https?:\/\/[^\s<>"]+/g)) {
      const url = m[0].replace(/[.,;:!?)\]']+$/, "");
      out.push(text.slice(last, m.index), el("a", { href: url, target: "_blank", rel: "noopener noreferrer" }, url));
      last = m.index + url.length;
    }
    out.push(text.slice(last));
    return out;
  }

  // The email as the brand sent it (links, pictures, layout), cleaned by the server and shown in a sandboxed frame
  // that can't run scripts. Long emails are folded until she asks for the rest.
  function emailFrame(m) {
    const frame = el("iframe", { class: "email-frame", src: "/api/messages/" + m.id + "/email", loading: "lazy",
      title: "Email" + (m.subject ? ": " + m.subject : ""), referrerpolicy: "no-referrer",
      sandbox: "allow-popups allow-popups-to-escape-sandbox allow-same-origin" });
    const box = el("div", { class: "email-box" }, frame);
    let expanded = false, width = 0;
    const more = el("button", { class: "small email-more hidden", onclick: () => { expanded = true; fit(); } }, "Show the whole email");
    function fit() {
      const doc = frame.contentDocument;
      if (!doc || !doc.body) return;
      const h = Math.ceil(Math.max(doc.body.getBoundingClientRect().height, doc.body.scrollHeight));
      frame.style.height = h + "px";
      const clip = !expanded && h > 560;
      box.classList.toggle("clipped", clip);
      more.classList.toggle("hidden", !clip);
    }
    frame.addEventListener("load", () => {
      fit();
      frame.contentDocument?.addEventListener("toggle", fit, true); // "Show earlier messages"
    });
    new ResizeObserver(() => { if (box.clientWidth !== width) { width = box.clientWidth; fit(); } }).observe(box);
    return [box, more];
  }

  function showMessage(messageId) {
    const node = document.getElementById("msg-" + messageId);
    if (!node) return;
    node.classList.remove("hidden");
    node.scrollIntoView({ behavior: "smooth", block: "start" });
    node.classList.add("focus");
    setTimeout(() => node.classList.remove("focus"), 2500);
  }

  function progressCard(id, p, refresh) {
    const finished = p.state === "finished";
    const steps = el("ol", { class: "stages" + (p.state === "paused" ? " paused" : "") }, p.steps.map((s, i) => {
      const done = s.state === "done";
      return el("li", { class: s.state, "aria-current": s.state === "now" ? "step" : null },
        el("div", { class: "dot", "aria-hidden": "true" }, done ? "✓" : String(i + 1)),
        el("div", { class: "name" }, s.name),
        el("div", { class: "when" }, s.date ? [s.label, fmtDate(s.date)].filter(Boolean).join(" ") : ""));
    }));
    const box = el("div", { class: "stage-now " + p.state },
      el("div", { class: "what" }, el("strong", {}, p.headline), el("span", { class: "small" }, p.detail)),
      p.action ? el("button", { class: "small primary", onclick: action(async () => {
        await api("POST", "/api/opportunities/" + id + "/progress", { to: p.next });
        refresh();
      }, "Deal moved on") }, p.action) : null);
    return el("div", { class: "card", id: "deal-progress" },
      el("div", { class: "row" }, el("h3", {}, "Deal progress"), el("div", { class: "spacer" }),
        el("span", { class: "small muted" }, finished ? "All " + p.steps.length + " steps done" : "Step " + p.step + " of " + p.steps.length)),
      steps, box,
      el("p", { class: "small muted" }, "Moves on by itself as emails come in. Use the button if something happened outside email."));
  }

  async function openDeal(id, focusMessageId) {
    const [d, invoices] = await Promise.all([api("GET", "/api/opportunities/" + id), api("GET", "/api/opportunities/" + id + "/invoices")]);
    const drawer = clear(document.getElementById("drawer"));
    document.getElementById("drawer-backdrop").classList.remove("hidden");
    drawer.classList.remove("hidden");
    const o = d.opportunity;
    const refresh = () => { openDeal(id); route(); };

    drawer.appendChild(el("div", { class: "row" }, el("h1", {}, d.summary.brand), el("div", { class: "spacer" }),
      el("button", { class: "small", onclick: closeDrawer }, "Close")));

    // Deal progress: the steps of a deal the brand said yes to, moved along by emails
    const progress = await api("GET", "/api/opportunities/" + id + "/progress");
    if (progress.shown) drawer.appendChild(progressCard(id, progress, refresh));

    const statusSel = el("select", { onchange: action(async (e) => { await api("PATCH", "/api/opportunities/" + id, { status: e.target.value }); refresh(); }, "Status updated") },
      Object.entries(statuses).map(([k, v]) => el("option", { value: k, selected: o.status === k }, v)));
    drawer.appendChild(card(null,
      el("label", {}, "Status"), statusSel,
      facts([["Lead", d.summary.lead && (pretty(d.summary.lead) + (d.summary.leadWhy ? ": " + d.summary.leadWhy : ""))], ["Type", pretty(o.type)], ["Compensation", pretty(o.compensation)], ["Budget", o.budgetText],
             ["Deliverables", o.deliverables], ["Usage rights", o.usageRights], ["Campaign", o.campaign],
             ["Still unknown", o.missingInfo], ["Next step", o.nextStep], ["Origin", pretty(o.origin)],
             ["Contact", d.brand && [d.brand.contactName, d.brand.contactEmail, d.brand.instagram && "@" + d.brand.instagram].filter(Boolean).join(" · ")]])));

    // Rate advisor: what to ask for while the deal is still being decided
    if (["NEW_LEAD", "AWAITING_MY_REPLY", "NEGOTIATING"].includes(o.status) && !["GIFTED", "AFFILIATE"].includes(o.compensation)) {
      const advice = await api("GET", "/api/opportunities/" + id + "/rate");
      if (!advice.available) {
        drawer.appendChild(card("What to ask for", el("p", { class: "small muted" }, advice.why)));
      } else {
        const amount = el("input", { type: "number", min: "1", step: "10", value: String(advice.suggested), "aria-label": "Amount to ask for", class: "amount" });
        const gap = advice.offer == null ? null
          : el("p", { class: "small" + (advice.offerGapPercent < -10 ? " warn" : "") }, "Their offer: $" + Math.round(advice.offer).toLocaleString("en-US")
            + (advice.offerGapPercent === 0 ? ", right on your rate." : advice.offerGapPercent < 0
              ? ", " + -advice.offerGapPercent + "% under this." : ", " + advice.offerGapPercent + "% above this. Nice!"));
        drawer.appendChild(el("div", { class: "card", id: "rate-advisor" }, el("h2", {}, "What to ask for"),
          el("div", { class: "row" }, el("div", { class: "big-number" }, advice.suggestedText), el("div", { class: "small muted" }, "for " + advice.asks)),
          gap,
          el("table", { class: "lines" }, el("tbody", {}, advice.lines.map((l) => el("tr", {}, el("td", {}, l.text), el("td", { class: "num" }, l.amount))))),
          el("p", { class: "small muted" }, advice.basis + ". Change the uplifts in Settings, Rate advisor."),
          el("div", { class: "row" }, el("label", {}, "Ask for $"), amount,
            el("button", { class: "primary small", onclick: action(async () => {
              const n = Number(amount.value);
              if (!(n > 0)) { toast("Enter the amount to ask for", true); return; }
              const made = await api("POST", "/api/opportunities/" + id + "/counter", { amount: n });
              closeDrawer(); showDraft(made);
            }, "Counter-offer drafted — review it before sending") }, "Draft counter")),
          el("p", { class: "small muted" }, "The draft quotes only the amount above, and waits in Drafts until you send it.")));
      }
    }

    // Contract check: terms read from the contract the brand sent, against her limits
    const contracts = await api("GET", "/api/opportunities/" + id + "/contracts");
    if (contracts.length || ["CONTRACT_PENDING", "CONTRACT_TO_SIGN"].includes(o.status)) {
      const icon = { RED: "✕", AMBER: "!", OK: "✓" };
      const box = el("div", { class: "card", id: "contract-check" }, el("h2", {}, "Contract check"));
      for (const c of contracts) {
        const head = el("div", { class: "row" }, el("strong", {}, c.fileName || "Contract"), el("div", { class: "spacer" }),
          c.status === "CHECKED" ? el("span", { class: "badge " + (c.red ? "overdue" : c.amber ? "medium" : "ok") },
            c.red || c.amber ? [c.red ? c.red + " to push back on" : null, c.amber ? c.amber + " to look at" : null].filter(Boolean).join(" · ") : "Looks fine") : null);
        box.appendChild(head);
        if (c.note) box.appendChild(el("p", { class: "small muted" }, c.note));
        if (c.terms && c.terms.summary) box.appendChild(el("p", { class: "small" }, c.terms.summary));
        if (c.flags.length) {
          box.appendChild(el("ul", { class: "flags" }, c.flags.map((f) => el("li", { class: "flag " + f.level.toLowerCase() },
            el("span", { class: "mark", "aria-label": { RED: "Push back", AMBER: "Look at", OK: "Fine" }[f.level] }, icon[f.level]), el("span", {}, f.text)))));
        }
        if (c.terms && c.terms.deadlines && c.terms.deadlines.length) {
          box.appendChild(el("p", { class: "small" }, "Dates in the contract: " + c.terms.deadlines.map((x) => fmtDate(x.date) + " " + x.what).join("; ")));
        }
        if (c.status === "CHECKED") {
          box.appendChild(el("p", {}, el("button", { class: "small", onclick: action(async () => {
            await api("POST", "/api/contracts/" + c.id + "/recheck"); refresh();
          }, "Checked again with your current limits") }, "Check again")));
        }
      }
      const needsText = !contracts.some((c) => c.status === "CHECKED");
      const paste = el("textarea", { maxlength: "60000", placeholder: "Paste the contract's text, e.g. copied from the DocuSign page" });
      box.appendChild(el("details", needsText ? { open: true } : {},
        el("summary", { class: "small" }, contracts.length ? "Check another version" : "No contract file yet. Paste its text to check it"),
        paste,
        el("p", {}, el("button", { class: "primary small", onclick: action(async () => {
          await api("POST", "/api/opportunities/" + id + "/contracts", { text: paste.value });
          refresh();
        }, "Contract checked") }, "Check this contract"))));
      box.appendChild(el("p", { class: "small muted" }, "Claude reads the terms and the app checks them against your limits in Settings, Contract check. "
        + "It's a checklist, not legal advice; you decide what to sign."));
      drawer.appendChild(box);
    }

    // Invoices
    const invoiceAsked = d.tasks.some((t) => t.status === "OPEN" && t.type === "SEND_INVOICE");
    if (o.compensation !== "GIFTED" || invoices.length) {
      drawer.appendChild(card("Invoices",
        invoices.length ? el("ul", { class: "list" }, invoices.map((inv) => el("li", { class: "item" },
          el("div", { class: "body" }, el("div", { class: "title" }, inv.number + " · " + inv.amountText),
            el("div", { class: "detail" }, invoiceBadge(inv), " issued " + fmtDate(inv.issuedDate) + " · due " + fmtDate(inv.dueDate))),
          el("div", { class: "actions" }, el("button", { class: "small", onclick: () => openInvoice(inv.id) }, "Open")))))
          : emptyLine(invoiceAsked ? "The brand asked for an invoice." : "No invoices yet."),
        el("p", {}, el("button", { class: "small" + (invoiceAsked || o.status === "PAYMENT_PENDING" ? " primary" : ""), onclick: action(async () => {
          const inv = await api("POST", "/api/opportunities/" + id + "/invoices");
          openInvoice(inv.id);
        }) }, "Create invoice"))));
    }

    // Rebooking: a finished collab can be pitched again
    const finished = ["POSTED", "PAYMENT_PENDING", "CLOSED"].includes(o.status) && (o.closedReason || "").indexOf("Declined") !== 0;
    if (finished && (o.status !== "CLOSED" || o.compensation === "GIFTED" || invoices.some((i) => i.status === "PAID") || o.closedReason === "Paid")) {
      drawer.appendChild(card("Work together again",
        el("p", { class: "small muted" }, "Draft a short re-pitch that mentions this collab. Sending it starts a new pitch with " + d.summary.brand + "."),
        el("p", {}, el("button", { class: "small", onclick: action(async () => {
          const made = await api("POST", "/api/opportunities/" + id + "/repitch");
          closeDrawer(); showDraft(made);
        }, "Re-pitch drafted — review it before sending") }, "Pitch them again"))));
    }

    // Drafting
    const typeSel = el("select", {}, ["REPLY", "RATES", "MEDIA_KIT", "NEGOTIATION", "FOLLOW_UP", "ASK_BUDGET", "ASK_USAGE_RIGHTS",
      "ASK_DETAILS", "CONTRACT_CONFIRMATION", "CONTENT_SUBMISSION", "PRODUCT_ARRIVAL", "DECLINE", "OTHER"].map((t) => el("option", { value: t }, pretty(t))));
    const instr = el("input", { placeholder: "Optional guidance, e.g. 'ask for 50% upfront'", maxlength: "1000" });
    drawer.appendChild(card("Write a message",
      el("div", { class: "row" }, typeSel, instr),
      el("p", {}, el("button", { class: "primary small", onclick: action(async () => {
        const made = await api("POST", "/api/opportunities/" + id + "/drafts", { type: typeSel.value, instructions: instr.value });
        closeDrawer(); showDraft(made);
      }, "Draft created — review it before sending") }, "Draft with AI")),
      el("p", { class: "small muted" }, "Drafts are never sent until you approve them.")));

    // Follow-ups
    const fuItems = d.followUps.map((f) => el("li", { class: "item" }, el("div", { class: "body" },
      el("div", { class: "title" }, "Follow-up #" + f.number),
      el("div", { class: "detail" }, f.status === "DONE" ? "Sent " + fmtDate(f.completedDate)
        : f.status === "SCHEDULED" ? "Due " + fmtDate(f.scheduledDate) : "Cancelled (brand replied or stopped)"))));
    drawer.appendChild(card("Follow-ups",
      fuItems.length ? el("ul", { class: "list" }, fuItems) : emptyLine("No follow-ups yet."),
      el("div", { class: "row" },
        el("button", { class: "small", onclick: action(async () => { await api("POST", "/api/opportunities/" + id + "/followups/sent"); refresh(); }, "Recorded") }, "I sent a follow-up"),
        el("button", { class: "small danger", onclick: action(async () => { await api("POST", "/api/opportunities/" + id + "/followups/stop"); refresh(); }, "Follow-ups stopped") }, "Stop following up"))));

    // Tasks
    const taskInput = el("input", { placeholder: "Add a task…", maxlength: "500" });
    const taskDue = el("input", { type: "date" });
    drawer.appendChild(card("Tasks",
      d.tasks.length ? el("ul", { class: "list" }, d.tasks.map((t) => el("li", { class: "item" },
        el("div", { class: "body" }, el("div", { class: "title" }, t.description),
          el("div", { class: "detail" }, pretty(t.status) + (t.dueDate ? " · due " + fmtDate(t.dueDate) : "")),
          t.status === "OPEN" ? taskAbout(t.brief, t.links) : null),
        t.status === "OPEN" ? el("div", { class: "actions" },
          t.sourceMessageId && d.messages.some((m) => m.id === t.sourceMessageId)
            ? el("button", { class: "small", title: "Read the message this to-do came from", onclick: () => showMessage(t.sourceMessageId) }, "Read email") : null,
          el("button", { class: "small", onclick: action(async () => { await api("POST", "/api/tasks/" + t.id + "/done"); refresh(); }, "Done") }, "Done"),
          el("button", { class: "small", onclick: action(async () => { await api("POST", "/api/tasks/" + t.id + "/dismiss"); refresh(); }) }, "Dismiss")) : null))) : emptyLine("No tasks."),
      el("div", { class: "row" }, taskInput, taskDue, el("button", { class: "small", onclick: action(async () => {
        if (!taskInput.value.trim()) return;
        await api("POST", "/api/tasks", { opportunityId: id, description: taskInput.value, dueDate: taskDue.value || null });
        refresh();
      }, "Task added") }, "Add"))));

    // Campaign results: the post, its numbers, the results PDF and the recap email
    const result = await api("GET", "/api/opportunities/" + id + "/results");
    if (result || ["SCHEDULED_TO_POST", "POSTED", "PAYMENT_PENDING"].includes(o.status)) {
      drawer.appendChild(resultsCard(id, result, refresh));
    }

    // Exclusivity: another brand's exclusive window (or date) clashes with this deal's
    const clashes = await api("GET", "/api/opportunities/" + id + "/exclusivity");
    if (clashes.length) {
      drawer.appendChild(el("div", { class: "card", id: "exclusivity" }, el("h2", {}, "Exclusivity clash"),
        el("ul", { class: "flags" }, clashes.map((x) => el("li", { class: "flag amber" }, el("span", { class: "mark" }, "!"),
          el("span", {}, x.text, " ", el("a", { href: "#", onclick: (e) => { e.preventDefault(); openDeal(x.otherOpportunityId); } }, "Open " + x.otherBrand))))),
        el("p", { class: "small muted" }, "Exclusivity comes from the contract or what the brand wrote. Only you know whether the brands compete.")));
    }

    if (d.deadlines.length) {
      drawer.appendChild(card("Deadlines", el("ul", { class: "list" }, d.deadlines.map((x) => el("li", { class: "item" },
        el("div", { class: "body" }, el("div", { class: "title" }, pretty(x.type) + " — " + fmtDate(x.dueDate)),
          el("div", { class: "detail" }, (x.done ? "✓ done · " : "") + (x.calendarEventId ? "📅 on your calendar · " : "") + (x.description || ""))))))));
    }

    const msgs = d.messages.map((m) => ({ m, node: el("div", { class: "msg" + (m.direction === "OUTBOUND" ? " out" : ""), id: "msg-" + m.id },
      el("div", { class: "meta" }, (m.direction === "OUTBOUND" ? "You" : (m.from || "Brand")) + " · " + fmtDateTime(m.sentAt)
        + (m.type ? " · " + pretty(m.type) : ""),
        m.gmailUrl ? el("a", { class: "small open-gmail", href: m.gmailUrl, target: "_blank", rel: "noopener noreferrer" }, "Open in Gmail") : null),
      m.subject ? el("div", { class: "title small" }, m.subject) : null,
      m.fullEmail ? emailFrame(m) : el("div", { class: "text" }, linkify(m.content || ""))) }));
    const msgCount = el("span", { class: "small muted", role: "status" });
    const msgSearch = msgs.length > 2 ? searchBox("", "Search this conversation…", (q) => {
      let hits = 0;
      for (const x of msgs) {
        const keep = matchesQuery(q, x.m.from, x.m.subject, x.m.content);
        x.node.classList.toggle("hidden", !keep);
        if (keep) hits++;
      }
      msgCount.textContent = q.trim() ? hits + " of " + msgs.length + " messages" : "";
    }) : null;
    drawer.appendChild(card("Conversation",
      msgSearch ? el("div", { class: "row filters" }, el("div", { class: "spacer" }, msgSearch), msgCount) : null,
      msgs.length ? msgs.map((x) => x.node) : emptyLine("No messages linked (e.g. a pitch logged manually).")));
    if (focusMessageId) showMessage(focusMessageId);

    const activityItem = (a) => el("li", { class: "item" }, el("div", { class: "body" }, el("div", {}, a.text), el("div", { class: "detail" }, fmtDateTime(a.at))));
    const activityList = el("ul", { class: "list" }, d.activity.slice(0, 30).map(activityItem));
    const moreActivity = d.activity.length > 30 ? el("button", { class: "small", onclick: (e) => {
      d.activity.slice(30).forEach((a) => activityList.appendChild(activityItem(a)));
      e.currentTarget.remove();
    } }, "Show all " + d.activity.length) : null;
    drawer.appendChild(card("Activity", d.activity.length ? [activityList, moreActivity] : emptyLine("—")));
  }

  function facts(pairs) {
    return el("table", {}, el("tbody", {}, pairs.filter(([, v]) => v).map(([k, v]) => el("tr", {}, el("th", {}, k), el("td", {}, v)))));
  }

  // ---------- Outreach ----------

  const outreachFilter = loadPrefs("outreach", { q: "", response: "all", status: "", hideClosed: false, sort: { by: "pitchedAt", dir: "desc" } });

  async function renderOutreach(root) {
    const [rows, leads, ig] = await Promise.all([api("GET", "/api/pitches"), api("GET", "/api/leads"),
      api("GET", "/api/leads/instagram").catch(() => null)]);
    clear(root);
    root.appendChild(dealsSwitch("outreach"));
    root.appendChild(el("h1", {}, "Pitching brands"));
    root.appendChild(el("p", { class: "muted" }, "Every brand you've pitched, with automatic follow-up dates. Pitches you send from Gmail are detected automatically; log the rest here."));
    if (ig && ig.instagramConnected) root.appendChild(engagingBrandsCard(root, ig));
    root.appendChild(findBrandsCard(root, leads, ig));

    const f = {
      brand: el("input", { required: true, maxlength: "200" }), contactName: el("input", { maxlength: "200" }),
      contactEmail: el("input", { type: "email", maxlength: "320" }), instagram: el("input", { placeholder: "handle without @", maxlength: "100" }),
      platform: el("select", {}, ["EMAIL", "INSTAGRAM", "TIKTOK", "OTHER"].map((p) => el("option", { value: p }, pretty(p)))),
      opportunity: el("input", { placeholder: "e.g. UGC video for fall launch", maxlength: "500" }),
      pitchedAt: el("input", { type: "date", value: new Date().toISOString().slice(0, 10) }),
    };
    async function submit(force) {
      const body = { brand: f.brand.value.trim(), contactName: f.contactName.value, contactEmail: f.contactEmail.value || null,
        instagram: f.instagram.value.replace(/^@/, "") || null, platform: f.platform.value, opportunity: f.opportunity.value,
        pitchedAt: f.pitchedAt.value || null, notes: null, force };
      try {
        await api("POST", "/api/pitches", body);
        toast("Pitch logged — follow-up #1 scheduled");
        renderOutreach(root);
      } catch (err) {
        if (/already in your pipeline/.test(err.message) && confirm(err.message + "\n\nLog it anyway?")) return submit(true);
        toast(err.message, true);
      }
    }
    // Collapsible, so a long pitch table isn't pushed far down the page. Remembers whether it was open.
    const logOpen = loadPrefs("outreach-log", { open: true }).open;
    const logBox = el("details", { class: "card collapsible", open: logOpen },
      el("summary", {}, el("h3", {}, "Log a pitch")));
    logBox.addEventListener("toggle", () => savePrefs("outreach-log", { open: logBox.open }));
    root.appendChild(logBox);
    logBox.appendChild(el("div", {}, el("div", { class: "grid" },
      el("div", {}, el("label", {}, "Brand *"), f.brand), el("div", {}, el("label", {}, "Contact name"), f.contactName),
      el("div", {}, el("label", {}, "Contact email"), f.contactEmail), el("div", {}, el("label", {}, "Instagram"), f.instagram),
      el("div", {}, el("label", {}, "Platform"), f.platform), el("div", {}, el("label", {}, "Date pitched"), f.pitchedAt)),
      el("label", {}, "What you pitched"), f.opportunity,
      el("p", {}, el("button", { class: "primary", onclick: () => { if (f.brand.value.trim()) submit(false); else toast("Brand is required", true); } }, "Log pitch"))));

    if (!rows.length) { root.appendChild(card(null, emptyLine("No pitches yet."))); return; }
    const pf = outreachFilter;
    const save = () => savePrefs("outreach", pf);
    const n = rows[0].followUps.length;
    const statusNames = [...new Set(rows.map((r) => r.status))];
    if (pf.status && !statusNames.includes(pf.status)) pf.status = "";
    const statusSelect = el("select", { "aria-label": "Status", onchange: (e) => { pf.status = e.target.value; save(); draw(); } },
      el("option", { value: "" }, "All statuses"), statusNames.map((v) => el("option", { value: v, selected: pf.status === v }, v)));
    const hideClosed = el("input", { type: "checkbox", checked: pf.hideClosed, onchange: (e) => { pf.hideClosed = e.target.checked; save(); draw(); } });
    const search = searchBox(pf.q, "Search brand, contact, pitch…", (v) => { pf.q = v; save(); draw(); });
    const chips = el("div", {});
    root.appendChild(el("h2", {}, "Your pitches"));
    root.appendChild(el("div", { class: "row filters" }, el("div", { class: "spacer" }, search), chips, statusSelect,
      el("label", { class: "check" }, hideClosed, "Hide closed")));
    const list = el("div", {});
    root.appendChild(list);
    const responses = { all: () => true, none: (r) => !r.initialResponse, replied: (r) => !!r.initialResponse };

    function draw() {
      clear(chips).appendChild(chipRow([["all", "Any response"], ["none", "No reply yet"], ["replied", "Replied"]],
        pf.response, (v) => { pf.response = v; save(); draw(); }, "Response"));
      clear(list);
      const shown = sortRows(rows.filter((r) => (responses[pf.response] || responses.all)(r) && (!pf.hideClosed || !r.closed)
        && (!pf.status || r.status === pf.status)
        && matchesQuery(pf.q, r.brand, r.contact, r.contactSearch, r.opportunity, r.initialResponse, pretty(r.platform), r.status)),
      pf.sort, { brand: (r) => r.brand, pitchedAt: (r) => r.pitchedAt, nextFollowUp: (r) => r.nextFollowUp, status: (r) => r.status });
      const line = shownLine(shown.length, rows.length, "pitches", () => {
        Object.assign(pf, { q: "", response: "all", status: "", hideClosed: false });
        search.value = ""; statusSelect.value = ""; hideClosed.checked = false; save(); draw();
      });
      if (line) list.appendChild(line);
      if (!shown.length) { list.appendChild(card(null, emptyLine("No pitches match."))); return; }
      list.appendChild(el("div", { class: "card table-wrap" }, el("table", {},
        sortableHead([["Brand", "brand"], ["Contact", null], ["Pitched", "pitchedAt", "desc"], ["Platform", null], ["Opportunity", null], ["Response", null]]
          .concat(Array.from({ length: n }, (_, i) => ["FU #" + (i + 1), null]))
          .concat([["Next due", "nextFollowUp", "asc"], ["Status", "status"]]),
        pf.sort, (s) => { pf.sort = s; save(); draw(); }),
        el("tbody", {}, shown.map((r) => el("tr", { class: "clickable", onclick: () => openDeal(r.opportunityId) },
          el("td", {}, el("strong", {}, r.brand)), el("td", {}, r.contact || ""), el("td", {}, fmtDate(r.pitchedAt)),
          el("td", {}, pretty(r.platform)), el("td", {}, r.opportunity || ""), el("td", {}, r.initialResponse ? pretty(r.initialResponse) : "—"),
          r.followUps.map((x) => el("td", { class: x.startsWith("due") ? "" : "muted" }, x || "")),
          el("td", {}, r.nextFollowUp ? fmtDate(r.nextFollowUp) : "—"),
          el("td", {}, r.status)))))));
    }
    draw();
  }

  const compactNum = (n) => Number(n).toLocaleString(undefined, { notation: "compact", maximumFractionDigits: 1 });

  // Accounts that tagged, mentioned or commented on her: the warmest brands to pitch.
  function engagingBrandsCard(root, ig) {
    const kinds = { TAG: "tagged you", MENTION: "mentioned you", COMMENT: "commented" };
    const check = action(async () => {
      const r = await api("POST", "/api/leads/instagram/check");
      toast(r.accounts.length ? "Checked your recent posts" : "Checked your recent posts; no brands yet");
      renderOutreach(root);
    });
    const row = (a) => el("div", { class: "item" },
      el("div", { class: "body" },
        el("div", { class: "row" },
          el("a", { href: "https://www.instagram.com/" + encodeURIComponent(a.username) + "/", target: "_blank", rel: "noopener noreferrer" }, el("strong", {}, "@" + a.username)),
          a.mentions ? el("span", { class: "badge" }, "Tagged or mentioned you" + (a.mentions > 1 ? " ×" + a.mentions : "")) : null,
          a.comments ? el("span", { class: "badge" }, a.comments + " comment" + (a.comments === 1 ? "" : "s")) : null,
          a.isBusiness ? el("span", { class: "badge" }, "Business account" + (a.followers != null ? " · " + compactNum(a.followers) + " followers" : "")) : null),
        a.lastText ? el("div", { class: "detail" }, "Latest (" + kinds[a.lastKind] + ", " + fmtDate(a.lastSeenAt) + "): “" + a.lastText + "”") : null,
        a.lastPermalink && /^https:\/\/(www\.)?instagram\.com\//.test(a.lastPermalink)
          ? el("a", { href: a.lastPermalink, target: "_blank", rel: "noopener noreferrer", class: "small" }, "Open post") : null),
      el("div", { class: "actions" },
        el("button", { class: "small primary", onclick: action(async () => {
          await api("POST", "/api/leads/instagram/" + a.id + "/lead"); renderOutreach(root);
        }, "Added to your brand leads below") }, "Add as lead"),
        el("button", { class: "small", onclick: action(async () => {
          await api("POST", "/api/leads/instagram/" + a.id + "/dismiss"); renderOutreach(root);
        }) }, "Not a brand")));
    return card("Brands engaging with you on Instagram",
      el("p", { class: "small muted" }, "Accounts that tagged you in a post, @mentioned you or commented on your recent posts. Brands that already know you are the easiest to pitch. "
        + (ig.facebookConnected ? "Personal accounts (fans) are hidden automatically." : "Connect Facebook on the Settings page to hide fans automatically and see posts you're tagged in.")
        + " Checked each time the app looks for new messages."),
      ig.error ? el("div", { class: "alert error small" }, ig.error) : null,
      ig.accounts.length ? el("div", {}, ig.accounts.map(row)) : emptyLine("No brands have tagged, mentioned or commented on you yet."),
      el("div", { class: "row" }, el("button", { class: "small", onclick: check }, "Check now")));
  }

  function findBrandsCard(root, leads, ig) {
    const query = el("input", { placeholder: "e.g. clean skincare brands like Glossier that work with UGC creators", maxlength: "300" });
    const count = el("select", { class: "inline" }, [3, 5, 10].map((n) => el("option", { value: n }, n + " brands")));
    count.value = "5";
    // How hard to search: fewer web searches cost less. Labels show the real average once there are past runs.
    const depthNames = { QUICK: "Quick", STANDARD: "Standard", THOROUGH: "Thorough" };
    const depthNotes = { QUICK: "cheapest, may find fewer", STANDARD: "good balance", THOROUGH: "most brands, costs most" };
    const depth = el("select", { class: "inline", "aria-label": "How hard to search" });
    const fillDepth = (opts) => {
      clear(depth);
      opts.forEach((o) => depth.appendChild(el("option", { value: o.depth },
        // Price first, so it still shows when a phone cuts the end off.
        depthNames[o.depth] + ", " + (o.measured ? "" : "about ") + usd(o.usd) + (o.measured ? " on average" : "")
        + ": up to " + o.maxSearches + " searches (" + depthNotes[o.depth] + ")")));
      let saved = null;
      try { saved = localStorage.getItem("crm.searchDepth"); } catch (e) { /* private window */ }
      depth.value = opts.some((o) => o.depth === saved) ? saved : "STANDARD";
    };
    fillDepth([{ depth: "QUICK", maxSearches: 3, usd: 0.25 }, { depth: "STANDARD", maxSearches: 6, usd: 0.45 },
      { depth: "THOROUGH", maxSearches: 15, usd: 1.0 }]);
    api("GET", "/api/leads/search-options").then(fillDepth).catch(() => {});
    depth.addEventListener("change", () => { try { localStorage.setItem("crm.searchDepth", depth.value); } catch (e) { /* ignore */ } });
    const status = el("span", { class: "small muted" });
    const search = action(async () => {
      if (query.value.trim().length < 3) throw new Error("Describe the kind of brands to look for");
      status.textContent = "Researching brands on the web… this can take a minute or two.";
      try {
        const found = await api("POST", "/api/leads/search", { query: query.value, count: Number(count.value), depth: depth.value });
        toast(found.length ? found.length + " new brand" + (found.length === 1 ? "" : "s") + " found" : "No new brands found; try a different search");
        renderOutreach(root);
      } finally { status.textContent = ""; }
    });
    query.addEventListener("keydown", (e) => { if (e.key === "Enter") search(e); });
    // Look up a brand's Instagram account by handle (needs the Facebook connection).
    const handle = el("input", { placeholder: "@brandhandle", maxlength: "100", "aria-label": "Brand's Instagram handle" });
    const lookup = action(async () => {
      const h = handle.value.trim().replace(/^@/, "");
      if (!h) throw new Error("Type the brand's Instagram handle");
      const l = await api("POST", "/api/leads/lookup", { handle: h });
      toast(l.name + " added to your brand leads");
      renderOutreach(root);
    });
    handle.addEventListener("keydown", (e) => { if (e.key === "Enter") lookup(e); });
    const lookupRow = ig && ig.facebookConnected
      ? el("div", { class: "row" }, el("span", { class: "small muted" }, "Know a brand already?"), el("div", { class: "spacer" }, handle),
        el("button", { class: "small", onclick: lookup }, "Look up on Instagram"))
      : el("p", { class: "small muted" }, "Tip: connect Facebook on the Settings page to look up any brand's Instagram (followers, bio, creators they work with) and add it here.");
    return card("Find brands to pitch",
      el("p", { class: "small muted" }, "Claude searches the web for brands that fit your profile, checks their sites for a published partnerships or PR email, and suggests a pitch idea. Pick the ones you like and a pitch draft lands in Drafts for you to edit and send. Nothing is sent automatically."),
      el("div", { class: "row find-brands" }, el("div", { class: "query" }, query), count, el("button", { class: "primary", onclick: search }, "Find brands")),
      el("div", { class: "row find-depth" }, el("span", { class: "small muted" }, "Search depth:"), depth),
      status,
      lookupRow,
      leads.length ? el("div", {}, leads.map((l) => leadItem(root, l, ig && ig.facebookConnected))) : null);
  }

  function leadItem(root, l, canLookUp) {
    const safeUrl = (u) => u && /^https?:\/\//i.test(u) ? u : null;
    const email = el("input", { type: "email", placeholder: "Contact email", value: l.contactEmail || "", maxlength: "320" });
    const ig = el("input", { placeholder: "Instagram handle", value: l.instagram || "", maxlength: "100" });
    const contactEdit = el("div", { class: "grid hidden" }, email, ig,
      el("button", { class: "small", onclick: action(async () => {
        await api("PUT", "/api/leads/" + l.id + "/contact", { email: email.value, instagram: ig.value.replace(/^@/, "") });
        renderOutreach(root);
      }, "Contact saved") }, "Save contact"));
    const contact = l.contactEmail
      ? el("span", {}, "✉️ " + l.contactEmail, safeUrl(l.contactSourceUrl) ? el("a", { href: l.contactSourceUrl, target: "_blank", rel: "noopener noreferrer", class: "small" }, " (source)") : null)
      : el("span", { class: "muted" }, "No published email found");
    return el("div", { class: "item" },
      el("div", { class: "body" },
        el("div", { class: "row" }, el("strong", {}, l.name),
          safeUrl(l.website) ? el("a", { href: l.website, target: "_blank", rel: "noopener noreferrer", class: "small" }, l.website.replace(/^https?:\/\//, "")) : null,
          l.instagram ? el("span", { class: "badge" }, "@" + l.instagram) : null,
          l.source === "INSTAGRAM" ? el("span", { class: "badge" }, "Engaged with you") : null,
          l.source === "LOOKUP" ? el("span", { class: "badge" }, "Looked up") : null),
        l.fitReason ? el("div", { class: "detail" }, l.fitReason) : null,
        l.igCheckedAt ? el("div", { class: "detail" }, "📸 " + [l.igFollowers != null ? compactNum(l.igFollowers) + " followers" : null,
          l.igBio, l.igPartners ? "Works with " + l.igPartners.split(",").map((h) => "@" + h).join(", ") : null].filter(Boolean).join(" · ")) : null,
        l.pitchAngle ? el("div", { class: "detail" }, "💡 " + l.pitchAngle) : null,
        el("div", { class: "small" }, contact),
        contactEdit),
      el("div", { class: "actions" },
        el("button", { class: "small primary", onclick: action(async () => {
          const made = await api("POST", "/api/leads/" + l.id + "/pitch");
          showDraft(made);
        }, "Pitch drafted. Review it in Drafts") }, "Draft pitch"),
        el("button", { class: "small", onclick: () => contactEdit.classList.toggle("hidden") }, "Edit contact"),
        canLookUp && l.instagram ? el("button", { class: "small", onclick: action(async () => {
          await api("POST", "/api/leads/" + l.id + "/instagram"); renderOutreach(root);
        }, "Instagram details updated") }, l.igCheckedAt ? "Refresh Instagram" : "Check Instagram") : null,
        el("button", { class: "small danger", onclick: action(async () => { await api("POST", "/api/leads/" + l.id + "/dismiss"); renderOutreach(root); }) }, "Dismiss")));
  }

  // ---------- Links ----------

  /** Brand colours for well-known profiles; any other link gets a soft colour picked from its name. */
  const LINK_BRANDS = {
    "instagram.com": ["Instagram", "ig"], "tiktok.com": ["TikTok", "tt"], "youtube.com": ["YouTube", "yt"], "youtu.be": ["YouTube", "yt"],
    "x.com": ["X", "x"], "twitter.com": ["X", "x"], "threads.net": ["Threads", "x"], "facebook.com": ["Facebook", "fb"],
    "pinterest.com": ["Pinterest", "pin"], "linkedin.com": ["LinkedIn", "li"], "snapchat.com": ["Snapchat", "snap"],
    "twitch.tv": ["Twitch", "twitch"], "amazon.com": ["Amazon", "amz"], "canva.site": ["Canva", "canva"],
  };
  const SOCIAL_HOSTS = ["instagram.com", "tiktok.com", "youtube.com", "youtu.be", "x.com", "twitter.com", "threads.net",
    "facebook.com", "pinterest.com", "linkedin.com", "snapchat.com", "twitch.tv"];
  let linksAddMode = null; // which "Add links" tab is open; remembered while she stays on the page

  function linkHost(url) {
    try { return new URL(url).hostname.toLowerCase().replace(/^(www|m)\./, ""); } catch (e) { return url; }
  }
  function brandOf(host) {
    const key = Object.keys(LINK_BRANDS).find((h) => host === h || host.endsWith("." + h));
    return key ? LINK_BRANDS[key] : null;
  }
  function isSocial(url) { const h = linkHost(url); return SOCIAL_HOSTS.some((s) => h === s || h.endsWith("." + s)); }
  /** Same rule as the server: scheme, "www.", host case and a trailing slash don't make a link different. */
  function sameLinkKey(url) {
    let v = String(url).trim().replace(/^https?:\/\//i, "");
    if (!/^[a-z][a-z0-9+.-]*:/i.test(v)) v = v.replace(/^www\./i, "");
    const i = v.indexOf("/");
    return (i < 0 ? v : v.slice(0, i)).toLowerCase() + (i < 0 ? "" : v.slice(i)).replace(/\/+$/, "");
  }

  function linkIcon(l) {
    const host = linkHost(l.url);
    const brand = brandOf(host);
    const letter = ((l.label || host).match(/[\p{L}\p{N}]/u) || ["•"])[0].toUpperCase();
    if (brand) return el("span", { class: "link-icon brand-" + brand[1], "aria-hidden": "true" }, letter);
    let h = 0;
    for (const c of l.label || host) h = (h * 31 + c.charCodeAt(0)) >>> 0;
    return el("span", { class: "link-icon tone-" + (h % 6), "aria-hidden": "true" }, letter);
  }

  async function renderLinks(root) {
    const links = await api("GET", "/api/links");
    clear(root);
    const asText = () => links.map((l) => l.label + ": " + l.url).join("\n");
    root.appendChild(el("div", { class: "links-head" },
      el("div", {},
        el("h1", {}, "My links"),
        el("p", { class: "muted" }, "Your profiles, shop and portfolio in one place. Drafts use these exact links instead of placeholders like [MEDIA KIT LINK].")),
      links.length ? el("button", { onclick: action(async () => { await navigator.clipboard.writeText(asText()); }, "All links copied") }, "Copy all") : null));

    if (!links.length) {
      root.appendChild(el("div", { class: "links-empty" },
        el("div", { class: "links-empty-art", "aria-hidden": "true" }, "🔗"),
        el("h3", {}, "No links yet"),
        el("p", { class: "muted" }, "Bring everything over from your Linktree in one go, or add links one by one.")));
      root.appendChild(addLinksCard(root, links, linksAddMode || "linktree"));
      return;
    }

    const socials = links.filter((l) => isSocial(l.url));
    if (socials.length) {
      root.appendChild(el("div", { class: "links-socials", role: "list", "aria-label": "Profiles" },
        socials.map((l) => el("a", { class: "social-chip", role: "listitem", href: l.url, target: "_blank", rel: "noopener noreferrer", title: l.url },
          linkIcon(l), el("span", {}, l.label)))));
    }

    root.appendChild(el("div", { class: "links-count muted" }, links.length + " link" + (links.length === 1 ? "" : "s") + " · the order here is the order drafts see them"));
    root.appendChild(el("ul", { class: "links-list" }, links.map((l, i) => linkCard(root, l, i, links.length))));
    root.appendChild(addLinksCard(root, links, linksAddMode || "one"));
  }

  function linkCard(root, l, i, n) {
    const safe = /^https?:\/\//i.test(l.url); // the server only stores http(s), but never render anything else as a link
    const host = linkHost(l.url);
    const move = (up) => action(async () => { await api("POST", "/api/links/" + l.id + "/move?up=" + up); renderLinks(root); });
    const li = el("li", { class: "link-card" },
      linkIcon(l),
      el("div", { class: "link-body" },
        el("div", { class: "link-label" }, l.label),
        safe ? el("a", { class: "link-url", href: l.url, target: "_blank", rel: "noopener noreferrer", title: l.url }, host + shortPath(l.url))
          : el("div", { class: "link-url" }, l.url)),
      el("div", { class: "link-actions" },
        el("button", { class: "small", onclick: action(async () => { await navigator.clipboard.writeText(l.url); }, "Copied " + l.label) }, "Copy"),
        el("button", { class: "small", onclick: () => editLink(root, li, l) }, "Edit"),
        el("span", { class: "link-move" },
          el("button", { class: "small icon", title: "Move up", "aria-label": "Move " + l.label + " up", disabled: i === 0, onclick: move(true) }, "↑"),
          el("button", { class: "small icon", title: "Move down", "aria-label": "Move " + l.label + " down", disabled: i === n - 1, onclick: move(false) }, "↓")),
        el("button", { class: "small icon danger", title: "Delete", "aria-label": "Delete " + l.label, onclick: action(async () => {
          if (!confirm("Delete " + l.label + "?")) return;
          await api("DELETE", "/api/links/" + l.id); renderLinks(root);
        }, "Deleted") }, "✕")));
    return li;
  }

  function shortPath(url) {
    try {
      const u = new URL(url);
      const p = (u.pathname + u.search).replace(/\/$/, "");
      return p.length > 40 ? p.slice(0, 38) + "…" : p;
    } catch (e) { return ""; }
  }

  function editLink(root, li, l) {
    const label = el("input", { value: l.label, maxlength: "100", "aria-label": "Label" });
    const url = el("input", { value: l.url, maxlength: "1000", "aria-label": "Link" });
    const save = action(async () => { await api("PUT", "/api/links/" + l.id, { label: label.value, url: url.value }); renderLinks(root); }, "Saved");
    for (const input of [label, url]) input.addEventListener("keydown", (e) => { if (e.key === "Enter") save(e); if (e.key === "Escape") renderLinks(root); });
    clear(li);
    li.classList.add("editing");
    li.append(linkIcon(l), el("div", { class: "link-edit" }, label, url), el("div", { class: "link-actions" },
      el("button", { class: "small primary", onclick: save }, "Save"),
      el("button", { class: "small", onclick: () => renderLinks(root) }, "Cancel")));
    label.focus();
  }

  /** "Add links" card: one link, everything from a Linktree page, or a pasted list (one per line). */
  function addLinksCard(root, links, mode) {
    const saved = new Set(links.map((l) => sameLinkKey(l.url)));
    const body = el("div", { class: "links-add-body" });
    const modes = [["linktree", "From Linktree"], ["one", "One link"], ["paste", "Paste a list"]];
    const tabs = el("div", { class: "seg", role: "tablist" }, modes.map(([m, text]) =>
      el("button", { class: m === mode ? "active" : null, role: "tab", "aria-selected": String(m === mode), onclick: () => { linksAddMode = m; show(m); } }, text)));
    function show(m) {
      [...tabs.children].forEach((b, i) => { b.classList.toggle("active", modes[i][0] === m); b.setAttribute("aria-selected", String(modes[i][0] === m)); });
      clear(body);
      body.appendChild(m === "linktree" ? linktreeForm(root, saved) : m === "paste" ? pasteForm(root, saved) : oneLinkForm(root));
    }
    show(mode);
    return el("div", { class: "card links-add" }, el("div", { class: "row" }, el("h3", {}, "Add links"), el("div", { class: "spacer" }), tabs), body);
  }

  function oneLinkForm(root) {
    const url = el("input", { placeholder: "e.g. instagram.com/yourname", maxlength: "1000" });
    const label = el("input", { placeholder: "Optional, filled in for Instagram, TikTok, YouTube…", maxlength: "100" });
    const add = action(async () => {
      if (!url.value.trim()) throw new Error("Paste a link first");
      await api("POST", "/api/links", { url: url.value, label: label.value });
      renderLinks(root);
    }, "Link added");
    url.addEventListener("keydown", (e) => { if (e.key === "Enter") add(e); });
    label.addEventListener("keydown", (e) => { if (e.key === "Enter") add(e); });
    return el("div", {}, el("div", { class: "grid" },
      el("div", {}, el("label", {}, "Link"), url), el("div", {}, el("label", {}, "Label"), label)),
      el("p", {}, el("button", { class: "primary", onclick: add }, "Add link")));
  }

  function linktreeForm(root, saved) {
    const url = el("input", { placeholder: "linktr.ee/yourname", maxlength: "2000" });
    const out = el("div", {});
    const find = action(async () => {
      if (!url.value.trim()) throw new Error("Paste your Linktree link first");
      clear(out).appendChild(el("p", { class: "muted" }, "Reading your Linktree…"));
      try {
        const p = await api("POST", "/api/links/linktree", { url: url.value });
        clear(out).appendChild(pickLinks(root, saved, p.links, "Found " + p.links.length + " link" + (p.links.length === 1 ? "" : "s") + " on " + p.name + "'s Linktree"));
      } catch (err) { clear(out); throw err; }
    });
    url.addEventListener("keydown", (e) => { if (e.key === "Enter") find(e); });
    return el("div", {},
      el("p", { class: "muted" }, "Paste your Linktree link. The app reads the page, shows you what it found, and you pick what to add. Tracking bits like ?utm_source are dropped."),
      el("div", { class: "row" }, url, el("button", { class: "primary", onclick: find }, "Find links")),
      out);
  }

  function pasteForm(root, saved) {
    const text = el("textarea", { placeholder: "One link per line, for example:\nUGC portfolio: https://me.my.canva.site/portfolio\nhttps://www.tiktok.com/@me" });
    const out = el("div", {});
    const read = () => {
      const found = [];
      for (const line of text.value.split(/\r?\n/)) {
        const m = line.match(/(https?:\/\/\S+|(?:www\.)?[a-z0-9-]+(?:\.[a-z0-9-]+)+\/\S*)/i);
        if (!m) continue;
        const label = line.slice(0, m.index).replace(/[\s:–—-]+$/, "").replace(/^[\s•*\d.)-]+/, "").trim();
        found.push({ label, url: m[1], social: isSocial(/^https?:/i.test(m[1]) ? m[1] : "https://" + m[1]) });
      }
      clear(out);
      if (!found.length) { toast("No links found in that text", true); return; }
      out.appendChild(pickLinks(root, saved, found, found.length + " link" + (found.length === 1 ? "" : "s") + " found"));
    };
    return el("div", {}, text, el("p", {}, el("button", { class: "primary", onclick: read }, "Read links")), out);
  }

  /** Checklist of found links; ones already saved are shown but not ticked. */
  function pickLinks(root, saved, found, title) {
    const rows = found.map((f) => {
      const already = saved.has(sameLinkKey(f.url));
      const box = el("input", { type: "checkbox", checked: !already, disabled: already });
      const label = el("input", { value: f.label || "", maxlength: "100", placeholder: linkHost(/^https?:/i.test(f.url) ? f.url : "https://" + f.url), "aria-label": "Label" });
      return { f, box, label, node: el("li", { class: "pick" + (already ? " already" : "") },
        box, linkIcon({ label: f.label, url: /^https?:/i.test(f.url) ? f.url : "https://" + f.url }),
        el("div", { class: "link-body" }, label, el("div", { class: "link-url" }, f.url)),
        already ? el("span", { class: "badge ok" }, "Already saved") : f.social ? el("span", { class: "badge" }, "Profile") : null) };
    });
    const fresh = rows.filter((r) => !r.box.disabled);
    const addBtn = el("button", { class: "primary" });
    const count = () => { const n = rows.filter((r) => r.box.checked).length; addBtn.textContent = "Add " + n + " link" + (n === 1 ? "" : "s"); addBtn.disabled = n === 0; };
    rows.forEach((r) => r.box.addEventListener("change", count));
    addBtn.addEventListener("click", action(async () => {
      const picked = rows.filter((r) => r.box.checked).map((r) => ({ label: r.label.value, url: r.f.url }));
      const res = await api("POST", "/api/links/bulk", { links: picked });
      linksAddMode = "one";
      await renderLinks(root);
      toast("Added " + res.added + " link" + (res.added === 1 ? "" : "s") + (res.skipped ? " (" + res.skipped + " already saved or over the limit)" : ""));
    }));
    count();
    const all = el("button", { class: "small", onclick: () => { const on = fresh.some((r) => !r.box.checked); fresh.forEach((r) => { r.box.checked = on; }); count(); } }, "Select all / none");
    return el("div", { class: "links-pick" },
      el("div", { class: "row" }, el("strong", {}, title), el("div", { class: "spacer" }), fresh.length > 1 ? all : null),
      el("ul", {}, rows.map((r) => r.node)),
      el("div", { class: "row" }, addBtn, fresh.length ? el("span", { class: "muted small" }, "You can rename them now or later.") : el("span", { class: "muted small" }, "Everything here is already on your Links page.")));
  }

  // ---------- Drafts ----------

  /** Go to Drafts with this draft open (any object with an id, e.g. what the API returns after writing one). */
  function showDraft(draft) {
    const h = "#drafts" + (draft && draft.id ? "?id=" + draft.id : "");
    if (location.hash === h) route(); else location.hash = h;
  }

  const draftsFilter = loadPrefs("drafts", { q: "", type: "all", order: "oldest" });

  /**
   * "Ask Claude to change this" under a draft: quick buttons or a typed request. Claude rewrites the text on screen
   * and saves it; nothing is sent. Undo steps back through the earlier versions.
   */
  const quickChanges = [
    ["Warmer", "Make it warmer and friendlier."],
    ["Shorter", "Make it shorter. Keep the key points."],
    ["More formal", "Make it more formal and professional."],
    ["Add my rates", "Add my rates from my profile where they fit this message. If my profile has none for this, use placeholders."],
  ];
  function askClaude(d, subject, body) {
    const history = [];
    const ask = el("input", { class: "grow", placeholder: "Or say what to change, e.g. 'mention I'm free in March'", maxlength: "1000",
      "aria-label": "What should Claude change?" });
    const status = el("span", { class: "small muted" });
    const undo = el("button", { class: "small hidden", title: "Put back the version before Claude's change", onclick: action(async () => {
      const prev = history.pop();
      if (!prev) return;
      subject.value = prev.subject; body.value = prev.body; body.dispatchEvent(new Event("input"));
      undo.classList.toggle("hidden", !history.length);
      await api("PUT", "/api/drafts/" + d.id, prev);
    }, "Change undone") }, "Undo");
    const buttons = [];
    const run = async (request) => {
      if (!request.trim()) { ask.focus(); return; }
      buttons.forEach((b) => { b.disabled = true; });
      status.textContent = "Claude is rewriting it…";
      const before = { subject: subject.value, body: body.value };
      try {
        const r = await api("POST", "/api/drafts/" + d.id + "/revise", { subject: before.subject, body: before.body, request });
        history.push(before);
        subject.value = r.subject || ""; body.value = r.body; body.dispatchEvent(new Event("input"));
        undo.classList.remove("hidden");
        ask.value = "";
        status.textContent = "Changed. Read it over before you send.";
      } catch (err) {
        status.textContent = "";
        toast(err.message, true);
      } finally {
        buttons.forEach((b) => { b.disabled = false; });
      }
    };
    for (const [label, request] of quickChanges) buttons.push(el("button", { class: "small", onclick: () => run(request) }, label));
    const go = el("button", { class: "small", onclick: () => run(ask.value) }, "Change");
    buttons.push(go, ask);
    ask.addEventListener("keydown", (e) => { if (e.key === "Enter") { e.preventDefault(); run(ask.value); } });
    return el("div", { class: "ask-claude" },
      el("div", { class: "small muted" }, "Ask Claude to change it"),
      el("div", { class: "row" }, buttons.slice(0, quickChanges.length), undo),
      el("div", { class: "row" }, ask, go),
      status);
  }

  /**
   * What will go out, exactly as the brand gets it, before anything is sent. Resolves true for Send, false for
   * Keep editing (or Escape).
   */
  function sendPreview(d, brand, subject, body) {
    return new Promise((resolve) => {
      const email = d.channel === "EMAIL";
      const files = [d.invoiceId && email ? "Invoice PDF" : null, d.resultId && email ? "Results PDF" : null].filter(Boolean);
      const sendBtn = el("button", { class: "primary", onclick: () => close(true) }, email ? "Send email" : "Send DM");
      const dlg = el("dialog", { class: "send-preview", "aria-labelledby": "send-preview-title" },
        el("h2", { id: "send-preview-title" }, "Check before sending"),
        el("p", { class: "small muted" }, email ? "This is the email " + brand + " will get." : "This is the Instagram DM " + brand + " will get."),
        el("div", { class: "preview-mail" },
          el("div", { class: "preview-head" },
            el("div", {}, el("span", { class: "muted" }, "To: "), email ? (d.toAddress || brand) : brand + " on Instagram"),
            email ? el("div", {}, el("span", { class: "muted" }, "Subject: "), el("strong", {}, subject || "(no subject)")) : null,
            files.length ? el("div", {}, el("span", { class: "muted" }, "Attached: "), "📎 " + files.join(", ")) : null),
          el("div", { class: "preview-body" }, body)),
        el("p", { class: "small muted" }, "After you press Send you have a few seconds to undo it."),
        el("div", { class: "row" }, sendBtn, el("button", { onclick: () => close(false) }, "Keep editing")));
      let done = false;
      function close(ok) {
        if (done) return;
        done = true;
        dlg.close();
        dlg.remove();
        resolve(ok);
      }
      dlg.addEventListener("cancel", (e) => { e.preventDefault(); close(false); });
      document.body.appendChild(dlg);
      dlg.showModal();
      sendBtn.focus();
    });
  }

  /**
   * The undo bar after Send: the message goes out when the countdown ends unless she presses Undo. It stays on
   * screen across pages, then says whether it was sent. The server holds the actual wait, so closing the page
   * doesn't stop or lose the send.
   */
  const undoBars = new Map();
  function sendingBar(draftId, brand, sendAt) {
    if (undoBars.has(draftId)) return;
    let stack = document.getElementById("undo-bars");
    if (!stack) stack = document.body.appendChild(el("div", { id: "undo-bars", class: "undo-bars" }));
    const text = el("span", {});
    const undo = el("button", { class: "small", onclick: action(async () => {
      const r = await api("POST", "/api/drafts/" + draftId + "/undo-send");
      bar.finish();
      if (r.undone) {
        toast("Not sent. It's back in Drafts.");
        showDraft({ id: draftId });
      } else {
        await check();
      }
    }) }, "Undo");
    const bar = stack.appendChild(el("div", { class: "undo-bar", role: "status" }, text, undo));
    const end = Date.now() + Math.max(0, new Date(sendAt).getTime() - Date.now());
    const tick = () => {
      const left = Math.ceil((end - Date.now()) / 1000);
      if (left > 0) { text.textContent = "Sending to " + brand + " in " + left + "s"; return; }
      text.textContent = "Sending to " + brand + "…";
      undo.disabled = true;
      clearInterval(timer);
      check();
    };
    const timer = setInterval(tick, 250);
    tick();
    let tries = 0;
    async function check() {
      let st;
      try { st = await api("GET", "/api/drafts/" + draftId + "/send-status"); } catch (err) { st = { state: "unknown" }; }
      if (st.state === "waiting" && ++tries < 60) { setTimeout(check, 1000); return; }
      bar.finish();
      if (st.state === "sent") toast("Sent to " + brand + " ✓");
      else if (st.state === "failed") toast("Not sent to " + brand + ": " + st.error, true);
      else if (st.state !== "waiting" && st.state !== "unknown") toast("Not sent to " + brand + ". It's still in Drafts.", true);
      if (location.hash.startsWith("#drafts")) route();
    }
    bar.finish = () => { clearInterval(timer); bar.remove(); if (undoBars.get(draftId) === bar) undoBars.delete(draftId); };
    undoBars.set(draftId, bar);
  }

  async function renderDrafts(root) {
    const list = await api("GET", "/api/drafts");
    clear(root);
    root.classList.remove("reading-draft");
    root.appendChild(el("h1", {}, "Drafts awaiting approval"));
    root.appendChild(el("p", { class: "muted" }, "Nothing is sent until you press Send. Edit freely first."));
    if (!list.length) { root.appendChild(card(null, emptyLine("No drafts waiting."))); return; }
    const declines = list.filter((x) => x.draft.type === "DECLINE");
    if (declines.length > 1) {
      root.appendChild(el("div", { class: "row card", id: "send-declines" },
        el("span", {}, declines.length + " polite declines are ready in the list. Read them, then send them all at once."),
        el("div", { class: "spacer" }),
        el("button", { class: "primary small", onclick: action(async () => {
          if (!confirm("Send all " + declines.length + " declines now? Each deal is closed as declined.")) return;
          const r = await api("POST", "/api/drafts/send-declines");
          toast(r.done + " sent" + (r.skipped.length ? ". Not sent: " + r.skipped.join("; ") : ""), r.skipped.length > 0);
          route();
        }) }, "Approve all declines")));
    }
    // An inbox: a short list, with one draft open beside it (full screen on a phone). Every draft's card is built once
    // and only hidden, so text typed into a draft survives filtering, sorting and opening another one.
    const df = draftsFilter;
    const save = () => savePrefs("drafts", df);
    const groups = {
      all: () => true,
      pitch: (d) => d.type === "PITCH" || d.type === "REPITCH",
      followup: (d) => d.type === "FOLLOW_UP",
      money: (d) => d.type === "PAYMENT_REMINDER" || d.type === "INVOICE",
      reply: (d) => !["PITCH", "REPITCH", "FOLLOW_UP", "PAYMENT_REMINDER", "INVOICE"].includes(d.type),
    };
    const present = Object.keys(groups).filter((g) => g === "all" || list.some((x) => groups[g](x.draft)));
    if (!present.includes(df.type)) df.type = "all";
    const groupNames = { all: "All", pitch: "Pitches", followup: "Follow-ups", money: "Payment", reply: "Replies" };
    const search = searchBox(df.q, "Search brand or text…", (v) => { df.q = v; save(); draw(); });
    const chips = el("div", {});
    const order = el("select", { "aria-label": "Order", onchange: (e) => { df.order = e.target.value; save(); draw(); } },
      [["oldest", "Oldest first"], ["newest", "Newest first"], ["brand", "Brand A–Z"]].map(([v, l]) => el("option", { value: v, selected: df.order === v }, l)));
    const shownAt = el("div", {});
    const rows = el("ul", { class: "inbox-rows", "aria-label": "Drafts" });
    const holder = el("div", {});
    const inbox = el("div", { class: "inbox" },
      el("nav", { class: "inbox-list" }, rows),
      el("section", { class: "inbox-detail" },
        el("button", { class: "small inbox-back", onclick: () => { reading(false); history.replaceState(null, "", "#drafts"); window.scrollTo(0, 0); } },
          "← All drafts"),
        holder));
    if (list.length > 1) root.appendChild(el("div", { class: "row filters" }, el("div", { class: "spacer" }, search), chips, order));
    root.append(shownAt, inbox);
    // On a phone, an open draft fills the screen: the heading, filters and list step aside until Back.
    const reading = (on) => { inbox.classList.toggle("showing-detail", on); root.classList.toggle("reading-draft", on); };
    reading(false);
    const cards = [];
    for (const { draft: d, brand, blockedReason, sendingAt, sendError } of list) {
      // Already on its way (Send was pressed moments ago, maybe before a refresh): the undo bar shows the countdown.
      if (sendingAt) sendingBar(d.id, brand, sendingAt);
      const subject = el("input", { value: d.subject || "", maxlength: "1000" });
      const body = el("textarea", { class: "tall", maxlength: "20000" });
      body.value = d.body;
      const edits = () => ({ subject: subject.value, body: body.value });
      // Blanks like [RATE FOR 1 REEL] that Claude left for her. The server refuses to send while any remain.
      const blanksNote = el("div", { class: "alert warn hidden", role: "status" });
      const checkBlanks = () => {
        const found = blanksIn(body.value); // not the subject: brands' mail adds tags like [EXTERNAL]
        blanksNote.textContent = found.length ? (found.length === 1 ? "Fill in this blank before sending: " : "Fill in these blanks before sending: ") + found.join(", ") : "";
        blanksNote.classList.toggle("hidden", !found.length);
        return found;
      };
      const preview = el("span", { class: "inbox-preview" });
      const blankMark = el("span", { class: "badge medium hidden" }, "Has a blank");
      const showPreview = () => {
        preview.textContent = body.value.replace(/\s+/g, " ").trim().slice(0, 140);
        blankMark.classList.toggle("hidden", !blanksIn(body.value).length);
      };
      body.addEventListener("input", () => { checkBlanks(); showPreview(); });
      checkBlanks();
      showPreview();
      const row = el("li", {}, el("button", { class: "inbox-row", onclick: () => select(d.id, true) },
        el("span", { class: "inbox-top" }, el("strong", {}, brand), el("span", { class: "small muted" }, fmtDate(d.createdAt))),
        el("span", { class: "inbox-type small" }, pretty(d.type) + (d.channel === "EMAIL" ? "" : " · Instagram"), blankMark,
          sendingAt ? el("span", { class: "badge" }, "Sending") : null),
        preview));
      const node = holder.appendChild(card(null,
        el("div", { class: "row" }, el("h3", {}, brand + " — " + pretty(d.type)), el("div", { class: "spacer" }),
          el("span", { class: "badge" }, d.channel === "EMAIL" ? "Email" : "Instagram DM")),
        el("div", { class: "small muted" }, "To: " + (d.toAddress || "—") + (d.gmailDraftId ? " · also saved in your Gmail Drafts" : "")),
        d.invoiceId ? el("div", { class: "small" }, "📎 ", el("a", { href: "/api/invoices/" + d.invoiceId + "/pdf", target: "_blank", rel: "noopener" }, "Invoice PDF"), " is attached") : null,
        d.resultId && d.channel === "EMAIL" ? el("div", { class: "small" }, "📎 ", el("a", { href: "/api/opportunities/" + d.opportunityId + "/results/pdf", target: "_blank", rel: "noopener" }, "Results PDF"), " is attached") : null,
        d.type === "PAYMENT_REMINDER" ? el("div", { class: "small muted" }, "Payment reminders always wait for you here, even when follow-ups are sent automatically.") : null,
        d.type === "REPITCH" ? el("div", { class: "small muted" }, "A new email to a brand you've worked with before. Sending it adds a new pitch for " + brand + " to your pipeline, with follow-ups like any pitch.") : null,
        d.channel === "EMAIL" ? el("div", {}, el("label", {}, "Subject"), subject) : null,
        el("label", {}, "Message"), body,
        askClaude(d, subject, body),
        blanksNote,
        blockedReason ? el("div", { class: "alert info" }, blockedReason) : null,
        sendError ? el("div", { class: "alert error" }, "Your last Send didn't go out: " + sendError) : null,
        sendingAt ? el("div", { class: "alert info" }, "This is being sent. Press Undo at the bottom of the screen to stop it.") : null,
        el("div", { class: "row" + (sendingAt ? " hidden" : "") },
          blockedReason ? null : el("button", { class: "primary", onclick: action(async () => {
            if (checkBlanks().length) { body.focus(); throw new Error(blanksNote.textContent); }
            if (!await sendPreview(d, brand, subject.value, body.value)) return;
            const q = await api("POST", "/api/drafts/" + d.id + "/send", edits());
            sendingBar(d.id, brand, q.sendAt);
            route();
          }) }, "Send"),
          el("button", { onclick: action(async () => { await navigator.clipboard.writeText(body.value); }, "Copied") }, "Copy"),
          el("button", { onclick: action(async () => { await api("POST", "/api/drafts/" + d.id + "/sent-manually"); route(); }, "Recorded as sent") }, "I sent it myself"),
          el("button", { onclick: action(async () => { await api("PUT", "/api/drafts/" + d.id, edits()); }, "Saved") }, "Save edits"),
          el("div", { class: "spacer" }),
          el("button", { class: "danger", onclick: action(async () => { await api("POST", "/api/drafts/" + d.id + "/discard"); route(); }, "Discarded") }, "Discard"),
          el("button", { onclick: () => openDeal(d.opportunityId) }, "Open deal"))));
      cards.push({ node, row, d, brand, subject, body });
    }

    // Which draft opens first: the one asked for (#drafts?id=, or ?invoice= for its reminder), else the top of the list.
    // On a phone, the list shows first unless a particular draft was asked for.
    const q = new URLSearchParams(location.hash.split("?")[1] || "");
    const byId = (id) => cards.find((c) => String(c.d.id) === String(id));
    const asked = q.has("id") ? byId(q.get("id"))
      : q.has("invoice") ? cards.find((c) => String(c.d.invoiceId) === q.get("invoice") && c.d.type === "PAYMENT_REMINDER")
      : null;
    let selected = asked ? asked.d.id : null;
    if (asked) {
      reading(true);
      if (!(groups[df.type](asked.d) && matchesQuery(df.q, asked.brand, pretty(asked.d.type), asked.subject.value, asked.body.value, asked.d.toAddress))) {
        df.q = ""; df.type = "all"; search.value = ""; save();
      }
    }

    function select(id, opened) {
      selected = id;
      for (const c of cards) {
        const on = c.d.id === id;
        c.node.classList.toggle("hidden", !on);
        c.row.firstChild.classList.toggle("active", on);
        if (on) c.row.firstChild.setAttribute("aria-current", "true"); else c.row.firstChild.removeAttribute("aria-current");
      }
      if (opened) {
        reading(true);
        // Remembered in the address so a refresh keeps it open. replaceState doesn't fire hashchange, so nothing is rebuilt.
        history.replaceState(null, "", "#drafts?id=" + id);
        if (matchMedia("(max-width: 760px)").matches) window.scrollTo(0, 0);
      }
    }

    function draw() {
      clear(chips).appendChild(chipRow(present.map((g) => [g, groupNames[g]]), df.type, (v) => { df.type = v; save(); draw(); }, "Draft type"));
      const sorted = sortRows(cards, df.order === "brand" ? { by: "brand", dir: "asc" } : { by: "at", dir: df.order === "newest" ? "desc" : "asc" },
        { brand: (c) => c.brand, at: (c) => c.d.createdAt });
      const visible = [];
      for (const c of sorted) {
        const keep = groups[df.type](c.d) && matchesQuery(df.q, c.brand, pretty(c.d.type), c.subject.value, c.body.value, c.d.toAddress);
        c.row.classList.toggle("hidden", !keep);
        if (keep) visible.push(c);
        rows.appendChild(c.row);
      }
      const shown = visible.length;
      // When the filters hide the open draft, the first one still shown opens instead
      select(visible.some((c) => c.d.id === selected) ? selected : visible.length ? visible[0].d.id : null, false);
      inbox.classList.toggle("hidden", !shown);
      clear(shownAt);
      const line = shownLine(shown, cards.length, "drafts", () => { df.q = ""; df.type = "all"; search.value = ""; save(); draw(); });
      if (line) shownAt.appendChild(line);
      if (!shown) shownAt.appendChild(card(null, emptyLine("No drafts match.")));
    }
    draw();
  }

  // ---------- Day summary ----------

  const DONE_ICONS = { TASK: "✔️", SENT: "📤", REPLY: "💬", MONEY: "💸", PITCH: "📣" };
  const COMP_BADGES = { PAID: ["Paid", "badge ok"], GIFTED: ["Gifted", "badge accent"], AFFILIATE: ["Affiliate", "badge medium"] };

  async function renderSummary(root) {
    const s = await api("GET", "/api/summary/eod");
    clear(root);
    const n = (count, one, many) => count + " " + (count === 1 ? one : many);
    const goTo = (id) => () => document.getElementById(id).scrollIntoView({ behavior: "smooth", block: "start" });
    const waiting = s.pending.length + s.followUps.length;

    const outlook = [];
    if (waiting) outlook.push(n(waiting, "thing is", "things are") + " still waiting for you");
    if (s.tomorrowItems.length) outlook.push(n(s.tomorrowItems.length, "is", "are") + " lined up for tomorrow");
    const sub = outlook.length ? outlook.join(", and ").replace(/^./, (c) => c.toUpperCase()) + "."
      : "Nothing is waiting on you. Enjoy your evening!";

    root.appendChild(el("div", { class: "card eod-hero" },
      el("div", { class: "eod-date" }, new Date(s.date + "T00:00:00").toLocaleDateString(undefined, { weekday: "long", month: "long", day: "numeric" })),
      el("h1", {}, s.headline),
      el("p", { class: "muted" }, sub),
      el("div", { class: "eod-stats" },
        eodStat("✅", s.done.length, "Done today", "ok", goTo("eod-done")),
        eodStat("⏳", waiting, "Still to do", waiting ? "warn" : "ok", goTo("eod-waiting")),
        eodStat("💰", s.newDeals.length, s.newDeals.length === 1 ? "New deal" : "New deals", "accent", goTo("eod-new")),
        eodStat("🌅", s.tomorrowItems.length, "For tomorrow", "plain", goTo("eod-tomorrow"))),
      s.draftsWaiting ? el("div", { class: "eod-nudge" },
        el("span", {}, "✉️ " + n(s.draftsWaiting, "draft is", "drafts are") + " ready for you to read and send."),
        el("button", { class: "small primary", onclick: () => { location.hash = "#drafts"; } }, "Review drafts")) : null));

    const doneCard = card("✅ What you got done",
      s.done.length ? showMore(s.done, 6, (items) => el("ul", { class: "list eod-done" }, items.map((d) => el("li", { class: "item" },
        el("span", { class: "eod-icon", "aria-hidden": "true" }, DONE_ICONS[d.kind] || "✔️"),
        el("div", { class: "body" },
          el("div", { class: "title" }, d.text),
          el("div", { class: "detail" }, d.brand && !d.text.includes(d.brand) ? d.brand + " · " : "",
            new Date(d.at).toLocaleTimeString(undefined, { hour: "numeric", minute: "2-digit" }))),
        d.opportunityId ? el("div", { class: "actions" }, el("button", { class: "small", onclick: () => openDeal(d.opportunityId) }, "Open")) : null))))
        : emptyLine("Nothing ticked off yet. When you finish a task, send a message or a brand writes back, it shows up here."));
    doneCard.id = "eod-done";

    const tomorrowCard = card("🌅 Lined up for tomorrow",
      s.tomorrowItems.length ? itemList(s.tomorrowItems, "", false)
        : emptyLine("Nothing is due tomorrow yet." + (waiting ? " A good start is whatever is still waiting below." : "")));
    tomorrowCard.id = "eod-tomorrow";
    root.appendChild(el("div", { class: "eod-grid" }, doneCard, tomorrowCard));

    const waitingCard = card("⏳ Still waiting on you");
    waitingCard.id = "eod-waiting";
    if (!waiting) waitingCard.appendChild(emptyLine("All clear. Nothing is waiting on you. 🎉"));
    if (s.pending.length) {
      waitingCard.appendChild(el("p", { class: "small muted" }, "Most important first. Press Done when you've handled one."));
      waitingCard.appendChild(showMore(s.pending, 5, (items) => itemList(items, "", false)));
    }
    if (s.followUps.length) {
      waitingCard.appendChild(el("h4", { class: "eod-sub" }, "📌 Follow-ups to send"));
      waitingCard.appendChild(showMore(s.followUps, 5, followUpList));
    }
    root.appendChild(waitingCard);

    const newCard = card("💰 New deals today",
      s.newDeals.length ? showMore(s.newDeals, 6, (items) => el("ul", { class: "list" }, items.map((d) => {
        const [label, cls] = COMP_BADGES[d.compensation] || ["Not sure yet", "badge"];
        return el("li", { class: "item" },
          el("div", { class: "body" },
            el("div", { class: "title" }, d.brand),
            el("div", { class: "detail" }, el("span", { class: cls }, label), " ",
              [d.type === label ? null : d.type, d.budget].filter(Boolean).join(" · "))),
          el("div", { class: "actions" }, el("button", { class: "small", onclick: () => openDeal(d.opportunityId) }, "Open")));
      })))
        : emptyLine("No new brands reached out today."));
    newCard.id = "eod-new";
    root.appendChild(newCard);
  }

  function eodStat(icon, value, label, tone, onclick) {
    return el("button", { class: "eod-stat " + tone, onclick },
      el("span", { class: "eod-stat-icon", "aria-hidden": "true" }, icon),
      el("span", { class: "eod-stat-v" }, String(value)),
      el("span", { class: "eod-stat-l" }, label));
  }

  /** Render the first few items, with a button that shows the rest. */
  function showMore(items, limit, render) {
    const box = el("div", {});
    const draw = (all) => {
      clear(box);
      box.appendChild(render(all ? items : items.slice(0, limit)));
      if (!all && items.length > limit) {
        box.appendChild(el("button", { class: "small eod-more", onclick: () => draw(true) }, "Show " + (items.length - limit) + " more"));
      }
    };
    draw(false);
    return box;
  }

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
    ["accounts", "Accounts", "Claude, Gmail, Google Calendar and Instagram"],
    ["you", "You", "Your name, voice, rates and to-do rules"],
    ["deals", "Deals & money", "Follow-ups, invoices, rates, contracts, rebooking"],
    ["app", "App", "Updates, backups, Claude spending, password"],
    ["advanced", "Advanced", "Claude models, extra connections, error reports, restoring a backup"],
  ];
  // Older links (and the OAuth return pages) name a card, not a tab.
  const SETTINGS_TAB_OF = { spend: "app", backup: "app", gmail: "accounts", instagram: "accounts", facebook: "advanced" };

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
      fb.connected ? el("p", { class: "small" }, "✅ Connected through the Facebook Page “" + fb.page + "”.") : null));

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
      el("p", { class: "small muted" }, s.mcpAllowSend ? "⚠️ Sending via MCP is enabled." : "MCP can draft but not send; you approve sends here.")));

    const [spend, learning, startup, errorReports, autoBackup] = await Promise.all([claudeSpendCard(root), learningCard(root),
      startWithWindowsCard(), errorReportsCard(root, c), autoBackupCard(root)]);
    if (stale()) return;
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
    return el("div", { class: "row" }, el("span", { class: "small" }, "📊 " + text), el("button", { class: "small", onclick: action(async () => {
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
    b.appendChild(el("span", {}, el("strong", {}, a.level === "OUT" ? "⚠️ Out of Claude credits. " : "Claude credits low. "),
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
    const names = { CLASSIFY: "Reading messages", DRAFT: "Writing drafts", REVISE: "Changing drafts", RESEARCH: "Finding brands", CONTRACT: "Checking contracts" };
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


  // ---------- updates, What's New, Help ----------
  // The app checks GitHub for a new release; on the Windows app "Update now" backs up, installs and reopens.

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
    if (location.hash.replace(/^#/, "")) return; // a link or bookmark to a page wins
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

  async function renderMore(root) {
    clear(root);
    root.appendChild(el("h1", {}, "More"));
    const link = (tab, title, detail) => el("a", { class: "more-link", href: "#" + tab, "data-tab": tab },
      el("span", { class: "title" }, title), el("span", { class: "detail" }, detail));
    root.appendChild(el("div", { class: "card more-list" },
      link("summary", "Day summary", "What you got done today and what's lined up for tomorrow"),
      link("links", "My links", "Your Instagram, TikTok, website and media kit, used in drafts"),
      link("settings", "Settings", "Accounts, your rates and voice, follow-ups, backups"),
      link("setup", "Setup guide", "Connect Claude and Gmail one step at a time"),
      link("help", "Help", "Short videos for every feature, and Report a problem"),
      link("whatsnew", "What's new", "The latest changes to the app")));
    root.appendChild(el("div", { class: "row" },
      el("button", { onclick: syncNow, title: "New emails and DMs are also checked by themselves every 30 minutes" }, "Check for new messages now"),
      el("div", { class: "spacer" }),
      el("button", { class: "danger", onclick: signOut }, "Sign out")));
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
        el("span", {}, el("strong", {}, "⚠️ " + name + " stopped connecting on " + when + ". "),
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
  maybeShowWhatsNew().then(maybeShowSetup).then(() => api("GET", "/api/statuses")).then((s) => { statuses = s; route(); });
})();
