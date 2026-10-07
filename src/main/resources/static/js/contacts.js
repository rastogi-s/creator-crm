/*
 * Creator CRM: Brand contacts: everyone at every brand, ranked, with import, export, merge and do-not-email.
 * Part of the dashboard; index.html loads these files in order and they share one scope (see core.js).
 */
"use strict";

// ---------- Contacts ----------

const CONTACT_ROLES = { PARTNERSHIPS: "Partnerships", PR: "PR", MARKETING: "Marketing", FOUNDER: "Founder",
  GENERAL: "General inbox", SUPPORT: "Support", OTHER: "Other" };
const CONTACT_SOURCES = { GMAIL: "your email", WEBSITE: "their website", HUNTER: "Hunter", APOLLO: "Apollo", FINDER: "a finder service",
  IMPORT: "your import", THIRD_PARTY: "someone else's list", MANUAL: "typed in by you", LEAD: "brand research", GUESS: "a guess" };
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
    el("button", { onclick: () => importCard.classList.toggle("hidden") }, "Import contacts"),
    el("a", { class: "btn", href: "/api/contacts/export.csv", download: "brand-contacts.csv" }, "Download all (CSV)")));

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
    root.appendChild(card(null, emptyLine("No contacts yet. Press Update from my email, or import a file or a picture.")));
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

const IMPORT_FIELDS = { email: "Email", name: "Full name", first: "First name", last: "Last name", title: "Job title",
  brand: "Brand or company", website: "Website", role: "Role", phone: "Phone", instagram: "Instagram", linkedin: "LinkedIn" };
/** Rows shown as boxes she can type in; longer files show a sample. */
const IMPORT_EDITABLE_ROWS = 25;

function fileAsBase64(f) {
  return new Promise((resolve, reject) => {
    const r = new FileReader();
    r.onload = () => resolve(String(r.result).replace(/^data:[^,]*,/, ""));
    r.onerror = () => reject(new Error("Couldn't open that file"));
    r.readAsDataURL(f);
  });
}

/**
 * Import from any file: a spreadsheet (CSV, Excel), contact cards from a phone (.vcf), a PDF, or a picture such as a
 * business card or screenshot. The file is read on this computer; she checks which column is which (and can fix
 * what was read off a picture), sees what would happen, then imports. Claude reads a picture only if she asks.
 */
function contactsImportCard(onDone) {
  const file = el("input", { type: "file", "aria-label": "File to import",
    accept: ".csv,.tsv,.txt,.xlsx,.xlsm,.xls,.vcf,.pdf,.png,.jpg,.jpeg,.gif,.webp,.bmp,.heic,image/*,text/csv,text/vcard" });
  const origin = el("select", { "aria-label": "Where these contacts came from" },
    el("option", { value: "" }, "Where are these from?"),
    el("option", { value: "OWN" }, "My own (people I met or emailed, cards I was given)"),
    el("option", { value: "THIRD_PARTY" }, "Bought, or got from someone else"),
    el("option", { value: "HUNTER" }, "Hunter"),
    el("option", { value: "APOLLO" }, "Apollo"),
    el("option", { value: "OTHER_FINDER" }, "Another contact-finder service"));
  const out = el("div", { class: "import-out" });
  let picked = null;

  const load = async (useClaude) => {
    clear(out);
    const f = file.files[0];
    if (!f) return;
    if (f.size > 8_000_000) { toast("That file is bigger than 8 MB. Split it into smaller files.", true); return; }
    out.appendChild(el("p", { class: "small muted" }, useClaude ? "Claude is reading the picture…" : "Reading " + f.name + "…"));
    picked = f;
    const r = await api("POST", "/api/contacts/import/read", { name: f.name, data: await fileAsBase64(f), claude: !!useClaude });
    if (picked !== f) return; // she picked another file meanwhile
    showRead(f, r);
  };

  const showRead = (f, r) => {
    clear(out);
    if (r.method) out.appendChild(el("p", { class: "small muted" }, r.method + "."));
    if (r.note) out.appendChild(el("p", {}, r.note));
    if (r.claudeCost) {
      out.appendChild(el("div", { class: "row" },
        el("button", { onclick: action(() => load(true)) },
          (r.rows.length ? "Read it with Claude instead" : "Read it with Claude") + " (" + r.claudeCost + ")"),
        el("span", { class: "small muted" }, "Uses your Claude credit. Only this picture is sent.")));
    }
    if (r.text) {
      out.appendChild(el("details", { class: "small" }, el("summary", {}, "What the app read"),
        el("pre", { class: "import-text" }, r.text)));
    }
    if (!r.rows.length) return;
    importGrid(out, f.name, r, origin, onDone);
  };

  file.addEventListener("change", action(() => load(false)));
  return card("Import contacts",
    el("p", { class: "small muted" }, "Spreadsheets (CSV or Excel, including Hunter, Apollo, Google and Outlook exports), contact cards "
      + "from your phone (.vcf), PDFs, and pictures like business cards or screenshots. Pictures are read on this computer for free. "
      + "You check every row before anything is saved; duplicates are merged and junk addresses skipped."),
    el("div", { class: "row" }, file, origin),
    el("p", { class: "small muted" }, "Lists you bought or got from someone else, and lists from contact-finder services, are for your "
      + "own pitches only: the app keeps them out of anything you share."),
    out);
}

