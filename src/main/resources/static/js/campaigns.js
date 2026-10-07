/*
 * Creator CRM: Pitch campaigns: saved contact lists, templates, and one pitch per brand sent slowly from her Gmail.
 * Part of the dashboard; index.html loads these files in order and they share one scope (see core.js).
 */
"use strict";

// ---------- Campaigns ----------

const TEMPLATE_KINDS = { COLLAB: "Collab pitch", UGC: "UGC offer", GIFTING: "Gifting ask", RATES: "Rate card", OTHER: "Other" };
const TARGET_STATES = { WAITING_LINE: "Writing opening line", DRAFTED: "In Drafts", APPROVED: "Approved, in the queue", SENT: "Sent",
  REPLIED: "Replied", BOUNCED: "Bounced", OPTED_OUT: "Said no thanks", COLD: "No reply", MOVED_ON: "Tried the next person",
  SKIPPED: "Skipped", STOPPED: "Stopped" };
const TARGET_TONES = { WAITING_LINE: "grey", DRAFTED: "blue", APPROVED: "blue", SENT: "violet", REPLIED: "green", BOUNCED: "amber",
  OPTED_OUT: "grey", COLD: "grey", MOVED_ON: "grey", SKIPPED: "grey", STOPPED: "grey" };
const LIST_ROLES = ["PARTNERSHIPS", "PR", "MARKETING", "FOUNDER", "GENERAL", "SUPPORT", "OTHER"];

function minutesUntil(iso) {
  const m = Math.max(0, Math.round((new Date(iso).getTime() - Date.now()) / 60000));
  return m <= 1 ? "in a minute" : "in " + m + " minutes";
}

/**
 * Sending rules: her postal address (required in every campaign email), how many new pitches a day, and the warm-up.
 * Shown on Campaigns and in Settings; both save the same settings.
 */
function campaignRulesCard(p, onSaved) {
  const address = el("textarea", { maxlength: "300", placeholder: "PO Box 123\nAustin, TX 78701\nUSA", rows: "3" });
  address.value = p.campaignPostalAddress || "";
  const cap = el("input", { type: "number", min: "10", max: "50", value: p.campaignDailyCap || "30", class: "short" });
  const warmup = el("input", { type: "checkbox" });
  warmup.checked = p.campaignWarmup !== "false";
  return el("div", { class: "card" },
    el("h3", {}, "Sending rules"),
    el("label", {}, "Your business postal address (a PO box works)"), address,
    el("p", { class: "small muted" }, "Every campaign email ends with your name, this address and a line saying \"Not a fit? Just reply "
      + "'no thanks' and I won't email you again\". US law (CAN-SPAM) asks for both in pitch emails. Anyone who answers like that is "
      + "never emailed again. Nothing is sent until this is filled in."),
    el("div", { class: "row" }, el("label", {}, "New pitches a day, at most"), cap),
    el("p", { class: "small muted" }, "10 to 50. Follow-ups don't count. Gmail allows far more, but going slowly keeps your emails out of spam."),
    el("label", { class: "check" }, warmup, " Warm up slowly: 10 a day the first week, 5 more each week after"),
    el("p", { class: "small muted" }, "New senders who go fast land in spam. The warm-up drops back to 10 a day if more than 2 in 100 emails bounce."),
    el("p", {}, el("button", { class: "primary small", onclick: action(async () => {
      const saved = await api("PUT", "/api/settings/preferences", { campaignPostalAddress: address.value,
        campaignDailyCap: cap.value, campaignWarmup: String(warmup.checked) });
      if (onSaved) onSaved(saved);
    }, "Saved") }, "Save")));
}

