/*
 * Creator CRM: Today: the day's list, follow-ups and rebooking.
 * Part of the dashboard; index.html loads these files in order and they share one scope (see core.js).
 */
"use strict";

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
  root.appendChild(healthStrip());

  // The list is already ranked (money, lateness, priority). Three things are a plan; thirteen are a pile.
  const first = t.urgent.slice(0, 3);
  const rest = t.urgent.slice(3);
  const firstCard = card(sectionTitle("sparkle", "accent", "Do these first"), itemList(first, "Nothing urgent. Nice.", true));
  firstCard.classList.add("lead-card");
  root.appendChild(firstCard);
  if (rest.length) {
    root.appendChild(el("details", { class: "card more-items" },
      el("summary", {}, el("h3", {}, sectionTitle("list", "grey", "Everything else for today", rest.length))),
      itemList(rest, "", false)));
  }
  root.appendChild(card(sectionTitle("bell", "amber", "Follow-ups", t.followUps.length), followUpList(t.followUps)));
  if (t.rebook && t.rebook.length) root.appendChild(rebookCard(t.rebook));

  if (t.approvals.length) {
    // The first few here; Drafts has them all.
    const shownDrafts = t.approvals.slice(0, 3);
    const moreDrafts = t.approvals.length - shownDrafts.length;
    root.appendChild(card(sectionTitle("mail", "blue", "Drafts awaiting your approval", t.approvals.length),
      el("ul", { class: "list" }, shownDrafts.map((a) => el("li", { class: "item" },
        el("div", { class: "body" }, el("div", { class: "title" }, a.brand + " — " + pretty(a.type)),
          el("div", { class: "detail" }, a.preview)),
        el("div", { class: "actions" }, el("button", { class: "small primary", onclick: () => showDraft({ id: a.draftId }) }, "Review"))))),
      moreDrafts > 0 ? el("p", { class: "see-all" }, el("a", { href: "#drafts" }, "See all " + t.approvals.length + " drafts")) : null));
  }

  const o = t.newOpportunities;
  const newCount = o.paid + o.gifted + o.affiliate + o.other;
  const newList = o.items.length > 5
    ? el("details", { class: "fold" }, el("summary", {}, "Show all " + o.items.length), el("ul", {}, o.items.map((s) => el("li", {}, s))))
    : o.items.length ? el("ul", {}, o.items.map((s) => el("li", {}, s))) : null;
  root.appendChild(card(sectionTitle("money", "green", "New opportunities (last 24h)", newCount),
    el("p", {}, o.paid + " paid · " + o.gifted + " gifted · " + o.affiliate + " affiliate · " + o.other + " other"), newList));

  root.appendChild(card(sectionTitle("calendar", "teal", "Upcoming", t.upcoming.length), itemList(t.upcoming, "Nothing scheduled in the next two weeks.", false)));
}

// Win back past brands: re-pitches drafted for brands she worked with before, a few each week.
function rebookCard(items) {
  const c = card(sectionTitle("repeat", "violet", "Rebook past brands", items.length),
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
      el("a", { class: "chip", href: l.url, target: "_blank", rel: "noopener noreferrer", title: l.url }, icon("link"), " " + (l.label || "Link")))) : null);
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
      }, "Marked paid") }, "Mark paid") : null,
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
