# Walkthrough videos

Short captioned videos of each feature, shown in the app's **What's New** page (once, after an update) and on
the **Help** page. They are recorded automatically by the Release workflow on every version tag and attached to
the GitHub release, so they always match the current screens. The app downloads each video the first time it's
played and keeps it in the data folder.

## Adding a video for a new feature

1. Add the feature to `src/main/resources/whats-new.json` under the new version, with `"video": "<name>.webm"`
   and `"tryIt": "#<tab>"` (where its **Try it** button goes).
2. Add a scenario named `<name>` to `record.mjs`: a few `say(...)` captions and `click(...)` steps. Keep it under
   a minute. Demo mode's made-up deals live in `src/main/java/com/creatorcrm/demo/DemoInbox.java`.
3. Record locally to check it (needs Node 20+ and ffmpeg):

   ```bash
   ./mvnw -q -DskipTests package
   CRM_DATA_DIR=$(mktemp -d) CRM_DEMO_VIDEOS=$PWD/walkthroughs/videos \
     java -jar target/creator-manager-*.jar --spring.profiles.active=demo &
   cd walkthroughs && npm install && npx playwright install chromium
   CRM_URL=http://localhost:8080 node record.mjs <name>     # videos/<name>.webm
   ```

Demo mode signs in as `demo` / `demo-password-123`, uses a fake Claude, never touches Gmail or Instagram, and
pretends a newer version is available so the update banner can be filmed.
