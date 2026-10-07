/*
 * Creator CRM: Outreach: pitches and finding brands.
 * Part of the dashboard; index.html loads these files in order and they share one scope (see core.js).
 */
"use strict";

// ---------- Outreach ----------

const outreachFilter = loadPrefs("outreach", { q: "", response: "all", status: "", hideClosed: false, sort: { by: "pitchedAt", dir: "desc" } });

async function renderOutreach(root) {
  const [rows, leads, ig] = await Promise.all([api("GET", "/api/pitches"), api("GET", "/api/leads"),
    api("GET", "/api/leads/instagram").catch(() => null)]);
  clear(root);
  root.appendChild(dealsSwitch("outreach"));
  root.appendChild(el("h1", {}, "Pitching brands"));
  root.appendChild(el("p", { class: "muted" }, "Every brand you've pitched, with automatic follow-up dates. Pitches you send from Gmail are detected automatically; log the rest here."));
  if (ig && ig.instagramConnected) root.appendChild(engagingBrandsCard(root, ig));
  root.appendChild(findBrandsCard(root, leads, ig));

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
  // Collapsible, so a long pitch table isn't pushed far down the page. Remembers whether it was open.
  const logOpen = loadPrefs("outreach-log", { open: true }).open;
  const logBox = el("details", { class: "card collapsible", open: logOpen },
    el("summary", {}, el("h3", {}, "Log a pitch")));
  logBox.addEventListener("toggle", () => savePrefs("outreach-log", { open: logBox.open }));
  root.appendChild(logBox);
  logBox.appendChild(el("div", {}, el("div", { class: "grid" },
    el("div", {}, el("label", {}, "Brand *"), f.brand), el("div", {}, el("label", {}, "Contact name"), f.contactName),
    el("div", {}, el("label", {}, "Contact email"), f.contactEmail), el("div", {}, el("label", {}, "Instagram"), f.instagram),
    el("div", {}, el("label", {}, "Platform"), f.platform), el("div", {}, el("label", {}, "Date pitched"), f.pitchedAt)),
    el("label", {}, "What you pitched"), f.opportunity,
    el("p", {}, el("button", { class: "primary", onclick: () => { if (f.brand.value.trim()) submit(false); else toast("Brand is required", true); } }, "Log pitch"))));

  if (!rows.length) { root.appendChild(card(null, emptyLine("No pitches yet."))); return; }
  const pf = outreachFilter;
  const save = () => savePrefs("outreach", pf);
  const n = rows[0].followUps.length;
  const statusNames = [...new Set(rows.map((r) => r.status))];
  if (pf.status && !statusNames.includes(pf.status)) pf.status = "";
  const statusSelect = el("select", { "aria-label": "Status", onchange: (e) => { pf.status = e.target.value; save(); draw(); } },
    el("option", { value: "" }, "All statuses"), statusNames.map((v) => el("option", { value: v, selected: pf.status === v }, v)));
  const hideClosed = el("input", { type: "checkbox", checked: pf.hideClosed, onchange: (e) => { pf.hideClosed = e.target.checked; save(); draw(); } });
  const search = searchBox(pf.q, "Search brand, contact, pitch…", (v) => { pf.q = v; save(); draw(); });
  const chips = el("div", {});
  root.appendChild(el("h2", {}, "Your pitches"));
  root.appendChild(el("div", { class: "row filters" }, el("div", { class: "spacer" }, search), chips, statusSelect,
    el("label", { class: "check" }, hideClosed, "Hide closed")));
  const list = el("div", {});
  root.appendChild(list);
  const responses = { all: () => true, none: (r) => !r.initialResponse, replied: (r) => !!r.initialResponse };

  function draw() {
    clear(chips).appendChild(chipRow([["all", "Any response"], ["none", "No reply yet"], ["replied", "Replied"]],
      pf.response, (v) => { pf.response = v; save(); draw(); }, "Response"));
    clear(list);
    const shown = sortRows(rows.filter((r) => (responses[pf.response] || responses.all)(r) && (!pf.hideClosed || !r.closed)
      && (!pf.status || r.status === pf.status)
      && matchesQuery(pf.q, r.brand, r.contact, r.contactSearch, r.opportunity, r.initialResponse, pretty(r.platform), r.status)),
    pf.sort, { brand: (r) => r.brand, pitchedAt: (r) => r.pitchedAt, nextFollowUp: (r) => r.nextFollowUp, status: (r) => r.status });
    const line = shownLine(shown.length, rows.length, "pitches", () => {
      Object.assign(pf, { q: "", response: "all", status: "", hideClosed: false });
      search.value = ""; statusSelect.value = ""; hideClosed.checked = false; save(); draw();
    });
    if (line) list.appendChild(line);
    if (!shown.length) { list.appendChild(card(null, emptyLine("No pitches match."))); return; }
    list.appendChild(el("div", { class: "card table-wrap" }, el("table", {},
      sortableHead([["Brand", "brand"], ["Contact", null], ["Pitched", "pitchedAt", "desc"], ["Platform", null], ["Opportunity", null], ["Response", null]]
        .concat(Array.from({ length: n }, (_, i) => ["FU #" + (i + 1), null, null, "fu-col"]))
        .concat([["Follow-ups", null, null, "fu-sum"]])
        .concat([["Next due", "nextFollowUp", "asc"], ["Status", "status"]]),
      pf.sort, (s) => { pf.sort = s; save(); draw(); }),
      el("tbody", {}, shown.map((r) => el("tr", { class: "clickable", onclick: () => openDeal(r.opportunityId) },
        el("td", { class: "brand-cell" }, el("strong", {}, r.brand)), el("td", { class: "clip", title: r.contact || null }, r.contact || ""),
        el("td", { class: "nowrap" }, fmtDate(r.pitchedAt)),
        el("td", { class: "nowrap" }, pretty(r.platform)), el("td", { class: "opp-cell" }, r.opportunity || ""), el("td", {}, r.initialResponse ? pretty(r.initialResponse) : "—"),
        r.followUps.map((x) => el("td", { class: "fu-col nowrap" + (x.startsWith("due") ? "" : " muted") }, shortFollowUp(x))),
        el("td", { class: "fu-sum" }, followUpDots(r.followUps)),
        el("td", { class: "nowrap" }, r.nextFollowUp ? fmtDate(r.nextFollowUp) : "—"),
        el("td", { class: "nowrap" }, r.status)))))));
  }
  draw();
}