async function renderCampaigns(root) {
  const [o, settings] = await Promise.all([api("GET", "/api/campaigns"), api("GET", "/api/settings")]);
  clear(root);
  const redraw = () => renderCampaigns(root);
  root.appendChild(el("h1", {}, "Pitch campaigns"));
  root.appendChild(el("p", { class: "muted" }, "One pitch to each brand on a list, to its best-ranked person, from your own Gmail. "
    + "You read and approve every email in Drafts. Approved ones go out a few minutes apart, on weekdays during the brand's working "
    + "hours. A reply from anyone at a brand stops that brand. Templates use no Claude at all."));

  if (!o.addressSet) {
    root.appendChild(el("div", { class: "alert warn" }, "Add your business postal address under Sending rules below before your first campaign."));
  }
  root.appendChild(sendingCard(o, redraw));
  root.appendChild(campaignListCard(o, redraw));
  root.appendChild(newCampaignCard(o, redraw));
  root.appendChild(savedListsCard(o, redraw));
  root.appendChild(templatesCard(o, redraw));
  root.appendChild(campaignRulesCard(settings.preferences, redraw));
}

/** Is it sending, how many today, what's next, and the approved emails waiting their turn. */
function sendingCard(o, redraw) {
  const box = card(null);
  box.id = "campaign-sending";
  if (o.pausedReason) {
    box.appendChild(el("div", { class: "alert warn" }, el("strong", {}, "Sending is paused. "), o.pausedReason));
    box.appendChild(el("div", { class: "row" }, el("button", { class: "primary", onclick: action(async () => {
      await api("POST", "/api/campaigns/sending/resume");
      redraw();
    }, "Sending again") }, "Resume sending")));
  } else {
    box.appendChild(el("div", { class: "row" },
      el("h3", { class: "spacer" }, "Sending"),
      el("button", { class: "small", onclick: action(async () => {
        await api("POST", "/api/campaigns/sending/pause");
        redraw();
      }, "Paused") }, "Pause all sending")));
  }
  const warm = o.warmup && o.allowance < o.cap ? " (warming up; your limit is " + o.cap + ")" : "";
  box.appendChild(el("p", {}, "Today: " + o.sentToday + " of " + o.allowance + " new pitches sent" + warm + "."));
  if (!o.queue.length) {
    box.appendChild(el("p", { class: "small muted" }, "Nothing waiting to go. Approve campaign emails in Drafts and they line up here."));
    return box;
  }
  box.appendChild(el("p", { class: "small muted" }, o.queue.length + (o.queue.length === 1 ? " email is" : " emails are") + " approved and waiting"
    + (o.nextSendAt && !o.pausedReason ? "; the next can go " + minutesUntil(o.nextSendAt) : "")
    + ". Each goes on a weekday between 9 and 5 where the brand is."));
  box.appendChild(el("ul", { class: "campaign-queue" }, o.queue.slice(0, 50).map((q) => el("li", { class: "row" },
    el("div", { class: "spacer" }, el("strong", {}, q.brand), " ", el("span", { class: "small muted" }, (q.type === "FOLLOW_UP" ? "Follow-up to " : "Pitch to ") + q.to)),
    el("button", { class: "small", title: "Take it out of the queue and back to Drafts", onclick: action(async () => {
      await api("POST", "/api/campaigns/drafts/" + q.draftId + "/unapprove");
      redraw();
    }, "Back in Drafts") }, "Take back")))));
  if (o.queue.length > 50) box.appendChild(el("p", { class: "small muted" }, "…and " + (o.queue.length - 50) + " more."));
  return box;
}

