# Deploying AltoroJ-reCAPTCHA on a Windows VM (Tomcat 9)

This guide covers deploying the reCAPTCHA build to a Tomcat 9 server on Windows, **including running it
side-by-side with the `altoroj-mfa` app on the same Tomcat.**

## 1. Prerequisites

- **Apache Tomcat 9.x** — do **not** use Tomcat 10+ (it uses `jakarta.*`; this app uses `javax.*` and will not run).
- **JDK 8 or newer** (JDK 17 is fine; that's what it's built and tested with).
- **Outbound HTTPS to `www.google.com`** from the VM. reCAPTCHA needs it twice:
  1. the browser loads the checkbox widget from `google.com`;
  2. **Tomcat itself** calls `https://www.google.com/recaptcha/api/siteverify` on every login.
  If the VM cannot reach Google, the checkbox will not render **and every login will be rejected**.

## 2. reCAPTCHA keys and hostname

The delivered war (`altoro-recaptcha.war`) has **real reCAPTCHA v2 keys baked in**, registered for hostname:

```
<vm-hostname>
```

Because these are real keys (not Google's test keys), the widget only renders on the **registered hostname**:

- Access the app as **`http://<vm-hostname>:8080/altoro-recaptcha/`** — not by raw IP.
  reCAPTCHA v2 does not accept raw IP addresses as domains; it must be the registered hostname.
- If `<vm-hostname>` does not resolve on the machine you browse from, add it to that machine's hosts file
  (`C:\Windows\System32\drivers\etc\hosts`), e.g. `192.168.x.x  <vm-hostname>`.
- To change keys later: edit `webapps\altoro-recaptcha\WEB-INF\classes\recaptcha.properties` and restart Tomcat
  (no rebuild needed), or edit `src/recaptcha.properties` and rebuild.

> The source repo ships with Google's public **test keys** as a safe default; the real Secret key is
> intentionally **not** committed. Only the delivered war contains the real keys.

## 3. Deploy

1. Copy **`altoro-recaptcha.war`** to `C:\path\to\tomcat\webapps\`.
2. Start Tomcat (`bin\startup.bat`, or the Windows service). Tomcat auto-expands the war.
3. Browse to **`http://<vm-hostname>:8080/altoro-recaptcha/`**.

The war filename sets the URL context path, so keep it named `altoro-recaptcha.war` → `/altoro-recaptcha/`.

Open Windows Firewall for TCP **8080** if you're connecting from another machine.

> ⚠️ **Upgrading / redeploying over an earlier war?** A newer war will **not** rebuild an existing (possibly
> broken) database on its own — Derby only initializes when the database folder is absent. Follow
> **[§6 Upgrading or resetting the reCAPTCHA database](#6-upgrading-or-resetting-the-recaptcha-database-windows)**,
> which starts with how to find the exact database path (it depends on the account Tomcat runs as).

**Credentials — two separate things, don't mix them up:**
- **Main login page** ("Online Banking Login"): bank customer login — `jsmith / demo1234`, or the admin
  *user account* **`admin / admin`**.
- **Administration panel** ("Administration Login", `/admin/login.jsp`): a password-only page reachable
  *after* a normal user login; password **`Altoro1234`**. This is NOT the main-login admin password —
  typing `admin / Altoro1234` on the main page will (correctly) fail.

## 4. Running BOTH altoroj-mfa and altoro-recaptcha on the same Tomcat

This works because each app now uses a **separate embedded Derby database**. Both apps embed Derby and,
by default, boot the same database folder (`%USERPROFILE%\altoro\altoro`), which Derby locks to a single
booter — so two unmodified AltoroJ apps cannot run together. This build changes the reCAPTCHA app's
database name to **`altoro_recaptcha`**, so the two apps no longer collide:

| App | War / context | Derby database folder |
|-----|---------------|-----------------------|
| MFA | `altoromutual.war` → `/altoromutual/` | `%USERPROFILE%\altoro\altoro` |
| reCAPTCHA | `altoro-recaptcha.war` → `/altoro-recaptcha/` | `%USERPROFILE%\altoro\altoro_recaptcha` |

Steps:
1. Drop **both** wars into the same `webapps\` folder.
2. Start Tomcat once. Both deploy on the same port (8080), at their own URLs, each creating its own database
   on first login.

No second Tomcat instance and no extra port are needed. **Changing only the context/folder name or the port
is NOT enough** — the database separation (already done in this build) is what makes coexistence possible.

## 5. Database notes

- The Derby database is created automatically on first login under the **JVM's `user.home`** —
  i.e. the profile of the **account the Tomcat process runs as**, not the user you RDP in with. On a typical
  Windows Server / EC2 host, Tomcat runs as a **Windows Service**, so the DB is under the service account's
  profile, e.g.:
  - Local System (`NT AUTHORITY\SYSTEM`, most common): `C:\Windows\System32\config\systemprofile\altoro\altoro_recaptcha`
  - Local Service: `C:\Windows\ServiceProfiles\LocalService\altoro\altoro_recaptcha`
  - Network Service: `C:\Windows\ServiceProfiles\NetworkService\altoro\altoro_recaptcha`
  - Started via `startup.bat` in your own session: `C:\Users\<you>\altoro\altoro_recaptcha`

  So if you don't see `%USERPROFILE%\altoro\altoro_recaptcha`, it's because either the app was never logged
  into yet (DB is created on first login) or Tomcat runs as a service and the DB is under that account's
  profile. Find it with PowerShell:
  ```powershell
  Get-CimInstance Win32_Service -Filter "Name like '%Tomcat%'" | Select Name, StartName   # which account
  Get-ChildItem C:\ -Recurse -Filter altoro_recaptcha -Directory -ErrorAction SilentlyContinue | Select FullName
  Get-ChildItem C:\ -Recurse -Filter derby.log -ErrorAction SilentlyContinue | Select FullName
  ```
  `derby.log` records the exact database directory, and Tomcat's log prints `Derby Home=...`.
- **Fresh first-time deploy** creates a correct DB automatically — there is nothing to delete. The
  "delete the DB folder" step only applies when upgrading over a build that already created a (possibly
  broken/empty) database.
- If you see *"Failed to create database"*, the Tomcat account lacks write access to that folder. Either grant
  it write access, or start Tomcat with a different home via `setenv`/service options:
  `set CATALINA_OPTS=-Duser.home=D:\altoro-home`
- To reset the app to a clean state, stop Tomcat and delete the `...\altoro\altoro_recaptcha` folder. The
  database re-initializes (accounts, transactions, users) on the next login. **After a reset, Sign Off and log
  back in** so your browser's account cookie is regenerated from the fresh database.

## 6. Upgrading or resetting the reCAPTCHA database (Windows)

When you deploy a newer war over one that has already been logged into, the existing Derby database is left
in place — Derby only initializes when the database folder is **absent**. So if the previous build was the
broken one, its data (empty accounts, failing transactions) persists. To force a clean rebuild, delete the
reCAPTCHA database folder. Do this in order:

### Step 1 — Find the exact database path

The location depends on the account Tomcat runs as (see [§5 Database notes](#5-database-notes)), so don't
assume `%USERPROFILE%`. Run this in PowerShell on the VM:

```powershell
Get-ChildItem C:\ -Recurse -Filter altoro_recaptcha -Directory -ErrorAction SilentlyContinue | Select FullName
```

Example result when Tomcat runs as **Local Service**:

```
C:\Windows\ServiceProfiles\LocalService\altoro\altoro_recaptcha
```

- If it returns **nothing**, no reCAPTCHA database exists yet — **skip to Step 3**; a fresh deploy will
  create a correct database on first login, and there is nothing to delete.
- Optionally confirm the account Tomcat runs as:
  ```powershell
  Get-CimInstance Win32_Service -Filter "Name like '%Tomcat%'" | Select Name, StartName
  ```

### Step 2 — Stop Tomcat

The database files are locked while Tomcat runs.

```powershell
Stop-Service -Name "<TomcatServiceName>"     # or use services.msc
```

### Step 3 — Replace the war

Remove the old exploded folder and war, then drop in the latest build:

```powershell
Remove-Item "C:\path\to\tomcat\webapps\altoro-recaptcha" -Recurse -Force
Remove-Item "C:\path\to\tomcat\webapps\altoro-recaptcha.war" -Force
Copy-Item   "C:\path\to\new\altoro-recaptcha.war" "C:\path\to\tomcat\webapps\"
```

### Step 4 — Delete ONLY the reCAPTCHA database

Use the exact path from Step 1:

```powershell
Remove-Item "C:\Windows\ServiceProfiles\LocalService\altoro\altoro_recaptcha" -Recurse -Force
```

> ✅ **Safe:** this removes the reCAPTCHA app's database **only**. It does **not** affect the `altoroj-mfa`
> app, whose database is the separate `altoro` folder (often under a different account's profile, e.g.
> `C:\Windows\System32\config\systemprofile\altoro\altoro`). Delete the **`altoro_recaptcha`** subfolder
> only — never the parent `altoro` folder.

### Step 5 — Start Tomcat and log in fresh

```powershell
Start-Service -Name "<TomcatServiceName>"
```

Browse to `http://<vm-hostname>:8080/altoro-recaptcha/` and log in (`jsmith / demo1234`). The app rebuilds
`altoro_recaptcha` with correct accounts and transactions on first login. Because the data is correct from
that first login, the `AltoroAccounts` cookie is correct too — you will **not** hit the "Originating account
is invalid" transfer error.

## 7. Running a test-key instance for AppScan / DAST scanning

reCAPTCHA is an anti-automation control; a DAST scanner is automation. **No scanner can generate a valid
reCAPTCHA token** (that's the whole point of reCAPTCHA), and the token is single-use + expires in ~2 minutes,
so a recorded login cannot be replayed. The standard, HCL-recommended approach is to scan an instance where
the CAPTCHA is neutralized using Google's **public test keys** (they always pass verification). The scanner
then tests the real authenticated attack surface; the CAPTCHA itself is not the DAST target.

> **How easy is this?** No code changes — just the two test-key values in `recaptcha.properties`, which are the **repo default**, so the default build is *already* scannable. To flip an already-deployed real-key instance to scannable, edit `webapps\altoro-recaptcha\WEB-INF\classes\recaptcha.properties` back to the test keys and restart Tomcat (~2-line change, no rebuild).

Thanks to the configurable `db.name` (see [§5](#5-database-notes)), you can run a **second, test-key instance
alongside your real-key instance on the same Tomcat** — one to demo the real reCAPTCHA UX, one for AppScan to
actually scan:

| Purpose | War / context | reCAPTCHA keys | `db.name` |
|---------|---------------|----------------|-----------|
| Real demo | `altoro-recaptcha.war` → `/altoro-recaptcha/` | real (registered hostname) | `altoro_recaptcha` |
| AppScan / DAST | `altoro-recaptcha-scan.war` → `/altoro-recaptcha-scan/` | Google test keys | `altoro_recaptcha_scan` |

### Build the two wars

You need two wars that differ only in their reCAPTCHA keys and `db.name`. Two ways (full explanation and caveats in the [README](README.md#two-versions-real-recaptcha-vs-scannable-test-key-build)):

**Build twice** — bake the config into each war:
```sh
# Scan war: src/recaptcha.properties -> keep the Google TEST keys, db.name=altoro_recaptcha_scan
./gradlew war
mv build/libs/altoro-recaptcha.war build/libs/altoro-recaptcha-scan.war
# Real war: src/recaptcha.properties -> real keys, db.name=altoro_recaptcha
./gradlew war        # -> build/libs/altoro-recaptcha.war
```

**Build once** — one war, configured per deployment:
1. `./gradlew war` → `altoro-recaptcha.war` (test keys, `db.name=altoro_recaptcha`).
2. Copy it into `webapps\` under **both** names: `altoro-recaptcha.war` and `altoro-recaptcha-scan.war`.
3. Start Tomcat once so both expand — **do not log in yet** (the DB is created on first login).
4. Edit each expanded copy's `WEB-INF\classes\recaptcha.properties`:
   - `altoro-recaptcha` → real keys, `db.name=altoro_recaptcha`
   - `altoro-recaptcha-scan` → test keys (default), **`db.name=altoro_recaptcha_scan`**
5. **Restart Tomcat**, then log in to each.

> ⚠️ The scan instance's `db.name` must be `altoro_recaptcha_scan` **before its first login**, or the second app to boot fails with *"Another instance of Derby may have already booted the database."*

### Deploy both
Drop **both** wars into the same `webapps\` folder and start Tomcat once. They deploy on the same port at
their own URLs, each with its own database (`altoro_recaptcha` vs `altoro_recaptcha_scan`) — no conflict.

### Point AppScan at the test-key instance (`/altoro-recaptcha-scan/`)
- **Login:** record the login normally (`jsmith` / `demo1234`, tick the checkbox). With test keys the replayed
  `g-recaptcha-response` still passes, so both the initial login and mid-scan re-logins succeed.
- **Untrack `g-recaptcha-response`** in Login Session IDs / Parameters — it's a one-time token, not a session
  identifier; tracking it just re-injects a stale value.
- **In-session detection:** request `/altoro-recaptcha-scan/bank/main.jsp`, pattern `>Sign Off<` (this is
  correct — it only failed before because the session wasn't authenticated).
- **Exclude logout** from scan scope: `/altoro-recaptcha-scan/logout.jsp` and the **Sign Off** link, so the
  crawler doesn't log itself out mid-scan.

### Keep the test-key instance safe
Test keys **disable the login bot-protection** on that instance (the risk is exposure, not secret leakage —
the test keys are public and reversible). So:
- Keep it **isolated / internal-only**, reachable only by the AppScan host (security group / firewall).
- Keep the name distinct (`/altoro-recaptcha-scan/`) so it's never confused with the real-key deployment.
- **Time-box it** — deploy for the scan, remove it afterward. Never expose it publicly or reuse the real
  hostname for it.
- The **real-key** instance stays untouched for the realistic demo.

> Customer framing: the message is not "AppScan defeats reCAPTCHA" — it's "AppScan scans the authenticated
> application correctly; the recommended way to enable that while bot-protection is in place is to point the
> scanner at a test-key build in an isolated test environment." (TOTP MFA is also an anti-automation control,
> so the `altoroj-mfa` demo has the same consideration.)

## 8. Troubleshooting

| Symptom | Cause / fix |
|---------|-------------|
| Checkbox doesn't appear | VM can't reach `google.com`, or you're not browsing via `<vm-hostname>`. |
| "Invalid domain for site key" on the widget | Hostname not registered for the key / accessed by IP. Use `<vm-hostname>`. |
| Every login says "Please confirm that you are not a robot" | Server-side `siteverify` can't reach Google (outbound HTTPS blocked). |
| App won't start, `jakarta.*` errors | You're on Tomcat 10+. Use Tomcat 9. |
| Second app fails: "Another instance of Derby may have already booted the database" | Both apps using the same DB folder. This build already separates them (`altoro_recaptcha`); make sure you deployed **this** war, not an old copy. |
| After login: account summary empty, recent transactions 500-errors, transfer dropdowns empty | The database wasn't initialized (usually leftover data from an older/broken war). Reset it — see [§6 Upgrading or resetting the reCAPTCHA database](#6-upgrading-or-resetting-the-recaptcha-database-windows). |
| Transfer Funds fails with **"ERROR: Originating account is invalid"** for every account | Stale account cookie. AltoroJ stores your account list in an `AltoroAccounts` cookie written **at login**; if you were logged in before the database was fixed/reset, that cookie is empty. **Sign Off and log back in** (or clear cookies for the site / use a private window). |
| `javax.xml.bind` JAXB warning on JDK 17 | Harmless; JSON endpoints still work. |
