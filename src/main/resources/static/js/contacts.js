/*
 * Creator CRM: Brand contacts: everyone at every brand, ranked, with import, export, merge and do-not-email.
 * Part of the dashboard; index.html loads these files in order and they share one scope (see core.js).
 */
"use strict";

// ---------- Contacts ----------

const CONTACT_ROLES = { PARTNERSHIPS: "Partnerships", PR: "PR", MARKETING: "Marketing", FOUNDER: "Founder",
  GENERAL: "General inbox", SUPPORT: "Support", OTHER: "Other" };
const CONTACT_SOURCES = { GMAIL: "your email", WEBSITE: "their website", HUNTER: "Hunter", APOLLO: "Apollo", FINDER: "a finder service",
  IMPORT: "your spreadsheet", MANUAL: "typed in by you", LEAD: "brand research", GUESS: "a guess" };
let contactsFilter = loadPrefs("contacts", { q: "", show: "all" });

function contactRankTone(score) {
  return score >= 60 ? "green" : score >= 35 ? "blue" : score > 0 ? "amber" : "grey";
}

/** One ranked person: score, who, why they rank there, and the two ways to stop emailing them. */
function contactRow(v, onChange, showBrand) {
  const c = v.contact;
  const stopped = !!v.doNotEmail || c.bounced || c.optedOut || c.verified === "INVALID";
  const who = c.name ? [el("strong", {}, c.name), " ", el("span", { class: "muted" }, c.email)] : [el("strong", {}, c.email)];
  const facts = [c.role === "OTHER" ? null : CONTACT_ROLES[c.role] || pretty(c.role), c.title, c.phone,
    c.lastRepliedAt ? "last replied " + fmtDate(c.lastRepliedAt) : null,
    v.sources.length ? "from " + v.sources.map((s) => CONTACT_SOURCES[s] || pretty(s)).join(", ") : null].filter(Boolean);
  return el("div", { class: "contact-row" + (stopped ? " stopped" : "") },
    el("span", { class: "rank tone-" + contactRankTone(c.score), title: "Rank score out of 100" }, String(c.score)),
    el("div", { class: "who" },
      el("div", {}, who, showBrand && v.brandName ? el("span", { class: "muted" }, " · " + v.brandName) : null),
      el("div", { class: "small" }, c.scoreReason || ""),
      el("div", { class: "small muted" }, facts.join(" · ")),
      v.doNotEmail ? el("div", { class: "small warn" }, v.doNotEmail) : null),
    el("div", { class: "actions" },
      stopped || c.verified === "VALID" || c.replies > 0 ? null : el("button", { class: "small",
        title: "Check the address exists before you email it (free check of the brand's email first, then half a Hunter credit)",
        onclick: action(async () => {
          const r = await api("POST", "/api/finders/contact/" + c.id + "/check");
          toast(r.message);
          onChange();
        }) }, "Check address"),
      stopped ? null : el("button", { class: "small", title: "Never send this person a pitch or follow-up",
        onclick: action(async () => { await api("POST", "/api/contacts/" + c.id + "/do-not-email"); onChange(); }, "Won't be emailed again") }, "Don't email"),
      el("button", { class: "small danger", title: "Delete everything about this person (keeps only their address so they're never added again)",
        onclick: (e) => {
          if (!confirm("Forget " + c.email + "? Their details are deleted, and they're never added or emailed again.")) return;
          action(async () => { await api("DELETE", "/api/contacts/" + c.id); onChange(); }, "Forgotten")(e);
        } }, "Forget")));
}

