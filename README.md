# Creator CRM

A self-hosted brand-collaboration manager for content creators. It reads your Gmail and Instagram DMs,
works out what each brand message means, and turns it into a prioritized daily plan: replies to send,
contracts to sign, content deadlines, and a disciplined five-step follow-up schedule. You approve every
message before anything is sent.

> **AI decides what a message means. The app decides what happens next.**
> Claude classifies messages and writes drafts; statuses, tasks, deadlines and follow-ups are driven by
> plain, testable rules in the database.

## What you get

- **Today view**: high-priority tasks (overdue ones escalated), follow-ups due (`Brand X — Follow-up #3`),
  new paid/gifted/affiliate opportunities, upcoming deadlines, and drafts awaiting approval.
- **Pipeline**: every brand with a status (🆕 New Lead → 💰 Negotiating → ✍️ Contract to Sign → 🎬 Content
  To Create → 👀 Awaiting Approval → 💵 Payment Pending → …), updated automatically from new messages.
- **Follow-up engine**: #1 after 4 days, then 5, 7, 7, 7 (configurable). Stops when the brand replies;
  marks the deal 🧊 Cold if there's still no answer after the final one. You pick the daily time follow-ups
  are drafted, and can opt in to sending email follow-ups automatically at that time.
- **Learns from your writing**: every message you send is kept next to Claude's original draft, with whether
  the brand replied. New drafts get your closest past examples, favouring the ones you edited and the ones
  that got answers, so drafts drift toward how you actually write. You can see and prune the examples in
  Settings, or switch it off.
- **Your Instagram numbers**: followers, engagement rate on recent posts and 28-day reach are read from your
  connected account once a day, so rate replies and pitches quote real figures.
- **Links page**: your Instagram, TikTok, YouTube, website and portfolio in one list you can add to any time.
  Drafts use these exact links instead of placeholders.
- **Brands from Instagram**: Outreach lists accounts that tagged, @mentioned or commented on you (warm leads you
  can turn into pitches). With the optional Facebook connection you can also look up any brand's Instagram by
  handle (followers, bio, an email in the bio, creators it tags in sponsored posts), web-researched leads get
  the same details, and fans are hidden. Official API only; nothing is scraped.
