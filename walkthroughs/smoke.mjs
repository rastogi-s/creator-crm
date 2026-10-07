// Smoke test: signs in to a demo-mode Creator CRM and opens every page the way she would, at laptop and phone
// size. Fails on a JavaScript error, an error in the console, a failed API call, a red error toast, an empty page,
// or (on the phone) a page wider than the screen. Runs in CI on every pull request.
//
//   CRM_DATA_DIR=$(mktemp -d) java -jar target/creator-manager-*.jar --spring.profiles.active=demo &
//   cd walkthroughs && npm install && npx playwright install chromium && node smoke.mjs
//
// Screenshots of any page that failed go to OUT_DIR (default smoke-results/).
// Selectors stick to the tab bar (.tab[data-tab]), More's links (.more-link[data-tab]) and the #view-<name>
// sections, so that changes inside a page don't break this test.
import { chromium } from "playwright";
import { mkdirSync } from "node:fs";
import { join } from "node:path";

const BASE = process.env.CRM_URL || "http://localhost:8080";
const OUT = process.env.OUT_DIR || "smoke-results";
const SIZES = {
  laptop: { viewport: { width: 1366, height: 768 } },
  phone: { viewport: { width: 390, height: 844 }, isMobile: true, hasTouch: true, deviceScaleFactor: 2 },
};

// [page, how to get there]. "tab" is in the tab bar, "more" is one tap further under More, "hash" is by address.
const PLACES = [
  ["today", "tab"], ["drafts", "tab"], ["pipeline", "tab"], ["money", "tab"], ["more", "tab"],
  ["settings", "more"], ["setup", "more"], ["help", "more"], ["whatsnew", "more"], ["summary", "more"], ["links", "more"], ["contacts", "more"], ["directory", "more"],
  ["outreach", "hash"],
];

async function signIn(page) {
  await page.goto(BASE + "/login.html");
  await page.fill("#username", process.env.CRM_USER || "demo");
  await page.fill("#password", process.env.CRM_PASSWORD || "demo-password-123");
  await Promise.all([page.waitForURL((u) => !u.pathname.endsWith("login.html")), page.click("button[type=submit]")]);
}

async function open(page, name, how) {
  if (how === "hash") return page.evaluate((h) => { location.hash = "#" + h; }, name);
  if (how === "more") {
    await page.locator(".tab[data-tab=more]").click();
    await page.locator(`#view-more .more-link[data-tab=${name}]`).click();
    return;
  }
  await page.locator(`.tab[data-tab=${name}]`).click();
}

// Waits for the page to be shown and drawn, then lists what's wrong with it.
async function check(page, name, size) {
  const view = page.locator("#view-" + name);
  const problems = [];
  try {
    await view.waitFor({ state: "visible", timeout: 10000 });
    await view.locator("h1").first().waitFor({ state: "visible", timeout: 10000 });
  } catch (e) {
    problems.push("the page never showed a heading");
  }
  await page.waitForLoadState("networkidle").catch(() => {});
  await page.waitForTimeout(300);
  const state = await page.evaluate((id) => {
    const v = document.getElementById(id);
    const t = document.getElementById("toast");
    const shown = [...document.querySelectorAll(".view")].filter((s) => !s.classList.contains("hidden")).map((s) => s.id);
    return {
      text: v ? v.innerText.trim().length : 0,
      shown,
      toast: t && t.classList.contains("error") && !t.classList.contains("hidden") ? t.textContent : null,
      width: document.documentElement.scrollWidth,
      screen: window.innerWidth,
    };
  }, "view-" + name);
  if (state.text < 20) problems.push("the page is (nearly) empty");
  if (state.shown.length !== 1 || state.shown[0] !== "view-" + name) problems.push("pages shown: " + state.shown.join(", "));
  if (state.toast) problems.push("error message: " + state.toast);
  if (size === "phone" && state.width > state.screen + 1) {
    problems.push(`page is ${state.width}px wide on a ${state.screen}px screen (sideways scrolling)`);
  }
  return problems;
}

const failures = [];
mkdirSync(OUT, { recursive: true });
const browser = await chromium.launch(process.env.CHROMIUM_PATH ? { executablePath: process.env.CHROMIUM_PATH } : {});
try {
  for (const [size, options] of Object.entries(SIZES)) {
    const context = await browser.newContext({ ...options, colorScheme: "light" });
    const page = await context.newPage();
    let errors = [];
    page.on("pageerror", (e) => errors.push("JavaScript error: " + e.message));
    page.on("console", (m) => { if (m.type() === "error") errors.push("console error: " + m.text()); });
    page.on("response", (r) => {
      if (new URL(r.url()).pathname.startsWith("/api/") && r.status() >= 400) {
        errors.push(`${r.request().method()} ${new URL(r.url()).pathname} returned ${r.status()}`);
      }
    });
    // Nothing in this test should ever ask her to confirm something.
    page.on("dialog", (d) => { errors.push("unexpected dialog: " + d.message()); d.dismiss().catch(() => {}); });

    await signIn(page);
    await page.goto(BASE + "/#today");
    for (const [name, how] of PLACES) {
      errors = [];
      let problems;
      try {
        await open(page, name, how);
        problems = await check(page, name, size);
      } catch (e) {
        problems = ["couldn't get there: " + e.message.split("\n")[0]];
      }
      problems.push(...errors);
      if (problems.length) {
        const shot = join(OUT, `${size}-${name}.png`);
        await page.screenshot({ path: shot, fullPage: true }).catch(() => {});
        failures.push({ size, name, problems });
        console.log(`FAIL ${size} ${name}\n  - ${problems.join("\n  - ")}\n  screenshot: ${shot}`);
      } else {
        console.log(`ok   ${size} ${name}`);
      }
    }
    await context.close();
  }
} finally {
  await browser.close();
}

if (failures.length) {
  console.log(`\n${failures.length} page(s) failed.`);
  for (const f of failures) {
    if (process.env.GITHUB_ACTIONS) console.log(`::error title=Smoke test: ${f.name} (${f.size})::${f.problems.join("; ")}`);
  }
  process.exit(1);
}
console.log("\nAll pages opened cleanly at laptop and phone size.");
