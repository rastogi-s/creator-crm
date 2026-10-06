/*
 * Creator CRM: Links: her link-in-bio list.
 * Part of the dashboard; index.html loads these files in order and they share one scope (see core.js).
 */
"use strict";

// ---------- Links ----------

/** Brand colours for well-known profiles; any other link gets a soft colour picked from its name. */
const LINK_BRANDS = {
  "instagram.com": ["Instagram", "ig"], "tiktok.com": ["TikTok", "tt"], "youtube.com": ["YouTube", "yt"], "youtu.be": ["YouTube", "yt"],
  "x.com": ["X", "x"], "twitter.com": ["X", "x"], "threads.net": ["Threads", "x"], "facebook.com": ["Facebook", "fb"],
  "pinterest.com": ["Pinterest", "pin"], "linkedin.com": ["LinkedIn", "li"], "snapchat.com": ["Snapchat", "snap"],
  "twitch.tv": ["Twitch", "twitch"], "amazon.com": ["Amazon", "amz"], "canva.site": ["Canva", "canva"],
};
const SOCIAL_HOSTS = ["instagram.com", "tiktok.com", "youtube.com", "youtu.be", "x.com", "twitter.com", "threads.net",
  "facebook.com", "pinterest.com", "linkedin.com", "snapchat.com", "twitch.tv"];
let linksAddMode = null; // which "Add links" tab is open; remembered while she stays on the page

