/*
 * Creator CRM: Brand directory, the part of her contacts she may share or sell.
 * Part of the dashboard; index.html loads these files in order and they share one scope (see core.js).
 */
"use strict";

// ---------- Brand directory ----------
// Brands that work with creators and the inbox they publish for it. Never a named person, never anything bought
// from Hunter or Apollo, never anyone on the do-not-email list (the server decides, see BrandDirectory.java).

const DIRECTORY_PREVIEW = 50;

async function renderDirectory(root) {
  const d = await api("GET", "/api/contacts/directory");
  clear(root);
  const n = (count, one, many) => count + " " + (count === 1 ? one : many);
  root.appendChild(el("h1", {}, "Brand directory"));
  root.appendChild(el("p", { class: "muted" }, "A list of brands that work with creators and the inbox they use for it, "
    + "ready to share with other creators or sell as a pack. Only facts about brands go in, never people."));

  root.appendChild(el("div", { class: "card" },
    el("div", { class: "stats" },
      el("div", { class: "stat" }, el("div", { class: "v" }, String(d.brands)), el("div", { class: "l" }, d.brands === 1 ? "Brand" : "Brands")),
      el("div", { class: "stat" }, el("div", { class: "v" }, String(d.rows.length)), el("div", { class: "l" }, d.rows.length === 1 ? "Inbox" : "Inboxes")),
      el("div", { class: "stat" }, el("div", { class: "v" }, String(d.rows.filter((r) => r.answered).length)), el("div", { class: "l" }, "Have answered creators"))),
    el("div", { class: "row directory-actions" },
      d.rows.length
        ? el("a", { class: "btn primary", href: "/api/contacts/directory/export.csv", download: "brand-directory.csv" }, "Download CSV")
        : el("span", { class: "muted" }, "Nothing to share yet. Brand inboxes like collabs@ show up here as you find them."))));

  root.appendChild(el("div", { class: "card" },
    el("h3", {}, "What's in it"),
    el("ul", { class: "directory-why" },
      el("li", {}, "Brand name, niche (the search that found it), website and Instagram."),
      el("li", {}, "The brand's own team inbox, like collabs@, partnerships@ or pr@, and what kind of inbox it is."),
      el("li", {}, "Whether that inbox has answered you, and the last date it was seen working.")),
    el("h3", {}, "What's never in it, and why"),
    el("ul", { class: "directory-why" },
      el("li", {}, "People's names and personal addresses (" + d.leftOutPeople + " left out). Selling those would make you a "
        + "data broker, which California and other states require registering for, and GDPR would hold you responsible "
        + "for what every buyer does with them."),
      el("li", {}, "Anything found through Hunter, Apollo or another finder service (" + d.leftOutPaid + " left out). "
        + "Their terms let you email these people yourself but forbid passing them on."),
      el("li", {}, "Anyone on your do-not-email list, or whose email bounced (" + d.leftOutDoNotEmail + " left out). "
        + "They asked to be left alone, or the address doesn't work."),
      el("li", {}, "Gmail-style addresses, addresses the app only guessed and never saw work, and general addresses like "
        + "founder@ (" + d.leftOutOther + " left out).")),
    el("p", { class: "muted" }, "Your deals, rates, emails and notes never leave the app.")));

  if (!d.rows.length) return;
  const shown = d.rows.slice(0, DIRECTORY_PREVIEW);
  root.appendChild(el("div", { class: "card" },
    el("h3", {}, "Preview"),
    el("ul", { class: "list" }, shown.map((r) => el("li", { class: "item" },
      el("div", { class: "body" },
        el("div", { class: "title" }, r.brand, " ", r.answered ? el("span", { class: "badge ok" }, "Answers") : null),
        el("div", { class: "detail" }, [r.inbox, r.inboxType, r.niche].filter(Boolean).join(" · ")),
        el("div", { class: "detail" }, [r.website, r.instagram ? "@" + r.instagram : null,
          r.lastVerified ? "seen working " + fmtDate(r.lastVerified) : null].filter(Boolean).join(" · ")))))),
    d.rows.length > shown.length ? el("p", { class: "muted" }, "Showing " + shown.length + " of " + n(d.rows.length, "inbox", "inboxes")
      + ". The CSV has them all.") : null));
}
