/*
 * Creator CRM: Instagram DMs: every conversation in one list she can sort, tag as a lead, and see who's waiting.
 * Part of the dashboard; index.html loads these files in order and they share one scope (see core.js).
 */
"use strict";

// ---------- Instagram DMs ----------
// A lead is a DM she tagged as one, or one where Claude saw a brand and she didn't say otherwise. Lead DMs left
// unanswered get a "Reply to @name" to-do after the hours set in Settings (see DmReminders.java).

const DMS_DEFAULTS = { q: "", show: "", sort: "waiting" };
let dmsFilter = loadPrefs("dms", DMS_DEFAULTS);

// "3 hours", "2 days": how long someone has been waiting.
function waitedFor(iso) {
  const mins = Math.max(0, Math.round((Date.now() - new Date(iso).getTime()) / 60000));
  if (mins < 60) return mins + (mins === 1 ? " minute" : " minutes");
  const hours = Math.round(mins / 60);
  if (hours < 48) return hours + (hours === 1 ? " hour" : " hours");
  return Math.round(hours / 24) + " days";
}

// The reply to-do's due day, as Today would say it.
function replyDueLabel(due) {
  if (!due) return "Reply to-do";
  const today = new Date(); today.setHours(0, 0, 0, 0);
  const d = new Date(due + "T00:00:00");
  return d < today ? "Reply overdue" : d.getTime() === today.getTime() ? "Reply today" : "Reply by " + fmtDate(due);
}

function dmLeadBadge(r) {
  if (r.tag === "LEAD") return el("span", { class: "badge accent", title: "You tagged this as a lead" }, "Lead");
  if (r.tag === "NOT_LEAD") return el("span", { class: "badge", title: "You said this isn't a lead" }, "Not a lead");
  if (r.lead) return el("span", { class: "badge accent", title: "Claude spotted a brand in this conversation" }, "Lead · Claude");
  return null;
}

async function tagDm(id, tag) {
  const r = await api("POST", "/api/dms/" + id + "/tag", { tag });
  toast(tag === "LEAD" ? "Tagged as a lead" + (r.reminderTaskId ? ". A reply to-do is on Today." : ". You'll get a reminder if it waits for a reply.")
    : tag === "NOT_LEAD" ? "Marked as not a lead" : "Claude decides again");
  return r;
}

function dmTagButtons(r, after) {
  const pick = (tag, label, cls) => el("button", { class: "small" + (cls ? " " + cls : ""), onclick: action(async (e) => {
    e.stopPropagation();
    await tagDm(r.id, tag);
    after();
  }) }, label);
  return r.lead ? [pick("NOT_LEAD", "Not a lead")] : [pick("LEAD", "Mark as lead", "primary")];
}

