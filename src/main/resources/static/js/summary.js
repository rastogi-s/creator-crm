/*
 * Creator CRM: Day summary.
 * Part of the dashboard; index.html loads these files in order and they share one scope (see core.js).
 */
"use strict";

// ---------- Day summary ----------

const DONE_ICONS = { TASK: "check", SENT: "send", REPLY: "chat", MONEY: "money", PITCH: "send" };
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
      eodStat(icon("checkCircle"), s.done.length, "Done today", "ok", goTo("eod-done")),
      eodStat(icon("hourglass"), waiting, "Still to do", waiting ? "warn" : "ok", goTo("eod-waiting")),
      eodStat(icon("money"), s.newDeals.length, s.newDeals.length === 1 ? "New deal" : "New deals", "accent", goTo("eod-new")),
      eodStat(icon("sun"), s.tomorrowItems.length, "For tomorrow", "plain", goTo("eod-tomorrow"))),
    s.draftsWaiting ? el("div", { class: "eod-nudge" },
      el("span", {}, icon("mail"), " " + n(s.draftsWaiting, "draft is", "drafts are") + " ready for you to read and send."),
      el("button", { class: "small primary", onclick: () => { location.hash = "#drafts"; } }, "Review drafts")) : null));

  const doneCard = card(sectionTitle("checkCircle", "green", "What you got done"),
    s.done.length ? showMore(s.done, 6, (items) => el("ul", { class: "list eod-done" }, items.map((d) => el("li", { class: "item" },
      el("span", { class: "eod-icon", "aria-hidden": "true" }, icon(DONE_ICONS[d.kind] || "check")),
      el("div", { class: "body" },
        el("div", { class: "title" }, d.text),
        el("div", { class: "detail" }, d.brand && !d.text.includes(d.brand) ? d.brand + " · " : "",
          new Date(d.at).toLocaleTimeString(undefined, { hour: "numeric", minute: "2-digit" }))),
      d.opportunityId ? el("div", { class: "actions" }, el("button", { class: "small", onclick: () => openDeal(d.opportunityId) }, "Open")) : null))))
      : emptyLine("Nothing ticked off yet. When you finish a task, send a message or a brand writes back, it shows up here."));
  doneCard.id = "eod-done";

  const tomorrowCard = card(sectionTitle("sun", "amber", "Lined up for tomorrow"),
    s.tomorrowItems.length ? itemList(s.tomorrowItems, "", false)
      : emptyLine("Nothing is due tomorrow yet." + (waiting ? " A good start is whatever is still waiting below." : "")));
  tomorrowCard.id = "eod-tomorrow";
  root.appendChild(el("div", { class: "eod-grid" }, doneCard, tomorrowCard));

  const waitingCard = card(sectionTitle("hourglass", "blue", "Still waiting on you"));
  waitingCard.id = "eod-waiting";
  if (!waiting) waitingCard.appendChild(emptyLine("All clear. Nothing is waiting on you."));
  if (s.pending.length) {
    waitingCard.appendChild(el("p", { class: "small muted" }, "Most important first. Press Done when you've handled one."));
    waitingCard.appendChild(showMore(s.pending, 5, (items) => itemList(items, "", false)));
  }
  if (s.followUps.length) {
    waitingCard.appendChild(el("h4", { class: "eod-sub" }, icon("bell"), " Follow-ups to send"));
    waitingCard.appendChild(showMore(s.followUps, 5, followUpList));
  }
  root.appendChild(waitingCard);

  const newCard = card(sectionTitle("money", "teal", "New deals today"),
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
