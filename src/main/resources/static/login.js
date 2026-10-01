(function () {
  "use strict";

  function csrf() {
    const m = document.cookie.match(/(?:^|;\s*)XSRF-TOKEN=([^;]+)/);
    return m ? decodeURIComponent(m[1]) : "";
  }

  const params = new URLSearchParams(location.search);
  const notice = document.getElementById("notice");
  const errorBox = document.getElementById("error");
  if (params.has("created")) { notice.textContent = "Account created. Sign in to continue."; notice.classList.remove("hidden"); }
  if (params.has("logout")) { notice.textContent = "You've been signed out."; notice.classList.remove("hidden"); }
  if (params.has("error")) {
    errorBox.textContent = "Wrong username or password. After 5 failed attempts the account is locked for 15 minutes.";
    errorBox.classList.remove("hidden");
  }

  fetch("/api/setup/status", { credentials: "same-origin" })
    .then((r) => r.json())
    .then((s) => { if (!s.setupComplete) location.replace("/setup.html"); });

  document.getElementById("login-form").addEventListener("submit", async (e) => {
    e.preventDefault();
    const body = new URLSearchParams({ username: e.target.username.value, password: e.target.password.value });
    const res = await fetch("/login", {
      method: "POST",
      credentials: "same-origin",
      headers: { "Content-Type": "application/x-www-form-urlencoded", "X-XSRF-TOKEN": csrf() },
      body,
      redirect: "follow",
    });
    location.replace(res.url && res.url.indexOf("error") === -1 ? "/" : "/login.html?error");
  });
})();