async function renderDms(root) {
  const f = dmsFilter;
  const rows = await api("GET", "/api/dms");
  clear(root);
  const save = () => savePrefs("dms", f);
  root.appendChild(el("h1", {}, "Instagram DMs"));
  root.appendChild(el("p", { class: "muted" }, "Every Instagram conversation, newest or longest-waiting first. Tag the ones that could turn into "
    + "work as leads: they become deals, and if one waits too long for your reply it goes on Today as a to-do."));

  const search = searchBox(f.q, "Search name or message…", (v) => { f.q = v; save(); draw(); });
  const sortSelect = el("select", { "aria-label": "Sort", onchange: (e) => { f.sort = e.target.value; save(); draw(); } },
    [["waiting", "Waiting longest"], ["newest", "Newest first"], ["oldest", "Oldest first"], ["name", "Name A–Z"]]
      .map(([v, l]) => el("option", { value: v, selected: f.sort === v }, l)));
  root.appendChild(el("div", { class: "row filters" }, el("div", { class: "spacer" }, search), sortSelect));
  const chips = el("div", {});
  const list = el("div", {});
  root.append(chips, list);

  const groups = {
    "": () => true,
    LEADS: (r) => r.lead,
    WAITING: (r) => !!r.waitingSince,
    LEADS_WAITING: (r) => r.lead && !!r.waitingSince,
    OTHER: (r) => !r.lead,
  };
  const sorts = {
    waiting: { by: "waiting", dir: "asc" }, newest: { by: "lastAt", dir: "desc" },
    oldest: { by: "lastAt", dir: "asc" }, name: { by: "handle", dir: "asc" },
  };

  function draw() {
    const n = (k) => rows.filter(groups[k]).length;
    clear(chips).appendChild(chipRow([["", "All " + rows.length], ["LEADS", "Leads " + n("LEADS")],
      ["LEADS_WAITING", "Leads waiting for you " + n("LEADS_WAITING")], ["WAITING", "Waiting for you " + n("WAITING")],
      ["OTHER", "Not leads " + n("OTHER")]], f.show, (v) => { f.show = v; save(); draw(); }, "Show"));
    clear(list);
    if (!rows.length) {
      list.appendChild(card(null, emptyLine("No Instagram DMs yet. Once Instagram is connected in Settings, your DMs show up here "
        + "every time the app checks for new messages.")));
      return;
    }
    const shown = sortRows(rows.filter((r) => (groups[f.show] || groups[""])(r)
      && matchesQuery(f.q, r.handle, r.lastText, r.brand)), sorts[f.sort] || sorts.waiting, {
      // Not waiting sorts last; among the waiting, the one who wrote first is on top.
      waiting: (r) => r.waitingSince, lastAt: (r) => r.lastAt, handle: (r) => r.handle,
    });
    const line = shownLine(shown.length, rows.length, "conversations",
      () => { Object.assign(f, { q: "", show: "" }); search.value = ""; save(); draw(); });
    if (line) list.appendChild(line);
    if (!shown.length) { list.appendChild(card(null, emptyLine("No DMs match. Try another filter."))); return; }
    list.appendChild(el("div", { class: "card" }, el("ul", { class: "list dm-list" }, shown.map((r) =>
      el("li", { class: "item clickable", onclick: () => openDm(r.id) },
        el("div", { class: "body" },
          el("div", { class: "title" }, "@" + r.handle, " ", dmLeadBadge(r),
            r.waitingSince ? el("span", { class: "badge " + (r.lead ? "high" : "medium") }, "Waiting " + waitedFor(r.waitingSince)) : null,
            r.reminderTaskId ? el("span", { class: "badge", title: "On your to-do list" }, icon("bell"), replyDueLabel(r.reminderDue)) : null),
          el("div", { class: "detail" }, (r.lastFromMe ? "You: " : "") + (r.lastText || "")),
          el("div", { class: "detail small" }, [fmtDateTime(r.lastAt), r.brand && r.brand !== r.handle ? r.brand : null,
            r.status].filter(Boolean).join(" · "))),
        el("div", { class: "actions", onclick: (e) => e.stopPropagation() }, ...dmTagButtons(r, () => renderDms(root))))))));
  }
  draw();
}

// One conversation, with the lead switch and a link to its deal.
async function openDm(id) {
  const d = await api("GET", "/api/dms/" + id);
  const r = d.dm;
  const drawer = clear(document.getElementById("drawer"));
  document.getElementById("drawer-backdrop").classList.remove("hidden");
  drawer.classList.remove("hidden");
  const refresh = () => { openDm(id); route(); };
  drawer.appendChild(el("div", { class: "row" }, el("h1", {}, "@" + r.handle), el("div", { class: "spacer" }),
    el("button", { class: "small", onclick: closeDrawer }, "Close")));

  const choice = (tag, label) => el("button", { class: "chip" + ((r.tag || "") === tag ? " active" : ""), "aria-pressed": String((r.tag || "") === tag),
    onclick: action(async () => { await tagDm(id, tag); refresh(); }) }, label);
  drawer.appendChild(card("Is this a lead?",
    el("div", { class: "chips", role: "group", "aria-label": "Lead" }, choice("LEAD", "Lead"), choice("NOT_LEAD", "Not a lead"), choice("", "Let Claude decide")),
    el("p", { class: "small muted" }, r.tag ? "Your call. Claude " + (r.claudeLead ? "also thinks it's a brand." : r.claudeLead === false ? "didn't see a brand here." : "hasn't read it yet.")
      : r.claudeLead ? "Claude saw a brand here, so it counts as a lead." : r.claudeLead === false ? "Claude didn't see a brand here." : "Claude hasn't read this yet."),
    r.waitingSince ? el("p", { class: "small" + (r.lead ? " warn" : "") }, "Waiting for your reply for " + waitedFor(r.waitingSince) + ". "
      + (r.replyWindowEnds && new Date(r.replyWindowEnds) > new Date() ? "Instagram lets the app send your reply until " + fmtDateTime(r.replyWindowEnds) + "."
        : "It's past 24 hours, so reply in the Instagram app.")) : null,
    r.opportunityId ? el("p", {}, el("button", { class: "small primary", onclick: () => openDeal(r.opportunityId) }, "Open deal"),
      " ", el("span", { class: "small muted" }, [r.brand, r.status].filter(Boolean).join(" · "))) : null));

  drawer.appendChild(card("Conversation", el("div", { class: "dm-thread" }, d.messages.map((m) =>
    el("div", { class: "dm-bubble" + (m.fromMe ? " mine" : "") },
      el("div", {}, m.text || ""), el("div", { class: "small muted" }, (m.fromMe ? "You · " : "") + fmtDateTime(m.sentAt)))))));
}
