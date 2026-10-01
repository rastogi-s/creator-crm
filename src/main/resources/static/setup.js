(function () {
  "use strict";

  function csrf() {
    const m = document.cookie.match(/(?:^|;\s*)XSRF-TOKEN=([^;]+)/);
    return m ? decodeURIComponent(m[1]) : "";
  }

  const form = document.getElementById("setup-form");
  const errorBox = document.getElementById("error");

  // The installed app opens /setup.html#code=… on first run. The fragment never reaches the server;
  // remove it from the address bar and history straight away.
  const fromInstaller = location.hash.match(/code=([^&]+)/);
  if (fromInstaller) {
    form.code.value = decodeURIComponent(fromInstaller[1]);
    history.replaceState(null, "", location.pathname);
    document.getElementById("code-help").textContent = "Filled in automatically by the Creator CRM app on this computer.";
    form.username.focus();
  }

  function showError(msg) {
    errorBox.textContent = msg;
    errorBox.classList.remove("hidden");
  }

  // Already set up? Go to login. (Also primes the CSRF cookie.)
  fetch("/api/setup/status", { credentials: "same-origin" })
    .then((r) => r.json())
    .then((s) => { if (s.setupComplete) location.replace("/login.html"); });

  form.addEventListener("submit", async (e) => {
    e.preventDefault();
    errorBox.classList.add("hidden");
    const password = form.password.value;
    if (password !== document.getElementById("password2").value) {
      showError("Passwords don't match.");
      return;
    }
    const res = await fetch("/api/setup/admin", {
      method: "POST",
      credentials: "same-origin",
      headers: { "Content-Type": "application/json", "X-XSRF-TOKEN": csrf() },
      body: JSON.stringify({ code: form.code.value.trim(), username: form.username.value.trim(), password }),
    });
    if (res.ok) {
      location.replace("/login.html?created");
    } else {
      const body = await res.json().catch(() => ({}));
      showError(body.error || "Setup failed (" + res.status + ").");
    }
  });
})();
