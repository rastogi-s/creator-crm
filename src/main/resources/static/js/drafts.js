/*
 * Creator CRM: Drafts: the inbox of replies waiting to be sent.
 * Part of the dashboard; index.html loads these files in order and they share one scope (see core.js).
 */
"use strict";

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
      el("p", { class: "small muted" }, "After you press Send you have a few seconds to undo it."
        + (practiceInside ? " Practice: nothing is really sent." : "")),
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
      d.invoiceId ? el("div", { class: "small" }, icon("clip"), " ", el("a", { href: "/api/invoices/" + d.invoiceId + "/pdf", target: "_blank", rel: "noopener" }, "Invoice PDF"), " is attached") : null,
      d.resultId && d.channel === "EMAIL" ? el("div", { class: "small" }, icon("clip"), " ", el("a", { href: "/api/opportunities/" + d.opportunityId + "/results/pdf", target: "_blank", rel: "noopener" }, "Results PDF"), " is attached") : null,
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
