/*
 * Creator CRM: Deals: the pipeline table.
 * Part of the dashboard; index.html loads these files in order and they share one scope (see core.js).
 */
"use strict";

// ---------- Pipeline ----------

const PIPELINE_DEFAULTS = { q: "", status: "", comp: "", lead: "", closed: false, sort: { by: "updatedAt", dir: "desc" } };
let pipelineFilter = loadPrefs("pipeline", PIPELINE_DEFAULTS);

async function renderPipeline(root) {
  const f = pipelineFilter;
  const rows = await api("GET", "/api/pipeline?includeClosed=" + f.closed);
  clear(root);
  const save = () => savePrefs("pipeline", f);
  const statusOrder = Object.keys(statuses);
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
  root.appendChild(el("div", { class: "row filters" }, el("div", { class: "spacer" }, search), leadSelect, compSelect));
  // The stage pills are the status filter: one row, scrolling sideways on a phone.
  const chips = el("div", { class: "chips stage-chips", role: "group", "aria-label": "Filter by stage" });
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
    // Stage pills count every deal; clicking one filters to it, clicking it again (or All) shows all.
    const counts = {};
    rows.forEach((r) => { counts[r.status] = (counts[r.status] || 0) + 1; });
    if (f.status && !counts[f.status]) f.status = "";
    chips.classList.toggle("filtering", !!f.status);
    clear(chips).append(
      el("button", { class: "chip stage tone-grey" + (f.status ? "" : " active"), "aria-pressed": String(!f.status),
        onclick: () => { f.status = ""; save(); draw(); } }, "All", el("span", { class: "n" }, String(rows.length))),
      ...Object.keys(counts).sort((a, b) => statusOrder.indexOf(a) - statusOrder.indexOf(b)).map((k) =>
        el("button", { class: "chip stage tone-" + (STATUS_TONES[k] || "grey") + (f.status === k ? " active" : ""),
          "aria-pressed": String(f.status === k), title: "Show only " + (statuses[k] || pretty(k)),
          onclick: () => { f.status = f.status === k ? "" : k; save(); draw(); } },
          icon(STATUS_ICONS[k]), stripEmoji(statuses[k] || pretty(k)), el("span", { class: "n" }, String(counts[k])))));
    const active = chips.querySelector(".active");
    if (active && f.status) active.scrollIntoView({ block: "nearest", inline: "nearest" });
    clear(list);
    if (!rows.length) { list.appendChild(card(null, emptyLine("No deals yet. They appear here as brand emails and DMs come in, or when you log a pitch."))); return; }
    const shown = sortRows(rows.filter((r) => (!f.status || r.status === f.status) && (!f.comp || r.compensation === f.comp)
      && (f.lead !== "LEADS" || r.lead) && (f.lead !== "LOW" || r.lead === "LOW")
      && matchesQuery(f.q, r.brand, r.contact, r.campaign, r.statusLabel, pretty(r.type), pretty(r.compensation), r.budget, r.deliverables, r.nextStep)),
    f.sort, {
      brand: (r) => r.brand, lead: (r) => leadRank[r.lead], status: (r) => statusOrder.indexOf(r.status), nextFollowUp: (r) => r.nextFollowUp,
      openTasks: (r) => r.openTasks, updatedAt: (r) => r.updatedAt,
    });
    const clearAll = () => { Object.assign(f, { q: "", status: "", comp: "", lead: "" }); search.value = ""; compSelect.value = ""; leadSelect.value = ""; save(); draw(); };
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
        el("td", {}, statusTag(r.status, r.statusLabel)),
        el("td", {}, pretty(r.type) + " · " + pretty(r.compensation)),
        el("td", {}, r.budget || "—"),
        el("td", {}, r.nextFollowUp ? "#" + r.nextFollowUpNumber + " · " + fmtDate(r.nextFollowUp) : "—"),
        el("td", {}, String(r.openTasks)),
        el("td", { class: "muted" }, fmtDate(r.updatedAt))))))));
  }
  draw();
}
