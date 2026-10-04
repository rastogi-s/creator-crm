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
  marks the deal 🧊 Cold if there's still no answer after the final one.
- **Outreach database**: brands you've pitched, with follow-up #1–#5 dates, and duplicate-pitch protection.
- **Drafts in your voice**: replies, rates, negotiation, follow-ups, declines, "ask for budget / usage
  rights", and so on. Email drafts are also saved in your Gmail Drafts folder.
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
java -jar target/creator-manager-1.1.0.jar
```

Open <http://localhost:8080> and enter the one-time setup code printed in the console.

## Connect your accounts (Settings page)

Every credential you enter is **your own** and is stored encrypted (AES-256-GCM) in your database.

1. **Claude**: create an API key at [console.anthropic.com](https://console.anthropic.com) and paste it.
   Use **Test** to confirm it works. Default model: `claude-opus-5-5` for both classification (low
   effort) and writing (medium effort). You can set the classifier to `claude-haiku-4-5` to cut cost.
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
   2. Request `instagram_business_basic` and `instagram_business_manage_messages`, and add your own account
      as an Instagram tester. **App Review is not needed** while only accounts with a role on the app use it.
   3. Either paste an access token generated in the dashboard (works on localhost), or save the app ID and
      secret and click **Connect Instagram**. Meta requires an **https** redirect URI for this, so it needs a
      deployed URL or a tunnel.
   4. Optional, for real-time DMs: set the webhook callback to `<your https URL>/webhooks/instagram`,
      generate a verify token in Settings, and subscribe to `messages`. Without webhooks, DMs are fetched on
      every sync (every 30 minutes).

   Meta only allows **replies within 24 hours** of the brand's last message. Outside that window (cold
   pitches, most follow-ups) the app shows **Copy** and **I sent it myself** instead of **Send**.
4. **About you**: your name, voice, rates and rules. Drafts only quote rates written here; missing numbers
   become placeholders like `[RATE FOR 1 REEL]`. You can also adjust the follow-up cadence and time zone here.
5. **MCP** (optional): generate an API key, then:

   ```bash
   claude mcp add --transport http creator-crm http://localhost:8080/mcp --header "Authorization: Bearer <key>"
   ```

   Tools: `get_today_plan`, `get_end_of_day_summary`, `list_pipeline`, `get_opportunity`,
   `get_followups_due`, `find_brand`, `log_pitch`, `create_task`, `complete_task`, `update_status`,
   `mark_followup_sent`, `stop_followups`, `draft_message`, `list_pending_drafts`, `sync_now`, `send_draft`.
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
- Schedules: sync every 30 minutes; at 08:00 a fresh sync plus follow-up drafts for everything due today.
  Both are configurable in `application.yml`.

## Backups and moving to another machine

Settings → **Backup & restore** → **Download backup** gives you one `.crmbak` file with all deals, messages,
settings and credentials, encrypted with a passphrase you choose. Store the file and the passphrase separately;
the passphrase can't be recovered.

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
| Sending | Only via an explicit click on a specific draft; Instagram's 24-hour rule enforced |
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

**Releasing:** push a tag (`git tag v1.2.0 && git push origin v1.2.0`). The Release workflow builds the installers,
records the walkthrough videos and publishes the release; installed apps offer it within a few hours. A tag like
`v1.2.0-beta.1` becomes a pre-release that installed apps ignore, for trying a build first. Describe each version's
features in `src/main/resources/whats-new.json` (it becomes the in-app What's New and the release notes) and add a
video scenario for each one: see [walkthroughs/README.md](walkthroughs/README.md).

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