async function renderContacts(root) {
  const page = await api("GET", "/api/contacts");
  clear(root);
  root.appendChild(el("h1", {}, "Brand contacts"));
  root.appendChild(el("p", { class: "muted" }, "Everyone at every brand, best person to pitch first. People who have replied to you rank "
    + "highest, then partnerships and PR inboxes. Built from your email for free; nothing here uses Claude."));
  const redraw = () => renderContacts(root);

  root.appendChild(el("div", { class: "row" },
    el("button", { onclick: action(async () => {
      await api("POST", "/api/contacts/refresh");
      redraw();
    }, "Contacts updated from your email"), title: "Read your brand emails again for people, replies and signatures" }, "Update from my email"),
    el("button", { onclick: () => importCard.classList.toggle("hidden") }, "Import a spreadsheet"),
    el("a", { class: "btn", href: "/api/contacts/export.csv", download: "brand-contacts.csv" }, "Download all (CSV)"),
    el("a", { class: "btn", href: "#directory", title: "Only brand inboxes like collabs@, safe to share or sell" }, "Brand directory")));

  const importCard = contactsImportCard(redraw);
  importCard.classList.add("hidden");
  root.appendChild(importCard);

  if (page.duplicates.length) {
    root.appendChild(card("These might be the same brand",
      page.duplicates.map((d) => el("div", { class: "row" },
        el("div", { class: "spacer" }, el("strong", {}, d.brandName + " and " + d.otherBrandName), el("div", { class: "small muted" }, d.why)),
        el("button", { class: "small", onclick: (e) => {
          if (!confirm("Move everything from " + d.brandName + " into " + d.otherBrandName + "? Deals, emails, invoices and contacts all move.")) return;
          action(async () => {
            await api("POST", "/api/contacts/merge", { keepBrandId: d.otherBrandId, dropBrandId: d.brandId });
            redraw();
          }, "Merged")(e);
        } }, "Merge into " + d.otherBrandName)))));
  }

  const all = page.contacts;
  if (!all.length) {
    root.appendChild(card(null, emptyLine("No contacts yet. Press Update from my email, or import a spreadsheet.")));
    return;
  }
  const pf = contactsFilter;
  const save = () => savePrefs("contacts", pf);
  const shows = {
    all: () => true,
    replied: (v) => v.contact.replies > 0,
    fresh: (v) => v.contact.emailsSent === 0 && v.contact.replies === 0 && v.contact.score > 0,
    stopped: (v) => v.contact.score === 0 || !!v.doNotEmail,
  };
  const chips = el("div", {});
  const list = el("div", { class: "card contact-list" });
  const shown = el("div", {});
  root.appendChild(el("div", { class: "row filters" },
    el("div", { class: "spacer" }, searchBox(pf.q, "Search name, email, brand, role…", (q) => { pf.q = q; save(); draw(); })), chips));
  root.appendChild(shown);
  root.appendChild(list);
  function draw() {
    clear(chips).appendChild(chipRow([["all", "All " + all.length], ["replied", "Replied"], ["fresh", "Not contacted yet"], ["stopped", "Don't email"]],
      pf.show, (v) => { pf.show = v; save(); draw(); }, "Show"));
    const rows = all.filter((v) => (shows[pf.show] || shows.all)(v)
      && matchesQuery(pf.q, v.contact.name, v.contact.email, v.brandName, v.contact.title, CONTACT_ROLES[v.contact.role]));
    clear(shown);
    const line = shownLine(Math.min(rows.length, 300), all.length, "contacts", () => { pf.q = ""; pf.show = "all"; save(); renderContacts(root); });
    if (line) shown.appendChild(line);
    clear(list);
    if (!rows.length) list.appendChild(emptyLine("No contacts match."));
    rows.slice(0, 300).forEach((v) => list.appendChild(contactRow(v, redraw, true)));
  }
  draw();
}

/** Spreadsheet import: pick the file, say where it came from, see what would happen, then import. */
function contactsImportCard(onDone) {
  const file = el("input", { type: "file", accept: ".csv,text/csv" });
  const origin = el("select", { "aria-label": "Where this list came from" },
    el("option", { value: "" }, "Where is this list from?"),
    el("option", { value: "OWN" }, "My own list"),
    el("option", { value: "HUNTER" }, "Hunter"),
    el("option", { value: "APOLLO" }, "Apollo"),
    el("option", { value: "OTHER_FINDER" }, "Another contact-finder service"));
  const out = el("div", {});
  let text = null;
  file.addEventListener("change", action(async () => {
    clear(out);
    const f = file.files[0];
    if (!f) return;
    if (f.size > 5_000_000) { toast("That file is bigger than 5 MB. Split it into smaller files.", true); return; }
    text = await f.text();
    const p = await api("POST", "/api/contacts/import/preview", { csv: text });
    out.appendChild(el("p", {}, p.added + " new, " + p.merged + " already saved (blanks filled in), " + p.newBrands + " new brands, "
      + p.skipped + " skipped."));
    const skipped = p.rows.filter((r) => r.action === "SKIP").slice(0, 8);
    if (skipped.length) out.appendChild(el("ul", { class: "small muted" }, skipped.map((r) => el("li", {}, "Row " + r.line + ": " + (r.email || "(blank)") + " — " + r.note))));
    out.appendChild(el("button", { class: "primary", onclick: (e) => {
      if (!origin.value) { toast("Pick where this list came from first", true); return; }
      action(async () => {
        await api("POST", "/api/contacts/import", { csv: text, origin: origin.value });
        onDone();
      }, "Imported")(e);
    } }, "Import " + (p.added + p.merged) + " contacts"));
  }));
  return card("Import a spreadsheet",
    el("p", { class: "small muted" }, "Any CSV with an Email column works, including exports from Hunter and Apollo. Brand, website, name, "
      + "title and phone columns are picked up too. Duplicates are merged and junk addresses are skipped."),
    el("div", { class: "row" }, file, origin),
    el("p", { class: "small muted" }, "Lists from contact-finder services are for your own pitches only: their terms don't allow sharing or "
      + "selling them, so the app keeps them out of anything you share."),
    out);
}

