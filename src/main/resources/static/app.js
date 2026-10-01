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

  function pretty(s) {
    if (!s) return "";
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

  // ---------- routing ----------

  const views = { today: renderToday, pipeline: renderPipeline, outreach: renderOutreach,
                  drafts: renderDrafts, summary: renderSummary, settings: renderSettings };
  let statuses = {};

  function currentTab() {
    const h = location.hash.replace(/^#/, "").split("?")[0];
    return views[h] ? h : "today";
  }

  async function route() {
    const tab = currentTab();
    document.querySelectorAll(".tab").forEach((b) => b.classList.toggle("active", b.dataset.tab === tab));
    document.querySelectorAll(".view").forEach((v) => v.classList.toggle("hidden", v.id !== "view-" + tab));
    const q = new URLSearchParams(location.hash.split("?")[1] || "");
    const notes = {
      "gmail=connected": "Gmail connected.", "gmail=denied": "Gmail access was not granted.",
      "gmail=failed": "Gmail connection failed. Check the client ID/secret and redirect URI.",
      "gmail=no-refresh-token": "Google didn't return a refresh token. Remove the app's access in your Google account and try again.",
      "gmail=state-mismatch": "Gmail connection expired. Please try again.",
      "instagram=connected": "Instagram connected.", "instagram=denied": "Instagram access was not granted.",
      "instagram=failed": "Instagram connection failed. Check the app ID/secret and redirect URI.",
      "instagram=state-mismatch": "Instagram connection expired. Please try again.",
    };
    for (const [k, v] of q.entries()) {
      const msg = notes[k + "=" + v];
      if (msg) toast(msg, /failed|denied|no-refresh|mismatch/.test(v));
    }
    try {
      await views[tab](document.getElementById("view-" + tab));
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
    root.appendChild(el("div", { class: "stats card" },
      stat(t.urgent.length, "High priority"), stat(t.followUps.length, "Follow-ups due"),
      stat(t.approvals.length, "Drafts to approve"), stat(pipelineTotal, "Deals in pipeline"),
      stat(t.openPipelineValue ? "$" + Number(t.openPipelineValue).toLocaleString() : "—", "Open pipeline value")));

    root.appendChild(card("🔥 Today — high priority", itemList(t.urgent, "Nothing urgent. Nice.", true)));
    root.appendChild(card("📌 Follow-ups", followUpList(t.followUps)));

    if (t.approvals.length) {
      root.appendChild(card("✉️ Drafts awaiting your approval",
        el("ul", { class: "list" }, t.approvals.map((a) => el("li", { class: "item" },
          el("div", { class: "body" }, el("div", { class: "title" }, a.brand + " — " + pretty(a.type)),
            el("div", { class: "detail" }, a.preview)),
          el("div", { class: "actions" }, el("button", { class: "small primary", onclick: () => { location.hash = "#drafts"; } }, "Review")))))));
    }

    const o = t.newOpportunities;
    root.appendChild(card("💰 New opportunities (last 24h)",
      el("p", {}, o.paid + " paid · " + o.gifted + " gifted · " + o.affiliate + " affiliate · " + o.other + " other"),
      o.items.length ? el("ul", {}, o.items.map((s) => el("li", {}, s))) : null));

    root.appendChild(card("📅 Upcoming", itemList(t.upcoming, "Nothing scheduled in the next two weeks.", false)));
  }

  function stat(v, l) { return el("div", { class: "stat" }, el("div", { class: "v" }, String(v)), el("div", { class: "l" }, l)); }

  function itemList(items, emptyText, numbered) {
    if (!items.length) return emptyLine(emptyText);
    return el("ul", { class: "list" }, items.map((it, i) => el("li", { class: "item" },
      numbered ? el("span", { class: "num" }, (i + 1) + ".") : null,
      el("div", { class: "body" },
        el("div", { class: "title" }, it.title),
        el("div", { class: "detail" },
          it.overdueDays > 0 ? el("span", { class: "badge overdue" }, "Overdue " + it.overdueDays + "d") : null, " ",
          it.priority === "HIGH" && !it.overdueDays ? el("span", { class: "badge high" }, "High") : null, " ",
          it.detail)),
      el("div", { class: "actions" },
        it.kind === "TASK" ? el("button", { class: "small", onclick: action(async () => { await api("POST", "/api/tasks/" + it.refId + "/done"); route(); }, "Marked done") }, "Done") : null,
        it.kind === "DEADLINE" ? el("button", { class: "small", onclick: action(async () => { await api("POST", "/api/deadlines/" + it.refId + "/done"); route(); }, "Deadline cleared") }, "Done") : null,
        it.opportunityId ? el("button", { class: "small", onclick: () => openDeal(it.opportunityId) }, "Open") : null))));
  }

  function followUpList(items) {
    if (!items.length) return emptyLine("No follow-ups due today.");
    return el("ul", { class: "list" }, items.map((it) => el("li", { class: "item" },
      el("div", { class: "body" }, el("div", { class: "title" }, it.title),
        el("div", { class: "detail" }, it.overdueDays > 0 ? el("span", { class: "badge overdue" }, it.detail) : it.detail)),
      el("div", { class: "actions" },
        el("button", { class: "small", title: "Write a follow-up draft", onclick: action(async () => {
          await api("POST", "/api/opportunities/" + it.opportunityId + "/drafts", { type: "FOLLOW_UP", instructions: it.title });
          location.hash = "#drafts";
        }, "Draft created") }, "Draft"),
        el("button", { class: "small", title: "I already sent it", onclick: action(async () => {
          await api("POST", "/api/opportunities/" + it.opportunityId + "/followups/sent"); route();
        }, "Follow-up recorded") }, "Sent"),
        el("button", { class: "small", onclick: () => openDeal(it.opportunityId) }, "Open")))));
  }

  // ---------- Pipeline ----------

  let pipelineFilter = { status: "", closed: false };

  async function renderPipeline(root) {
    const rows = await api("GET", "/api/pipeline?includeClosed=" + pipelineFilter.closed);
    clear(root);
    const statusSelect = el("select", { onchange: (e) => { pipelineFilter.status = e.target.value; renderPipeline(root); } },
      el("option", { value: "" }, "All statuses"),
      Object.entries(statuses).map(([k, v]) => el("option", { value: k, selected: pipelineFilter.status === k }, v)));
    const closedBox = el("input", { type: "checkbox", checked: pipelineFilter.closed,
      onchange: (e) => { pipelineFilter.closed = e.target.checked; renderPipeline(root); } });
    root.appendChild(el("div", { class: "row" }, el("h1", {}, "Pipeline"), el("div", { class: "spacer" }),
      el("div", {}, statusSelect), el("label", { class: "row" }, closedBox, "Show closed")));

    const shown = rows.filter((r) => !pipelineFilter.status || r.status === pipelineFilter.status);
    const counts = {};
    rows.forEach((r) => { counts[r.statusLabel] = (counts[r.statusLabel] || 0) + 1; });
    root.appendChild(el("div", { class: "row card" }, Object.entries(counts).map(([k, v]) => el("span", { class: "badge" }, k + " · " + v))));

    if (!shown.length) { root.appendChild(card(null, emptyLine("No deals yet. They appear here as brand emails and DMs come in, or when you log a pitch."))); return; }
    root.appendChild(el("div", { class: "card table-wrap" }, el("table", {},
      el("thead", {}, el("tr", {}, ["Brand", "Status", "Deal", "Budget", "Next follow-up", "Open tasks", "Updated"].map((h) => el("th", {}, h)))),
      el("tbody", {}, shown.map((r) => el("tr", { class: "clickable", onclick: () => openDeal(r.id) },
        el("td", {}, el("strong", {}, r.brand)),
        el("td", {}, r.statusLabel),
        el("td", {}, pretty(r.type) + " · " + pretty(r.compensation)),
        el("td", {}, r.budget || "—"),
        el("td", {}, r.nextFollowUp ? "#" + r.nextFollowUpNumber + " · " + fmtDate(r.nextFollowUp) : "—"),
        el("td", {}, String(r.openTasks)),
        el("td", { class: "muted" }, fmtDate(r.updatedAt))))))));
  }

  // ---------- Deal drawer ----------

  function closeDrawer() {
    document.getElementById("drawer").classList.add("hidden");
    document.getElementById("drawer-backdrop").classList.add("hidden");
  }

  async function openDeal(id) {
    const d = await api("GET", "/api/opportunities/" + id);
    const drawer = clear(document.getElementById("drawer"));
    document.getElementById("drawer-backdrop").classList.remove("hidden");
    drawer.classList.remove("hidden");
    const o = d.opportunity;
    const refresh = () => { openDeal(id); route(); };

    drawer.appendChild(el("div", { class: "row" }, el("h1", {}, d.summary.brand), el("div", { class: "spacer" }),
      el("button", { class: "small", onclick: closeDrawer }, "Close")));

    const statusSel = el("select", { onchange: action(async (e) => { await api("PATCH", "/api/opportunities/" + id, { status: e.target.value }); refresh(); }, "Status updated") },
      Object.entries(statuses).map(([k, v]) => el("option", { value: k, selected: o.status === k }, v)));
    drawer.appendChild(card(null,
      el("label", {}, "Status"), statusSel,
      facts([["Type", pretty(o.type)], ["Compensation", pretty(o.compensation)], ["Budget", o.budgetText],
             ["Deliverables", o.deliverables], ["Usage rights", o.usageRights], ["Campaign", o.campaign],
             ["Still unknown", o.missingInfo], ["Next step", o.nextStep], ["Origin", pretty(o.origin)],
             ["Contact", d.brand && [d.brand.contactName, d.brand.contactEmail, d.brand.instagram && "@" + d.brand.instagram].filter(Boolean).join(" · ")]])));

    // Drafting
    const typeSel = el("select", {}, ["REPLY", "RATES", "MEDIA_KIT", "NEGOTIATION", "FOLLOW_UP", "ASK_BUDGET", "ASK_USAGE_RIGHTS",
      "ASK_DETAILS", "CONTRACT_CONFIRMATION", "CONTENT_SUBMISSION", "PRODUCT_ARRIVAL", "DECLINE", "OTHER"].map((t) => el("option", { value: t }, pretty(t))));
    const instr = el("input", { placeholder: "Optional guidance, e.g. 'ask for 50% upfront'", maxlength: "1000" });
    drawer.appendChild(card("Write a message",
      el("div", { class: "row" }, typeSel, instr),
      el("p", {}, el("button", { class: "primary small", onclick: action(async () => {
        await api("POST", "/api/opportunities/" + id + "/drafts", { type: typeSel.value, instructions: instr.value });
        closeDrawer(); location.hash = "#drafts"; route();
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
          el("div", { class: "detail" }, pretty(t.status) + (t.dueDate ? " · due " + fmtDate(t.dueDate) : ""))),
        t.status === "OPEN" ? el("div", { class: "actions" },
          el("button", { class: "small", onclick: action(async () => { await api("POST", "/api/tasks/" + t.id + "/done"); refresh(); }, "Done") }, "Done"),
          el("button", { class: "small", onclick: action(async () => { await api("POST", "/api/tasks/" + t.id + "/dismiss"); refresh(); }) }, "Dismiss")) : null))) : emptyLine("No tasks."),
      el("div", { class: "row" }, taskInput, taskDue, el("button", { class: "small", onclick: action(async () => {
        if (!taskInput.value.trim()) return;
        await api("POST", "/api/tasks", { opportunityId: id, description: taskInput.value, dueDate: taskDue.value || null });
        refresh();
      }, "Task added") }, "Add"))));

    if (d.deadlines.length) {
      drawer.appendChild(card("Deadlines", el("ul", { class: "list" }, d.deadlines.map((x) => el("li", { class: "item" },
        el("div", { class: "body" }, el("div", { class: "title" }, pretty(x.type) + " — " + fmtDate(x.dueDate)),
          el("div", { class: "detail" }, (x.done ? "✓ done · " : "") + (x.description || ""))))))));
    }

    drawer.appendChild(card("Conversation",
      d.messages.length ? d.messages.map((m) => el("div", { class: "msg" + (m.direction === "OUTBOUND" ? " out" : "") },
        el("div", { class: "meta" }, (m.direction === "OUTBOUND" ? "You" : (m.from || "Brand")) + " · " + fmtDateTime(m.sentAt)
          + (m.type ? " · " + pretty(m.type) : "")),
        m.subject ? el("div", { class: "title small" }, m.subject) : null,
        el("div", { class: "text" }, m.content || ""))) : emptyLine("No messages linked (e.g. a pitch logged manually).")));

    drawer.appendChild(card("Activity", d.activity.length ? el("ul", { class: "list" }, d.activity.slice(0, 30).map((a) =>
      el("li", { class: "item" }, el("div", { class: "body" }, el("div", {}, a.text), el("div", { class: "detail" }, fmtDateTime(a.at)))))) : emptyLine("—")));
  }

  function facts(pairs) {
    return el("table", {}, el("tbody", {}, pairs.filter(([, v]) => v).map(([k, v]) => el("tr", {}, el("th", {}, k), el("td", {}, v)))));
  }

  // ---------- Outreach ----------

  async function renderOutreach(root) {
    const rows = await api("GET", "/api/pitches");
    clear(root);
    root.appendChild(el("h1", {}, "Outreach"));
    root.appendChild(el("p", { class: "muted" }, "Every brand you've pitched, with automatic follow-up dates. Pitches you send from Gmail are detected automatically; log the rest here."));

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
    root.appendChild(card("Log a pitch", el("div", { class: "grid" },
      el("div", {}, el("label", {}, "Brand *"), f.brand), el("div", {}, el("label", {}, "Contact name"), f.contactName),
      el("div", {}, el("label", {}, "Contact email"), f.contactEmail), el("div", {}, el("label", {}, "Instagram"), f.instagram),
      el("div", {}, el("label", {}, "Platform"), f.platform), el("div", {}, el("label", {}, "Date pitched"), f.pitchedAt)),
      el("label", {}, "What you pitched"), f.opportunity,
      el("p", {}, el("button", { class: "primary", onclick: () => { if (f.brand.value.trim()) submit(false); else toast("Brand is required", true); } }, "Log pitch"))));

    if (!rows.length) { root.appendChild(card(null, emptyLine("No pitches yet."))); return; }
    const n = rows[0].followUps.length;
    root.appendChild(el("div", { class: "card table-wrap" }, el("table", {},
      el("thead", {}, el("tr", {}, ["Brand", "Contact", "Pitched", "Platform", "Opportunity", "Response"].concat(
        Array.from({ length: n }, (_, i) => "FU #" + (i + 1))).concat(["Status"]).map((h) => el("th", {}, h)))),
      el("tbody", {}, rows.map((r) => el("tr", { class: "clickable", onclick: () => openDeal(r.opportunityId) },
        el("td", {}, el("strong", {}, r.brand)), el("td", {}, r.contact || ""), el("td", {}, fmtDate(r.pitchedAt)),
        el("td", {}, pretty(r.platform)), el("td", {}, r.opportunity || ""), el("td", {}, r.initialResponse ? pretty(r.initialResponse) : "—"),
        r.followUps.map((x) => el("td", { class: x.startsWith("due") ? "" : "muted" }, x || "")),
        el("td", {}, r.status)))))));
  }

  // ---------- Drafts ----------

  async function renderDrafts(root) {
    const list = await api("GET", "/api/drafts");
    clear(root);
    root.appendChild(el("h1", {}, "Drafts awaiting approval"));
    root.appendChild(el("p", { class: "muted" }, "Nothing is sent until you press Send. Edit freely first."));
    if (!list.length) { root.appendChild(card(null, emptyLine("No drafts waiting."))); return; }
    for (const { draft: d, brand, blockedReason } of list) {
      const subject = el("input", { value: d.subject || "", maxlength: "1000" });
      const body = el("textarea", { class: "tall", maxlength: "20000" });
      body.value = d.body;
      const edits = () => ({ subject: subject.value, body: body.value });
      root.appendChild(card(null,
        el("div", { class: "row" }, el("h3", {}, brand + " — " + pretty(d.type)), el("div", { class: "spacer" }),
          el("span", { class: "badge" }, d.channel === "EMAIL" ? "Email" : "Instagram DM")),
        el("div", { class: "small muted" }, "To: " + (d.toAddress || "—") + (d.gmailDraftId ? " · also saved in your Gmail Drafts" : "")),
        d.channel === "EMAIL" ? el("div", {}, el("label", {}, "Subject"), subject) : null,
        el("label", {}, "Message"), body,
        blockedReason ? el("div", { class: "alert info" }, blockedReason) : null,
        el("div", { class: "row" },
          blockedReason ? null : el("button", { class: "primary", onclick: action(async () => {
            if (!confirm("Send this " + (d.channel === "EMAIL" ? "email" : "DM") + " to " + brand + " now?")) return;
            await api("POST", "/api/drafts/" + d.id + "/send", edits()); route();
          }, "Sent ✓") }, "Send"),
          el("button", { onclick: action(async () => { await navigator.clipboard.writeText(body.value); }, "Copied") }, "Copy"),
          el("button", { onclick: action(async () => { await api("POST", "/api/drafts/" + d.id + "/sent-manually"); route(); }, "Recorded as sent") }, "I sent it myself"),
          el("button", { onclick: action(async () => { await api("PUT", "/api/drafts/" + d.id, edits()); }, "Saved") }, "Save edits"),
          el("div", { class: "spacer" }),
          el("button", { class: "danger", onclick: action(async () => { await api("POST", "/api/drafts/" + d.id + "/discard"); route(); }, "Discarded") }, "Discard"),
          el("button", { onclick: () => openDeal(d.opportunityId) }, "Open deal"))));
    }
  }

  // ---------- Day summary ----------

  async function renderSummary(root) {
    const s = await api("GET", "/api/summary/eod");
    clear(root);
    root.appendChild(el("h1", {}, "End of day — " + new Date(s.date + "T00:00:00").toLocaleDateString(undefined, { weekday: "long", month: "long", day: "numeric" })));
    const list = (items, empty) => items.length ? el("ul", {}, items.map((x) => el("li", {}, x))) : emptyLine(empty);
    const o = s.newOpportunities;
    root.appendChild(el("div", { class: "grid" },
      card("✅ Completed", list(s.completed, "Nothing recorded yet today.")),
      card("⏳ Still pending", list(s.stillPending, "All clear.")),
      card("💰 New opportunities", el("p", {}, o.total + " new · " + o.paid + " paid · " + o.gifted + " gifted · " + o.affiliate + " affiliate"),
        list(o.items, "")),
      card("➡️ Tomorrow's priorities", list(s.tomorrow, "Nothing planned yet."))));
  }

  // ---------- Settings ----------

  async function renderSettings(root) {
    const s = await api("GET", "/api/settings");
    const c = s.credentials;
    const p = s.preferences;
    clear(root);
    root.appendChild(el("h1", {}, "Settings"));
    root.appendChild(el("p", { class: "muted" }, "Everything here is stored encrypted in your own database. Saved credentials are never shown again — leave a field blank to keep the current value."));

    const secretField = (name, label, placeholder) => {
      const input = el("input", { type: "password", autocomplete: "off", placeholder: c[name] ? "•••••••• (saved)" : placeholder || "" });
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
    const channel = s.channels;
    const steps = el("ol", { class: "steps" });
    root.appendChild(card(null, steps));

    // 1. Claude
    const claudeBox = el("div", {}, secretField("ANTHROPIC_API_KEY", "Claude API key", "sk-ant-…"));
    steps.appendChild(el("li", { class: c.ANTHROPIC_API_KEY ? "done" : "" },
      el("h3", {}, "Connect Claude"),
      el("p", { class: "small muted" }, "Create a key at console.anthropic.com → API keys. It reads and classifies your messages and writes drafts."),
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
      el("p", { class: "small muted" }, "In Google Cloud Console: enable the Gmail API, create an OAuth client of type “Web application”, and add this authorized redirect URI:"),
      el("div", { class: "code" }, s.googleRedirectUri),
      gmailBox,
      el("div", { class: "row" },
        el("button", { class: "small", onclick: saveSecrets(gmailBox) }, "Save client"),
        c.GOOGLE_CLIENT_ID && c.GOOGLE_CLIENT_SECRET ? el("button", { class: "primary small", onclick: action(async () => {
          const r = await api("POST", "/oauth/google/start"); location.href = r.url;
        }) }, channel.EMAIL.connected ? "Reconnect Gmail" : "Connect Gmail") : null,
        channel.EMAIL.connected ? el("button", { class: "small danger", onclick: action(async () => { await api("POST", "/oauth/google/disconnect"); renderSettings(root); }, "Disconnected") }, "Disconnect") : null),
      channelStatus(channel.EMAIL),
      channel.EMAIL.connected ? importHistory() : null,
      el("p", { class: "small muted" }, "Permissions requested: read mail + create/send drafts. The app cannot delete or change existing mail.")));

    // 3. Instagram
    const igBox = el("div", {}, secretField("INSTAGRAM_APP_ID", "Instagram app ID"), secretField("INSTAGRAM_APP_SECRET", "Instagram app secret"));
    const igTokenBox = el("div", {}, secretField("INSTAGRAM_ACCESS_TOKEN", "…or paste an access token from the Meta App Dashboard"));
    const webhookOut = el("div", { class: "code hidden" });
    steps.appendChild(el("li", { class: channel.INSTAGRAM.connected ? "done" : "" },
      el("h3", {}, "Connect Instagram (optional)"),
      el("p", { class: "small muted" }, "Needs an Instagram Business or Creator account and a Meta app using “Instagram API with Instagram login” with the instagram_business_basic and instagram_business_manage_messages permissions. Add yourself as a tester; App Review is only needed if other people's accounts will use your app."),
      el("p", { class: "small muted" }, "OAuth redirect URI (Meta requires https — deploy the app or use a tunnel):"),
      el("div", { class: "code" }, s.instagramRedirectUri),
      igBox,
      el("div", { class: "row" },
        el("button", { class: "small", onclick: saveSecrets(igBox) }, "Save app"),
        c.INSTAGRAM_APP_ID && c.INSTAGRAM_APP_SECRET ? el("button", { class: "primary small", onclick: action(async () => {
          const r = await api("POST", "/oauth/instagram/start"); location.href = r.url;
        }) }, channel.INSTAGRAM.connected ? "Reconnect Instagram" : "Connect Instagram") : null,
        channel.INSTAGRAM.connected ? el("button", { class: "small danger", onclick: action(async () => { await api("POST", "/oauth/instagram/disconnect"); renderSettings(root); }, "Disconnected") }, "Disconnect") : null),
      igTokenBox,
      el("div", { class: "row" }, el("button", { class: "small", onclick: saveSecrets(igTokenBox) }, "Save token")),
      channelStatus(channel.INSTAGRAM),
      s.instagramTokenExpiresAt ? el("p", { class: "small muted" }, "Token renews automatically; current expiry " + fmtDate(s.instagramTokenExpiresAt) + ".") : null,
      el("p", { class: "small muted" }, "Real-time DMs (optional): in the Meta dashboard set the webhook callback URL to the address below, subscribe to “messages”, and use a verify token generated here. Without webhooks, DMs are fetched on each sync."),
      el("div", { class: "code" }, s.instagramWebhookUrl),
      el("div", { class: "row" }, el("button", { class: "small", onclick: action(async () => {
        const r = await api("POST", "/api/settings/instagram-webhook-token");
        webhookOut.textContent = "Verify token (shown once): " + r.verifyToken;
        webhookOut.classList.remove("hidden");
      }) }, c.INSTAGRAM_WEBHOOK_VERIFY_TOKEN ? "Regenerate verify token" : "Generate verify token")),
      webhookOut));

    // 4. Profile
    const pf = {
      creatorName: el("input", { value: p.creatorName, maxlength: "200" }),
      creatorProfile: el("textarea", { class: "tall", maxlength: "20000" }),
      followupCadenceDays: el("input", { value: p.followupCadenceDays, pattern: "[0-9, ]+" }),
      timezone: el("input", { value: p.timezone }),
      brandKeywords: el("textarea", { maxlength: "2000" }),
      classifierModel: el("input", { value: p.classifierModel }),
      writerModel: el("input", { value: p.writerModel }),
    };
    pf.creatorProfile.value = p.creatorProfile;
    pf.brandKeywords.value = p.brandKeywords;
    steps.appendChild(el("li", { class: p.creatorName !== "Creator" ? "done" : "" },
      el("h3", {}, "About you"),
      el("label", {}, "Your name (used in sign-offs)"), pf.creatorName,
      el("label", {}, "Voice, rates & rules — drafts only quote rates written here"), pf.creatorProfile,
      el("div", { class: "grid" },
        el("div", {}, el("label", {}, "Follow-up schedule (days before #1, #2, …)"), pf.followupCadenceDays),
        el("div", {}, el("label", {}, "Time zone"), pf.timezone)),
      el("label", {}, "Brand keywords (emails without these in bulk/automated mail are skipped before AI)"), pf.brandKeywords,
      el("div", { class: "grid" },
        el("div", {}, el("label", {}, "Classifier model"), pf.classifierModel),
        el("div", {}, el("label", {}, "Writer model"), pf.writerModel)),
      el("p", {}, el("button", { class: "primary small", onclick: action(async () => {
        const body = {};
        for (const [k, v] of Object.entries(pf)) body[k] = v.value;
        await api("PUT", "/api/settings/preferences", body);
      }, "Saved") }, "Save"))));

    // 5. MCP
    const keyOut = el("div", { class: "code hidden" });
    steps.appendChild(el("li", { class: c.MCP_API_KEY_HASH ? "done" : "" },
      el("h3", {}, "Use it from Claude (MCP, optional)"),
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

    root.appendChild(backupCard(root));

    // Password
    const cur = el("input", { type: "password", autocomplete: "current-password" });
    const nw = el("input", { type: "password", autocomplete: "new-password", minlength: "12" });
    root.appendChild(card("Change password", el("div", { class: "grid" },
      el("div", {}, el("label", {}, "Current password"), cur), el("div", {}, el("label", {}, "New password (12+ characters)"), nw)),
      el("p", {}, el("button", { class: "small", onclick: action(async () => {
        await api("POST", "/api/settings/password", { currentPassword: cur.value, newPassword: nw.value });
        cur.value = ""; nw.value = "";
      }, "Password changed") }, "Change password"))));

    if (s.pendingAnalysis > 0) {
      root.appendChild(el("p", { class: "muted small" }, s.pendingAnalysis + " message(s) waiting for AI analysis" + (c.ANTHROPIC_API_KEY ? "." : " — add your Claude API key.")));
    }
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
    const rePass = el("input", { type: "password", autocomplete: "off" });
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

    return card("Backup & restore",
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
      el("p", {}, el("button", { class: "danger small", onclick: doRestore }, "Restore…")));
  }

  function channelStatus(ch) {
    return el("div", {},
      el("p", { class: "small" }, el("span", { class: "status-dot" + (ch.connected ? " on" : "") }),
        ch.connected ? "Connected" + (ch.account ? " as " + ch.account : "") : "Not connected",
        ch.lastSync ? " · last sync " + fmtDateTime(ch.lastSync) : ""),
      ch.lastError ? channelError(ch.lastError) : null);
  }

  function importHistory() {
    const days = el("select", { "aria-label": "How far back" },
      [["30", "Last 30 days"], ["90", "Last 3 months"], ["180", "Last 6 months"], ["365", "Last year"]]
        .map(([v, label]) => el("option", { value: v }, label)));
    days.value = "90";
    return el("div", { class: "import-box" },
      el("h4", {}, "Import older email"),
      el("p", { class: "small muted" }, "Brings in past deals. Older email is analyzed oldest first so each deal's status builds up in order, and "
        + "reply drafts are only written for unanswered email from the last 2 months. Already-imported email is skipped; "
        + "Gmail rate limits pause and resume it automatically."),
      el("div", { class: "row" }, days,
        el("button", { class: "small", onclick: action(async () => {
          await api("POST", "/api/sync/import", { days: Number(days.value) });
          setTimeout(pollStatus, 1000);
        }, "Import started. Progress shows at the top.") }, "Import")));
  }

  // Short first line always visible; anything longer folds into "Details" so the page never scrolls sideways.
  function channelError(text) {
    const first = text.split("\n")[0];
    const summary = first.length > 160 ? first.slice(0, 160) + "…" : first;
    return el("div", { class: "alert error channel-error" },
      el("strong", {}, "Last error: "), summary,
      text !== summary ? el("details", {}, el("summary", {}, "Details"), el("pre", {}, text)) : null);
  }

  // ---------- boot ----------

  document.querySelectorAll(".tab").forEach((b) => b.addEventListener("click", () => { location.hash = "#" + b.dataset.tab; }));
  document.getElementById("drawer-backdrop").addEventListener("click", closeDrawer);
  document.addEventListener("keydown", (e) => { if (e.key === "Escape") closeDrawer(); });
  document.getElementById("sync-btn").addEventListener("click", action(async () => {
    await api("POST", "/api/sync");
    setTimeout(pollStatus, 1000);
  }, "Syncing… new items appear as they're analyzed"));

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
    if (s.analyzing) text = "Analyzing " + msgs + "…";
    else if (s.syncing) text = since ? "Importing email since " + since + "…" : "Syncing…";
    else if (since) text = "Import since " + since + " continues on the next sync";
    else if (n > 0 && !s.aiConfigured) {
      text = msgs + " waiting: add your Claude API key in Settings"; warn = true;
    } else if (n > 0 && s.aiError) {
      text = msgs + " waiting: " + (/\(401\)/.test(s.aiError) ? "Claude API key rejected"
        : /\(429\)/.test(s.aiError) ? "Claude rate limit, retrying next sync" : "AI error");
      warn = true; title = s.aiError;
    } else if (n > 0) text = msgs + " waiting for analysis";
    const chip = document.getElementById("sync-status");
    chip.textContent = text;
    chip.title = title;
    chip.className = "sync-status" + (warn ? " warn" : "");
    document.getElementById("sync-status-row").classList.toggle("hidden", !text);
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
  document.getElementById("logout-btn").addEventListener("click", async () => {
    await fetch("/logout", { method: "POST", credentials: "same-origin", headers: { "X-XSRF-TOKEN": csrf() } });
    location.replace("/login.html?logout");
  });
  window.addEventListener("hashchange", route);
  pollStatus();

  api("GET", "/api/statuses").then((s) => { statuses = s; route(); });
})();
