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
  const [rows, ig] = await Promise.all([api("GET", "/api/dms"), api("GET", "/api/dms/status").catch(() => null)]);
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
      list.appendChild(dmStatusCard(ig, root));
      list.appendChild(dmExportCard(root, true));
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
    list.appendChild(dmExportCard(root, false));
  }
  draw();
}

// Waits (up to two minutes) for a check that was just started to finish.
async function dmWaitForSync() {
  for (let i = 0; i < 60; i++) {
    await new Promise((r) => setTimeout(r, 2000));
    const st = await api("GET", "/api/sync/status").catch(() => null);
    if (st && !st.syncing) return;
  }
}

// An empty page says why: Instagram not connected, a check that failed, or what Instagram sent back last time.
function dmStatusCard(ig, root) {
  const c = card(null);
  if (!ig || !ig.connected) {
    c.append(emptyLine("No Instagram DMs yet, because Instagram isn't connected."),
      el("p", {}, el("a", { class: "btn primary small", href: "#settings?tab=accounts" }, "Connect Instagram")));
    return c;
  }
  const lines = [el("p", {}, el("strong", {}, "Instagram is connected" + (ig.account ? " as " + ig.account : "") + ","),
    " but no DMs have come in yet.")];
  if (ig.error) {
    lines.push(el("p", { class: "small warn" }, "The last check didn't work: " + ig.error.replace(/^\S+\s+/, "")));
  } else if (ig.fetch) {
    const f = ig.fetch;
    lines.push(el("p", { class: "small" }, "Last check, " + fmtDateTime(f.at) + ": Instagram sent back "
      + f.conversations + (f.conversations === 1 ? " conversation" : " conversations")
      + (f.conversations ? ", " + f.activeSince + " with messages since " + fmtDate(f.since)
        + (f.newestConversation ? " (newest " + fmtDateTime(f.newestConversation) + ")" : "") : "") + "."));
    if (!f.conversations) {
      lines.push(el("p", { class: "small" }, "Instagram only shares chats with people who have a role on your Meta app until Meta approves "
        + "the app (App Review), so brands' DMs don't come through yet. Bring them in from your Instagram download below. "
        + "Also check Allow access to messages is on: Instagram, Settings, Messages and story replies, Connected tools."));
    }
  } else if (ig.lastCheck) {
    lines.push(el("p", { class: "small muted" }, "Last checked " + fmtDateTime(ig.lastCheck) + ". Press Check for new messages to see what Instagram sends back."));
  }
  if (ig.importingSince) lines.push(el("p", { class: "small" }, "Bringing in DMs since " + fmtDate(ig.importingSince) + "…"));
  lines.push(el("p", { class: "small muted" }, "Checks bring in new DMs. DMs from before Instagram was connected come in with Bring in older DMs."));
  lines.push(el("div", { class: "row" },
    el("button", { class: "small primary", onclick: action(async () => {
      await api("POST", "/api/sync");
      await dmWaitForSync();
      renderDms(root);
    }) }, "Check for new messages"),
    el("button", { class: "small", onclick: action(async () => {
      await api("POST", "/api/dms/import", { days: 90 });
      await dmWaitForSync();
      renderDms(root);
    }) }, "Bring in older DMs")));
  c.append(...lines);
  return c;
}

// Instagram only lets an app read DMs from brands once Meta approves it (App Review), so until then her DMs come in
// from her own Instagram download: Messages, JSON. Only the chat files are sent, not photos or videos.
const DM_EXPORT_FILE = /(^|\/)((inbox|message_requests)\/[^/]+\/message_\d+\.json|personal_information\.json)$/;

function dmExportCard(root, open) {
  const days = el("select", { "aria-label": "How far back" },
    [["30", "Last 30 days"], ["90", "Last 90 days"], ["365", "Last year"]].map(([v, l]) => el("option", { value: v, selected: v === "90" }, l)));
  const out = el("div", {});
  const picker = el("input", { type: "file", webkitdirectory: "", directory: "", multiple: "", class: "hidden",
    onchange: action(async (e) => {
      const files = [...e.target.files].filter((f) => DM_EXPORT_FILE.test(f.webkitRelativePath || f.name));
      e.target.value = "";
      if (!files.length) {
        toast("No Instagram messages in that folder. Pick the folder you extracted from the download.", true);
        return;
      }
      clear(out).appendChild(el("p", { class: "small muted" }, "Reading " + files.length + " files…"));
      const sent = await Promise.all(files.map(async (f) => ({ path: f.webkitRelativePath || f.name, text: await f.text() })));
      const r = await api("POST", "/api/dms/import-export", { files: sent, days: Number(days.value) });
      clear(out).appendChild(el("p", { class: "small" }, r.newMessages
        ? "Brought in " + r.newMessages + " new messages from " + r.chats + (r.chats === 1 ? " chat" : " chats")
          + ". Claude is reading them now; leads and reply to-dos show up as it goes."
        : "Nothing new: those " + r.messages + " messages are already here."));
      toast("Instagram DMs imported");
      setTimeout(() => renderDms(root), 1500);
    }) });
  return el("details", { class: "card", open: open || null },
    el("summary", {}, el("strong", {}, "Bring in DMs from your Instagram download")),
    el("p", { class: "small" }, "Instagram only shares brands' DMs with an app after Meta reviews it. Until then, "
      + "download your messages from Instagram and bring them in here."),
    el("ol", { class: "small" },
      el("li", {}, "In Instagram: Settings, Accounts Center, Your information and permissions, Download your information."),
      el("li", {}, "Pick Some of your information, tick Messages, then Download to device. Set Format to JSON and the date range to Last 3 months, and create the files."),
      el("li", {}, "When Instagram emails you, download the zip on this laptop. Right-click it and choose Extract All."),
      el("li", {}, "Press Choose folder below and pick the extracted folder.")),
    el("div", { class: "row" }, days,
      el("button", { class: "small primary", onclick: () => picker.click() }, "Choose folder"), picker),
    el("p", { class: "small muted" }, "Only the message files are read, not photos or videos. Bring in a newer download any time; nothing comes in twice. "
      + "Replies to these chats go from the Instagram app."),
    out);
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
      el("div", {}, linkify(m.text || "")), el("div", { class: "small muted" }, (m.fromMe ? "You · " : "") + fmtDateTime(m.sentAt)))))));
}