- **Outreach database**: brands you've pitched, with follow-up #1–#5 dates, and duplicate-pitch protection.
- **Find brands to pitch**: describe the brands you want ("clean skincare brands like Glossier that work with
  UGC creators") and Claude searches the web for ones that fit your profile, finds their published
  partnerships/PR email, and suggests a pitch idea. Pick one and a personalised pitch lands in Drafts; once you
  send it, the follow-up schedule starts like any other pitch.
- **Drafts in your voice**: replies, rates, negotiation, follow-ups, declines, "ask for budget / usage
  rights", and so on. Email drafts are also saved in your Gmail Drafts folder.
- **Invoices and the Money tab**: create an invoice from a deal (amount and deliverables filled in, numbered
  `INV-2026-001` upward per year), email it with the PDF attached after you approve the draft, and mark it paid
  when the money lands. The Money tab shows booked, invoiced, paid-this-month and overdue totals, and exports
  the year's invoices as CSV. Overdue invoices also show on Today.
- **End-of-day summary**: what was completed, what's pending, new opportunities, tomorrow's priorities.
- **MCP server**: use the CRM from Claude Desktop, Claude Code or Cowork ("what's on my plate today?").

## Download & install

Get the installer for your computer from the **[latest release](../../releases/latest)**. Java is included,
so there's nothing else to install.

| Your computer | File |
|---|---|
| Windows 10/11 | `Creator-CRM-<version>-windows-x64.msi` |
| Mac with Apple chip (M1–M4) | `Creator-CRM-<version>-macos-apple-silicon.dmg` |
| Mac with Intel chip | `Creator-CRM-<version>-macos-intel.dmg` |
| Ubuntu / Debian | `Creator-CRM-<version>-linux-amd64.deb` |

1. Install it. On Windows no admin rights are needed; it installs just for you.
2. Open **Creator CRM** from the Start menu, Applications folder or app launcher. Your browser opens the
   setup page with your one-time code already filled in: choose a username and password.
3. Follow the checklist on the **Settings** page to connect Claude, Gmail and (optionally) Instagram.

The app keeps running in the background with an icon in the system tray / menu bar: **Open Creator CRM** or
**Quit**. Opening it again just brings the dashboard back. Your data lives in `~/.creator-crm` (Windows:
`C:\Users\<you>\.creator-crm`), and new versions install over old ones without touching it.

**Updates.** From version 1.1.0 the app checks for new releases every few hours. When one is out, a banner offers
**Update now**: on Windows that one click backs up your data (to `~/.creator-crm/backups`), installs the new version
and reopens the app; on macOS and Linux it links to the download. After an update, **What's New** shows what changed,
with a short video per feature, and **Help** keeps all the videos. Turn automatic checks off under Settings → Updates
(or set `CRM_UPDATES_ENABLED=false`).

**"Unknown publisher" warnings.** The installers aren't code-signed yet, so your computer may warn you once:
- **Windows SmartScreen:** click **More info → Run anyway**.
- **macOS:** if it says the app "can't be opened", go to **System Settings → Privacy & Security** and click
  **Open Anyway** (older macOS: right-click the app → **Open**).

You can check a download against `SHA256SUMS.txt` on the release page.

### Server install (Docker)

```bash
docker run -d --name creator-crm -p 127.0.0.1:8080:8080 -v creator-crm:/data ghcr.io/rastogi-s/creator-crm:latest
docker logs creator-crm 2>&1 | grep -A3 "setup code"
```

Or with PostgreSQL: `docker compose up -d` using the included `docker-compose.yml`.

### Run from source

Needs JDK 21+.

```bash
./mvnw package                       # Windows: mvnw.cmd package
java -jar target/creator-manager-*.jar
```

Open <http://localhost:8080> and enter the one-time setup code printed in the console.

## Connect your accounts (Settings page)

Every credential you enter is **your own** and is stored encrypted (AES-256-GCM) in your database.

1. **Claude**: create an API key at [console.anthropic.com](https://console.anthropic.com) and paste it.
   Use **Test** to confirm it works. Default models: `claude-sonnet-5-5` for reading messages (low effort)
   and `claude-opus-5-5` for writing drafts (medium effort). You can change either under **About you**.
2. **Gmail**:
   1. In [Google Cloud Console](https://console.cloud.google.com), create a project and enable the **Gmail API**.
   2. Configure the OAuth consent screen (External) and add yourself as a test user.
   3. Create an **OAuth client ID** of type **Web application**. Add the redirect URI shown in Settings
      (default `http://localhost:8080/oauth/google/callback`).
   4. Paste the client ID and secret, then click **Connect Gmail**.

   Scopes requested: `gmail.readonly` + `gmail.compose`. The app can read mail and create/send drafts, but
   it can't delete, archive or relabel anything.
   *Note:* while the Google app is in "Testing" status, Google expires the connection after 7 days. Set the
   consent screen to "In production" for personal use to avoid reconnecting every week.
3. **Instagram** (optional; needs a Business or Creator account):
   1. At [developers.facebook.com](https://developers.facebook.com), create an app and add the **Instagram**
      product using **"API setup with Instagram login"**.
   2. Request `instagram_business_basic` and `instagram_business_manage_messages` (plus
      `instagram_business_manage_insights` if you want your 28-day reach shown, and
      `instagram_business_manage_comments` so brands commenting on your posts show up on Outreach), and add your own account
      as an Instagram tester. **App Review is not needed** while only accounts with a role on the app use it.
   3. Either paste an access token generated in the dashboard (works on localhost), or save the app ID and
      secret and click **Connect Instagram**. Meta requires an **https** redirect URI for this, so it needs a
      deployed URL or a tunnel.
   4. Optional, for real-time DMs: set the webhook callback to `<your https URL>/webhooks/instagram`,
      generate a verify token in Settings, and subscribe to `messages` (plus `comments` and `mentions`). Without
      webhooks, DMs and comments are fetched on every sync (every 30 minutes).
   5. Optional, for brand lookups: link your Instagram account to a Facebook Page you manage, add the
      **"Instagram API with Facebook Login"** use case to the same Meta app with `instagram_basic`,
      `instagram_manage_comments`, `instagram_manage_insights`, `pages_show_list`, `pages_read_engagement` and
      `business_management`, set its redirect URI to the one shown in Settings, give your Facebook account a
      role on the app, then save the Meta app ID and secret in Settings and click **Connect Facebook**. Only
      lookups use this connection; DMs and stats stay on the Instagram one.

   Meta only allows **replies within 24 hours** of the brand's last message. Outside that window (cold
   pitches, most follow-ups) the app shows **Copy** and **I sent it myself** instead of **Send**.
4. **About you**: your name, voice, rates and rules. Drafts only quote rates written here; missing numbers
   become placeholders like `[RATE FOR 1 REEL]`. You can also set your time zone here.
5. **Follow-ups**: the days to wait before each follow-up, the daily time they're drafted, and an opt-in switch
   to send **email** follow-ups automatically at that time. Replies, rates, other drafts and Instagram DMs
   always wait for your approval.
6. **MCP** (optional): generate an API key, then:

   ```bash
   claude mcp add --transport http creator-crm http://localhost:8080/mcp --header "Authorization: Bearer <key>"
   ```

   Tools: `get_today_plan`, `get_end_of_day_summary`, `list_pipeline`, `get_opportunity`,
   `get_followups_due`, `find_brand`, `log_pitch`, `create_task`, `complete_task`, `update_status`,
   `mark_followup_sent`, `stop_followups`, `draft_message`, `list_pending_drafts`, `sync_now`, `send_draft`,
   money and invoices (`get_money_summary`, `list_unpaid_invoices`, `create_invoice`, `draft_invoice_email`,
   `mark_invoice_paid`), outreach (`find_brands_to_pitch`, `list_brand_leads`, `draft_pitch_for_lead`,
   `dismiss_brand_lead`), rebooking (`list_rebook_candidates`, `draft_rebook_pitch`) and `get_instagram_stats`.
   Every draft tool only queues a draft for approval. `find_brands_to_pitch` runs web research and costs Claude credit.

   Claude Desktop (needs Node.js), in `claude_desktop_config.json`:

   ```json
   {"mcpServers": {"creator-crm": {"command": "npx", "args": ["-y", "mcp-remote", "http://localhost:8080/mcp",
     "--header", "Authorization: Bearer <key>"]}}}
   ```
   `send_draft` refuses unless you set `crm.mcp.allow-send=true`.

## How it works

```
Gmail / Instagram ──► ingestion (dedupe) ──► pre-filter (code) ──► Claude classifier ──► Workflow engine
                                                 skips bulk/automated    intent + facts        status, tasks,
                                                 mail w/o brand keywords (structured JSON)     deadlines, follow-ups
                                                                                                   │
                     Dashboard / MCP ◄── Digest (morning plan, EOD) ◄── Database ◄── Draft writer (Claude)
```

- Each message is classified once, using a rolling conversation summary plus the last few messages rather
  than the whole thread, which keeps token use low.
- `IntentRules` is a single table mapping "what the message means" to "status + task". Edit it to change
  the workflow.
- Schedules: sync every 30 minutes (`application.yml`); once a day at your follow-up time (Settings, default
  08:00) a fresh sync plus follow-up drafts for everything due today. If the app wasn't running at that time,
  the daily run happens as soon as it starts.

## Backups and moving to another machine

Settings → **Backup & restore** → **Download backup** gives you one `.crmbak` file with all deals, messages,
settings and credentials, encrypted with a passphrase you choose. Store the file and the passphrase separately;
the passphrase can't be recovered.

**Automatic backups** (Settings → **Automatic backups**): choose a passphrase once and the same file is written every
night to a folder you pick (OneDrive by default when Windows has it, else Documents\Creator CRM Backups, or
`<data-dir>/auto-backups` on a server). The newest 14 are kept; if the computer was off at 02:00 it backs up soon after
the app starts. On Docker you can supply the passphrase as the `BACKUP_PASSPHRASE` environment variable.

To move: install Creator CRM on the new machine, create a temporary admin, then **Restore** the file. Everything
comes back, including your original login, connected Gmail/Instagram and Claude key. Restores work across database
types (embedded ↔ PostgreSQL) and across installs with different master keys.

Alternatively, with the app stopped, copy the whole data folder (`creator-crm.mv.db` **and** `master.key`) to the
new machine.

## Security model

| Area | Protection |
|---|---|
| First run | Admin account can only be created with a one-time code printed to the server console |
| Dashboard / API | Session login (BCrypt), CSRF protection, 5-strikes lockout, strict CSP, `X-Frame-Options: DENY`, no-referrer |
| Credentials | AES-256-GCM encrypted at rest; write-only in the UI; env vars override for headless installs |
| Master key | `CRM_ENCRYPTION_KEY` env var, or auto-generated `master.key` in the data folder (restrict access) |
| Backups | Whole-install export encrypted with your passphrase (PBKDF2 600k + AES-256-GCM); export and restore both re-check your password; restore signs everyone out |
| Sessions | Changing your password signs out your other devices |
| MCP | Separate API key (only its SHA-256 hash stored), stateless, no sending by default |
| Instagram webhook | HMAC-SHA256 signature (`X-Hub-Signature-256`) verified with constant-time compare |
| OAuth | Your own client credentials, `state` bound to your session, least-privilege scopes |
| Sending | Only via an explicit click on a specific draft, except email follow-ups when you turn on auto-send; Instagram's 24-hour rule enforced |
| Prompt injection | Emails/DMs wrapped as untrusted data; model output is schema-constrained and validated; the AI can't trigger actions |
| XSS / header injection | Third-party text rendered with `textContent` only; CR/LF stripped from email headers |
| Network | Binds to `127.0.0.1` by default. To expose it, put it behind HTTPS and set `HOST=0.0.0.0`, `PUBLIC_BASE_URL=https://…`, `SECURE_COOKIES=true` |

Everything runs on your machine or server. Message content is sent only to Anthropic (for analysis and
drafting), Google and Meta.

## Configuration

| Env var | Default | Purpose |
|---|---|---|
| `PORT`, `HOST` | `8080`, `127.0.0.1` | Listen address |
| `PUBLIC_BASE_URL` | `http://localhost:8080` | Browser-facing URL; OAuth redirect URIs derive from it |
| `CRM_DATA_DIR` | `~/.creator-crm/data` | Embedded DB + master key location (keep it out of OneDrive/Dropbox folders) |
| `DATABASE_URL`, `DATABASE_USER`, `DATABASE_PASSWORD` | embedded H2 | Use PostgreSQL instead |
| `CRM_ENCRYPTION_KEY` | generated | Base64 32-byte master key |
| `SECURE_COOKIES` | `false` | Set `true` behind HTTPS |
| `ANTHROPIC_API_KEY`, `GOOGLE_CLIENT_ID`, … | — | Optional env overrides for any credential in Settings |

## Development

```bash
./mvnw test
```

Tests cover the full deal lifecycle using a scripted fake model (inbound rates request → reply → follow-ups
→ contract → content deadline → decline), the five-follow-up path ending in Cold, duplicate pitches, the
pre-filter, encryption, header-injection safety, and the security rules (setup code, login, CSRF, MCP key,
webhook signatures).

**Releasing:** every merge to `main` publishes a release automatically (`.github/workflows/auto-release.yml`): the
next patch version by default (1.2.0 → 1.2.1), or the version you add to `src/main/resources/whats-new.json` in the
PR when it's newer (use that for feature releases, e.g. 1.3.0). The Release workflow builds the installers, records
the walkthrough videos and publishes it; installed apps offer it within a few hours. Put `[skip release]` in the
merge commit message to merge without releasing. You can still release by hand with a tag
(`git tag v1.2.0 && git push origin v1.2.0`); a tag like `v1.2.0-beta.1` becomes a pre-release that installed apps
ignore. Describe each feature in `whats-new.json` (it becomes the in-app What's New and the release notes) and add
a video scenario for it: see [walkthroughs/README.md](walkthroughs/README.md).

**Demo mode** (`--spring.profiles.active=demo`, with `CRM_DATA_DIR` pointing at an empty temp folder) starts the
app with made-up deals, a fake Claude and the login `demo` / `demo-password-123`.

Adding a channel (Outlook, TikTok, …) means implementing `ChannelConnector` as a Spring bean. Adding an AI
provider means implementing `LlmClient`.

### Building installers locally

```bash
./mvnw package
bash packaging/package.sh msi        # on Windows (needs WiX Toolset 3.x on PATH); dmg on macOS; deb on Linux
```

Output goes to `target/installer/`. Icons are generated by `java packaging/GenerateIcons.java`.

### Publishing a release

Push a version tag. GitHub Actions tests the app, builds the Windows/macOS/Linux installers, pushes the
Docker image to `ghcr.io`, and publishes a release with all files and checksums:

```bash
git tag v1.0.1
git push origin v1.0.1
```

Then make the Docker package public once: GitHub → your profile → **Packages** → `creator-crm` →
**Package settings** → **Change visibility**.

## License

[MIT](LICENSE)
