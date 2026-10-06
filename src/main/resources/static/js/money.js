/*
 * Creator CRM: Money: invoices and totals.
 * Part of the dashboard; index.html loads these files in order and they share one scope (see core.js).
 */
"use strict";

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
        }, "Marked paid") }, "Mark paid"),
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