/** Deal drawer: everyone at this brand, best first, a way to find more (Hunter, Apollo), and a quick way to add someone. */
async function brandContactsCard(brandId) {
  const box = el("div", { class: "card" }, el("h3", {}, "People at this brand"));
  const notes = el("div", { class: "small" });
  const body = el("div", {});
  const find = async (again) => {
    const r = await api("POST", "/api/finders/brand/" + brandId + "/find" + (again ? "?again=true" : ""));
    if (r.askFirst) {
      if (confirm(r.askFirst)) return find(true);
      return;
    }
    clear(notes).appendChild(el("p", {}, r.found ? "Found " + r.found + (r.found === 1 ? " person" : " people") + ": " + r.added + " new, "
      + r.alreadyKnown + " already saved" + (r.skipped ? ", " + r.skipped + " on your do-not-email list" : "") + "." : ""));
    r.notes.forEach((n) => notes.appendChild(el("p", { class: "muted" }, n)));
    await load();
  };
  box.appendChild(el("div", { class: "row" },
    el("button", { class: "small", title: "Ask Hunter (and Apollo, if you added it) who else works there. Uses 1 Hunter credit.",
      onclick: action(() => find(false)) }, "Find more people"),
    el("button", { class: "small", title: "Check that addresses not checked in the last six months exist. Half a Hunter credit each.",
      onclick: action(async () => {
        const r = await api("POST", "/api/finders/brand/" + brandId + "/check");
        clear(notes).appendChild(el("p", {}, r.checked ? "Checked " + r.checked + ": " + r.valid + " exist, " + r.risky
          + " can't be proven, " + r.invalid + " don't exist." : ""));
        r.notes.forEach((n) => notes.appendChild(el("p", { class: "muted" }, n)));
        await load();
      }) }, "Check addresses")));
  box.appendChild(notes);
  box.appendChild(body);
  async function load() {
    const list = await api("GET", "/api/contacts/brand/" + brandId);
    clear(body);
    if (!list.length) body.appendChild(el("p", { class: "small muted" }, "No one saved yet."));
    list.forEach((v) => body.appendChild(contactRow(v, load, false)));
    const email = el("input", { type: "email", placeholder: "Email", maxlength: "320", "aria-label": "Email" });
    const name = el("input", { placeholder: "Name (optional)", maxlength: "200", "aria-label": "Name" });
    const title = el("input", { placeholder: "Job title (optional)", maxlength: "200", "aria-label": "Job title" });
    body.appendChild(el("div", { class: "row" }, email, name, title,
      el("button", { class: "small", onclick: (e) => {
        if (!email.value.trim()) { toast("Type their email first", true); return; }
        action(async () => {
          await api("POST", "/api/contacts/brand/" + brandId, { email: email.value.trim(), name: name.value, title: title.value });
          load();
        }, "Contact added")(e);
      } }, "Add person")));
    const find = el("button", { class: "small", title: "Reads the brand's own contact, partnerships and press pages for published emails. Free, no Claude.",
      onclick: action(async () => {
        find.textContent = "Reading website…";
        try {
          const r = await api("POST", "/api/contacts/brand/" + brandId + "/website");
          toast(r.message);
          await load();
        } finally {
          find.textContent = "Find contacts on website";
        }
      }) }, "Find contacts on website");
    body.appendChild(el("div", { class: "row" }, find,
      el("span", { class: "small muted" }, "New pitches go to the top person who can be emailed.")));
  }
  await load();
  return box;
}