/** Which column is which (one dropdown per column), the rows (editable when there are few), then what importing would do. */
function importGrid(out, fileName, r, origin, onDone) {
  const rows = r.rows.map((row) => r.headers.map((_, i) => row[i] == null ? "" : String(row[i])));
  const mapping = Object.assign({}, r.mapping);
  const result = el("div", {});
  const fieldOf = (i) => Object.keys(mapping).find((k) => mapping[k] === i) || "";

  const preview = action(async () => {
    clear(result);
    if (mapping.email === undefined) { result.appendChild(el("p", { class: "alert warn" }, "Pick which column has the email addresses.")); return; }
    const body = { headers: r.headers, rows, firstLine: r.firstLine, mapping, fileName };
    const p = await api("POST", "/api/contacts/import/preview", body);
    result.appendChild(el("p", {}, p.added + " new, " + p.merged + " already saved (blanks filled in), " + p.newBrands + " new brands, "
      + p.skipped + " skipped."));
    const skipped = p.rows.filter((x) => x.action === "SKIP").slice(0, 8);
    if (skipped.length) result.appendChild(el("ul", { class: "small muted" }, skipped.map((x) => el("li", {}, "Row " + x.line + ": " + (x.email || "(blank)") + " — " + x.note))));
    if (p.added + p.merged === 0) return;
    result.appendChild(el("button", { class: "primary", onclick: (e) => {
      if (!origin.value) { toast("Pick where these contacts came from first", true); origin.focus(); return; }
      action(async () => {
        await api("POST", "/api/contacts/import", Object.assign({ origin: origin.value }, body));
        onDone();
      }, "Imported")(e);
    } }, "Import " + (p.added + p.merged) + (p.added + p.merged === 1 ? " contact" : " contacts")));
  });

  const head = el("tr", {}, r.headers.map((h, i) => {
    const pick = el("select", { "aria-label": "Column " + (i + 1) + " is", onchange: () => {
      for (const k of Object.keys(mapping)) if (mapping[k] === i || k === pick.value) delete mapping[k];
      if (pick.value) mapping[pick.value] = i;
      head.querySelectorAll("select").forEach((s, j) => { s.value = fieldOf(j); });
      preview();
    } }, el("option", { value: "" }, "Skip this column"), Object.entries(IMPORT_FIELDS).map(([k, label]) => el("option", { value: k }, label)));
    pick.value = fieldOf(i);
    return el("th", {}, el("div", { class: "small muted import-head" }, h), pick);
  }));
  const editable = rows.length <= IMPORT_EDITABLE_ROWS;
  const shown = editable ? rows : rows.slice(0, 5);
  const body = el("tbody", {}, shown.map((row, ri) => el("tr", {}, row.map((v, ci) => el("td", {}, editable
    ? el("input", { value: v, "aria-label": r.headers[ci] + ", row " + (ri + 1), onchange: (e) => { rows[ri][ci] = e.target.value.trim(); preview(); } })
    : v)))));
  out.appendChild(el("p", { class: "small" }, editable ? "Check each column, and fix anything that was read wrong:" : "Check what each column is:"));
  out.appendChild(el("div", { class: "table-wrap" }, el("table", { class: "import-grid" }, el("thead", {}, head), body)));
  if (!editable) out.appendChild(el("p", { class: "small muted" }, "…and " + (rows.length - 5) + " more rows."));
  out.appendChild(result);
  preview();
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
    body.appendChild(el("p", { class: "small muted" }, "New pitches go to the top person who can be emailed."));
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