// A follow-up cell from the server reads "due 2026-10-09" or "✓ 2026-10-05": show the date the way the rest of the app does.
function shortFollowUp(x) {
  return (x || "").replace(/\d{4}-\d{2}-\d{2}/, (d) => fmtDate(d));
}

// Laptop: the five follow-ups as one row of dots (sent, due, not yet), with each one's date on hover.
function followUpDots(list) {
  const sent = list.filter((x) => x.startsWith("✓")).length;
  return el("span", { class: "fu-dots", title: list.map((x, i) => "#" + (i + 1) + ": " + (shortFollowUp(x) || "—")).join("\n") },
    list.map((x) => el("span", { class: "fu-dot" + (x.startsWith("✓") ? " sent" : x.startsWith("due") ? " due" : ""), "aria-hidden": "true" })),
    el("span", { class: "fu-text" }, sent + " of " + list.length + " sent"));
}

const compactNum = (n) => Number(n).toLocaleString(undefined, { notation: "compact", maximumFractionDigits: 1 });

// Accounts that tagged, mentioned or commented on her: the warmest brands to pitch.
function engagingBrandsCard(root, ig) {
  const kinds = { TAG: "tagged you", MENTION: "mentioned you", COMMENT: "commented" };
  const check = action(async () => {
    const r = await api("POST", "/api/leads/instagram/check");
    toast(r.accounts.length ? "Checked your recent posts" : "Checked your recent posts; no brands yet");
    renderOutreach(root);
  });
  const row = (a) => el("div", { class: "item" },
    el("div", { class: "body" },
      el("div", { class: "row" },
        el("a", { href: "https://www.instagram.com/" + encodeURIComponent(a.username) + "/", target: "_blank", rel: "noopener noreferrer" }, el("strong", {}, "@" + a.username)),
        a.mentions ? el("span", { class: "badge" }, "Tagged or mentioned you" + (a.mentions > 1 ? " ×" + a.mentions : "")) : null,
        a.comments ? el("span", { class: "badge" }, a.comments + " comment" + (a.comments === 1 ? "" : "s")) : null,
        a.isBusiness ? el("span", { class: "badge" }, "Business account" + (a.followers != null ? " · " + compactNum(a.followers) + " followers" : "")) : null),
      a.lastText ? el("div", { class: "detail" }, "Latest (" + kinds[a.lastKind] + ", " + fmtDate(a.lastSeenAt) + "): “" + a.lastText + "”") : null,
      a.lastPermalink && /^https:\/\/(www\.)?instagram\.com\//.test(a.lastPermalink)
        ? el("a", { href: a.lastPermalink, target: "_blank", rel: "noopener noreferrer", class: "small" }, "Open post") : null),
    el("div", { class: "actions" },
      el("button", { class: "small primary", onclick: action(async () => {
        await api("POST", "/api/leads/instagram/" + a.id + "/lead"); renderOutreach(root);
      }, "Added to your brand leads below") }, "Add as lead"),
      el("button", { class: "small", onclick: action(async () => {
        await api("POST", "/api/leads/instagram/" + a.id + "/dismiss"); renderOutreach(root);
      }) }, "Not a brand")));
  return card("Brands engaging with you on Instagram",
    el("p", { class: "small muted" }, "Accounts that tagged you in a post, @mentioned you or commented on your recent posts. Brands that already know you are the easiest to pitch. "
      + (ig.facebookConnected ? "Personal accounts (fans) are hidden automatically." : "Connect Facebook on the Settings page to hide fans automatically and see posts you're tagged in.")
      + " Checked each time the app looks for new messages."),
    ig.error ? el("div", { class: "alert error small" }, ig.error) : null,
    ig.accounts.length ? el("div", {}, ig.accounts.map(row)) : emptyLine("No brands have tagged, mentioned or commented on you yet."),
    el("div", { class: "row" }, el("button", { class: "small", onclick: check }, "Check now")));
}

