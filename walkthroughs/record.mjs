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
      transition: "opacity .25s",
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
    await click(page.locator(".tab[data-tab=settings]"));
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
    await page.locator("#view-drafts").getByText("is attached").first().waitFor();
    await point(page.locator("#view-drafts").getByText("Invoice PDF").first());
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

  async "task-briefs"(page) {
    const { say, point, click } = helpers(page);
    const drawer = page.locator("#drawer");
    await page.goto(BASE + "/#today");
    const task = page.locator("#view-today .item", { hasText: "Sparkle Socks" }).filter({ has: page.locator(".task-about") }).first();
    await task.waitFor();
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
    await click(page.locator(".tab[data-tab=settings]"));
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
    await click(page.locator(".tab[data-tab=settings]"));
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
    await click(page.locator(".tab[data-tab=settings]"));
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
    const draft = page.locator("#view-drafts .card", { hasText: "Fern & Field" }).first();
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
    const decline = page.locator("#view-drafts .card", { hasText: "Tiny Treats — Decline" }).first();
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
    await page.locator("#view-drafts .card h3").first().waitFor();
    await pause(2500); // let the first refresh settle so the card isn't replaced mid-shot
    const draft = page.locator("#view-drafts .card", { hasText: "Glowberry Skin — Reply" }).first();
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
    await say("Each list has its own search and filters too. Pipeline, Outreach, Money and Drafts all have them.", 4400);
    await tabSearch.pressSequentially("glow", { delay: 90 });
    await pause(600);
    await tabSearch.fill("");
    await pause(400);
    await click(page.locator("#view-pipeline th button", { hasText: "Updated" }));
    await say("Click a column name to sort by it. Click it again to flip the order.", 3800);
    await click(page.locator("#view-pipeline .chip").first());
    await say("The status counts are filters too. Your choices stay put, even after the app restarts.", 4200);
    await click(page.locator("#view-pipeline .chip.active"));
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
    const draft = page.locator("#view-drafts .card", { hasText: "Coastline Coffee — Re-pitch" }).first();
    await draft.waitFor();
    await point(draft.locator("textarea"));
    await say("It mentions your last collab and one new idea. Edit anything, then press Send.", 4200);
    await point(draft.getByText("Sending it adds a new pitch"));
    await say("Sending it adds a new pitch to your pipeline, with follow-ups like any other.", 4000);
    await click(page.locator(".tab[data-tab=settings]"));
    const step = page.locator("#settings-rebook");
    await step.waitFor();
    await step.scrollIntoViewIfNeeded();
    await point(step.locator("input").first());
    await say("Under Settings, choose how long a brand must be quiet, and how many re-pitches you'd like each week.", 4600);
    await say("", 600);
  },

  async "payment-reminders"(page) {
    const { say, point, click } = helpers(page);
    await page.goto(BASE + "/#today");
    const late = page.locator("#view-today .item", { hasText: "Maple & Moss: payment" });
    await late.waitFor();
    await say("When a brand pays late, Today tells you how late, and how many reminders you've already sent.", 4400);
    await point(late);
    await say("A polite reminder with the invoice attached is already written. It gets a little firmer each time.", 4400);
    await click(late.getByRole("button", { name: "Review reminder" }));
    const draft = page.locator("#view-drafts .card", { hasText: "Payment reminder" }).first();
    await draft.waitFor();
    await point(draft.locator("textarea"));
    await say("Check it, change anything you like, then press Send. Reminders never go out without you.", 4400);
    await click(page.locator(".tab[data-tab=settings]"));
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
    await click(page.locator(".tab[data-tab=help]"));
    await page.locator("#view-help h1").waitFor();
    await say("Help keeps every walkthrough video, newest first, to rewatch any time.", 4000);
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