function linkHost(url) {
  try { return new URL(url).hostname.toLowerCase().replace(/^(www|m)\./, ""); } catch (e) { return url; }
}
function brandOf(host) {
  const key = Object.keys(LINK_BRANDS).find((h) => host === h || host.endsWith("." + h));
  return key ? LINK_BRANDS[key] : null;
}
function isSocial(url) { const h = linkHost(url); return SOCIAL_HOSTS.some((s) => h === s || h.endsWith("." + s)); }
/** Same rule as the server: scheme, "www.", host case and a trailing slash don't make a link different. */
function sameLinkKey(url) {
  let v = String(url).trim().replace(/^https?:\/\//i, "");
  if (!/^[a-z][a-z0-9+.-]*:/i.test(v)) v = v.replace(/^www\./i, "");
  const i = v.indexOf("/");
  return (i < 0 ? v : v.slice(0, i)).toLowerCase() + (i < 0 ? "" : v.slice(i)).replace(/\/+$/, "");
}

function linkIcon(l) {
  const host = linkHost(l.url);
  const brand = brandOf(host);
  const letter = ((l.label || host).match(/[\p{L}\p{N}]/u) || ["•"])[0].toUpperCase();
  if (brand) return el("span", { class: "link-icon brand-" + brand[1], "aria-hidden": "true" }, letter);
  let h = 0;
  for (const c of l.label || host) h = (h * 31 + c.charCodeAt(0)) >>> 0;
  return el("span", { class: "link-icon tone-" + (h % 6), "aria-hidden": "true" }, letter);
}

async function renderLinks(root) {
  const links = await api("GET", "/api/links");
  clear(root);
  const asText = () => links.map((l) => l.label + ": " + l.url).join("\n");
  root.appendChild(el("div", { class: "links-head" },
    el("div", {},
      el("h1", {}, "My links"),
      el("p", { class: "muted" }, "Your profiles, shop and portfolio in one place. Drafts use these exact links instead of placeholders like [MEDIA KIT LINK].")),
    links.length ? el("button", { onclick: action(async () => { await navigator.clipboard.writeText(asText()); }, "All links copied") }, "Copy all") : null));

  if (!links.length) {
    root.appendChild(el("div", { class: "links-empty" },
      el("div", { class: "links-empty-art", "aria-hidden": "true" }, icon("link")),
      el("h3", {}, "No links yet"),
      el("p", { class: "muted" }, "Bring everything over from your Linktree in one go, or add links one by one.")));
    root.appendChild(addLinksCard(root, links, linksAddMode || "linktree"));
    return;
  }

  const socials = links.filter((l) => isSocial(l.url));
  if (socials.length) {
    root.appendChild(el("div", { class: "links-socials", role: "list", "aria-label": "Profiles" },
      socials.map((l) => el("a", { class: "social-chip", role: "listitem", href: l.url, target: "_blank", rel: "noopener noreferrer", title: l.url },
        linkIcon(l), el("span", {}, l.label)))));
  }

  root.appendChild(el("div", { class: "links-count muted" }, links.length + " link" + (links.length === 1 ? "" : "s") + " · the order here is the order drafts see them"));
  root.appendChild(el("ul", { class: "links-list" }, links.map((l, i) => linkCard(root, l, i, links.length))));
  root.appendChild(addLinksCard(root, links, linksAddMode || "one"));
}

function linkCard(root, l, i, n) {
  const safe = /^https?:\/\//i.test(l.url); // the server only stores http(s), but never render anything else as a link
  const host = linkHost(l.url);
  const move = (up) => action(async () => { await api("POST", "/api/links/" + l.id + "/move?up=" + up); renderLinks(root); });
  const li = el("li", { class: "link-card" },
    linkIcon(l),
    el("div", { class: "link-body" },
      el("div", { class: "link-label" }, l.label),
      safe ? el("a", { class: "link-url", href: l.url, target: "_blank", rel: "noopener noreferrer", title: l.url }, host + shortPath(l.url))
        : el("div", { class: "link-url" }, l.url)),
    el("div", { class: "link-actions" },
      el("button", { class: "small", onclick: action(async () => { await navigator.clipboard.writeText(l.url); }, "Copied " + l.label) }, "Copy"),
      el("button", { class: "small", onclick: () => editLink(root, li, l) }, "Edit"),
      el("span", { class: "link-move" },
        el("button", { class: "small icon", title: "Move up", "aria-label": "Move " + l.label + " up", disabled: i === 0, onclick: move(true) }, "↑"),
        el("button", { class: "small icon", title: "Move down", "aria-label": "Move " + l.label + " down", disabled: i === n - 1, onclick: move(false) }, "↓")),
      el("button", { class: "small icon danger", title: "Delete", "aria-label": "Delete " + l.label, onclick: action(async () => {
        if (!confirm("Delete " + l.label + "?")) return;
        await api("DELETE", "/api/links/" + l.id); renderLinks(root);
      }, "Deleted") }, "✕")));
  return li;
}

function shortPath(url) {
  try {
    const u = new URL(url);
    const p = (u.pathname + u.search).replace(/\/$/, "");
    return p.length > 40 ? p.slice(0, 38) + "…" : p;
  } catch (e) { return ""; }
}

function editLink(root, li, l) {
  const label = el("input", { value: l.label, maxlength: "100", "aria-label": "Label" });
  const url = el("input", { value: l.url, maxlength: "1000", "aria-label": "Link" });
  const save = action(async () => { await api("PUT", "/api/links/" + l.id, { label: label.value, url: url.value }); renderLinks(root); }, "Saved");
  for (const input of [label, url]) input.addEventListener("keydown", (e) => { if (e.key === "Enter") save(e); if (e.key === "Escape") renderLinks(root); });
  clear(li);
  li.classList.add("editing");
  li.append(linkIcon(l), el("div", { class: "link-edit" }, label, url), el("div", { class: "link-actions" },
    el("button", { class: "small primary", onclick: save }, "Save"),
    el("button", { class: "small", onclick: () => renderLinks(root) }, "Cancel")));
  label.focus();
}

/** "Add links" card: one link, everything from a Linktree page, or a pasted list (one per line). */
function addLinksCard(root, links, mode) {
  const saved = new Set(links.map((l) => sameLinkKey(l.url)));
  const body = el("div", { class: "links-add-body" });
  const modes = [["linktree", "From Linktree"], ["one", "One link"], ["paste", "Paste a list"]];
  const tabs = el("div", { class: "seg", role: "tablist" }, modes.map(([m, text]) =>
    el("button", { class: m === mode ? "active" : null, role: "tab", "aria-selected": String(m === mode), onclick: () => { linksAddMode = m; show(m); } }, text)));
  function show(m) {
    [...tabs.children].forEach((b, i) => { b.classList.toggle("active", modes[i][0] === m); b.setAttribute("aria-selected", String(modes[i][0] === m)); });
    clear(body);
    body.appendChild(m === "linktree" ? linktreeForm(root, saved) : m === "paste" ? pasteForm(root, saved) : oneLinkForm(root));
  }
  show(mode);
  return el("div", { class: "card links-add" }, el("div", { class: "row" }, el("h3", {}, "Add links"), el("div", { class: "spacer" }), tabs), body);
}

function oneLinkForm(root) {
  const url = el("input", { placeholder: "e.g. instagram.com/yourname", maxlength: "1000" });
  const label = el("input", { placeholder: "Optional, filled in for Instagram, TikTok, YouTube…", maxlength: "100" });
  const add = action(async () => {
    if (!url.value.trim()) throw new Error("Paste a link first");
    await api("POST", "/api/links", { url: url.value, label: label.value });
    renderLinks(root);
  }, "Link added");
  url.addEventListener("keydown", (e) => { if (e.key === "Enter") add(e); });
  label.addEventListener("keydown", (e) => { if (e.key === "Enter") add(e); });
  return el("div", {}, el("div", { class: "grid" },
    el("div", {}, el("label", {}, "Link"), url), el("div", {}, el("label", {}, "Label"), label)),
    el("p", {}, el("button", { class: "primary", onclick: add }, "Add link")));
}

function linktreeForm(root, saved) {
  const url = el("input", { placeholder: "linktr.ee/yourname", maxlength: "2000" });
  const out = el("div", {});
  const find = action(async () => {
    if (!url.value.trim()) throw new Error("Paste your Linktree link first");
    clear(out).appendChild(el("p", { class: "muted" }, "Reading your Linktree…"));
    try {
      const p = await api("POST", "/api/links/linktree", { url: url.value });
      clear(out).appendChild(pickLinks(root, saved, p.links, "Found " + p.links.length + " link" + (p.links.length === 1 ? "" : "s") + " on " + p.name + "'s Linktree"));
    } catch (err) { clear(out); throw err; }
  });
  url.addEventListener("keydown", (e) => { if (e.key === "Enter") find(e); });
  return el("div", {},
    el("p", { class: "muted" }, "Paste your Linktree link. The app reads the page, shows you what it found, and you pick what to add. Tracking bits like ?utm_source are dropped."),
    el("div", { class: "row" }, url, el("button", { class: "primary", onclick: find }, "Find links")),
    out);
}

function pasteForm(root, saved) {
  const text = el("textarea", { placeholder: "One link per line, for example:\nUGC portfolio: https://me.my.canva.site/portfolio\nhttps://www.tiktok.com/@me" });
  const out = el("div", {});
  const read = () => {
    const found = [];
    for (const line of text.value.split(/\r?\n/)) {
      const m = line.match(/(https?:\/\/\S+|(?:www\.)?[a-z0-9-]+(?:\.[a-z0-9-]+)+\/\S*)/i);
      if (!m) continue;
      const label = line.slice(0, m.index).replace(/[\s:–—-]+$/, "").replace(/^[\s•*\d.)-]+/, "").trim();
      found.push({ label, url: m[1], social: isSocial(/^https?:/i.test(m[1]) ? m[1] : "https://" + m[1]) });
    }
    clear(out);
    if (!found.length) { toast("No links found in that text", true); return; }
    out.appendChild(pickLinks(root, saved, found, found.length + " link" + (found.length === 1 ? "" : "s") + " found"));
  };
  return el("div", {}, text, el("p", {}, el("button", { class: "primary", onclick: read }, "Read links")), out);
}

