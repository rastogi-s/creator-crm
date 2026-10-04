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

  const views = { today: renderToday, pipeline: renderPipeline, money: renderMoney, outreach: renderOutreach, links: renderLinks,
                  drafts: renderDrafts, summary: renderSummary, settings: renderSettings,
                  help: renderHelp, whatsnew: renderWhatsNew };
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
      if (q.has("spend")) { const c = document.getElementById("spend"); if (c) c.scrollIntoView({ block: "start" }); }
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

  // ---------- Money ----------

  let moneyYear = null;

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
    root.appendChild(el("div", { class: "stats card" },
      stat(moneyTotal(m.booked), "Booked, not invoiced yet"),
      stat(moneyTotal(m.outstanding), "Invoiced, waiting for payment"),
      stat(moneyTotal(m.paidThisMonth), "Paid this month"),
      stat(moneyTotal(m.overdue), "Overdue")));

    root.appendChild(card("Ready to invoice", m.readyToInvoice.length ? el("ul", { class: "list" }, m.readyToInvoice.map((r) => el("li", { class: "item" },
      el("div", { class: "body" }, el("div", { class: "title" }, r.brand + (r.campaign ? " — " + r.campaign : "")),
        el("div", { class: "detail" }, r.amountText + " · " + r.status)),
      el("div", { class: "actions" },
        el("button", { class: "small primary", onclick: action(async () => {
          const inv = await api("POST", "/api/opportunities/" + r.opportunityId + "/invoices");
          openInvoice(inv.id);
        }) }, "Create invoice"),
        el("button", { class: "small", onclick: () => openDeal(r.opportunityId) }, "Open deal")))))
      : emptyLine("Every agreed paid deal has an invoice.")));

    if (!m.months.length) { root.appendChild(card(null, emptyLine("No invoices in " + m.year + " yet."))); return; }
    root.appendChild(el("p", { class: "muted small" }, "Paid in " + m.year + ": " + moneyTotal(m.paidThisYear)));
    for (const g of m.months) {
      const label = new Date(g.month + "-01T00:00:00").toLocaleDateString(undefined, { month: "long", year: "numeric" });
      root.appendChild(el("div", { class: "card table-wrap" }, el("h3", {}, label), el("table", {},
        el("thead", {}, el("tr", {}, ["Invoice", "Brand", "Amount", "Issued", "Due", "Status"].map((h) => el("th", {}, h)))),
        el("tbody", {}, g.invoices.map((inv) => el("tr", { class: "clickable", onclick: () => openInvoice(inv.id) },
          el("td", {}, el("strong", {}, inv.number)),
          el("td", {}, inv.brand),
          el("td", {}, inv.amountText),
          el("td", { class: "muted" }, fmtDate(inv.issuedDate)),
          el("td", { class: "muted" }, fmtDate(inv.dueDate)),
          el("td", {}, invoiceBadge(inv))))))));
    }
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
               ["Paid", fmtDate(inv.paidDate)], ["Notes", inv.notes]]),
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
          el("button", { class: "small", title: "Put the invoice email in Drafts again", onclick: action(async () => {
            await api("POST", "/api/invoices/" + id + "/email"); closeDrawer(); location.hash = "#drafts"; route();
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
          await api("POST", "/api/invoices/" + id + "/email");
          closeDrawer(); location.hash = "#drafts"; route();
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

  function closeDrawer() {
    document.getElementById("drawer").classList.add("hidden");
    document.getElementById("drawer-backdrop").classList.add("hidden");
  }

  async function openDeal(id) {
    const [d, invoices] = await Promise.all([api("GET", "/api/opportunities/" + id), api("GET", "/api/opportunities/" + id + "/invoices")]);
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
    const [rows, leads] = await Promise.all([api("GET", "/api/pitches"), api("GET", "/api/leads")]);
    clear(root);
    root.appendChild(el("h1", {}, "Outreach"));
    root.appendChild(el("p", { class: "muted" }, "Every brand you've pitched, with automatic follow-up dates. Pitches you send from Gmail are detected automatically; log the rest here."));
    root.appendChild(findBrandsCard(root, leads));

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

  function findBrandsCard(root, leads) {
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
        depthNames[o.depth] + ": up to " + o.maxSearches + " searches, " + (o.measured ? "" : "about ") + usd(o.usd)
        + (o.measured ? " on average" : "") + " (" + depthNotes[o.depth] + ")")));
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
    return card("Find brands to pitch",
      el("p", { class: "small muted" }, "Claude searches the web for brands that fit your profile, checks their sites for a published partnerships or PR email, and suggests a pitch idea. Pick the ones you like and a pitch draft lands in Drafts for you to edit and send. Nothing is sent automatically."),
      el("div", { class: "row" }, el("div", { class: "spacer" }, query), count, el("button", { class: "primary", onclick: search }, "Find brands")),
      el("div", { class: "row" }, el("span", { class: "small muted" }, "Search depth:"), depth),
      status,
      leads.length ? el("div", {}, leads.map((l) => leadItem(root, l))) : null);
  }

  function leadItem(root, l) {
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
          l.instagram ? el("span", { class: "badge" }, "@" + l.instagram) : null),
        l.fitReason ? el("div", { class: "detail" }, l.fitReason) : null,
        l.pitchAngle ? el("div", { class: "detail" }, "💡 " + l.pitchAngle) : null,
        el("div", { class: "small" }, contact),
        contactEdit),
      el("div", { class: "actions" },
        el("button", { class: "small primary", onclick: action(async () => {
          await api("POST", "/api/leads/" + l.id + "/pitch");
          location.hash = "#drafts"; route();
        }, "Pitch drafted. Review it in Drafts") }, "Draft pitch"),
        el("button", { class: "small", onclick: () => contactEdit.classList.toggle("hidden") }, "Edit contact"),
        el("button", { class: "small danger", onclick: action(async () => { await api("POST", "/api/leads/" + l.id + "/dismiss"); renderOutreach(root); }) }, "Dismiss")));
  }

  // ---------- Links ----------

  async function renderLinks(root) {
    const links = await api("GET", "/api/links");
    clear(root);
    root.appendChild(el("h1", {}, "My links"));
    root.appendChild(el("p", { class: "muted" }, "Your profiles, website and portfolio in one place. Drafts use these exact links instead of placeholders like [MEDIA KIT LINK]."));

    const url = el("input", { placeholder: "e.g. instagram.com/yourname", maxlength: "1000" });
    const label = el("input", { placeholder: "Optional — filled in for Instagram, TikTok, YouTube…", maxlength: "100" });
    const add = action(async () => {
      if (!url.value.trim()) throw new Error("Paste a link first");
      await api("POST", "/api/links", { url: url.value, label: label.value });
      renderLinks(root);
    }, "Link added");
    url.addEventListener("keydown", (e) => { if (e.key === "Enter") add(e); });
    label.addEventListener("keydown", (e) => { if (e.key === "Enter") add(e); });
    root.appendChild(card("Add a link", el("div", { class: "grid" },
      el("div", {}, el("label", {}, "Link"), url), el("div", {}, el("label", {}, "Label"), label)),
      el("p", {}, el("button", { class: "primary", onclick: add }, "Add link"))));

    if (!links.length) { root.appendChild(card(null, emptyLine("No links yet. Add your Instagram, TikTok, YouTube, website or portfolio."))); return; }
    const asText = () => links.map((l) => l.label + ": " + l.url).join("\n");
    root.appendChild(el("div", { class: "card" },
      el("div", { class: "row" }, el("h3", {}, links.length + " link" + (links.length === 1 ? "" : "s")), el("div", { class: "spacer" }),
        el("button", { class: "small", onclick: action(async () => { await navigator.clipboard.writeText(asText()); }, "All links copied") }, "Copy all")),
      el("div", { class: "table-wrap" }, el("table", {}, el("tbody", {}, links.map((l, i) => linkRow(root, l, i, links.length)))))));
  }

  function linkRow(root, l, i, n) {
    const safe = /^https?:\/\//i.test(l.url); // the server only stores http(s), but never render anything else as a link
    const row = el("tr", {},
      el("th", {}, l.label),
      el("td", {}, safe ? el("a", { href: l.url, target: "_blank", rel: "noopener noreferrer" }, l.url) : l.url),
      el("td", {}, el("div", { class: "row" },
        el("button", { class: "small", onclick: action(async () => { await navigator.clipboard.writeText(l.url); }, "Copied") }, "Copy"),
        el("button", { class: "small", onclick: () => editLink(root, row, l) }, "Edit"),
        el("button", { class: "small", title: "Move up", disabled: i === 0, onclick: action(async () => { await api("POST", "/api/links/" + l.id + "/move?up=true"); renderLinks(root); }) }, "↑"),
        el("button", { class: "small", title: "Move down", disabled: i === n - 1, onclick: action(async () => { await api("POST", "/api/links/" + l.id + "/move?up=false"); renderLinks(root); }) }, "↓"),
        el("button", { class: "small danger", onclick: action(async () => {
          if (!confirm("Delete " + l.label + "?")) return;
          await api("DELETE", "/api/links/" + l.id); renderLinks(root);
        }, "Deleted") }, "Delete"))));
    return row;
  }

  function editLink(root, row, l) {
    const label = el("input", { value: l.label, maxlength: "100" });
    const url = el("input", { value: l.url, maxlength: "1000" });
    clear(row);
    row.append(el("th", {}, label), el("td", {}, url), el("td", {}, el("div", { class: "row" },
      el("button", { class: "small primary", onclick: action(async () => {
        await api("PUT", "/api/links/" + l.id, { label: label.value, url: url.value }); renderLinks(root);
      }, "Saved") }, "Save"),
      el("button", { class: "small", onclick: () => renderLinks(root) }, "Cancel"))));
    url.focus();
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
        d.invoiceId ? el("div", { class: "small" }, "📎 ", el("a", { href: "/api/invoices/" + d.invoiceId + "/pdf", target: "_blank", rel: "noopener" }, "Invoice PDF"), " is attached") : null,
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
    updateStatus = await api("GET", "/api/updates").catch(() => updateStatus);
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
      el("p", { class: "small muted" }, "Needs an Instagram Business or Creator account and a Meta app using “Instagram API with Instagram login” with the instagram_business_basic and instagram_business_manage_messages permissions (add instagram_business_manage_insights to show your reach). Add yourself as a tester; App Review is only needed if other people's accounts will use your app."),
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
      channel.INSTAGRAM.connected ? instagramStatsLine(s.instagramStats, root) : null,
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
      timezone: el("input", { value: p.timezone }),
      brandKeywords: el("textarea", { maxlength: "2000" }),
      classifierModel: modelSelect(p.classifierModel, [
        ["claude-sonnet-5-5", "Sonnet 5.5: cheaper, under 1¢ per message (recommended)"],
        ["claude-opus-5-5", "Opus 5.5: most careful, about 2× the cost"]]),
      writerModel: modelSelect(p.writerModel, [
        ["claude-opus-5-5", "Opus 5.5: best writing, about 2–4¢ per draft (recommended)"],
        ["claude-sonnet-5-5", "Sonnet 5.5: about half the cost, plainer drafts"]]),
    };
    pf.creatorProfile.value = p.creatorProfile;
    pf.brandKeywords.value = p.brandKeywords;
    steps.appendChild(el("li", { class: p.creatorName !== "Creator" ? "done" : "" },
      el("h3", {}, "About you"),
      el("label", {}, "Your name (used in sign-offs)"), pf.creatorName,
      el("label", {}, "Voice, rates & rules — drafts only quote rates written here"), pf.creatorProfile,
      el("label", {}, "Time zone"), pf.timezone,
      el("label", {}, "Brand keywords (emails without these in bulk/automated mail are skipped before AI)"), pf.brandKeywords,
      el("div", { class: "grid" },
        el("div", {}, el("label", {}, "Claude for reading messages"), pf.classifierModel),
        el("div", {}, el("label", {}, "Claude for writing drafts and finding brands"), pf.writerModel)),
      el("p", { class: "small muted" }, "Costs are rough. Settings → Claude spending shows what you actually spend."),
      el("p", {}, el("button", { class: "primary small", onclick: action(async () => {
        const body = {};
        for (const [k, v] of Object.entries(pf)) body[k] = v.value;
        await api("PUT", "/api/settings/preferences", body);
      }, "Saved") }, "Save"))));

    // 5. Follow-ups
    const fu = {
      followupCadenceDays: el("input", { value: p.followupCadenceDays, pattern: "[0-9, ]+" }),
      followupTime: el("input", { type: "time", value: p.followupTime }),
      followupAutoSend: el("input", { type: "checkbox", id: "followup-auto-send" }),
    };
    fu.followupAutoSend.checked = p.followupAutoSend === "true";
    steps.appendChild(el("li", { class: "done" },
      el("h3", {}, "Follow-ups"),
      el("p", { class: "small muted" }, "Each day at this time the app syncs and drafts every follow-up that's due. Brands that reply drop out automatically."),
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

    // 6. Invoices
    const iv = {
      invoiceBusinessName: el("input", { value: p.invoiceBusinessName, maxlength: "200", placeholder: p.creatorName }),
      invoiceAddress: el("textarea", { maxlength: "2000", placeholder: "Street\nCity, postcode\nCountry" }),
      invoiceTaxId: el("input", { value: p.invoiceTaxId, maxlength: "100" }),
      invoicePaymentDetails: el("textarea", { maxlength: "2000", placeholder: "Bank name, account holder, account number / IBAN, or PayPal email" }),
      invoicePrefix: el("input", { value: p.invoicePrefix, maxlength: "10" }),
      invoiceTermsDays: el("input", { type: "number", min: "0", max: "365", value: p.invoiceTermsDays }),
    };
    iv.invoiceAddress.value = p.invoiceAddress;
    iv.invoicePaymentDetails.value = p.invoicePaymentDetails;
    steps.appendChild(el("li", { class: p.invoiceAddress && p.invoicePaymentDetails ? "done" : "" },
      el("h3", {}, "Invoices"),
      el("p", { class: "small muted" }, "Printed on every invoice. Payment details only appear in the PDF; they're never shown to Claude."),
      el("div", { class: "grid" },
        el("div", {}, el("label", {}, "Business name"), iv.invoiceBusinessName),
        el("div", {}, el("label", {}, "Tax ID (optional)"), iv.invoiceTaxId)),
      el("label", {}, "Address"), iv.invoiceAddress,
      el("label", {}, "How brands pay you"), iv.invoicePaymentDetails,
      el("div", { class: "grid" },
        el("div", {}, el("label", {}, "Invoice number prefix (" + (p.invoicePrefix || "INV") + "-" + new Date().getFullYear() + "-001)"), iv.invoicePrefix),
        el("div", {}, el("label", {}, "Payment due after (days)"), iv.invoiceTermsDays)),
      el("p", {}, el("button", { class: "primary small", onclick: action(async () => {
        const body = {};
        for (const [k, v] of Object.entries(iv)) body[k] = v.value;
        await api("PUT", "/api/settings/preferences", body);
        renderSettings(root);
      }, "Saved") }, "Save"))));

    // 7. MCP
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

    root.appendChild(await claudeSpendCard(root));
    root.appendChild(await learningCard(root));
    root.appendChild(updatesCard(root));
    const startup = await startWithWindowsCard();
    if (startup) root.appendChild(startup);
    root.appendChild(await errorReportsCard(root, c));
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
    const names = { CLASSIFY: "Reading messages", DRAFT: "Writing drafts", RESEARCH: "Finding brands" };
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
        + "reply drafts are only written for unanswered email from the last 2 months. Big imports are analyzed at half price "
        + "in the background, so deals fill in over a few hours. Already-imported email is skipped; "
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
      const input = el("input", { type: "password", autocomplete: "off", placeholder: c.ERROR_REPORT_TOKEN ? "•••••••• (saved)" : "github_pat_…" });
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
          : "Needs Gmail connected (step 2 above), because reports are sent from that account."),
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
    } else if (s.inBatch > 0 && !s.aiError) {
      text = s.inBatch + " older message" + (s.inBatch === 1 ? "" : "s") + " being analyzed at half price"
        + (n > s.inBatch ? " (" + n + " waiting in all)" : "") + ". Results come in over the next few hours.";
    } else if (n > 0 && s.aiError) {
      const reason = s.aiError.replace(/^\S+\s+/, "");
      text = msgs + " waiting: " + (/\(401\)/.test(reason) ? "Claude API key rejected"
        : /\(429\)/.test(reason) ? "Claude rate limit, retrying next sync" : reason);
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
  refreshCredits();
  setInterval(refreshCredits, 5 * 60 * 1000);

  refreshUpdate();
  setInterval(refreshUpdate, 30 * 60 * 1000);
  maybeShowWhatsNew().then(() => api("GET", "/api/statuses")).then((s) => { statuses = s; route(); });
})();