function campaignListCard(o, redraw) {
  const box = el("div", { class: "card" }, el("h3", {}, "Your campaigns"));
  if (!o.campaigns.length) {
    box.appendChild(emptyLine("No campaigns yet. Make a list and pick a template below, then start one."));
    return box;
  }
  for (const v of o.campaigns) {
    const c = v.campaign;
    const n = (k) => v.counts[k] || 0;
    const facts = [["In Drafts", n("DRAFTED") + n("WAITING_LINE")], ["Approved", n("APPROVED")], ["Sent", n("SENT") + n("COLD") + n("MOVED_ON")],
      ["Replied", n("REPLIED")], ["Bounced", n("BOUNCED")], ["No thanks", n("OPTED_OUT")]].filter(([, x]) => x > 0);
    const details = el("div", { class: "hidden" });
    const status = c.status === "ACTIVE" ? null : el("span", { class: "badge" }, c.status === "PAUSED" ? "Paused" : "Ended");
    const buttons = c.status === "DONE" ? [] : [
      n("DRAFTED") ? el("button", { class: "small primary", onclick: action(async () => {
        if (!confirm("Approve all " + n("DRAFTED") + " pitches from " + c.name + " that are in Drafts? Read a few first: each goes out "
          + "exactly as written. Any with a blank to fill in stay in Drafts.")) return;
        const r = await api("POST", "/api/campaigns/" + c.id + "/approve-all");
        toast(r.approved + " approved" + (r.skipped.length ? ". Still in Drafts: " + r.skipped.join("; ") : ""), r.skipped.length > 0);
        redraw();
      }) }, "Approve all in Drafts") : null,
      c.status === "ACTIVE" ? el("button", { class: "small", title: "Add more brands from the list, up to about two days of sending",
        onclick: action(async () => {
          const r = await api("POST", "/api/campaigns/" + c.id + "/draft-more");
          toast(r.added ? r.added + " more pitches are in Drafts" : "Nothing added: the queue already holds about two days of sending, or the list has no new brands");
          redraw();
        }) }, "Draft more") : null,
      el("button", { class: "small", onclick: action(async () => {
        await api("POST", "/api/campaigns/" + c.id + (c.status === "ACTIVE" ? "/pause" : "/resume"));
        redraw();
      }) }, c.status === "ACTIVE" ? "Pause" : "Resume"),
      el("button", { class: "small danger", onclick: action(async () => {
        if (!confirm("End " + c.name + "? Pitches not sent yet are taken out of Drafts. Sent ones keep their follow-ups.")) return;
        await api("POST", "/api/campaigns/" + c.id + "/end");
        redraw();
      }) }, "End")];
    box.appendChild(el("div", { class: "campaign-row" },
      el("div", { class: "row" },
        el("div", { class: "spacer" }, el("strong", {}, c.name), " ", status,
          el("div", { class: "small muted" }, v.listName + " · " + v.templateName + (c.personalise ? " · personal opening lines" : ""))),
        el("button", { class: "small", onclick: action(async () => {
          if (!details.classList.contains("hidden")) { details.classList.add("hidden"); return; }
          const rows = await api("GET", "/api/campaigns/" + c.id + "/targets");
          clear(details).appendChild(targetsTable(rows));
          details.classList.remove("hidden");
        }) }, "Brands")),
      facts.length ? el("div", { class: "chips" }, facts.map(([k, x]) => el("span", { class: "chip" }, k + " " + x))) : null,
      el("div", { class: "row" }, buttons),
      details));
  }
  return box;
}

function targetsTable(rows) {
  if (!rows.length) return emptyLine("No brands yet.");
  return el("div", { class: "table-wrap" }, el("table", { class: "table" },
    el("thead", {}, el("tr", {}, el("th", {}, "Brand"), el("th", {}, "To"), el("th", {}, "Where it is"))),
    el("tbody", {}, rows.map(({ target: t, brand }) => el("tr", {},
      el("td", {}, t.opportunityId ? el("a", { href: "#", onclick: (e) => { e.preventDefault(); openDeal(t.opportunityId); } }, brand) : brand),
      el("td", { class: "small" }, t.email + (t.attempt > 1 ? " (second try)" : "")),
      el("td", {}, el("span", { class: "status-tag tone-" + (TARGET_TONES[t.state] || "grey") }, TARGET_STATES[t.state] || pretty(t.state)),
        t.endedReason ? el("div", { class: "small muted" }, t.endedReason) : null))))));
}

