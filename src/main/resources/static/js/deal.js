/*
 * Creator CRM: The deal drawer that opens over any page.
 * Part of the dashboard; index.html loads these files in order and they share one scope (see core.js).
 */
"use strict";

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

// Where a piece of brand news came from, as a link she can open to check it.
function newsSource(n) {
  const names = { EMAIL: "their email to you", INSTAGRAM: "their Instagram post", WEBSITE: "their website",
    NEWS: "a news article", WEB_SEARCH: "a web page" };
  const name = names[n.source] || "source";
  const href = n.source === "EMAIL" && n.messageId ? "/api/messages/" + n.messageId + "/email" : n.url;
  return href && (n.source === "EMAIL" || /^https?:\/\//i.test(href))
    ? el("a", { href, target: "_blank", rel: "noopener noreferrer" }, name) : name;
}

// A re-pitch's "why now": the news it opens with, and where it came from, so she can check it before sending.
function whyNow(n) {
  if (!n) return null;
  return el("div", { class: "why-now" },
    el("div", {}, el("strong", {}, "Why now: "), n.headline || n.title),
    el("div", { class: "small muted" }, "From ", newsSource(n), " · " + fmtDate(n.date) + ". Check it before you send: the email mentions it."));
}

// A past brand's recent launches and teasers, on the deal. The best fresh one opens its next re-pitch.
function brandNewsBox(brandId, brand) {
  const box = el("div", { class: "brand-news" });
  const draw = (items, note) => {
    const shown = items.filter((n) => !n.hidden).slice(0, 5);
    clear(box).append(el("h3", {}, "What " + brand + " is up to"),
      shown.length ? el("ul", { class: "list" }, shown.map((n) => el("li", { class: "item" },
        el("div", { class: "body" },
          el("div", { class: "title" }, n.kind ? el("span", { class: "badge" }, pretty(n.kind)) : null, " " + (n.headline || n.title)),
          el("div", { class: "detail" }, newsSource(n), " · " + fmtDate(n.date) + (n.used ? " · used in a re-pitch" : ""))),
        el("div", { class: "actions" }, el("button", { class: "small", title: "Not relevant: never open a re-pitch with this",
          onclick: action(async () => { await api("POST", "/api/news/" + n.id + "/hide"); load(); }, "Hidden") }, "Hide")))))
        : emptyLine("No recent launches or teasers found yet."),
      note ? el("p", { class: "small muted" }, note) : null,
      el("p", {}, el("button", { class: "small", onclick: action(async () => {
        const r = await api("POST", "/api/brands/" + brandId + "/news/check");
        draw(r.news, (r.added ? r.added + " new item" + (r.added === 1 ? "" : "s") + " found." : "Nothing new found.")
          + (r.notes.length ? " Couldn't read: " + r.notes.join("; ") : ""));
      }, "Checked") }, "Check for news")),
      el("p", { class: "small muted" }, "From their emails to you, their Instagram posts, their website and the news, last 45 days. "
        + "Checking reads their website slowly and politely, so it can take a minute."));
  };
  const load = async () => draw(await api("GET", "/api/brands/" + brandId + "/news"));
  load().catch(() => draw([], "Couldn't load their news."));
  return box;
}

// Reel and video ideas for a deal, written from the brand's guidelines, her profile and how her posts did.
function ideasCard(id, brand, v, refresh) {
  const box = el("div", { class: "card", id: "content-ideas" });
  const note = el("input", { maxlength: "1000", "aria-label": "What to change",
    placeholder: "Anything to change? e.g. funnier, no talking to camera, film it outdoors" });
  const paste = el("textarea", { maxlength: "60000", "aria-label": "The brand's brief",
    placeholder: "Paste the brief's text, e.g. copied from the Google Doc or Canva deck" });
  const go = el("button", { class: "small primary", onclick: action(async () => {
    go.disabled = true;
    go.textContent = "Writing ideas…";
    try {
      await api("POST", "/api/opportunities/" + id + "/ideas", { note: note.value, guidelines: paste.value });
      refresh();
    } finally {
      go.disabled = false;
    }
  }, "New ideas ready") }, v ? "Refresh ideas" : "Get ideas");
  box.append(el("div", { class: "row" }, el("h2", {}, "Content ideas"), el("div", { class: "spacer" }),
    v && v.createdAt ? el("span", { class: "small muted" }, "Written " + fmtDateTime(v.createdAt)) : null));
  if (!v) {
    box.append(el("p", { class: "small muted" }, "When " + brand + " sends the brief (in the email, or as a PDF, Word or PowerPoint file), "
      + "Claude writes reel and video ideas here by itself. You can also get ideas now from the deal details."));
  } else if (v.status === "FAILED") {
    box.append(el("p", { class: "small warn" }, v.error || "Claude couldn't write the ideas. Try again."));
  } else if (v.error) {
    box.append(el("p", { class: "small warn" }, v.error));
  }
  if (v && v.briefSummary) box.append(el("p", {}, v.briefSummary));
  const checks = (title, items, kind, mark) => items.length ? [el("h3", {}, title),
    el("ul", { class: "checks " + kind }, items.map((x) => el("li", {}, el("span", { class: "mark", "aria-hidden": "true" }, mark), el("span", {}, x))))] : null;
  if (v) box.append(...[checks("Must include", v.mustInclude, "must", "✓"), checks("Avoid", v.avoid, "avoid", "✕")].flat().filter(Boolean));
  if (v && v.ideas.length) {
    box.append(el("h3", {}, v.ideas.length + " ideas"));
    v.ideas.forEach((idea, i) => box.append(el("details", Object.assign({ class: "idea" }, i === 0 ? { open: true } : {}),
      el("summary", {}, el("span", {}, (i + 1) + ". " + idea.title), idea.format ? el("span", { class: "badge" }, idea.format) : null),
      idea.hook ? el("div", { class: "hook" }, el("strong", {}, "Hook: "), idea.hook) : null,
      idea.concept ? el("p", {}, idea.concept) : null,
      idea.shots && idea.shots.length ? el("ol", { class: "shots" }, idea.shots.map((s) => el("li", {}, s))) : null,
      idea.caption ? [el("div", { class: "row" }, el("strong", { class: "small" }, "Caption"), el("div", { class: "spacer" }),
        el("button", { class: "small", title: "YouTube, Instagram and TikTok captions for this idea, with your shop links",
          onclick: () => { closeDrawer(); openCaptions({ about: idea.title + ". " + (idea.concept || ""), opportunityId: Number(id) }); } }, "Write captions"),
        copyButton(() => idea.caption, "Copy", { class: "copy-btn small" })),
        el("div", { class: "caption" }, idea.caption)] : null,
      idea.whyItFits ? el("p", { class: "small muted" }, "Why it fits you: " + idea.whyItFits) : null)));
  }
  const pasteOpen = v ? v.source === "DEAL" || (v.error || "").includes("Paste its text") : false;
  box.append(el("div", { class: "row" }, note, go),
    el("details", pasteOpen ? { open: true } : {}, el("summary", { class: "small" }, "Brief behind a link? Paste its text"), paste),
    el("p", { class: "small muted" }, "Based on " + (v && v.fileNames ? brand + "'s brief (" + v.fileNames + ")" : v && v.hasGuidelines ? brand + "'s brief" : "the deal")
      + ", your About you, your recent Instagram posts and how your past brand posts did. Each refresh costs a few cents of Claude."));
  return box;
}

function closeDrawer() {
  document.getElementById("drawer").classList.add("hidden");
  document.getElementById("drawer-backdrop").classList.add("hidden");
}

// Scrolls the open deal to one message and highlights it for a moment.
// Web addresses in a message's text (with https:// or starting with www.) become links that open in a new window.
function linkify(text) {
  const out = [];
  let last = 0;
  for (const m of text.matchAll(/\b(?:https?:\/\/|www\.)[^\s<>"]+/gi)) {
    const url = m[0].replace(/[.,;:!?)\]']+$/, "");
    if (url.length <= 4) continue;
    const href = /^www\./i.test(url) ? "https://" + url : url;
    out.push(text.slice(last, m.index), el("a", { href, target: "_blank", rel: "noopener noreferrer" }, url));
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

  // Everyone at the brand, ranked: who new pitches go to
  if (d.brand && d.brand.id) drawer.appendChild(await brandContactsCard(d.brand.id));

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
      // Red and amber lines get a tick box: the ones she ticks go into an email asking the brand to change or explain them
      const askable = c.status === "CHECKED" && c.flags.some((f) => f.level !== "OK");
      const picks = [];
      if (c.flags.length) {
        box.appendChild(el("ul", { class: "flags" }, c.flags.map((f) => {
          const mark = el("span", { class: "mark", "aria-label": { RED: "Push back", AMBER: "Look at", OK: "Fine" }[f.level] }, icon[f.level]);
          if (!askable || f.level === "OK") return el("li", { class: "flag " + f.level.toLowerCase() }, mark, el("span", {}, f.text));
          const tick = el("input", { type: "checkbox", checked: f.level === "RED", title: "Ask the brand about this" });
          picks.push({ tick, text: f.text });
          return el("li", { class: "flag pick " + f.level.toLowerCase() }, el("label", {}, tick, mark, el("span", {}, f.text)));
        })));
      }
      if (askable) {
        const note = el("input", { placeholder: "Anything else to ask, e.g. 'can we post a week later?'", maxlength: "1000" });
        box.appendChild(el("div", { class: "ask-brand" },
          el("p", { class: "small muted" }, "Tick what you want changed or explained, and Claude writes the brand a reply in your voice."),
          el("div", { class: "row" }, note, el("button", { class: "primary small", onclick: action(async () => {
            const flags = picks.filter((p) => p.tick.checked).map((p) => p.text);
            if (!flags.length && !note.value.trim()) throw new Error("Tick at least one point to ask about");
            const made = await api("POST", "/api/contracts/" + c.id + "/questions", { flags, note: note.value });
            closeDrawer(); showDraft(made);
          }, "Email drafted — review it before sending") }, "Draft email to brand"))));
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

  // Content ideas: written by themselves when the brand sends its brief, or on demand
  const ideas = await api("GET", "/api/opportunities/" + id + "/ideas");
  if (ideas || ["PRODUCT_PENDING", "PRODUCT_RECEIVED", "CONTENT_TO_CREATE", "AWAITING_APPROVAL"].includes(o.status)
      || ["PRODUCT", "CREATE_CONTENT"].includes(o.stage)) {
    drawer.appendChild(ideasCard(id, d.summary.brand, ideas, refresh));
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
      el("p", { class: "small muted" }, "Draft a short re-pitch that mentions this collab, and opens with their latest launch if they have one. "
        + "Sending it starts a new pitch with " + d.summary.brand + "."),
      el("p", {}, el("button", { class: "small", onclick: action(async () => {
        const made = await api("POST", "/api/opportunities/" + id + "/repitch");
        closeDrawer(); showDraft(made);
      }, "Re-pitch drafted — review it before sending") }, "Pitch them again")),
      d.brand && d.brand.id ? brandNewsBox(d.brand.id, d.summary.brand) : null));
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
      : f.status === "SCHEDULED" ? "Due " + fmtDate(f.scheduledDate)
      : f.stoppedReason ? "Stopped: " + f.stoppedReason : "Cancelled (brand replied or stopped)"))));
  drawer.appendChild(card("Follow-ups",
    fuItems.length ? el("ul", { class: "list" }, fuItems) : emptyLine("No follow-ups yet."),
    el("div", { class: "row" },
      el("button", { class: "small", onclick: action(async () => { await api("POST", "/api/opportunities/" + id + "/followups/sent"); refresh(); }, "Recorded") }, "I sent a follow-up"),
      el("button", { class: "small danger", onclick: action(async () => { await api("POST", "/api/opportunities/" + id + "/followups/stop"); refresh(); }, "Follow-ups stopped") }, "Stop following up"))));

  // Tasks
  const taskInput = el("input", { placeholder: "Add a task…", maxlength: "500" });
  const taskDue = el("input", { type: "date" });
  drawer.appendChild(card("Tasks",
    d.tasks.length ? el("ul", { class: "list" }, d.tasks.map((t) => {
      const ask = t.status === "OPEN" ? tellClaude(t.id) : null;
      return el("li", { class: "item" },
      el("div", { class: "body" }, el("div", { class: "title" }, t.description),
        el("div", { class: "detail" }, pretty(t.status) + (t.dueDate ? " · due " + fmtDate(t.dueDate) : "")),
        t.autoClosedReason ? el("div", { class: "detail auto-closed" }, "Closed automatically: " + t.autoClosedReason) : null,
        t.status === "OPEN" ? taskAbout(t.brief, t.links) : null,
        ask ? ask.form : null),
      t.autoClosedReason ? el("div", { class: "actions" },
        el("button", { class: "small", title: "This still needs you: put it back on your list", onclick: action(async () => {
          await api("POST", "/api/tasks/" + t.id + "/reopen"); refresh();
        }, "Back on your list") }, "Reopen")) : null,
      t.status === "OPEN" ? el("div", { class: "actions" },
        ask.button,
        t.sourceMessageId && d.messages.some((m) => m.id === t.sourceMessageId)
          ? el("button", { class: "small", title: "Read the message this to-do came from", onclick: () => showMessage(t.sourceMessageId) }, "Read email") : null,
        el("button", { class: "small", onclick: action(async () => { await api("POST", "/api/tasks/" + t.id + "/done"); refresh(); }, "Done") }, "Done"),
        el("button", { class: "small", onclick: action(async () => { await api("POST", "/api/tasks/" + t.id + "/dismiss"); refresh(); }) }, "Dismiss")) : null);
    })) : emptyLine("No tasks."),
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
        el("div", { class: "detail" }, (x.done ? "✓ done · " : "") + (x.calendarEventId ? "on your calendar · " : "") + (x.description || "")),
        x.doneReason ? el("div", { class: "detail auto-closed" }, "Cleared automatically: " + x.doneReason) : null),
      x.doneReason ? el("div", { class: "actions" },
        el("button", { class: "small", title: "This date still matters: watch it again", onclick: action(async () => {
          await api("POST", "/api/deadlines/" + x.id + "/reopen"); refresh();
        }, "Back on your list") }, "Reopen")) : null)))));
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

// Opens a deal at one of its cards, e.g. the content ideas from a "create content" to-do.
async function openDealAt(id, cardId) {
  await openDeal(id);
  const node = document.getElementById(cardId);
  if (node) node.scrollIntoView({ behavior: "smooth", block: "start" });
}

function facts(pairs) {
  return el("table", {}, el("tbody", {}, pairs.filter(([, v]) => v).map(([k, v]) => el("tr", {}, el("th", {}, k), el("td", {}, v)))));
}
