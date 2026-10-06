/*
 * Creator CRM dashboard. Vanilla JS, no build step.
 * All third-party text (emails, DMs, brand names) is inserted with textContent, never innerHTML.
 *
 * The dashboard is split by page into the files in this folder. index.html loads them as plain scripts, in order,
 * so they share one global scope: this file first, each page next, main.js last. Code that runs while a file
 * loads may only use what an earlier file defined; everything else runs later, once all files are in.
 */
"use strict";

// ---------- helpers ----------

function csrf() {
  const m = document.cookie.match(/(?:^|;\s*)XSRF-TOKEN=([^;]+)/);
  return m ? decodeURIComponent(m[1]) : "";
}

async function api(method, path, body) {
  const opts = { method, credentials: "same-origin", headers: {} };
  if (method !== "GET") opts.headers["X-XSRF-TOKEN"] = csrf();
  if (body !== undefined) {
    opts.headers["Content-Type"] = "application/json";
    opts.body = JSON.stringify(body);
  }
  const res = await fetch(path, opts);
  if (res.status === 401) { location.replace("/login.html"); throw new Error("Signed out"); }
  const data = res.status === 204 ? null : await res.json().catch(() => null);
  if (!res.ok) throw new Error((data && data.error) || "Request failed (" + res.status + ")");
  return data;
}

/** el("div", {class: "x", onclick: fn}, "text", childNode, [more]) */
function el(tag, attrs, ...children) {
  const node = document.createElement(tag);
  for (const [k, v] of Object.entries(attrs || {})) {
    if (v === null || v === undefined || v === false) continue;
    if (k.startsWith("on")) node.addEventListener(k.slice(2), v);
    else if (k === "class") node.className = v;
    else if (k === "value") node.value = v;
    else node.setAttribute(k, v === true ? "" : v);
  }
  for (const c of children.flat()) {
    if (c === null || c === undefined || c === false) continue;
    node.appendChild(typeof c === "string" || typeof c === "number" ? document.createTextNode(String(c)) : c);
  }
  return node;
}

function clear(node) { while (node.firstChild) node.removeChild(node.firstChild); return node; }

let toastTimer;
function toast(msg, isError) {
  const t = document.getElementById("toast");
  t.textContent = msg;
  t.className = "toast" + (isError ? " error" : "");
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => t.classList.add("hidden"), isError ? 7000 : 3500);
}

/** Wrap an async click handler: disable the button, report errors. */
function action(fn, okMsg) {
  return async (e) => {
    const btn = e && e.currentTarget;
    if (btn) btn.disabled = true;
    try {
      await fn(e);
      if (okMsg) toast(okMsg);
    } catch (err) {
      toast(err.message, true);
    } finally {
      if (btn) btn.disabled = false;
    }
  };
}