function newCampaignCard(o, redraw) {
  const box = el("div", { class: "card" }, el("h3", {}, "Start a campaign"));
  if (!o.lists.length || !o.templates.length) {
    box.appendChild(emptyLine("First save a list of contacts below. Four starter templates are ready to edit."));
    return box;
  }
  const name = el("input", { maxlength: "120", placeholder: "e.g. Skincare, October" });
  const list = el("select", { "aria-label": "List" }, o.lists.map((l) => el("option", { value: l.list.id }, l.list.name + " (" + l.brands + " brands)")));
  const tpl = el("select", { "aria-label": "Template" }, o.templates.map((t) => el("option", { value: t.id }, t.name)));
  const personal = el("input", { type: "checkbox", id: "campaign-personal", disabled: !o.claudeReady });
  box.append(
    el("div", { class: "grid" },
      el("div", {}, el("label", {}, "Name"), name),
      el("div", {}, el("label", {}, "Who to pitch"), list),
      el("div", {}, el("label", {}, "Template"), tpl)),
    el("label", { class: "check", for: "campaign-personal" }, personal, " Personalise: Claude writes one opening line for each brand"),
    el("p", { class: "small muted" }, o.claudeReady
      ? "Uses Claude Haiku in a half-price overnight batch, roughly a tenth of a cent per brand. Off: the template only, no Claude at all."
      : "Needs Claude connected in Settings. The template on its own works without it."),
    el("p", { class: "small muted" }, "Each brand gets one email, to its best-ranked person you can email. Brands you already have a deal "
      + "with, brands another campaign is pitching, and anyone on your do-not-email list are left out. About two days of pitches "
      + "are written at a time, and more each morning."),
    el("p", {}, el("button", { class: "primary", disabled: !o.addressSet, title: o.addressSet ? null : "Add your postal address under Sending rules first",
      onclick: action(async () => {
        await api("POST", "/api/campaigns", { name: name.value, listId: Number(list.value), templateId: Number(tpl.value), personalise: personal.checked });
        toast(personal.checked ? "Started. Pitches reach Drafts once their opening lines are written." : "Started. The first pitches are in Drafts.");
        redraw();
      }) }, "Start campaign")));
  return box;
}

// ----- saved lists -----

function savedListsCard(o, redraw) {
  const box = el("div", { class: "card", id: "campaign-lists" }, el("h3", {}, "Saved lists"),
    el("p", { class: "small muted" }, "A list is a set of rules over your Brand contacts, so it stays up to date as people are added. "
      + "From each brand that matches, only its best-ranked person is pitched."));
  for (const v of o.lists) {
    const editor = el("div", { class: "hidden" });
    box.appendChild(el("div", { class: "campaign-row" },
      el("div", { class: "row" },
        el("div", { class: "spacer" }, el("strong", {}, v.list.name), " ", el("span", { class: "small muted" }, v.brands + " brands")),
        el("button", { class: "small", onclick: () => {
          if (editor.classList.toggle("hidden")) return;
          clear(editor).appendChild(listEditor(v.list, v.filter, redraw));
        } }, "Edit"),
        el("button", { class: "small danger", onclick: action(async () => {
          if (!confirm("Delete the list " + v.list.name + "? The contacts themselves stay.")) return;
          await api("DELETE", "/api/campaigns/lists/" + v.list.id);
          redraw();
        }) }, "Delete")),
      editor));
  }
  const fresh = el("div", { class: "hidden" });
  box.appendChild(el("p", {}, el("button", { onclick: () => {
    if (fresh.classList.toggle("hidden")) return;
    clear(fresh).appendChild(listEditor(null, { roles: ["PARTNERSHIPS", "PR", "MARKETING"], neverEmailed: true, skipBrandsInDeals: true }, redraw));
  } }, "New list")));
  box.appendChild(fresh);
  return box;
}

