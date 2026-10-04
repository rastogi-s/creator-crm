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
    await locator.scrollIntoViewIfNeeded();
    const box = await locator.boundingBox();
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