function findBrandsCard(root, leads, ig) {
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
      // Price first, so it still shows when a phone cuts the end off.
      depthNames[o.depth] + ", " + (o.measured ? "" : "about ") + usd(o.usd) + (o.measured ? " on average" : "")
      + ": up to " + o.maxSearches + " searches (" + depthNotes[o.depth] + ")")));
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
  // Look up a brand's Instagram account by handle (needs the Facebook connection).
  const handle = el("input", { placeholder: "@brandhandle", maxlength: "100", "aria-label": "Brand's Instagram handle" });
  const lookup = action(async () => {
    const h = handle.value.trim().replace(/^@/, "");
    if (!h) throw new Error("Type the brand's Instagram handle");
    const l = await api("POST", "/api/leads/lookup", { handle: h });
    toast(l.name + " added to your brand leads");
    renderOutreach(root);
  });
  handle.addEventListener("keydown", (e) => { if (e.key === "Enter") lookup(e); });
  const lookupRow = ig && ig.facebookConnected
    ? el("div", { class: "row" }, el("span", { class: "small muted" }, "Know a brand already?"), el("div", { class: "spacer" }, handle),
      el("button", { class: "small", onclick: lookup }, "Look up on Instagram"))
    : el("p", { class: "small muted" }, "Tip: connect Facebook on the Settings page to look up any brand's Instagram (followers, bio, creators they work with) and add it here.");
  // Free search by category: Wikidata's open brand data, then each brand's own website for emails. No Claude.
  const category = el("input", { placeholder: "A category, e.g. skincare, running shoes, coffee", maxlength: "100", "aria-label": "Category" });
  const categoryStatus = el("span", { class: "small muted" });
  const byCategory = action(async () => {
    if (category.value.trim().length < 3) throw new Error("Type a category, like skincare or running shoes");
    categoryStatus.textContent = "Looking up brands in the free brand database…";
    try {
      const r = await api("POST", "/api/leads/category", { category: category.value, count: Number(count.value) });
      toast(r.message);
      renderOutreach(root);
      if (r.added.length) setTimeout(() => { if (currentTab() === "outreach") renderOutreach(root); }, 60000);
    } finally { categoryStatus.textContent = ""; }
  });
  category.addEventListener("keydown", (e) => { if (e.key === "Enter") byCategory(e); });
  const categoryRow = el("div", { class: "row find-category" }, el("div", { class: "query" }, category),
    el("button", { onclick: byCategory, title: "Uses Wikidata, a free open database of companies, then reads each brand's own website for published emails" }, "Search category (free)"));
  return card("Find brands to pitch",
    el("p", { class: "small muted" }, "Claude searches the web for brands that fit your profile, checks their sites for a published partnerships or PR email, and suggests a pitch idea. Pick the ones you like and a pitch draft lands in Drafts for you to edit and send. Nothing is sent automatically."),
    el("div", { class: "row find-brands" }, el("div", { class: "query" }, query), count, el("button", { class: "primary", onclick: search }, "Find brands")),
    el("div", { class: "row find-depth" }, el("span", { class: "small muted" }, "Search depth:"), depth),
    status,
    el("p", { class: "small muted" }, "Or search a category for free (no Claude): brands come from Wikidata, an open database of companies, and their websites are read for published emails."),
    categoryRow, categoryStatus,
    lookupRow,
    leads.length ? el("div", {}, leads.map((l) => leadItem(root, l, ig && ig.facebookConnected))) : null);
}