function listEditor(list, f, redraw) {
  const name = el("input", { maxlength: "120", value: list ? list.name : "", placeholder: "e.g. Partnerships inboxes, never emailed" });
  const roles = new Set(f.roles || []);
  const roleChips = el("div", {});
  const drawRoles = () => clear(roleChips).appendChild(el("div", { class: "chips", role: "group", "aria-label": "Roles" },
    LIST_ROLES.map((r) => el("button", { class: "chip" + (roles.has(r) ? " active" : ""), "aria-pressed": String(roles.has(r)),
      onclick: () => { if (roles.has(r)) roles.delete(r); else roles.add(r); drawRoles(); } }, CONTACT_ROLES[r] || pretty(r)))));
  drawRoles();
  const box = (label, checked) => {
    const i = el("input", { type: "checkbox" });
    i.checked = !!checked;
    return [i, el("label", { class: "check" }, i, " " + label)];
  };
  const [verified, verifiedLabel] = box("Only addresses checked as real", f.verifiedOnly);
  const [never, neverLabel] = box("Only people you haven't emailed yet", f.neverEmailed);
  const [skip, skipLabel] = box("Leave out brands you already have a deal with", f.skipBrandsInDeals !== false);
  const replied = el("select", { "aria-label": "Replied before" },
    el("option", { value: "", selected: f.replied == null }, "Replied to you before, or not"),
    el("option", { value: "true", selected: f.replied === true }, "Only people who replied to you before"),
    el("option", { value: "false", selected: f.replied === false }, "Only people who never replied"));
  const brandText = el("input", { maxlength: "100", value: f.brandText || "", placeholder: "Any brand" });
  const minScore = el("input", { type: "number", min: "0", max: "100", value: f.minScore == null ? "" : f.minScore, class: "short", placeholder: "0" });
  const filter = () => ({ roles: [...roles], verifiedOnly: verified.checked, neverEmailed: never.checked,
    replied: replied.value === "" ? null : replied.value === "true", brandText: brandText.value || null,
    minScore: minScore.value === "" ? null : Number(minScore.value), skipBrandsInDeals: skip.checked });
  const result = el("div", {});
  return el("div", { class: "stack list-editor" },
    el("label", {}, "Name"), name,
    el("label", {}, "Roles (none picked = any)"), roleChips,
    verifiedLabel, neverLabel, skipLabel,
    el("div", { class: "grid" },
      el("div", {}, el("label", {}, "Replied before"), replied),
      el("div", {}, el("label", {}, "Brand name contains"), brandText),
      el("div", {}, el("label", {}, "Rank score at least"), minScore)),
    el("div", { class: "row" },
      el("button", { class: "small", onclick: action(async () => {
        const r = await api("POST", "/api/campaigns/lists/preview", { name: name.value, filter: filter() });
        clear(result).append(el("p", {}, r.brands + (r.brands === 1 ? " brand matches." : " brands match.") + (r.brands > r.sample.length ? " The first " + r.sample.length + ":" : "")),
          r.sample.length ? el("div", { class: "table-wrap" }, el("table", { class: "table" },
            el("thead", {}, el("tr", {}, el("th", {}, "Brand"), el("th", {}, "Would be pitched"), el("th", {}, "Rank"))),
            el("tbody", {}, r.sample.map((x) => el("tr", {}, el("td", {}, x.brand),
              el("td", {}, x.name ? x.name + " " : "", el("span", { class: "small muted" }, x.email)),
              el("td", { class: "small" }, x.score + " · " + (x.why || ""))))))) : null);
      }) }, "Preview"),
      el("button", { class: "small primary", onclick: action(async () => {
        await api(list ? "PUT" : "POST", "/api/campaigns/lists" + (list ? "/" + list.id : ""), { name: name.value, filter: filter() });
        redraw();
      }, "List saved") }, "Save list")),
    result);
}

// ----- templates -----