// ---------- Contact finders (Settings, Accounts) ----------
// Hunter finds people at a brand's domain and checks addresses; Apollo is an optional second finder. Neither uses
// Claude. Credits are counted per month against her own limit (Hunter Free: 50), like the Claude spending card.

async function contactFindersCard(root, creds, secretField, saveSecrets) {
  const st = await api("GET", "/api/finders").catch(() => null);
  if (!st) return el("div");
  const h = st.hunter, a = st.apollo;
  const n = (x) => Number.isInteger(x) ? String(x) : x.toFixed(1);
  const keys = el("div", { class: "grid" },
    secretField("HUNTER_API_KEY", "Hunter API key", "From hunter.io, API keys"),
    secretField("APOLLO_API_KEY", "Apollo API key (optional)", "From Apollo, Settings, API"));
  const hLimit = el("input", { type: "number", min: "0", step: "1", value: h.monthlyLimit });
  const aLimit = el("input", { type: "number", min: "0", step: "1", value: a.monthlyLimit });
  const fromHunter = h.serviceAvailable != null
    ? el("p", { class: "small muted" }, "Hunter says: " + (h.plan ? h.plan + " plan, " : "") + n(h.serviceUsed) + " of "
      + n(h.serviceAvailable) + " credits used" + (h.resetDate ? ", resets " + fmtDate(h.resetDate) : "") + ".")
    : null;
  const alert = [h.alert, a.alert].filter(Boolean).map((x) => el("p", { class: "alert " + (x.level === "OUT" ? "error" : "warn") }, x.message));
  const c = card("Contact finders (Hunter, Apollo)",
    el("p", { class: "small muted" }, "Find more people at a brand with one click (Find more people, on any deal), and check that an "
      + "address exists before you email it. No Claude is used. Hunter's free plan gives 50 credits a month: looking up a brand "
      + "costs 1, checking one address costs half. Addresses at a brand that can't receive email are caught for free every night."),
    h.connected || a.connected ? el("div", { class: "stats" },
      h.connected ? stat(n(h.usedThisMonth) + " / " + h.monthlyLimit, "Hunter credits used this month") : null,
      h.connected ? stat(n(h.remaining), "Hunter credits left") : null,
      a.connected ? stat(n(a.usedThisMonth) + " / " + a.monthlyLimit, "Apollo credits used this month") : null) : null,
    fromHunter,
    ...alert,
    keys,
    el("div", { class: "row" },
      el("button", { class: "primary small", onclick: saveSecrets(keys) }, "Save keys"),
      h.connected ? el("button", { class: "small", onclick: action(async () => { await api("POST", "/api/finders/refresh"); renderSettings(root); },
        "Credits updated from Hunter") }, "Update from Hunter") : null,
      h.connected ? el("button", { class: "small danger", onclick: action(async () => {
        await api("PUT", "/api/settings/credentials", { HUNTER_API_KEY: "" }); renderSettings(root); }, "Hunter removed") }, "Remove Hunter") : null,
      a.connected ? el("button", { class: "small danger", onclick: action(async () => {
        await api("PUT", "/api/settings/credentials", { APOLLO_API_KEY: "" }); renderSettings(root); }, "Apollo removed") }, "Remove Apollo") : null),
    el("div", { class: "grid" },
      el("div", {}, el("label", {}, "Most Hunter credits to use a month"), hLimit),
      el("div", {}, el("label", {}, "Most Apollo credits to use a month"), aLimit)),
    el("div", { class: "row" }, el("button", { class: "small", onclick: action(async () => {
      await api("PUT", "/api/finders/limits", { hunter: Number(hLimit.value), apollo: Number(aLimit.value) });
      renderSettings(root);
    }, "Saved") }, "Save limits")),
    el("p", { class: "small muted" }, "No account yet? Sign up free at hunter.io, then copy the key from API keys. People found through "
      + "Hunter or Apollo are for your own pitches only: their terms don't allow sharing or selling them, so the app keeps them out "
      + "of anything you share."));
  c.id = "finders";
  return c;
}