function leadItem(root, l, canLookUp) {
  const safeUrl = (u) => u && /^https?:\/\//i.test(u) ? u : null;
  const email = el("input", { type: "email", placeholder: "Contact email", value: l.contactEmail || "", maxlength: "320" });
  const ig = el("input", { placeholder: "Instagram handle", value: l.instagram || "", maxlength: "100" });
  const contactEdit = el("div", { class: "grid hidden" }, email, ig,
    el("button", { class: "small", onclick: action(async () => {
      await api("PUT", "/api/leads/" + l.id + "/contact", { email: email.value, instagram: ig.value.replace(/^@/, "") });
      renderOutreach(root);
    }, "Contact saved") }, "Save contact"));
  const contact = l.contactEmail
    ? el("span", {}, icon("mail"), " " + l.contactEmail, safeUrl(l.contactSourceUrl) ? el("a", { href: l.contactSourceUrl, target: "_blank", rel: "noopener noreferrer", class: "small" }, " (source)") : null)
    : el("span", { class: "muted" }, "No published email found");
  return el("div", { class: "item" },
    el("div", { class: "body" },
      el("div", { class: "row" }, el("strong", {}, l.name),
        safeUrl(l.website) ? el("a", { href: l.website, target: "_blank", rel: "noopener noreferrer", class: "small" }, l.website.replace(/^https?:\/\//, "")) : null,
        l.instagram ? el("span", { class: "badge" }, "@" + l.instagram) : null,
        l.source === "INSTAGRAM" ? el("span", { class: "badge" }, "Engaged with you") : null,
        l.source === "LOOKUP" ? el("span", { class: "badge" }, "Looked up") : null,
        l.source === "CATEGORY" ? el("span", { class: "badge" }, l.searchQuery) : null),
      l.fitReason ? el("div", { class: "detail" }, l.fitReason) : null,
      l.igCheckedAt ? el("div", { class: "detail" }, icon("camera"), " " + [l.igFollowers != null ? compactNum(l.igFollowers) + " followers" : null,
        l.igBio, l.igPartners ? "Works with " + l.igPartners.split(",").map((h) => "@" + h).join(", ") : null].filter(Boolean).join(" · ")) : null,
      l.pitchAngle ? el("div", { class: "detail" }, icon("bulb"), " " + l.pitchAngle) : null,
      el("div", { class: "small" }, contact),
      contactEdit),
    el("div", { class: "actions" },
      el("button", { class: "small primary", onclick: action(async () => {
        const made = await api("POST", "/api/leads/" + l.id + "/pitch");
        showDraft(made);
      }, "Pitch drafted. Review it in Drafts") }, "Draft pitch"),
      el("button", { class: "small", onclick: () => contactEdit.classList.toggle("hidden") }, "Edit contact"),
      !l.contactEmail && safeUrl(l.website) ? el("button", { class: "small", title: "Reads the brand's contact, partnerships and press pages for a published email. Free, no Claude.",
        onclick: action(async (e) => {
          e.currentTarget.textContent = "Reading website…";
          const r = await api("POST", "/api/leads/" + l.id + "/website");
          toast(r.message);
          renderOutreach(root);
        }) }, "Find contacts on website") : null,
      canLookUp && l.instagram ? el("button", { class: "small", onclick: action(async () => {
        await api("POST", "/api/leads/" + l.id + "/instagram"); renderOutreach(root);
      }, "Instagram details updated") }, l.igCheckedAt ? "Refresh Instagram" : "Check Instagram") : null,
      el("button", { class: "small danger", onclick: action(async () => { await api("POST", "/api/leads/" + l.id + "/dismiss"); renderOutreach(root); }) }, "Dismiss")));
}