// Same rule as Placeholders.java: capitals in square brackets, e.g. [RATE FOR 1 REEL] or [MEDIA KIT LINK].
function blanksIn(...texts) {
  const found = new Set();
  for (const t of texts) for (const m of (t || "").matchAll(/\[[A-Z][A-Z0-9 &'\/+.,:#$%-]{2,80}\]/g)) found.add(m[0]);
  return [...found];
}

function pretty(s) {
  if (!s) return "";
  if (s === "REPITCH") return "Re-pitch";
  s = String(s).toLowerCase().replace(/_/g, " ");
  return s.charAt(0).toUpperCase() + s.slice(1);
}

function fmtDate(iso) {
  if (!iso) return "";
  const d = new Date(iso.length === 10 ? iso + "T00:00:00" : iso);
  return d.toLocaleDateString(undefined, { month: "short", day: "numeric" });
}

function fmtDateTime(iso) {
  return iso ? new Date(iso).toLocaleString(undefined, { month: "short", day: "numeric", hour: "numeric", minute: "2-digit" }) : "";
}

// ---------- icons ----------
// One line-drawn icon set, in the same style as the tab bar, instead of emoji (which look different on every
// computer and phone). icon("mail") is decorative; screen readers read the words next to it.
const ICONS = {
  check: "M5 12.5l4.5 4.5L19 7",
  x: "M6 6l12 12M18 6L6 18",
  alert: "M12 4 2.5 20h19zM12 10v4.5M12 17.5h.01",
  mail: "M3.5 6.5h17v11a1 1 0 0 1-1 1h-15a1 1 0 0 1-1-1zm0 0 8.5 6.5 8.5-6.5",
  money: "M3.5 7h17v10h-17zM12 9.5a2.5 2.5 0 1 0 0 5 2.5 2.5 0 0 0 0-5z",
  calendar: "M4 6.5h16v13.5H4zM4 10.5h16M8.5 4v4M15.5 4v4",
  bell: "M6 16.5V11a6 6 0 0 1 12 0v5.5l1.5 2h-15zM10 20.5a2 2 0 0 0 4 0",
  repeat: "M4 11V9a3 3 0 0 1 3-3h12l-3-3M20 13v2a3 3 0 0 1-3 3H5l3 3",
  link: "M10 14a4 4 0 0 0 5.7 0l3-3a4 4 0 0 0-5.7-5.7l-1 1M14 10a4 4 0 0 0-5.7 0l-3 3a4 4 0 0 0 5.7 5.7l1-1",
  clip: "M20 11.5l-8 8a5 5 0 0 1-7-7l8.5-8.5a3.3 3.3 0 0 1 4.7 4.7L9.7 17.2a1.7 1.7 0 0 1-2.4-2.4L15 7",
  chart: "M4 20V4M4 20h16M8 16v-5M12 16V8M16 16v-3",
  bulb: "M9 18h6M10 21h4M12 3a6 6 0 0 0-3.5 10.9c.6.5 1 1.2 1 2V16h5v-.1c0-.8.4-1.5 1-2A6 6 0 0 0 12 3z",
  send: "M21 3 10 14M21 3l-7 18-4-7-7-4z",
  chat: "M4 5h16v11H9l-5 4z",
  sparkle: "M12 3v4M12 17v4M3 12h4M17 12h4M6 6l2.5 2.5M15.5 15.5 18 18M6 18l2.5-2.5M15.5 8.5 18 6",
  sun: "M12 8a4 4 0 1 0 0 8 4 4 0 0 0 0-8zM12 2.5v2M12 19.5v2M2.5 12h2M19.5 12h2M5.3 5.3l1.4 1.4M17.3 17.3l1.4 1.4M5.3 18.7l1.4-1.4M17.3 6.7l1.4-1.4",
  hourglass: "M7 3.5h10M7 20.5h10M8 3.5c0 4 8 5 8 8.5s-8 4.5-8 8.5M16 3.5c0 4-8 5-8 8.5s8 4.5 8 8.5",
  camera: "M4 8h3.5L9 5.5h6L16.5 8H20v11H4zM12 10.5a3 3 0 1 0 0 6 3 3 0 0 0 0-6z",
  pen: "M4 20l4-1 11-11-3-3L5 16zM14 7l3 3",
  box: "M3.5 7.5 12 3l8.5 4.5v9L12 21l-8.5-4.5zM3.5 7.5 12 12l8.5-4.5M12 12v9",
  film: "M4 6h12v12H4zM16 10l4-2.5v9L16 14",
  eye: "M2.5 12S6 5.5 12 5.5 21.5 12 21.5 12 18 18.5 12 18.5 2.5 12 2.5 12zM12 9.5a2.5 2.5 0 1 0 0 5 2.5 2.5 0 0 0 0-5z",
  inbox: "M3.5 13.5 6 5h12l2.5 8.5V19h-17zM3.5 13.5H9l1 2h4l1-2h5.5",
  snow: "M12 3v18M4.2 7.5l15.6 9M4.2 16.5l15.6-9",
  checkCircle: "M12 3.5a8.5 8.5 0 1 0 0 17 8.5 8.5 0 0 0 0-17zM8 12.5l3 3 5-6",
  xCircle: "M12 3.5a8.5 8.5 0 1 0 0 17 8.5 8.5 0 0 0 0-17zM9 9l6 6M15 9l-6 6",
};
const SVG_NS = "http://www.w3.org/2000/svg";

function icon(name) {
  const svg = document.createElementNS(SVG_NS, "svg");
  svg.setAttribute("viewBox", "0 0 24 24");
  svg.setAttribute("class", "ico-inline");
  svg.setAttribute("aria-hidden", "true");
  const path = document.createElementNS(SVG_NS, "path");
  path.setAttribute("d", ICONS[name] || ICONS.sparkle);
  svg.appendChild(path);
  return svg;
}

const STATUS_ICONS = { NEW_LEAD: "sparkle", PITCHED: "send", AWAITING_MY_REPLY: "inbox", NEGOTIATING: "money",
  CONTRACT_PENDING: "pen", CONTRACT_TO_SIGN: "pen", PRODUCT_PENDING: "box", PRODUCT_RECEIVED: "box",
  CONTENT_TO_CREATE: "film", AWAITING_APPROVAL: "eye", SCHEDULED_TO_POST: "calendar", POSTED: "checkCircle",
  PAYMENT_PENDING: "money", FOLLOW_UP_NEEDED: "repeat", COLD: "snow", CLOSED: "xCircle" };

// A deal's status with its icon. Older saved text may still start with an emoji; that's dropped.
function statusTag(key, label) {
  return el("span", { class: "status-tag" }, icon(STATUS_ICONS[key]), stripEmoji(label || pretty(key)));
}

function stripEmoji(text) {
  return String(text).replace(/^[\p{Extended_Pictographic}️‍\s]+/u, "");
}

function card(title, ...children) {
  return el("div", { class: "card" }, title ? el("h3", {}, title) : null, ...children);
}

function emptyLine(text) { return el("div", { class: "empty" }, text); }

// ---------- password managers ----------
// Browsers treat every password box on a page as one big login form: they fill her saved password into
// the first one (the Claude API key) and her username into whatever text box comes before it (a search
// box). Keys and tokens are marked as not-a-login, and each card that asks for her password gets its own
// form with a hidden username box, so the saved login never lands anywhere else.

const NOT_A_LOGIN = { autocomplete: "new-password", "data-lpignore": "true", "data-1p-ignore": "true", "data-bwignore": "true" };

function passwordForm(node) {
  // Buttons in a form submit it by default, and Enter in a field "clicks" the first one. Neither should run an action.
  node.querySelectorAll("button:not([type])").forEach((b) => { b.type = "button"; });
  return el("form", { class: "password-form", onsubmit: (e) => e.preventDefault() },
    el("input", { type: "text", name: "username", autocomplete: "username", hidden: true, tabindex: "-1", "aria-hidden": "true" }),
    node);
}

// ---------- list controls: search, filter chips, sortable columns ----------

/** A view's filters and sort, kept in this browser so they survive a refresh. */
function loadPrefs(view, defaults) {
  try {
    const saved = JSON.parse(localStorage.getItem("crm.view." + view));
    if (saved && typeof saved === "object") return Object.assign({}, defaults, saved);
  } catch (e) { /* private window or bad JSON */ }
  return Object.assign({}, defaults);
}

function savePrefs(view, prefs) {
  try { localStorage.setItem("crm.view." + view, JSON.stringify(prefs)); } catch (e) { /* ignore */ }
}

function searchBox(value, placeholder, onChange) {
  const input = el("input", { type: "search", class: "search", value: value || "", placeholder, "aria-label": placeholder, maxlength: "100",
    autocomplete: "off", name: "filter" });
  let timer;
  input.addEventListener("input", () => { clearTimeout(timer); timer = setTimeout(() => onChange(input.value), 150); });
  return input;
}

/** Every word of the query appears somewhere in the fields, ignoring case. */
function matchesQuery(q, ...fields) {
  const words = String(q || "").toLowerCase().split(/\s+/).filter(Boolean);
  if (!words.length) return true;
  const hay = fields.filter((f) => f !== null && f !== undefined).join(" ").toLowerCase();
  return words.every((w) => hay.includes(w));
}

/** options: [[value, label], …]; picking the active chip again goes back to the first option ("All"). */
function chipRow(options, selected, onPick, label) {
  return el("div", { class: "chips", role: "group", "aria-label": label || "Filter" }, options.map(([value, text]) =>
    el("button", { class: "chip" + (value === selected ? " active" : ""), "aria-pressed": String(value === selected),
      onclick: () => onPick(value === selected ? options[0][0] : value) }, text)));
}

/** keys: {column: row => comparable}. Blank values always sort last. */
function sortRows(rows, sort, keys) {
  const key = keys[sort && sort.by];
  if (!key) return rows;
  const dir = sort.dir === "asc" ? 1 : -1;
  return rows.slice().sort((a, b) => {
    const x = key(a), y = key(b);
    const xb = x === null || x === undefined || x === "", yb = y === null || y === undefined || y === "";
    if (xb || yb) return xb === yb ? 0 : xb ? 1 : -1;
    if (typeof x === "string") return x.localeCompare(y, undefined, { sensitivity: "base", numeric: true }) * dir;
    return (x < y ? -1 : x > y ? 1 : 0) * dir;
  });
}

/** cols: [[label, sortKey or null, first direction]]. Clicking the sorted column again flips it. */
function sortableHead(cols, sort, onSort) {
  return el("thead", {}, el("tr", {}, cols.map(([label, key, firstDir]) => {
    if (!key) return el("th", {}, label);
    const active = sort.by === key;
    return el("th", { "aria-sort": active ? (sort.dir === "asc" ? "ascending" : "descending") : "none" },
      el("button", { class: "th-sort", title: "Sort by " + label.toLowerCase(),
        onclick: () => onSort(active ? { by: key, dir: sort.dir === "asc" ? "desc" : "asc" } : { by: key, dir: firstDir || "asc" }) },
        label, el("span", { class: "sort-mark", "aria-hidden": "true" }, active ? (sort.dir === "asc" ? " ▲" : " ▼") : "")));
  })));
}

function shownLine(shown, total, noun, onClear) {
  if (shown === total) return null;
  return el("div", { class: "row small muted shown-line" }, "Showing " + shown + " of " + total + " " + noun + ".",
    onClear ? el("button", { class: "small", onclick: onClear }, "Clear filters") : null);
}