function templatesCard(o, redraw) {
  const box = el("div", { class: "card", id: "campaign-templates" }, el("h3", {}, "Templates"),
    el("p", { class: "small muted" }, "Words in curly brackets are filled in for each brand. Add a fallback after a bar for when there's "
      + "nothing to fill in: {why_you|I love your products.} A field with no value and no fallback becomes a blank like [PRODUCT], "
      + "and that email waits in Drafts until you fill it in."));
  for (const t of o.templates) {
    const editor = el("div", { class: "hidden" });
    box.appendChild(el("div", { class: "campaign-row" },
      el("div", { class: "row" },
        el("div", { class: "spacer" }, el("strong", {}, t.name), " ", el("span", { class: "small muted" }, TEMPLATE_KINDS[t.kind] || pretty(t.kind))),
        el("button", { class: "small", onclick: () => {
          if (editor.classList.toggle("hidden")) return;
          clear(editor).appendChild(templateEditor(t, o.fields, redraw));
        } }, "Edit")),
      editor));
  }
  const fresh = el("div", { class: "hidden" });
  box.appendChild(el("p", {}, el("button", { onclick: () => {
    if (fresh.classList.toggle("hidden")) return;
    clear(fresh).appendChild(templateEditor(null, o.fields, redraw));
  } }, "New template")));
  box.appendChild(fresh);
  return box;
}

function templateEditor(t, fields, redraw) {
  const name = el("input", { maxlength: "120", value: t ? t.name : "" });
  const kind = el("select", { "aria-label": "Kind" }, Object.entries(TEMPLATE_KINDS).map(([k, label]) =>
    el("option", { value: k, selected: t ? t.kind === k : k === "COLLAB" }, label)));
  const subject = el("input", { maxlength: "300", value: t ? t.subject : "", placeholder: "Collab idea for {brand}" });
  const body = el("textarea", { class: "tall", maxlength: "8000" });
  body.value = t ? t.body : "Hi {first_name},\n\n";
  const followUp = el("textarea", { maxlength: "4000", rows: "5" });
  followUp.value = t && t.followUpBody ? t.followUpBody : "";
  let lastFocus = body;
  [subject, body, followUp].forEach((x) => x.addEventListener("focus", () => { lastFocus = x; }));
  const insert = (key) => {
    const x = lastFocus, at = x.selectionStart ?? x.value.length;
    x.value = x.value.slice(0, at) + "{" + key + "}" + x.value.slice(x.selectionEnd ?? at);
    x.focus();
    x.selectionStart = x.selectionEnd = at + key.length + 2;
  };
  const preview = el("div", {});
  const input = () => ({ name: name.value, kind: kind.value, subject: subject.value, body: body.value, followUpBody: followUp.value });
  return el("div", { class: "stack" },
    el("div", { class: "grid" }, el("div", {}, el("label", {}, "Name"), name), el("div", {}, el("label", {}, "Kind"), kind)),
    el("label", {}, "Subject"), subject,
    el("label", {}, "Email"), body,
    el("div", { class: "small muted" }, "Insert a field:"),
    el("div", { class: "chips" }, Object.entries(fields).map(([k, help]) => el("button", { class: "chip", title: help, onclick: () => insert(k) }, k))),
    el("label", {}, "Follow-up (used for every follow-up; blank = Claude writes them)"), followUp,
    el("p", { class: "small muted" }, "Your name, postal address and the \"no thanks\" line are added at the end of every email; you don't need to type them."),
    el("div", { class: "row" },
      el("button", { class: "small", onclick: action(async () => {
        const r = await api("POST", "/api/campaigns/templates/preview", input());
        clear(preview).appendChild(el("div", { class: "preview-mail" },
          el("div", { class: "preview-head" }, el("span", { class: "muted" }, "Subject: "), el("strong", {}, r.subject)),
          el("div", { class: "preview-body" }, r.body)));
      }) }, "Preview with a real brand"),
      el("button", { class: "small primary", onclick: action(async () => {
        await api(t ? "PUT" : "POST", "/api/campaigns/templates" + (t ? "/" + t.id : ""), input());
        redraw();
      }, "Template saved") }, "Save template"),
      t ? el("button", { class: "small danger", onclick: action(async () => {
        if (!confirm("Delete the template " + t.name + "?")) return;
        await api("DELETE", "/api/campaigns/templates/" + t.id);
        redraw();
      }) }, "Delete") : null),
    preview);
}
