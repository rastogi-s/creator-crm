/*
 * Creator CRM: Shop links (her Amazon, Walmart and other affiliate products) and Post captions (YouTube, Instagram
 * and TikTok captions for one video, with those links put in by the app).
 * Part of the dashboard; index.html loads these files in order and they share one scope (see core.js).
 */
"use strict";

const STORE_NAMES = { AMAZON: "Amazon", WALMART: "Walmart", OTHER: "Shop" };
const PLATFORM_NAMES = { YOUTUBE: "YouTube", INSTAGRAM: "Instagram", TIKTOK: "TikTok" };
/** Set by other pages (a deal's content idea, the Products page) just before they open Post captions. */
let captionsPrefill = null;

/** Opens Post captions with the form filled in: {about, productIds, opportunityId}. */
function openCaptions(prefill) {
  captionsPrefill = prefill;
  if (location.hash.replace(/^#/, "").split("?")[0] === "captions") route(); else location.hash = "#captions";
}

function storeBadge(store) {
  return el("span", { class: "store-badge store-" + String(store).toLowerCase() }, STORE_NAMES[store] || store);
}

// ---------- Shop links ----------

let productPrefs = null;
let productPicks = new Set(); // products ticked for "Write captions with these"

async function renderProducts(root) {
  productPrefs = productPrefs || loadPrefs("products", { q: "", store: "", tag: "" });
  const [list, settings] = await Promise.all([api("GET", "/api/products"), api("GET", "/api/settings")]);
  const prefs = settings.preferences;
  clear(root);
  root.appendChild(el("div", { class: "links-head" },
    el("div", {},
      el("h1", {}, "Shop links"),
      el("p", { class: "muted" }, "Your Amazon, Walmart and other affiliate links in one place. Post captions put these exact links in your descriptions and DMs.")),
    list.length ? el("button", { class: "primary", onclick: () => openCaptions(null) }, "Write post captions") : null));

  root.appendChild(addProductCard(root, prefs));
  if (!prefs.amazonTag && list.some((p) => p.store === "AMAZON")) {
    root.appendChild(el("div", { class: "card warn-card" }, el("strong", {}, "Add your Amazon tag. "),
      "Then every Amazon link you paste earns for you, even one copied straight from the product page. Open Shop settings below."));
  }
  if (!list.length) {
    root.appendChild(el("div", { class: "links-empty" },
      el("div", { class: "links-empty-art", "aria-hidden": "true" }, icon("link")),
      el("h3", {}, "No products yet"),
      el("p", { class: "muted" }, "Paste a link from Amazon, Walmart or any shop above. The app fills in the name for you.")));
  } else {
    root.appendChild(productList(root, list));
  }
  root.appendChild(shopSettingsCard(root, prefs, !prefs.amazonTag));
}

function addProductCard(root, prefs) {
  const url = el("input", { placeholder: "Paste a product link, e.g. amzn.to/3abc or walmart.com/ip/…", maxlength: "2000", "aria-label": "Product link", ...NOT_A_LOGIN });
  const found = el("div", { class: "product-found hidden" });
  const name = el("input", { maxlength: "200", placeholder: "Product name", "aria-label": "Product name" });
  const brand = el("input", { maxlength: "200", placeholder: "Brand (optional)", "aria-label": "Brand" });
  const tags = el("input", { maxlength: "300", placeholder: "Tags, e.g. skincare, morning (optional)", "aria-label": "Tags" });
  const code = el("input", { maxlength: "100", placeholder: "Discount code (optional)", "aria-label": "Discount code" });
  const note = el("div", { class: "small" });
  let previewed = "";
  const preview = async () => {
    const v = url.value.trim();
    if (!v || v === previewed) return;
    previewed = v;
    try {
      const p = await api("POST", "/api/products/preview", { url: v });
      if (url.value.trim() !== v) return;
      if (!name.value || name.dataset.auto === "1") { name.value = p.name || ""; name.dataset.auto = "1"; }
      clear(note);
      if (p.duplicateOf) note.append(el("span", { class: "warn" }, "Already saved as “" + p.duplicateOf + "”."));
      else if (p.warning) note.append(el("span", { class: "warn" }, p.warning));
      else note.append(el("span", { class: "ok-text" }, "✓ " + (STORE_NAMES[p.store] || "Shop") + " link" + (p.itemId ? " · item " + p.itemId : "") + (p.store === "AMAZON" && /tag=/.test(p.url) ? " · earns with your tag" : "")));
      clear(found.querySelector(".found-store")).append(storeBadge(p.store));
      found.classList.remove("hidden");
      (p.name ? tags : name).focus();
    } catch (err) { toast(err.message, true); previewed = ""; }
  };
  url.addEventListener("paste", () => setTimeout(preview, 0));
  url.addEventListener("change", preview);
  url.addEventListener("keydown", (e) => { if (e.key === "Enter") { e.preventDefault(); preview(); } });
  name.addEventListener("input", () => { name.dataset.auto = ""; });
  const add = action(async () => {
    if (!url.value.trim()) throw new Error("Paste a product link first");
    await api("POST", "/api/products", { url: url.value, name: name.value, brand: brand.value, tags: tags.value, code: code.value });
    await renderProducts(root);
    toast("Product added");
  });
  for (const i of [name, brand, tags, code]) i.addEventListener("keydown", (e) => { if (e.key === "Enter") add(e); });
  found.append(el("div", { class: "row" }, el("span", { class: "found-store" }), note),
    el("div", { class: "grid product-fields" }, name, brand, tags, code),
    el("div", { class: "row" }, el("button", { class: "primary", onclick: add }, "Add product")));

  const many = el("textarea", { placeholder: "One link per line. Each one gets its name from the link; add tags and codes later." });
  const addMany = action(async () => {
    const urls = many.value.split(/\r?\n/).map((l) => (l.match(/(https?:\/\/\S+|(?:www\.)?[a-z0-9-]+(?:\.[a-z0-9-]+)+\/\S*)/i) || [])[1]).filter(Boolean);
    if (!urls.length) throw new Error("No links found in that text");
    const r = await api("POST", "/api/products/bulk", { urls });
    await renderProducts(root);
    toast("Added " + r.added + " product" + (r.added === 1 ? "" : "s") + (r.skipped ? " (" + r.skipped + " skipped: " + r.problems.join("; ") + ")" : ""), r.skipped > 0 && !r.added);
  });
  return el("div", { class: "card add-product" },
    el("h3", {}, "Add a product"),
    el("div", { class: "row" }, url, el("button", { onclick: preview }, "Check link")),
    found,
    el("details", { class: "small" }, el("summary", {}, "Have a lot? Paste many links at once"), many,
      el("p", {}, el("button", { onclick: addMany }, "Add all"))));
}

function productList(root, list) {
  const wrap = el("div", {});
  const allTags = [...new Set(list.flatMap((p) => p.tags))].sort();
  const draw = () => {
    const shown = list.filter((p) => (!productPrefs.store || p.store === productPrefs.store)
      && (!productPrefs.tag || p.tags.includes(productPrefs.tag))
      && matchesQuery(productPrefs.q, p.name, p.brand, p.tags.join(" "), p.code, p.itemId));
    clear(wrap);
    const stores = ["AMAZON", "WALMART", "OTHER"].filter((s) => list.some((p) => p.store === s));
    const set = (k) => (v) => { productPrefs[k] = v; savePrefs("products", productPrefs); draw(); };
    const search = searchBox(productPrefs.q, "Search products", set("q"));
    wrap.append(el("div", { class: "row filters" }, search,
      stores.length > 1 ? chipRow([["", "All"]].concat(stores.map((s) => [s, STORE_NAMES[s]])), productPrefs.store, set("store"), "Store") : null,
      allTags.length ? chipRow([["", "Any tag"]].concat(allTags.slice(0, 12).map((t) => [t, t])), productPrefs.tag, set("tag"), "Tag") : null));
    wrap.append(shownLine(shown.length, list.length, "products", () => { productPrefs = { q: "", store: "", tag: "" }; savePrefs("products", productPrefs); draw(); }) || "");
    wrap.append(el("ul", { class: "product-list" }, shown.map((p) => productCard(root, p, draw))));
    wrap.append(pickBar(list, draw));
    if (productPrefs.q) { const i = search.input; i.focus(); i.setSelectionRange(i.value.length, i.value.length); }
  };
  draw();
  return wrap;
}

function pickBar(list, draw) {
  const picked = list.filter((p) => productPicks.has(p.id));
  if (!picked.length) return el("span", {});
  return el("div", { class: "pick-bar" },
    el("span", {}, picked.length + " picked"),
    el("button", { class: "small", onclick: () => { productPicks.clear(); draw(); } }, "Clear"),
    el("button", { class: "primary", onclick: () => {
      const ids = picked.map((p) => p.id);
      productPicks.clear();
      openCaptions({ productIds: ids });
    } }, "Write captions with " + (picked.length === 1 ? "it" : "these")));
}

function productCard(root, p, draw) {
  const safe = /^https?:\/\//i.test(p.url);
  const box = el("input", { type: "checkbox", checked: productPicks.has(p.id), "aria-label": "Pick " + p.name + " for a post",
    onchange: (e) => { if (e.target.checked) productPicks.add(p.id); else productPicks.delete(p.id); draw(); } });
  const li = el("li", { class: "product-card" + (productPicks.has(p.id) ? " picked" : "") },
    box,
    el("div", { class: "product-body" },
      el("div", { class: "product-name" }, storeBadge(p.store), el("span", {}, p.name)),
      el("div", { class: "small muted" }, [p.brand, p.tags.length ? p.tags.join(", ") : null,
        p.timesUsed ? "in " + p.timesUsed + " post" + (p.timesUsed === 1 ? "" : "s") : null].filter(Boolean).join(" · ")),
      safe ? el("a", { class: "link-url", href: p.url, target: "_blank", rel: "noopener noreferrer", title: p.url }, linkHost(p.url) + shortPath(p.url)) : null,
      p.code ? el("div", { class: "small" }, "Code: ", el("strong", {}, p.code)) : null,
      p.warning ? el("div", { class: "small warn" }, p.warning) : null),
    el("div", { class: "link-actions" },
      copyButton(() => p.url, "Copy link", { class: "copy-btn primary small" }),
      p.code ? copyButton(() => p.code, "Copy code", { class: "copy-btn small" }) : null,
      el("button", { class: "small", onclick: () => editProduct(root, li, p) }, "Edit"),
      el("button", { class: "small icon danger", title: "Delete", "aria-label": "Delete " + p.name, onclick: action(async () => {
        if (!confirm("Delete " + p.name + "?")) return;
        await api("DELETE", "/api/products/" + p.id); productPicks.delete(p.id); renderProducts(root);
      }, "Deleted") }, "✕")));
  return li;
}

function editProduct(root, li, p) {
  const url = el("input", { value: p.url, maxlength: "2000", "aria-label": "Link" });
  const name = el("input", { value: p.name, maxlength: "200", "aria-label": "Name" });
  const brand = el("input", { value: p.brand || "", maxlength: "200", placeholder: "Brand", "aria-label": "Brand" });
  const tags = el("input", { value: p.tags.join(", "), maxlength: "300", placeholder: "Tags", "aria-label": "Tags" });
  const code = el("input", { value: p.code || "", maxlength: "100", placeholder: "Discount code", "aria-label": "Discount code" });
  const save = action(async () => {
    await api("PUT", "/api/products/" + p.id, { url: url.value, name: name.value, brand: brand.value, tags: tags.value, code: code.value });
    renderProducts(root);
  }, "Saved");
  for (const i of [url, name, brand, tags, code]) i.addEventListener("keydown", (e) => { if (e.key === "Enter") save(e); if (e.key === "Escape") renderProducts(root); });
  clear(li);
  li.classList.add("editing");
  li.append(el("div", { class: "product-edit" },
    el("label", {}, "Name"), name, el("label", {}, "Link"), url,
    el("div", { class: "grid product-fields" }, brand, tags, code),
    el("div", { class: "row" }, el("button", { class: "primary small", onclick: save }, "Save"),
      el("button", { class: "small", onclick: () => renderProducts(root) }, "Cancel"))));
  name.focus();
}

function shopSettingsCard(root, prefs, open) {
  const tag = el("input", { value: prefs.amazonTag || "", maxlength: "40", placeholder: "e.g. ava-20", ...NOT_A_LOGIN });
  const hashtags = el("input", { value: prefs.captionHashtags || "", maxlength: "300", placeholder: "e.g. #avamakes #cozyhome" });
  const label = el("input", { value: prefs.disclosureTag || "#ad", maxlength: "60" });
  const save = action(async () => {
    await api("PUT", "/api/settings/preferences", { amazonTag: tag.value, captionHashtags: hashtags.value, disclosureTag: label.value });
    renderProducts(root);
  }, "Shop settings saved");
  return el("details", Object.assign({ class: "card shop-settings", id: "shop-settings" }, open ? { open: true } : {}),
    el("summary", {}, el("h3", {}, "Shop settings")),
    el("label", {}, "Amazon Associates tag"), tag,
    el("p", { class: "small muted" }, "Find it at the top right of Associates Central. The app adds it to Amazon product links that don't have one, so every link earns for you."),
    el("label", {}, "Hashtags on every post"), hashtags,
    el("p", { class: "small muted" }, "Added to your Instagram and TikTok captions, after the ones Claude picks."),
    el("label", {}, "Ad label"), label,
    el("p", { class: "small muted" }, "Added to Instagram and TikTok captions that have shop links or are for a brand deal. YouTube descriptions also get Amazon's \"As an Amazon Associate I earn from qualifying purchases.\""),
    el("p", {}, el("button", { class: "primary", onclick: save }, "Save")));
}

// ---------- Post captions ----------

let captionsState = null; // the form, kept while she moves between pages

async function renderCaptions(root) {
  const [products, deals, recent, learned] = await Promise.all([api("GET", "/api/products"), api("GET", "/api/pipeline"), api("GET", "/api/captions"),
    api("GET", "/api/captions/learning").catch(() => null)]);
  const saved = loadPrefs("captions", { platforms: ["YOUTUBE", "INSTAGRAM", "TIKTOK"], youtubeFormat: "SHORT" });
  if (!captionsState) captionsState = { about: "", note: "", productIds: [], pick: true, opportunityId: null, pictures: [], result: null };
  const pre = captionsPrefill;
  captionsPrefill = null;
  if (pre) {
    captionsState = { about: pre.about || "", note: "", productIds: pre.productIds || [], pick: !(pre.productIds || []).length,
      opportunityId: pre.opportunityId || null, pictures: [], result: null };
  }
  const st = captionsState;
  st.productIds = st.productIds.filter((id) => products.some((p) => p.id === id));
  clear(root);
  root.appendChild(el("div", { class: "links-head" },
    el("div", {},
      el("h1", {}, "Post captions"),
      el("p", { class: "muted" }, "Say what your video is about and get the YouTube, Instagram and TikTok captions in your voice, with your shop links already in. Copy each one with a tap."),
      learningLine(learned))));

  const resultBox = el("div", { id: "caption-result" });
  root.appendChild(captionForm(root, st, saved, products, deals, resultBox));
  root.appendChild(resultBox);
  if (st.result) showCaptions(resultBox, st.result, st, root);
  root.appendChild(recentCaptions(root, recent, resultBox));
}

function captionForm(root, st, saved, products, deals, resultBox) {
  const about = el("textarea", { class: "about", maxlength: "4000", rows: "3",
    placeholder: "e.g. My 5-minute morning skincare routine with the Glow Haus serum. Ends with me dancing in the kitchen." });
  about.value = st.about;
  about.addEventListener("input", () => { st.about = about.value; });

  // Screenshots, made small on this device before they're sent.
  const thumbs = el("div", { class: "thumbs" });
  const file = el("input", { type: "file", accept: "image/*", multiple: true, class: "hidden" });
  const drawThumbs = () => {
    clear(thumbs);
    st.pictures.forEach((p, i) => thumbs.append(el("span", { class: "thumb" }, el("img", { src: "data:" + p.mediaType + ";base64," + p.base64, alt: p.name }),
      el("button", { type: "button", class: "thumb-x", title: "Remove", "aria-label": "Remove " + p.name, onclick: () => { st.pictures.splice(i, 1); drawThumbs(); } }, "×"))));
    if (st.pictures.length < 3) thumbs.append(el("button", { type: "button", class: "small", onclick: () => file.click() }, st.pictures.length ? "Add another" : "Add screenshots"));
  };
  file.addEventListener("change", async () => {
    for (const f of [...file.files].slice(0, 3 - st.pictures.length)) {
      try { st.pictures.push(await shrinkPicture(f)); } catch (e) { toast("Couldn't read " + f.name, true); }
    }
    file.value = "";
    drawThumbs();
  });
  drawThumbs();

  // Products in the video: chips, plus a box that suggests from her list.
  const chosen = el("div", { class: "chosen" });
  const pick = el("input", { type: "checkbox", checked: st.pick });
  pick.addEventListener("change", () => { st.pick = pick.checked; });
  const pickRow = el("label", { class: "check small" }, pick, " Let Claude pick the matching products from my list");
  const drawChosen = () => {
    clear(chosen);
    for (const id of st.productIds) {
      const p = products.find((x) => x.id === id);
      if (!p) continue;
      chosen.append(el("span", { class: "chip active product-chip" }, storeBadge(p.store), " " + p.name,
        el("button", { type: "button", class: "chip-x", "aria-label": "Remove " + p.name, onclick: () => { st.productIds = st.productIds.filter((x) => x !== id); drawChosen(); } }, "×")));
    }
    pickRow.classList.toggle("hidden", st.productIds.length > 0);
  };
  const finder = searchBox("", products.length ? "Add a product from your list" : "No products yet", () => {});
  suggestions(finder, (q) => products.filter((p) => !st.productIds.includes(p.id) && matchesQuery(q, p.name, p.brand, p.tags.join(" ")))
    .slice(0, 6).map((p) => ({ title: p.name, detail: [STORE_NAMES[p.store], p.brand, p.tags.join(", ")].filter(Boolean).join(" · "), p })),
  (h) => { st.productIds.push(h.p.id); finder.value = ""; drawChosen(); });
  drawChosen();

  const deal = el("select", { "aria-label": "Brand deal" },
    el("option", { value: "" }, "Not for a deal"),
    deals.slice().sort((a, b) => a.brand.localeCompare(b.brand)).map((d) => el("option", { value: String(d.id) }, d.brand + (d.statusLabel ? " · " + d.statusLabel : ""))));
  deal.value = st.opportunityId ? String(st.opportunityId) : "";
  deal.addEventListener("change", () => { st.opportunityId = deal.value ? Number(deal.value) : null; });

  const platforms = new Set(saved.platforms);
  const formatSeg = el("div", { class: "seg small", role: "group", "aria-label": "YouTube format" });
  const drawFormat = () => {
    clear(formatSeg);
    for (const [v, t] of [["SHORT", "Short"], ["VIDEO", "Long video"]]) {
      formatSeg.append(el("button", { type: "button", class: saved.youtubeFormat === v ? "active" : null, "aria-pressed": String(saved.youtubeFormat === v),
        onclick: () => { saved.youtubeFormat = v; savePrefs("captions", saved); drawFormat(); } }, t));
    }
    formatSeg.classList.toggle("hidden", !platforms.has("YOUTUBE"));
  };
  const platformRow = el("div", { class: "chips", role: "group", "aria-label": "Platforms" });
  const drawPlatforms = () => {
    clear(platformRow);
    for (const k of ["YOUTUBE", "INSTAGRAM", "TIKTOK"]) {
      platformRow.append(el("button", { type: "button", class: "chip platform-" + k.toLowerCase() + (platforms.has(k) ? " active" : ""), "aria-pressed": String(platforms.has(k)),
        onclick: () => { if (platforms.has(k)) platforms.delete(k); else platforms.add(k); saved.platforms = [...platforms]; savePrefs("captions", saved); drawPlatforms(); drawFormat(); } },
        platforms.has(k) ? "✓ " + PLATFORM_NAMES[k] : PLATFORM_NAMES[k]));
    }
  };
  drawPlatforms();
  drawFormat();

  const note = el("input", { maxlength: "1000", placeholder: "Anything else? e.g. make it funny, mention my giveaway (optional)" });
  note.value = st.note;
  note.addEventListener("input", () => { st.note = note.value; });

  const go = el("button", { class: "primary big" }, "Write captions");
  go.addEventListener("click", action(async () => {
    if (!platforms.size) throw new Error("Pick at least one of YouTube, Instagram or TikTok");
    if (about.value.trim().length < 3 && !st.pictures.length) { about.focus(); throw new Error("Say in a sentence what the video is about"); }
    await writeCaptions(root, st, saved, resultBox, go, null);
  }));

  return el("div", { class: "card caption-form" },
    el("label", { for: "caption-about" }, "What's the video about?"), Object.assign(about, { id: "caption-about" }),
    el("p", { class: "small muted mic-tip" }, "Rather talk? Tap the mic on your phone keyboard, or press Windows key + H on the laptop."),
    el("div", { class: "form-block" }, el("div", { class: "label" }, "Screenshots from the video (optional)"), thumbs, file),
    el("div", { class: "form-block" }, el("div", { class: "label" }, "Products in the video"),
      products.length ? [chosen, finder, pickRow]
        : el("p", { class: "small muted" }, "No shop links yet. ", el("a", { href: "#products" }, "Add your Amazon and Walmart links"), " and they'll go into your captions.")),
    el("div", { class: "grid caption-options" },
      el("div", {}, el("div", { class: "label" }, "Post on"), platformRow, formatSeg),
      deals.length ? el("div", {}, el("label", {}, "For a brand deal?"), deal) : null),
    note,
    el("div", { class: "row" }, go, el("span", { class: "small muted" }, "Takes about 15 seconds and a few cents of Claude.")));
}

async function writeCaptions(root, st, saved, resultBox, btn, tweak) {
  const label = btn ? btn.textContent : "";
  if (btn) btn.textContent = "Writing your captions…";
  clear(resultBox).append(el("div", { class: "card caption-wait" }, el("span", { class: "spinner", "aria-hidden": "true" }), "Claude is writing your captions…"));
  resultBox.scrollIntoView({ behavior: "smooth", block: "start" });
  try {
    const note = [st.note, tweak].filter(Boolean).join(". ");
    st.result = await api("POST", "/api/captions", { about: st.about, note, opportunityId: st.opportunityId, productIds: st.productIds,
      pick: st.pick, youtubeFormat: saved.youtubeFormat, platforms: saved.platforms, pictures: st.pictures });
    showCaptions(resultBox, st.result, st, root);
    resultBox.scrollIntoView({ behavior: "smooth", block: "start" });
    toast("Captions ready");
    const list = document.getElementById("recent-captions");
    if (list) list.replaceWith(recentCaptions(root, await api("GET", "/api/captions"), resultBox));
  } catch (err) {
    clear(resultBox);
    throw err;
  } finally {
    if (btn) btn.textContent = label;
  }
}

let captionTab = null;

/** "Claude has learned from 12 captions you used, 5 with your edits." */
function learningLine(l) {
  if (!l) return null;
  if (!l.enabled) return el("p", { class: "small muted learning-line" }, "Learning from your past captions is off (Settings › You › Learning from your writing).");
  if (!l.used) return el("p", { class: "small muted learning-line" }, "✨ Claude learns from every caption you copy, and most from the changes you make before copying.");
  return el("p", { class: "small muted learning-line" }, "✨ Claude has learned from " + l.used + " caption" + (l.used === 1 ? "" : "s") + " you used"
    + (l.edited ? ", " + l.edited + " with your changes" : "") + ". Keep editing before you copy and it keeps getting closer to you.");
}

/** She copied a box: tell the app, so new captions learn from it. Never blocks or bothers her if it fails. */
function captionUsed(id, field, text) {
  if (!id || !field || !text) return;
  api("POST", "/api/captions/" + id + "/used", { field, text }).catch(() => {});
}

function showCaptions(box, v, st, root) {
  const r = v.result;
  const tabs = v.platforms.concat(r.products.length ? ["LINKS"] : []);
  if (!tabs.includes(captionTab)) captionTab = tabs[0];
  clear(box);
  const card = el("div", { class: "card caption-card" });
  const body = el("div", { class: "caption-body" });
  const seg = el("div", { class: "seg caption-tabs", role: "tablist" });
  const drawTab = () => {
    clear(seg);
    for (const t of tabs) seg.append(el("button", { role: "tab", class: t === captionTab ? "active" : null, "aria-selected": String(t === captionTab),
      onclick: () => { captionTab = t; drawTab(); } }, t === "LINKS" ? "Links" : PLATFORM_NAMES[t]));
    clear(body).append(...captionPanel(captionTab, r, v.id));
  };
  const saved = loadPrefs("captions", { platforms: v.platforms, youtubeFormat: v.youtubeFormat });
  const again = (tweak) => action(async (e) => {
    const s = st || { about: v.about, note: v.note || "", productIds: v.productIds, pick: false, opportunityId: v.opportunityId, pictures: [] };
    if (!st) captionsState = s;
    await writeCaptions(root, s, Object.assign(saved, { platforms: v.platforms, youtubeFormat: v.youtubeFormat }), box, e.currentTarget, tweak);
  });
  card.append(...[
    el("div", { class: "row" }, el("h2", {}, v.title || "Your captions"), v.brand ? el("span", { class: "badge accent" }, v.brand) : null,
      el("div", { class: "spacer" }), el("span", { class: "small muted" }, "Written " + fmtDateTime(v.createdAt))),
    r.warnings.length ? el("div", { class: "warn-card small" }, r.warnings.map((w) => el("div", {}, "⚠ " + w))) : null,
    r.hooks.length ? el("div", { class: "hooks" }, el("div", { class: "label" }, "On-screen hook ideas"),
      r.hooks.map((h) => el("div", { class: "hook-row" }, el("span", {}, h), copyButton(h, "Copy", { class: "copy-btn small" })))) : null,
    seg, body,
    r.tips.length ? el("ul", { class: "tips small" }, r.tips.map((t) => el("li", {}, t))) : null,
    el("div", { class: "row tweak-row" }, el("span", { class: "small muted" }, "Not quite right?"),
      ["Shorter", "Funnier", "More emojis", "More professional"].map((t) => el("button", { class: "small", onclick: again(t.toLowerCase()) }, t)),
      el("button", { class: "small", onclick: again(null) }, "Try again")),
    el("p", { class: "small muted learning-line" }, "✏️ Change anything before you copy it. Claude learns from what you copy, so your next captions sound more like you.")].filter(Boolean));
  box.append(card);
  drawTab();
}

/** One box per thing she pastes somewhere, each with its own Copy button. Edits in a box are what gets copied. */
function captionPanel(tab, r, id) {
  const field = (label, text, hint, key) => {
    if (!text) return null;
    const area = el("textarea", { class: "copy-area", rows: "2", "aria-label": label });
    area.value = text;
    const fit = () => { area.style.height = "auto"; area.style.height = Math.min(area.scrollHeight + 2, 420) + "px"; };
    area.addEventListener("input", fit);
    requestAnimationFrame(fit);
    return el("div", { class: "copy-field" },
      el("div", { class: "row" }, el("strong", {}, label), el("div", { class: "spacer" }),
        el("span", { class: "small muted count" }, text.length + " characters"), copyButton(() => { captionUsed(id, key, area.value); return area.value; }, "Copy", { class: "copy-btn primary small" })),
      hint ? el("div", { class: "small muted" }, hint) : null, area);
  };
  if (tab === "YOUTUBE" && r.youtube) {
    return [field("Title", r.youtube.title, null, "YOUTUBE_TITLE"),
      field("Description", r.youtube.description, "Your shop links and Amazon's disclosure are already at the end.", "YOUTUBE_DESCRIPTION"),
      field("Tags", r.youtube.tags, "Paste into Tags under \"Show more\" when you upload."),
      field("Comment to pin", r.youtube.pinnedComment, "Post it, then tap ⋮ › Pin.", "YOUTUBE_PINNED")].filter(Boolean);
  }
  if (tab === "INSTAGRAM" && r.instagram) {
    return [field("Caption", r.instagram.caption, null, "INSTAGRAM_CAPTION"),
      r.keyword ? el("div", { class: "keyword-row" }, el("span", {}, "Comment keyword: "), el("strong", {}, r.keyword), copyButton(r.keyword, "Copy", { class: "copy-btn small" })) : null,
      field("DM reply with your links", r.dmReply, r.keyword ? "Paste into your comment-to-DM tool (like ManyChat) for the word " + r.keyword + ", or send it when someone asks." : "Send it when someone asks for the link.", "DM_REPLY"),
      field("Story text", r.instagram.storyText, r.products.length ? "Add a link sticker to the Story and paste one of these links:" : null, "INSTAGRAM_STORY"),
      r.products.length && r.instagram.storyText ? el("div", { class: "sticker-links" }, r.products.map((p) =>
        copyButton(p.url, "Copy link: " + p.name, { class: "copy-btn small" }))) : null].filter(Boolean);
  }
  if (tab === "TIKTOK" && r.tiktok) {
    return [field("Caption", r.tiktok.caption, null, "TIKTOK_CAPTION"),
      field("Keyword auto-reply for DMs", r.dmReply, "TikTok can't DM people who comment. Put your shop list in your bio, and paste this as a keyword auto-reply in TikTok's Business Suite.", "DM_REPLY")].filter(Boolean);
  }
  if (tab === "LINKS") {
    return [el("ul", { class: "product-list compact" }, r.products.map((p) => el("li", { class: "product-card" },
      el("div", { class: "product-body" }, el("div", { class: "product-name" }, storeBadge(p.store), el("span", {}, p.name)),
        el("div", { class: "small muted link-url" }, p.url), p.code ? el("div", { class: "small" }, "Code: ", el("strong", {}, p.code)) : null,
        p.warning ? el("div", { class: "small warn" }, p.warning) : null),
      el("div", { class: "link-actions" }, copyButton(p.url, "Copy link", { class: "copy-btn primary small" }),
        p.code ? copyButton(p.code, "Copy code", { class: "copy-btn small" }) : null)))),
      field("For your Linktree or bio page", r.bioList, "One line per product.")].filter(Boolean);
  }
  return [el("p", { class: "muted" }, "Nothing for this one.")];
}

function recentCaptions(root, recent, resultBox) {
  const box = el("div", { class: "card", id: "recent-captions" }, el("h3", {}, "Recent captions"));
  if (!recent.length) {
    box.append(el("p", { class: "small muted" }, "Captions you write show up here, so you can copy them again later."));
    return box;
  }
  box.append(el("ul", { class: "recent-list" }, recent.map((c) => el("li", {},
    el("button", { class: "recent-open", onclick: action(async () => {
      const v = await api("GET", "/api/captions/" + c.id);
      captionsState = { about: v.about, note: v.note || "", productIds: v.productIds, pick: false, opportunityId: v.opportunityId, pictures: [], result: v };
      renderCaptions(root);
    }) }, el("span", { class: "title" }, c.title), el("span", { class: "small muted" },
      [c.brand, c.platforms.map((p) => PLATFORM_NAMES[p]).join(", "), fmtDate(c.createdAt)].filter(Boolean).join(" · "))),
    el("button", { class: "small icon danger", title: "Delete", "aria-label": "Delete " + c.title, onclick: action(async () => {
      if (!confirm("Delete these captions?")) return;
      await api("DELETE", "/api/captions/" + c.id);
      if (captionsState && captionsState.result && captionsState.result.id === c.id) captionsState.result = null;
      renderCaptions(root);
    }) }, "✕")))));
  return box;
}

/** A screenshot made at most 1280 pixels wide or tall, as JPEG: small enough to send quickly, sharp enough to read. */
function shrinkPicture(file) {
  return new Promise((resolve, reject) => {
    const img = new Image();
    const url = URL.createObjectURL(file);
    img.onload = () => {
      const scale = Math.min(1, 1280 / Math.max(img.width, img.height));
      const c = document.createElement("canvas");
      c.width = Math.round(img.width * scale);
      c.height = Math.round(img.height * scale);
      c.getContext("2d").drawImage(img, 0, 0, c.width, c.height);
      URL.revokeObjectURL(url);
      resolve({ name: file.name, mediaType: "image/jpeg", base64: c.toDataURL("image/jpeg", 0.85).split(",")[1] });
    };
    img.onerror = () => { URL.revokeObjectURL(url); reject(new Error("not a picture")); };
    img.src = url;
  });
}