/** Checklist of found links; ones already saved are shown but not ticked. */
function pickLinks(root, saved, found, title) {
  const rows = found.map((f) => {
    const already = saved.has(sameLinkKey(f.url));
    const box = el("input", { type: "checkbox", checked: !already, disabled: already });
    const label = el("input", { value: f.label || "", maxlength: "100", placeholder: linkHost(/^https?:/i.test(f.url) ? f.url : "https://" + f.url), "aria-label": "Label" });
    return { f, box, label, node: el("li", { class: "pick" + (already ? " already" : "") },
      box, linkIcon({ label: f.label, url: /^https?:/i.test(f.url) ? f.url : "https://" + f.url }),
      el("div", { class: "link-body" }, label, el("div", { class: "link-url" }, f.url)),
      already ? el("span", { class: "badge ok" }, "Already saved") : f.social ? el("span", { class: "badge" }, "Profile") : null) };
  });
  const fresh = rows.filter((r) => !r.box.disabled);
  const addBtn = el("button", { class: "primary" });
  const count = () => { const n = rows.filter((r) => r.box.checked).length; addBtn.textContent = "Add " + n + " link" + (n === 1 ? "" : "s"); addBtn.disabled = n === 0; };
  rows.forEach((r) => r.box.addEventListener("change", count));
  addBtn.addEventListener("click", action(async () => {
    const picked = rows.filter((r) => r.box.checked).map((r) => ({ label: r.label.value, url: r.f.url }));
    const res = await api("POST", "/api/links/bulk", { links: picked });
    linksAddMode = "one";
    await renderLinks(root);
    toast("Added " + res.added + " link" + (res.added === 1 ? "" : "s") + (res.skipped ? " (" + res.skipped + " already saved or over the limit)" : ""));
  }));
  count();
  const all = el("button", { class: "small", onclick: () => { const on = fresh.some((r) => !r.box.checked); fresh.forEach((r) => { r.box.checked = on; }); count(); } }, "Select all / none");
  return el("div", { class: "links-pick" },
    el("div", { class: "row" }, el("strong", {}, title), el("div", { class: "spacer" }), fresh.length > 1 ? all : null),
    el("ul", {}, rows.map((r) => r.node)),
    el("div", { class: "row" }, addBtn, fresh.length ? el("span", { class: "muted small" }, "You can rename them now or later.") : el("span", { class: "muted small" }, "Everything here is already on your Links page.")));
}
