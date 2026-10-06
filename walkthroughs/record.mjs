// Records one short, captioned video per feature against a demo-mode Creator CRM, then compresses it (WebM).
//
//   CRM_DATA_DIR=$(mktemp -d) CRM_DEMO_VIDEOS=$PWD/walkthroughs/videos \
//     java -jar target/creator-manager-*.jar --spring.profiles.active=demo &
//   cd walkthroughs && npm install && npx playwright install chromium && node record.mjs [name ...]
//
// Each video's file name must match a "video" in src/main/resources/whats-new.json. Needs ffmpeg on PATH.
// CRM_DEMO_VIDEOS lets the app play videos recorded earlier in the same run (What's New shows them).
import { chromium } from "playwright";
import { execFileSync } from "node:child_process";
import { mkdirSync, mkdtempSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";

const BASE = process.env.CRM_URL || "http://localhost:8080";
const OUT = process.env.OUT_DIR || "videos";
const SIZE = { width: 1280, height: 720 };

// Caption bar and a visible cursor, added to every page the recording opens.
const overlay = () => {
  const install = () => {
    if (document.getElementById("wt-caption")) return;
    const cap = document.createElement("div");
    cap.id = "wt-caption";
    Object.assign(cap.style, {
      position: "fixed", left: "50%", bottom: "28px", transform: "translateX(-50%)", zIndex: 99999,
      maxWidth: "980px", padding: "12px 20px", borderRadius: "12px", background: "rgba(20,18,16,.88)",
      color: "#fff", font: "600 22px/1.35 system-ui, sans-serif", textAlign: "center", opacity: "0",
      transition: "opacity .25s", pointerEvents: "none", // never in the way of a click, however it wraps
    });
    const dot = document.createElement("div");
    dot.id = "wt-cursor";
    Object.assign(dot.style, {
      position: "fixed", left: "-40px", top: "-40px", width: "22px", height: "22px", marginLeft: "-11px",
      marginTop: "-11px", borderRadius: "50%", background: "rgba(194,65,12,.35)", border: "2px solid #c2410c",
      zIndex: 100000, pointerEvents: "none", transition: "transform .12s",
    });
    document.body.append(cap, dot);
    addEventListener("mousemove", (e) => { dot.style.left = e.clientX + "px"; dot.style.top = e.clientY + "px"; }, true);
    addEventListener("mousedown", () => { dot.style.transform = "scale(.7)"; }, true);
    addEventListener("mouseup", () => { dot.style.transform = ""; }, true);
  };
  window.__caption = (text) => {
    install();
    const cap = document.getElementById("wt-caption");
    cap.textContent = text || "";
    cap.style.opacity = text ? "1" : "0";
  };
  if (document.readyState === "loading") addEventListener("DOMContentLoaded", install); else install();
};

function helpers(page) {
  const pause = (ms) => page.waitForTimeout(ms);
  const say = async (text, ms = 3200) => { await page.evaluate((t) => window.__caption(t), text); await pause(ms); };
  const point = async (locator) => {
    // Views can re-render while we look (e.g. after a sync), so look again if the element was just replaced.
    let box = null;
    for (let i = 0; i < 10 && !box; i++) {
      try {
        // Today folds everything past the first three items; open the fold the way she would.
        // (Not for the fold's own header: clicking that is how a video opens it.)
        await locator.evaluate((n) => {
          const start = n.closest("summary") ? n.closest("summary").parentElement.parentElement : n;
          for (let d = start && start.closest("details"); d; d = d.parentElement && d.parentElement.closest("details")) d.open = true;
        }, null, { timeout: 2000 });
        await locator.scrollIntoViewIfNeeded({ timeout: 2000 });
        box = await locator.boundingBox();
      } catch (e) {
        box = null;
      }
      if (!box) await pause(300);
    }
    if (!box) throw new Error("Not visible: " + locator);
    await page.mouse.move(box.x + box.width / 2, box.y + box.height / 2, { steps: 25 });
    await pause(400);
  };
  const click = async (locator) => { await point(locator); await locator.click(); await pause(700); };
  return { pause, say, point, click };
}

// Pages that aren't in the tab bar (Settings, Help, …) are one tap further, under More. Settings has its own
// tabs (accounts, you, deals, app, advanced); pass one to open it.
async function goTo(page, tab, settingsTab) {
  const { click } = helpers(page);
  const direct = page.locator(`.tab[data-tab=${tab}]`);
  if (await direct.count()) return click(direct);
  await click(page.locator(".tab[data-tab=more]"));
  await click(page.locator(`.more-link[data-tab=${tab}]`));
  if (settingsTab) await click(page.locator(`[data-settings-tab=${settingsTab}]`));
}

// Drafts is an inbox: the list, and the one draft that's open (the others are built but hidden).
function openCard(page) {
  return page.locator("#view-drafts .inbox-detail .card:not(.hidden)");
}

async function openDraft(page, ...texts) {
  const { click } = helpers(page);
  let row = page.locator("#view-drafts .inbox-row");
  for (const t of texts) row = row.filter({ hasText: t });
  await click(row.first());
  return openCard(page);
}

// One entry per video. Keep each under a minute: what it does, where it is, one click to try it.
const scenarios = {
  async updates(page) {
    const { pause, say, point, click } = helpers(page);
    await page.goto(BASE + "/#today");
    const banner = page.locator("#update-banner");
    await banner.waitFor();
    await say("When a new version of Creator CRM is out, a banner appears at the top.", 3800);
    await point(banner.getByRole("button", { name: "Update now" }));
    await say("Click Update now. It backs up your data, installs the new version and reopens the app.", 4200);
    page.once("dialog", (d) => d.accept());
    await click(banner.getByRole("button", { name: "Update now" }));
    await say("Nothing is ever installed without your click. Afterwards, What's New shows what changed.", 4200);
    await goTo(page, "settings", "app");
    const card = page.locator(".card", { has: page.getByRole("heading", { name: "Updates" }) });
    await card.scrollIntoViewIfNeeded();
    await say("Under Settings, Updates you can check by hand, or turn automatic checks off.", 3600);
    await click(card.getByRole("button", { name: "Check now" }));
    await say("", 600);
  },

  async invoices(page) {
    const { say, point, click } = helpers(page);
    const drawer = page.locator("#drawer");
    await page.goto(BASE + "/#money");
    await page.locator("#view-money h1").waitFor();
    await say("The Money tab shows what's booked, what's invoiced and waiting, what's been paid and what's late.", 4200);
    const ready = page.locator("#view-money .item", { hasText: "Lumen Labs" });
    await point(ready);
    await say("Lumen Labs asked for an invoice. Press Create invoice.", 3200);
    await click(ready.getByRole("button", { name: "Create invoice" }));
    await drawer.getByRole("button", { name: "Email invoice" }).waitFor();
    await say("The amount and what you made come from the deal. Change anything you need.", 4000);
    await point(drawer.getByRole("button", { name: "Preview PDF" }));
    await say("Preview PDF shows exactly what the brand gets.", 3000);
    await click(drawer.getByRole("button", { name: "Email invoice" }));
    await openCard(page).getByText("is attached").waitFor();
    await point(openCard(page).getByText("Invoice PDF"));
    await say("A short note with the PDF attached waits in Drafts. Nothing is sent until you press Send.", 4400);
    await click(page.locator(".tab[data-tab=money]"));
    const late = page.locator("#view-money tr", { hasText: "Juniper Juice" });
    await point(late);
    await say("Juniper Juice's invoice is overdue, and they say they've paid.", 3600);
    await click(late);
    await point(drawer.getByRole("button", { name: "Mark paid" }));
    await say("Once the money is in your bank, press Mark paid. The app never decides that for you.", 4200);
    await click(drawer.getByRole("button", { name: "Mark paid" }));
    await click(drawer.getByRole("button", { name: "Close" }));
    await page.evaluate(() => window.scrollTo({ top: 0, behavior: "smooth" }));
    await point(page.locator("#view-money .stat").nth(2));
    await say("It now counts in Paid this month. Export CSV gives you the year's invoices for taxes.", 4200);
    await say("", 600);
  },

  async "campaign-results"(page) {
    const { say, point, click } = helpers(page);
    const drawer = page.locator("#drawer");
    await page.goto(BASE + "/#pipeline");
    const row = page.locator("#view-pipeline tr", { hasText: "Petal & Pine" });
    await row.waitFor();
    await say("Your Petal & Pine candle post went up three weeks ago.", 3400);
    await click(row.locator("strong"));
    const box = drawer.locator("#results");
    await box.waitFor();
    await box.scrollIntoViewIfNeeded();
    await point(box.locator("a", { hasText: "View post" }));
    await say("When a deal is marked Posted, the app finds your post on Instagram. Or paste its link here.", 4600);
    await point(box.locator(".grid.numbers"));
    await say("A week later it reads the post's reach, views, likes, comments, saves and shares.", 4400);
    await say("For a TikTok or a Story, just type the numbers in.", 3200);
    await point(box.getByRole("button", { name: "Preview PDF" }));
    await say("They go on a one-page results PDF for the brand.", 3400);
    await click(drawer.getByRole("button", { name: "Close" }));
    await click(page.locator(".tab[data-tab=drafts]"));
    const recap = await openDraft(page, "Petal & Pine", "Results recap");
    await recap.waitFor();
    await recap.scrollIntoViewIfNeeded();
    await point(recap.locator("textarea"));
    await say("And a thank-you email is drafted with the PDF attached, plus an idea for working together again.", 4800);
    await point(recap.locator("a", { hasText: "Results PDF" }));
    await say("Like every draft, nothing is sent until you check it and press Send.", 3800);
    await say("", 600);
  },

  async "deal-progress"(page) {
    const { say, point, click } = helpers(page);
    const drawer = page.locator("#drawer");
    await page.goto(BASE + "/#pipeline");
    const row = page.locator("#view-pipeline tr", { hasText: "Nova Nest Home" });
    await row.waitFor();
    await say("Once a brand says yes, every deal shows where it is.", 3200);
    await click(row.locator("strong"));
    const box = drawer.locator("#deal-progress");
    await box.waitFor();
    await point(box.locator(".stages"));
    await say("Contract, content, approval, posting, invoice and payment, step by step.", 4200);
    await point(box.locator(".stages li.now"));
    await say("The orange step is where the deal is now, with its due date.", 3600);
    await say("Claude reads every email you get or send, and moves the deal to the right step by itself, even if a few steps happen at once.", 5600);
    await point(box.locator(".stage-now button"));
    await say("If something happened outside email, press this button to move it on.", 3800);
    await click(drawer.getByRole("button", { name: "Close" }));
    await say("", 600);
  },

  async "task-briefs"(page) {
    const { say, point, click } = helpers(page);
    const drawer = page.locator("#drawer");
    await page.goto(BASE + "/#today");
    const task = page.locator("#view-today .item", { hasText: "Sparkle Socks" }).filter({ has: page.locator(".task-about") }).first();
    await task.waitFor({ state: "attached" }); // may be in the folded part of Today; point() opens it
    await task.scrollIntoViewIfNeeded();
    await say("To-dos that come from an email now tell you what it's about.", 3400);
    await point(task.locator(".brief"));
    await say("Claude sums up what's asked, what the opportunity is and what you need ready.", 4200);
    await point(task.locator(".links .chip").first());
    await say("Links from the email, like this sign-up form, are right on the to-do.", 3800);
    await point(task.getByRole("button", { name: "Read email" }));
    await say("Read email opens the deal at that email, in one click.", 3200);
    await click(task.getByRole("button", { name: "Read email" }));
    await drawer.locator(".msg.focus").first().waitFor();
    await say("Here it is, highlighted. For Gmail emails, Open in Gmail jumps to the original.", 4200);
    await click(drawer.getByRole("button", { name: "Close" }));
    await goTo(page, "settings", "you");
    const rules = page.locator("#task-rules");
    await rules.waitFor();
    await rules.scrollIntoViewIfNeeded();
    await point(rules);
    await say("Want to-dos written your way? Add your own rules here, and Claude follows them for new emails.", 4600);
    await say("", 600);
  },

  async "calendar-sync"(page) {
    const { say, point, click } = helpers(page);
    const drawer = page.locator("#drawer");
    await page.goto(BASE + "/#pipeline");
    await page.locator("#view-pipeline tr").first().waitFor();
    await say("Your deal dates can now go on your Google Calendar.", 3400);
    await goTo(page, "settings");
    const step = page.locator("#settings-calendar");
    await step.waitFor();
    await step.scrollIntoViewIfNeeded();
    await point(step.locator("h3"));
    await say("In Settings, press Reconnect Gmail once and allow calendar access.", 4000);
    await point(step.locator("label.check"));
    await say("Contracts to sign, content due, posting days and payments go on a calendar called Creator CRM.", 4600);
    await point(step.locator("p.small").last());
    await say("They move when a deal changes and disappear when they're done. Your other calendars stay private.", 4600);
    await page.goto(BASE + "/#pipeline");
    const row = page.locator("#view-pipeline tr", { hasText: "Nova Nest Home" });
    await row.waitFor();
    await click(row.locator("strong"));
    const clash = drawer.locator("#exclusivity");
    await clash.waitFor();
    await clash.scrollIntoViewIfNeeded();
    await point(clash.locator("li.flag").first());
    await say("Deals also warn you when their dates clash with another brand's exclusivity.", 4200);
    await say("Only you know if the brands compete, so it's a heads-up, not a rule.", 3800);
    const dates = drawer.locator(".card", { hasText: "on your calendar" }).first();
    if (await dates.count()) {
      await dates.scrollIntoViewIfNeeded();
      await point(dates.locator(".detail").first());
      await say("Dates on your calendar are marked here too.", 3200);
    }
    await say("", 600);
  },

  async "contract-check"(page) {
    const { say, point, click } = helpers(page);
    const drawer = page.locator("#drawer");
    await page.goto(BASE + "/#pipeline");
    const row = page.locator("#view-pipeline tr", { hasText: "Bloomleaf Tea" });
    await row.waitFor();
    await say("Bloomleaf Tea emailed a contract. Claude has already read it.", 3600);
    await click(row.locator("strong"));
    const box = drawer.locator("#contract-check");
    await box.waitFor();
    await box.scrollIntoViewIfNeeded();
    await point(box.locator(".badge"));
    await say("The contract check shows what to push back on and what to look at.", 4000);
    await point(box.locator("li.flag.red").first());
    await say("Red is outside your limits: here, waiting 60 days to be paid, and unlimited revisions.", 4600);
    await point(box.locator("li.flag.amber").first());
    await say("Amber is worth a look, like a year of ads, exclusivity, or no kill fee.", 4200);
    await point(box.locator("summary"));
    await say("Contract on DocuSign? Paste its text here and it's checked the same way.", 4200);
    await click(drawer.getByRole("button", { name: "Close" }));
    await goTo(page, "settings", "deals");
    const limits = page.locator("#settings-contracts");
    await limits.waitFor();
    await limits.scrollIntoViewIfNeeded();
    await point(limits.locator("input").first());
    await say("Set your own limits in Settings. It's a checklist, not legal advice; you decide what to sign.", 4600);
    await say("", 600);
  },

  async "rate-advisor"(page) {
    const { say, point, click } = helpers(page);
    const drawer = page.locator("#drawer");
    await page.goto(BASE + "/#pipeline");
    const row = page.locator("#view-pipeline tr", { hasText: "Fern & Field" });
    await row.waitFor();
    await say("Fern & Field offered $600 for a Reel and two Stories, and wants to run the Reel as an ad for three months.", 4600);
    await click(row.locator("strong"));
    const box = drawer.locator("#rate-advisor");
    await box.waitFor();
    await box.scrollIntoViewIfNeeded();
    await point(box.locator(".big-number"));
    await say("What to ask for shows a fair price for exactly what they want.", 3600);
    await point(box.locator("table.lines"));
    await say("Line by line: your usual price for each Reel and Story, plus extra for three months of ads.", 4400);
    await point(box.locator("p", { hasText: "Their offer" }));
    await say("Their offer is well under that. Your prices come from your last paid deals, or the rates in About you.", 4600);
    await point(box.locator("input.amount"));
    await say("Change the number if you like, then press Draft counter.", 3200);
    await click(box.getByRole("button", { name: "Draft counter" }));
    const draft = openCard(page).filter({ hasText: "Fern & Field" });
    await draft.waitFor();
    await point(draft.locator("textarea"));
    await say("A friendly counter-offer quoting exactly your amount waits here. Nothing is sent until you approve it.", 4600);
    await say("", 600);
  },

  async "lead-scoring"(page) {
    const { say, point, click } = helpers(page);
    await page.goto(BASE + "/#pipeline");
    const table = page.locator("#view-pipeline table");
    await table.waitFor();
    await say("Every new lead now gets a score: High value, Medium or Low value.", 3800);
    await point(table.locator("tr", { hasText: "Glowberry Skin" }).locator(".lead"));
    await say("Glowberry offers a paid Reel close to your usual rate, so it's high value. Hover to see why.", 4400);
    await point(table.locator("tr", { hasText: "Tiny Treats" }).locator(".lead"));
    await say("Tiny Treats offers far less than you usually get, so it's low value.", 3800);
    await page.locator("#lead-filter").selectOption("LOW");
    await point(page.locator("#lead-filter"));
    await say("Show just the low-value leads, then tick the ones that aren't a fit.", 3800);
    for (const box of await page.locator("#view-pipeline input.pick").all()) {
      await box.check();
      await page.waitForTimeout(400);
    }
    page.once("dialog", (d) => d.accept());
    await click(page.getByRole("button", { name: /Decline selected/ }));
    const bar = page.locator("#send-declines");
    await bar.waitFor();
    const decline = await openDraft(page, "Tiny Treats", "Decline");
    await point(decline.locator("textarea"));
    await say("A short, polite no-thanks is written for each one. Read them, and change anything you like.", 4400);
    await point(page.locator("#send-declines").getByRole("button", { name: "Approve all declines" }));
    await say("Happy with them? Approve all declines sends them together, and each deal is closed.", 4400);
    await say("", 600);
  },

  async "day-summary"(page) {
    const { pause, say, point, click } = helpers(page);
    await page.goto(BASE + "/#today");
    await page.locator("#view-today h1").waitFor();
    await pause(2500);
    await page.locator("#view-today button:text-is('Done')").first().click();
    await pause(3800); // let the toast fade before filming
    await page.goto(BASE + "/#summary");
    await page.locator("#view-summary .eod-hero").waitFor();
    await say("Day summary now shows your day at a glance.", 3400);
    await point(page.locator("#view-summary .eod-stats"));
    await say("What you got done, what's still waiting, new deals and tomorrow. Press a number to jump there.", 4400);
    await point(page.locator("#eod-done"));
    await say("Everything you did today, with the brand and the time.", 3600);
    await click(page.locator(".eod-stat", { hasText: "Still to do" }));
    await pause(900);
    await point(page.locator("#eod-waiting button:text-is('Done')").first());
    await say("Things still waiting on you, most important first. Press Done when you've handled one.", 4400);
    await click(page.locator(".eod-stat", { hasText: "For tomorrow" }));
    await pause(900);
    await say("And what's lined up for tomorrow, so you can close the laptop knowing where to start.", 4400);
    await say("", 600);
  },
  async "ask-claude"(page) {
    const { pause, say, point, click } = helpers(page);
    await page.goto(BASE + "/#drafts");
    await page.locator("#view-drafts .inbox-row").first().waitFor();
    await pause(2500); // let the first refresh settle so the card isn't replaced mid-shot
    const draft = await openDraft(page, "Glowberry Skin", "Reply");
    const box = draft.locator(".ask-claude");
    await point(box);
    await say("Under every draft you can now ask Claude to change it.", 3400);
    await click(box.getByRole("button", { name: "Warmer" }));
    await box.getByText("Changed.").waitFor();
    await point(draft.locator("textarea"));
    await say("Claude rewrites it in your voice and keeps the facts and numbers.", 4000);
    await point(box.getByRole("button", { name: "Undo" }));
    await say("Not quite right? Undo puts back the version from before.", 3600);
    await click(box.getByRole("button", { name: "Undo" }));
    const ask = box.getByRole("textbox");
    await click(ask);
    await ask.pressSequentially("mention I'm free to film in March", { delay: 60 });
    await say("Or type exactly what you want changed.", 2800);
    await click(box.getByRole("button", { name: "Change" }));
    await box.getByText("Changed.").waitFor();
    await say("Nothing is sent. The draft waits here until you read it and press Send.", 4200);
    await say("", 600);
  },
  async "send-preview"(page) {
    const { pause, say, point, click } = helpers(page);
    // Demo mode has no Gmail, so Send is stood in for here: the drafts list says it could go out, and the send
    // and undo calls answer as the real app does. Nothing leaves the demo either way.
    await page.route("**/api/drafts", async (route) => {
      const res = await route.fetch();
      const list = await res.json();
      for (const d of list) { d.blockedReason = null; d.sendingAt = null; }
      await route.fulfill({ response: res, json: list });
    });
    await page.route(/\/api\/drafts\/\d+\/send$/, (route) => {
      const id = Number(route.request().url().match(/drafts\/(\d+)\//)[1]);
      route.fulfill({ json: { draftId: id, sendAt: new Date(Date.now() + 10000).toISOString(), undoSeconds: 10 } });
    });
    await page.route(/\/api\/drafts\/\d+\/undo-send$/, (route) => route.fulfill({ json: { undone: true } }));
    await page.route(/\/api\/drafts\/\d+\/send-status$/, (route) => route.fulfill({ json: { state: "pending", error: null } }));

    await page.goto(BASE + "/#drafts");
    await page.addStyleTag({ content: ".undo-bars { bottom: 7rem; }" }); // above the video's caption
    await page.locator("#view-drafts .inbox-row").first().waitFor();
    await pause(2500); // let the first refresh settle so the card isn't replaced mid-shot
    const draft = await openDraft(page, "Maple & Moss", "Payment reminder");
    await click(draft.getByRole("button", { name: "Send", exact: true }));
    const preview = page.locator("dialog.send-preview");
    await preview.waitFor();
    await point(preview.locator(".preview-mail"));
    await say("Before anything goes out, you see exactly what the brand will get: who it's to, the subject, the message and the PDF attached.", 5200);
    await point(preview.getByRole("button", { name: "Keep editing" }));
    await say("Spot something? Keep editing takes you back to the draft.", 3400);
    await click(preview.getByRole("button", { name: "Send email" }));
    const bar = page.locator(".undo-bar");
    await bar.waitFor();
    await point(bar);
    await say("After you press Send, you have ten seconds to change your mind.", 3800);
    await click(bar.getByRole("button", { name: "Undo" }));
    await say("Undo stops it, and the draft is back here, unsent.", 3600);
    await say("Follow-ups you've set to go out automatically still send on their own, as before.", 4200);
    await say("", 600);
  },

  async "search"(page) {
    const { pause, say, point, click } = helpers(page);
    await page.goto(BASE + "/#pipeline");
    await page.locator("#view-pipeline table").waitFor();
    const box = page.locator("#global-search");
    await point(box);
    await say("Search everything from the top bar: brands, contacts, old emails and DMs, and invoices.", 4200);
    await box.click();
    await box.pressSequentially("serum", { delay: 90 });
    const hit = page.locator("#global-results .hit").first();
    await hit.waitFor();
    await point(hit);
    await say("It even finds words inside past emails. Click a result to open that deal.", 4000);
    await box.fill("");
    await page.keyboard.press("Escape");
    const tabSearch = page.locator("#view-pipeline input[type=search]");
    await point(tabSearch);
    await say("Each list has its own search and filters too. Deals, Pitching brands, Money and Drafts all have them.", 4400);
    await tabSearch.pressSequentially("glow", { delay: 90 });
    await pause(600);
    await tabSearch.fill("");
    await pause(400);
    await click(page.locator("#view-pipeline th button", { hasText: "Updated" }));
    await say("Click a column name to sort by it. Click it again to flip the order.", 3800);
    // The first pill is All; pick a stage so the filter visibly changes the list.
    await click(page.locator("#view-pipeline .chip.stage:not(.active)").first());
    await say("The status counts are filters too. Your choices stay put, even after the app restarts.", 4200);
    await click(page.locator("#view-pipeline .chip.stage", { hasText: "All" }));
    await click(page.locator(".tab[data-tab=money]"));
    const overdue = page.locator("#view-money .stat", { hasText: "Invoiced, waiting for payment" });
    await overdue.waitFor();
    await click(overdue);
    await say("On Money, click a total to see the invoices behind it.", 3800);
    await say("", 600);
  },

  async "linktree-import"(page) {
    const { say, point, click } = helpers(page);
    const view = page.locator("#view-links");
    await page.goto(BASE + "/#links");
    await view.locator("h1").waitFor();
    await say("The Links page keeps your profiles, shop and portfolio in one place, and drafts use these exact links.", 4400);
    await click(view.getByRole("tab", { name: "From Linktree" }));
    const input = view.locator(".links-add input").first();
    await point(input);
    await input.pressSequentially("linktr.ee/mayamakes", { delay: 60 });
    await say("Paste your Linktree link and press Find links.", 3000);
    await click(view.getByRole("button", { name: "Find links" }));
    await view.locator(".links-pick").waitFor();
    await say("Everything on your Linktree shows up here. Untick anything you don't want, or rename it.", 4400);
    const add = view.locator(".links-pick button.primary");
    await click(add);
    await view.locator(".link-card").first().waitFor();
    await page.evaluate(() => window.scrollTo({ top: 0, behavior: "smooth" }));
    await point(view.locator(".social-chip").first());
    await say("Your profiles sit at the top as quick buttons, and every link gets its own card.", 4200);
    const card = view.locator(".link-card").first();
    await point(card.getByRole("button", { name: "Copy" }));
    await say("Copy a link for a DM, change the order with the arrows, or press Edit to rename it.", 4200);
    await say("", 600);
  },

  async "win-back"(page) {
    const { say, point, click } = helpers(page);
    await page.goto(BASE + "/#today");
    const rebook = page.locator("#rebook");
    await rebook.waitFor();
    await rebook.scrollIntoViewIfNeeded();
    await say("Brands that paid you before are the easiest yes. Rebook past brands lists the ones worth pitching again.", 4600);
    const coastline = rebook.locator(".item", { hasText: "Coastline Coffee" });
    await point(coastline);
    await say("Coastline Coffee paid you three months ago and has gone quiet, so a short re-pitch is already written.", 4600);
    await point(rebook.locator(".item", { hasText: "Petal & Pine" }));
    await say("Petal & Pine sent a gifted collab. Three weeks after your post, it suggests a paid one this time.", 4600);
    await click(coastline.getByRole("button", { name: "Review re-pitch" }));
    const draft = openCard(page).filter({ hasText: "Coastline Coffee — Re-pitch" });
    await draft.waitFor();
    await point(draft.locator("textarea"));
    await say("It mentions your last collab and one new idea. Edit anything, then press Send.", 4200);
    await point(draft.getByText("Sending it adds a new pitch"));
    await say("Sending it adds a new pitch to your pipeline, with follow-ups like any other.", 4000);
    await goTo(page, "settings", "deals");
    const step = page.locator("#settings-rebook");
    await step.waitFor();
    await step.scrollIntoViewIfNeeded();
    await point(step.locator("input").first());
    await say("Under Settings, choose how long a brand must be quiet, and how many re-pitches you'd like each week.", 4600);
    await say("", 600);
  },

  async "new-layout"(page) {
    const { say, point, click } = helpers(page);
    await page.goto(BASE + "/#today");
    await page.locator("#view-today h1").waitFor();
    await point(page.locator(".tabs"));
    await say("The top bar now has five places: Today, Drafts, Deals, Money and More.", 4200);
    const first = page.locator("#view-today .card", { hasText: "Do these first" });
    await point(first);
    await say("Today starts with the three things to do first, picked by money and how late they are.", 4400);
    const rest = page.locator("#view-today details.more-items summary");
    await click(rest);
    await say("The rest of today's list is folded underneath, one tap away.", 3600);
    await click(page.locator(".tab[data-tab=pipeline]"));
    await point(page.locator("#view-pipeline .segmented"));
    await say("Deals has your deals and, next door, the brands you're pitching.", 4000);
    await click(page.locator(".tab[data-tab=more]"));
    await point(page.locator("#view-more .more-list"));
    await say("More has your day summary, your links, Settings and Help.", 4000);
    await say("On your phone, the five places sit at the bottom of the screen.", 3600);
    await say("", 600);
  },

  async "drafts-inbox"(page) {
    const { say, point, click } = helpers(page);
    await page.goto(BASE + "/#drafts");
    const list = page.locator("#view-drafts .inbox-list");
    await list.locator(".inbox-row").first().waitFor();
    await say("Drafts now works like your email inbox.", 3200);
    await point(list);
    await say("The list shows each brand, the kind of message, and its first line.", 4000);
    await point(list.locator(".inbox-row", { hasText: "Has a blank" }).first());
    await say("A yellow tag means Claude left a blank for you to fill in.", 3600);
    const draft = await openDraft(page, "Glowberry Skin", "Reply");
    await point(draft.locator("textarea"));
    await say("Press a draft to open it beside the list. Edit it, ask Claude to change it, or send it.", 4600);
    await click(list.locator(".inbox-row").first());
    await say("Anything you typed in the other draft is kept while you look at this one.", 4000);
    await say("On your phone, the list fills the screen. Press a draft to read it, and All drafts to go back.", 4600);
    await say("", 600);
  },

  async "full-emails"(page) {
    const { say, point, click } = helpers(page);
    const drawer = page.locator("#drawer");
    await page.goto(BASE + "/#pipeline");
    const row = page.locator("#view-pipeline tr", { hasText: "Glowberry Skin" });
    await row.waitFor();
    await say("Brand emails now look the way they did in Gmail.", 3200);
    await click(row.locator("strong"));
    const email = drawer.locator(".email-box").first();
    await email.waitFor();
    await email.scrollIntoViewIfNeeded();
    await page.waitForTimeout(800);
    await point(email);
    await say("Pictures, colours and layout are all there, and every link works.", 4200);
    await say("Links open in a new window, so the app stays where it was.", 3800);
    await say("Earlier messages in the thread fold away behind a Show earlier messages button.", 4200);
    await say("Emails are cleaned first: nothing in them can run in the app.", 3800);
    await click(drawer.getByRole("button", { name: "Close" }));
    await say("", 600);
  },

  async "safer-sending"(page) {
    const { say, point, click } = helpers(page);
    await page.goto(BASE + "/#drafts");
    await page.locator("#view-drafts .inbox-row").first().waitFor();
    const draft = await openDraft(page, "Peak Trail Co");
    await say("Sometimes Claude doesn't know a number yet, like your rate for one Reel. It leaves a blank for you.", 4400);
    const note = draft.locator(".alert.warn");
    await point(note);
    await say("Drafts now point out every blank, so it can't slip through.", 3600);
    await say("Until it's filled in, the app won't send it. Automatic follow-ups wait too.", 4200);
    const box = draft.locator("textarea");
    await point(box);
    await box.evaluate((t) => { t.value = t.value.replace("[RATE FOR 1 REEL]", "$650"); t.dispatchEvent(new Event("input")); });
    await say("Type your number in, and the warning goes away.", 3600);
    await say("If Gmail or Instagram ever stops connecting, a red bar on every page shows a Reconnect button.", 4400);
    await say("", 600);
  },

  async "payment-reminders"(page) {
    const { say, point, click } = helpers(page);
    await page.goto(BASE + "/#today");
    const late = page.locator("#view-today .item", { hasText: "Maple & Moss: payment" });
    await late.waitFor({ state: "attached" });
    await say("When a brand pays late, Today tells you how late, and how many reminders you've already sent.", 4400);
    await point(late);
    await say("A polite reminder with the invoice attached is already written. It gets a little firmer each time.", 4400);
    await click(late.getByRole("button", { name: "Review reminder" }));
    const draft = openCard(page).filter({ hasText: "Payment reminder" });
    await draft.waitFor();
    await point(draft.locator("textarea"));
    await say("Check it, change anything you like, then press Send. Reminders never go out without you.", 4400);
    await goTo(page, "settings", "deals");
    const days = page.getByText("Payment reminders: days after the due date");
    await point(days);
    await say("Under Settings, Invoices, pick the days: 3, 7 and 14 after the due date to start with.", 4200);
    await say("Reminders stop as soon as you mark the invoice paid.", 3200);
    await say("", 600);
  },

  async "auto-backup"(page) {
    const { say, point, click } = helpers(page);
    await page.goto(BASE + "/#settings?backup");
    const card = page.locator("#backup");
    await card.waitFor();
    await card.scrollIntoViewIfNeeded();
    await say("Automatic backups save all your deals, messages and settings every night, without you doing anything.", 4400);
    await point(card.locator("input").nth(1));
    await say("They go into this folder. When you have OneDrive, it's inside OneDrive, so a copy is safe even if the laptop is lost.", 4800);
    const pw = card.locator("input[type=password]");
    await pw.nth(0).fill(process.env.CRM_PASSWORD || "demo-password-123");
    await pw.nth(1).pressSequentially("my secret backup phrase", { delay: 40 });
    await pw.nth(2).pressSequentially("my secret backup phrase", { delay: 40 });
    await say("Choose a backup passphrase once, and write it down somewhere safe. Without it a backup can't be opened.", 4600);
    page.once("dialog", (d) => d.accept());
    await click(card.getByRole("button", { name: "Save and turn on" }));
    const fresh = page.locator("#backup");
    await fresh.getByRole("button", { name: "Back up now" }).waitFor();
    await fresh.scrollIntoViewIfNeeded();
    await click(fresh.getByRole("button", { name: "Back up now" }));
    await page.locator("#backup").getByText("Last backup").waitFor();
    await page.locator("#backup").getByText("Last backup").evaluate((e) => e.scrollIntoView({ block: "center", behavior: "smooth" }));
    await point(page.locator("#backup").getByText("Last backup"));
    await say("Done. It keeps the last two weeks. If the laptop is off at night, it backs up soon after you open the app.", 4800);
    await say("", 600);
  },

  async "practice-mode"(page) {
    const { say, point, click } = helpers(page);
    await page.goto(BASE + "/#more");
    const card = page.locator(".practice-card");
    await card.locator(".row button, .row a.btn").first().waitFor();
    // A practice copy left open by an earlier recording: close it off camera, so this one starts fresh.
    const leftOpen = card.getByRole("button", { name: "Close practice" });
    if (await leftOpen.count()) { await leftOpen.click(); await card.getByRole("button", { name: "Start practice" }).waitFor(); }
    await say("Want to try something without touching your real deals? Practise with sample brands.", 4200);
    await point(card);
    await say("Practice mode is under More.", 2600);
    await click(card.getByRole("button", { name: "Start practice" }));
    await say("It takes a few seconds to set up a copy of the app with made-up brands.", 3600);
    const banner = page.locator("#practice-banner");
    await banner.waitFor({ timeout: 60000 });
    await page.waitForTimeout(800);
    await point(banner);
    await say("This orange bar means you're practising. Nothing here is real, and nothing is sent.", 4400);
    await click(page.locator(".tab[data-tab=drafts]"));
    const row = page.locator("#view-drafts .inbox-row", { hasText: "Maple & Moss" }).first();
    await row.waitFor();
    await say("Everything works like the real app. Here are drafts for the sample brands.", 3800);
    await click(row);
    const send = page.locator("#view-drafts .inbox-detail .card:not(.hidden)").getByRole("button", { name: "Send" });
    await point(send);
    await say("Press Send to see what happens next. In practice, Send only pretends.", 4000);
    await click(send);
    const preview = page.locator("dialog.send-preview");
    await click(preview.locator("button.primary"));
    await say("The deal moves on, just like a real send. Your real brands never see it.", 4200);
    await point(banner.getByRole("button", { name: "Leave practice" }));
    await say("When you're done, press Leave practice to go back to your own deals.", 3800);
    await click(banner.getByRole("button", { name: "Leave practice" }));
    await page.locator(".practice-card").waitFor();
    await say("Starting again gives you fresh sample brands.", 3200);
    await say("", 600);
  },

  async "setup-guide"(page) {
    const { say, point, click } = helpers(page);
    const step = page.locator(".setup-step");
    await page.goto(BASE + "/#setup");
    await step.waitFor();
    await say("On a new computer, a setup guide opens by itself. One step per screen.", 4000);
    await point(page.locator(".setup-progress"));
    await say("Your name, Claude, Gmail and your rates. That's all it takes.", 3600);
    await click(step.getByRole("button", { name: "Start" }));
    await page.locator(".setup-step h1", { hasText: "Connect Claude" }).waitFor();
    await point(page.locator(".setup-why"));
    await say("Every step says why the app needs it.", 3000);
    await point(page.locator(".setup-howto"));
    await say("And exactly what to click, with links straight to the right page.", 4000);
    await click(step.getByRole("button", { name: /Skip for now|Next/ }));
    await page.locator(".setup-step h1", { hasText: "Connect Gmail" }).waitFor();
    await say("Gmail is the one technical step, done once. It's spelled out click by click.", 4400);
    await point(step.getByRole("button", { name: "Copy" }));
    await say("Copy puts the address Google asks for on your clipboard.", 3400);
    await click(step.getByRole("button", { name: /Skip for now|Next/ }));
    await page.locator(".setup-step h1", { hasText: "Your rates" }).waitFor();
    await say("Then your rates. Drafts only ever quote what you write here.", 3800);
    await click(step.getByRole("button", { name: "Skip for now" }));
    await page.locator(".setup-checklist").waitFor();
    await point(page.locator(".setup-checklist"));
    await say("The last screen shows what's connected, and what you can add later.", 4000);
    await goTo(page, "settings");
    await page.locator(".settings-tabs").waitFor();
    await say("Settings is now in five tabs instead of one long page.", 3600);
    await click(page.locator("[data-settings-tab=deals]"));
    await say("Deals & money has follow-ups, invoices, rates and contracts.", 3600);
    await click(page.locator("[data-settings-tab=advanced]"));
    await say("Advanced keeps the things you rarely need out of the way.", 3600);
    await say("", 600);
  },

  async "whats-new"(page) {
    const { pause, say, point, click } = helpers(page);
    await page.goto(BASE + "/#whatsnew");
    await page.locator("#view-whatsnew h1").waitFor();
    await say("After every update, What's New opens once and shows what changed.", 3800);
    await point(page.locator("#view-whatsnew .feature h3").first());
    const clip = page.locator("#view-whatsnew video").first();
    if (await clip.isVisible()) await clip.evaluate((v) => { v.muted = true; return v.play(); }).catch(() => {});
    await say("Each new feature gets a short video like this one.", 4500);
    if (await clip.isVisible()) await clip.evaluate((v) => v.pause()).catch(() => {});
    await point(page.locator("#view-whatsnew .feature a.btn").first());
    await say("Try it takes you straight to the feature.", 3000);
    await goTo(page, "help");
    await page.locator("#view-help h1").waitFor();
    await say("Help keeps every walkthrough video, newest first, to rewatch any time.", 4000);
    await say("", 600);
  },

  async "health-check"(page) {
    const { say, point, click } = helpers(page);
    await page.goto(BASE + "/#today");
    const strip = page.locator("#view-today .health-strip");
    await strip.locator("a, button").first().waitFor();
    await say("Today now tells you in one line when something in the app needs you.", 3800);
    const stripFix = strip.locator(".btn, button").first();
    if (await stripFix.count()) {
      await point(stripFix);
      await say("It comes with one button that fixes it.", 3000);
    }
    await goTo(page, "more");
    const card = page.locator("#health");
    await card.locator(".health-row").first().waitFor();
    await say("Everything working? is at the top of More: Claude, Gmail, Instagram, backups and updates.", 4400);
    await point(card.locator(".health-row.ok").first());
    await say("A tick means it's working.", 2600);
    await point(card.locator(".health-row", { hasText: "Gmail" }));
    await say("If Gmail or Instagram stops connecting, it says since when, and Reconnect signs you in again.", 4600);
    // Demo mode starts with automatic backups off: a real thing to fix on camera. (Skipped if an earlier video turned them on.)
    const turnOn = card.locator(".health-row", { hasText: "Backups" }).getByRole("link", { name: "Turn on" });
    if (await turnOn.count()) {
      await point(card.locator(".health-row", { hasText: "Backups" }));
      await say("Here, automatic backups are off. Turn on goes straight to the right place.", 3800);
      await click(turnOn);
      const backup = page.locator("#backup");
      await backup.waitFor();
      const pw = backup.locator("input[type=password]");
      await pw.nth(0).fill(process.env.CRM_PASSWORD || "demo-password-123");
      await pw.nth(1).pressSequentially("my secret backup phrase", { delay: 30 });
      await pw.nth(2).pressSequentially("my secret backup phrase", { delay: 30 });
      page.once("dialog", (d) => d.accept());
      await click(backup.getByRole("button", { name: "Save and turn on" }));
      await page.locator("#backup").getByRole("button", { name: "Back up now" }).waitFor();
      await goTo(page, "more");
    }
    const backups = page.locator("#health .health-row", { hasText: "Backups" });
    const now = backups.getByRole("button", { name: "Back up now" });
    if (await now.count()) {
      await say("No backup yet, so: Back up now.", 2600);
      await click(now);
      await page.locator("#health .health-row.ok", { hasText: "Backups" }).waitFor();
      await point(page.locator("#health .health-row", { hasText: "Backups" }));
      await say("Fixed: a tick, and when it was saved.", 3200);
    }
    await point(page.locator("#health").getByRole("button", { name: "Check again" }));
    await say("Check again looks for a new version once more. Everything else is always up to date.", 4200);
    await say("", 600);
  },
};

async function signIn(page) {
  await page.goto(BASE + "/login.html");
  await page.fill("#username", process.env.CRM_USER || "demo");
  await page.fill("#password", process.env.CRM_PASSWORD || "demo-password-123");
  await Promise.all([page.waitForURL((u) => !u.pathname.endsWith("login.html")), page.click("button[type=submit]")]);
}

const wanted = process.argv.slice(2).length ? process.argv.slice(2) : Object.keys(scenarios);
mkdirSync(OUT, { recursive: true });
const browser = await chromium.launch(process.env.CHROMIUM_PATH ? { executablePath: process.env.CHROMIUM_PATH } : {});
try {
  for (const name of wanted) {
    if (!scenarios[name]) throw new Error("Unknown walkthrough: " + name);
    // Sign in off camera, then record with the session cookie already set.
    const auth = await browser.newContext({ viewport: SIZE });
    const loginPage = await auth.newPage();
    await signIn(loginPage);
    const storageState = await auth.storageState();
    await auth.close();

    const raw = mkdtempSync(join(tmpdir(), "walkthrough-"));
    const context = await browser.newContext({ viewport: SIZE, storageState, bypassCSP: true, colorScheme: "light",
      recordVideo: { dir: raw, size: SIZE } });
    await context.addInitScript(overlay);
    const page = await context.newPage();
    await scenarios[name](page);
    const video = page.video();
    await context.close();
    const webm = await video.path();
    // VP9 WebM: plays in Edge, Chrome, Firefox and Safari, and (unlike H.264) in Playwright's own Chromium too.
    const out = join(OUT, name + ".webm");
    execFileSync("ffmpeg", ["-y", "-loglevel", "error", "-i", webm, "-c:v", "libvpx-vp9", "-crf", "36", "-b:v", "0",
      "-row-mt", "1", "-deadline", "good", "-an", out]);
    rmSync(raw, { recursive: true, force: true });
    console.log("Recorded " + out);
  }
} finally {
  await browser.close();
}
