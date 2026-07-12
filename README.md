# AltoroJ + Google reCAPTCHA

A fork of the deliberately-vulnerable **AltoroJ 3.2** sample banking web application that adds a
**Google reCAPTCHA v2 ("I'm not a robot") checkbox** to the login pages as an anti-automation control.

This is the reCAPTCHA counterpart to the separate **`altoroj-mfa`** project (which instead added TOTP
multi-factor authentication). This repo starts from the **clean AltoroJ 3.2 baseline** — it contains
**none** of the MFA code — and layers reCAPTCHA on top. The two projects are designed so they can run
**side by side on the same Tomcat** (see [Running altoroj-mfa and altoroj-recaptcha together](#running-altoroj-mfa-and-altoroj-recaptcha-together)).

> ⚠️ **AltoroJ is intentionally vulnerable** and exists only for security education and testing-tool
> demonstrations. reCAPTCHA is added here purely to demonstrate an anti-bot login control — it does **not**
> make the rest of the application safe. Never deploy this in production or on an untrusted network.

---

## What this fork changes vs. stock AltoroJ 3.2

| Area | File | Change |
|------|------|--------|
| Config | `src/recaptcha.properties` | reCAPTCHA site key + secret key (defaults to Google's **test keys**). Packaged into `WEB-INF/classes`. |
| Helper | `.../util/RecaptchaUtil.java` | Loads the keys and verifies the `g-recaptcha-response` token against Google's `siteverify` endpoint. |
| User login page | `WebContent/login.jsp` | Renders the reCAPTCHA widget + loads `api.js`. |
| Admin login page | `WebContent/admin/login.jsp` | Renders the reCAPTCHA widget + loads `api.js`. |
| User login servlet | `.../servlet/LoginServlet.java` | Verifies reCAPTCHA **before** checking credentials; rejects on failure. |
| Admin login servlet | `.../servlet/AdminLoginServlet.java` | Verifies reCAPTCHA **before** checking the admin-panel password. |
| Database name | `.../util/DBUtil.java`, `recaptcha.properties` | Derby database name is **configurable via `db.name`** (default `altoro_recaptcha`), so it coexists with `altoroj-mfa` and can even run as multiple isolated instances (e.g. a real-key demo + a test-key DAST/scan instance) on one Tomcat. |
| Build | `build.gradle` | Modernized for **Gradle 8 / JDK 17** (`implementation`, `archiveFileName = altoro-recaptcha.war`, `sourceCompatibility 1.8`). |

---

## Prerequisites

- **Apache Tomcat 9.x** — **do NOT use Tomcat 10+.** Tomcat 10 switched the Servlet API from `javax.*` to
  `jakarta.*`; this app uses `javax.*` and will not deploy on Tomcat 10 or newer.
- **JDK 8 or newer** (built and tested with **JDK 17**, Amazon Corretto).
- **Gradle** — not required to be installed; the bundled Gradle **wrapper** (`./gradlew`) fetches Gradle 8.10.
- **Outbound HTTPS to `www.google.com`** from wherever Tomcat runs. reCAPTCHA needs it in two places:
  1. the browser loads the checkbox widget from `google.com`, and
  2. **Tomcat itself** calls `https://www.google.com/recaptcha/api/siteverify` on every login.

  If Google is unreachable, the checkbox won't render **and every login is rejected**.

---

## Build

```sh
export JAVA_HOME=/path/to/jdk-17
./gradlew war
# -> build/libs/altoro-recaptcha.war
```

## Deploy & run

Copy `altoro-recaptcha.war` into `<tomcat>/webapps/` and start Tomcat. The war filename becomes the URL
context path:

```
http://<host>:8080/altoro-recaptcha/
```

Open the login page — the reCAPTCHA checkbox appears above the **Login** button; login only proceeds after
the challenge is solved.

### Credentials — two separate "admin" logins (don't mix them up)

| Where | What it is | Credentials |
|-------|------------|-------------|
| **Main login** ("Online Banking Login") | Bank-customer login. | `jsmith` / `demo1234`, or the admin **user account** `admin` / `admin` |
| **Administration panel** ("Administration Login", `/admin/login.jsp`) | A password-only page reached **after** you are already logged in as any user. | password `Altoro1234` |

> Common mistake: typing `admin` / `Altoro1234` on the **main** login page. That fails — `Altoro1234` is the
> *Administration panel* password, not the main-login admin password (which is `admin`).

---

## reCAPTCHA keys

By default the app ships with Google's **official reCAPTCHA v2 test keys**, so it runs with zero setup. The
test keys always pass and display a *"This reCAPTCHA is for testing purposes only"* banner on the widget.
These are Google's **official** automated-testing key pair ([reCAPTCHA FAQ](https://developers.google.com/recaptcha/docs/faq) → *"I'd like to run automated tests with reCAPTCHA. What should I do?"* — *"You will always get No CAPTCHA and all verification requests will pass"*), which is exactly why a DAST scanner — just automated traffic — can authenticate through them.

To use **real** protection:

1. Register a site at <https://www.google.com/recaptcha/admin> → **reCAPTCHA v2 → "I'm not a robot" Checkbox**,
   and add the **hostname(s)** you'll browse to. Note:
   - reCAPTCHA v2 does **not** accept raw IP addresses as domains — you must use a hostname.
   - `localhost` is allowed by default; any other hostname must be in your key's domain list or the widget
     shows *"Invalid domain for site key"*.
2. Put the generated **Site key** + **Secret key** into `src/recaptcha.properties`, then rebuild — **or**, to
   avoid a rebuild, edit `<tomcat>/webapps/altoro-recaptcha/WEB-INF/classes/recaptcha.properties` after
   deploy and restart Tomcat.

> 🔒 **Never commit a real Secret key to a repository.** This repo intentionally keeps the test keys as the
> default; real keys should live only in your deployed war / server config.

---

## Two versions: real reCAPTCHA vs. scannable (test-key) build

You'll often want **two deployments side by side**:

- **Real-auth version** — your registered Site + Secret keys; the checkbox genuinely blocks bots. For demos and realistic use.
- **Scannable version** — Google's public **test keys** (they always pass) so a DAST scanner such as **AppScan** can log in and reach the authenticated attack surface. reCAPTCHA is anti-automation *by design*, so a scanner cannot solve a real challenge — a test-key build is the HCL-recommended way to scan the app behind it.

> **How easy is this?** Making the reCAPTCHA app a scannable target takes **no code changes** — only the two test-key values in `src/recaptcha.properties`, and those are the **repo default**. So the out-of-the-box build (`./gradlew war`) is *already* scannable; adding **real** protection is the extra step, not removing it. To flip an already-deployed real-key instance to scannable, edit its `WEB-INF/classes/recaptcha.properties` back to the test keys and restart — a ~2-line change, no rebuild.

Both can run on **one Tomcat** as long as each uses a **different `db.name`** (the embedded Derby DB locks its directory to a single booter) and a **different war filename** (the filename becomes the URL context path):

| Version | war → context | reCAPTCHA keys | `db.name` |
|---------|---------------|----------------|-----------|
| Real | `altoro-recaptcha.war` → `/altoro-recaptcha/` | your **real** keys | `altoro_recaptcha` |
| Scan | `altoro-recaptcha-scan.war` → `/altoro-recaptcha-scan/` | Google **test** keys (default) | `altoro_recaptcha_scan` |

Both `db.name` **and** the keys live in `src/recaptcha.properties` (baked into the war at build time), and can also be overridden per-deployment in `<tomcat>/webapps/<context>/WEB-INF/classes/recaptcha.properties`.

### Option A — build twice (two self-contained wars)

Each war carries its own keys + `db.name`; just drop both into `webapps/`.

**1) Scan war** (test keys, separate DB):
```sh
# In src/recaptcha.properties:
#   recaptcha.siteKey / recaptcha.secretKey  -> leave the Google TEST keys (the default)
#   db.name=altoro_recaptcha_scan
export JAVA_HOME=/path/to/jdk-17
./gradlew war
mv build/libs/altoro-recaptcha.war build/libs/altoro-recaptcha-scan.war
```

**2) Real war** (your keys, default DB):
```sh
# In src/recaptcha.properties:
#   recaptcha.siteKey    = <your real Site key>
#   recaptcha.secretKey  = <your real Secret key>
#   db.name=altoro_recaptcha
./gradlew war
# -> build/libs/altoro-recaptcha.war
```
> 🔒 **Never commit the real Secret key.** Set it only for the build, then revert `src/recaptcha.properties` (the repo default is test keys). Keep the real-key war out of git.

Drop **both** wars into `<tomcat>/webapps/` and start Tomcat once.

### Option B — build once (configure per deployment)

One build; you set the keys + `db.name` on each deployed copy — no rebuild for the second flavor.

```sh
./gradlew war   # -> build/libs/altoro-recaptcha.war  (ships with test keys, db.name=altoro_recaptcha)
```
1. Copy the **same** war in twice, under the two context names:
   ```sh
   cp build/libs/altoro-recaptcha.war <tomcat>/webapps/altoro-recaptcha.war
   cp build/libs/altoro-recaptcha.war <tomcat>/webapps/altoro-recaptcha-scan.war
   ```
2. Start Tomcat once so both wars expand — **but don't log in yet** (the Derby DB is created on first login).
3. Edit each expanded copy's `WEB-INF/classes/recaptcha.properties`:
   - `webapps/altoro-recaptcha/…` → your **real** Site/Secret keys, `db.name=altoro_recaptcha`
   - `webapps/altoro-recaptcha-scan/…` → leave the **test** keys, **`db.name=altoro_recaptcha_scan`**
4. **Restart Tomcat**, then log in to each. Each now boots its own database.

> ⚠️ Change the scan instance's `db.name` **before its first login.** If both copies still point at `altoro_recaptcha`, the second to initialize fails with *"Another instance of Derby may have already booted the database."*

For pointing AppScan at the scan instance (recording the login, untracking `g-recaptcha-response`, in-session rules), see **[DEPLOY-WINDOWS.md §7](DEPLOY-WINDOWS.md#7-running-a-test-key-instance-for-appscan--dast-scanning)**.

---

## Running altoroj-mfa and altoroj-recaptcha together

**Why this matters:** you'll often want to demo or scan **both** login-hardening variants (MFA and reCAPTCHA)
on a single server. But two *stock* AltoroJ apps cannot run on the same Tomcat, and the reason is not obvious:

- Each app embeds its own Apache Derby engine and, unmodified, opens the **same database directory**
  (`<user.home>/altoro/altoro`).
- Derby puts an exclusive lock (`db.lck`) on that directory and allows only **one** booter per JVM.
- So the second app to start fails with *"Another instance of Derby may have already booted the database."*

**Changing only the context/folder name or the port does NOT fix this** — both apps still open the same
database folder. The fix is to give each app its **own database**. This fork already does that: it uses the
Derby database name **`altoro_recaptcha`**, so the two apps no longer collide:

| App | War / context | Derby database folder |
|-----|---------------|-----------------------|
| `altoroj-mfa` | `altoromutual.war` → `/altoromutual/` | `<user.home>/altoro/altoro` |
| `altoroj-recaptcha` | `altoro-recaptcha.war` → `/altoro-recaptcha/` | `<user.home>/altoro/altoro_recaptcha` |

**How to run both:** drop **both** wars into the same `<tomcat>/webapps/` and start Tomcat once. They deploy
on the same port at their own URLs, each creating and locking its own database on first login. No second
Tomcat instance and no extra port needed.

For step-by-step Windows-VM instructions, see **[DEPLOY-WINDOWS.md](DEPLOY-WINDOWS.md)**.

---

## Database notes

- Apache Derby is embedded; the database is created automatically on first login under the Tomcat user's home
  directory: `<user.home>/altoro/altoro_recaptcha` (e.g. `C:\Users\<you>\altoro\altoro_recaptcha` or
  `/Users/<you>/altoro/altoro_recaptcha`).
- **Reset to a clean state:** stop Tomcat and delete the `...\altoro\altoro_recaptcha` folder; it's recreated
  on next login.
- If you see *"Failed to create database"*, the Tomcat account lacks write access to its home folder. Grant it
  write access, or start Tomcat with `-Duser.home=<writable path>` in its Java options.

## REST API

AltoroJ ships a REST API (Swagger-documented) reachable via the **REST API** link in the page footer. Note the
reCAPTCHA control added here applies to the **web login pages only**, not to REST API authentication.

---

## Troubleshooting

| Symptom | Cause / fix |
|---------|-------------|
| Checkbox doesn't appear | Tomcat/host can't reach `google.com`, or (with real keys) you're not browsing via the registered hostname. |
| Widget shows *"Invalid domain for site key"* | Hostname not registered for the key, or you're accessing by raw IP. Use a registered hostname. |
| Every login says *"Please confirm that you are not a robot"* | Server-side `siteverify` can't reach Google (outbound HTTPS blocked). |
| `admin` / `Altoro1234` fails on the main page | Expected — main-login admin is `admin`/`admin`; `Altoro1234` is the Administration-panel password. |
| After login: account summary empty, recent transactions 500, transfer dropdowns empty | Database wasn't initialized (usually leftover data from an older/broken build). Stop the server, delete the `...\altoro\altoro_recaptcha` DB folder, restart, log in again. |
| Transfer Funds → **"ERROR: Originating account is invalid"** for every account | Stale `AltoroAccounts` cookie (written at login) from before the DB was fixed/reset. **Sign Off and log back in**, or clear cookies / use a private window. |
| App won't deploy, `jakarta.*` / class errors | You're on Tomcat 10+. Use **Tomcat 9**. |
| Second app fails: *"Another instance of Derby may have already booted the database"* | Two apps sharing one DB folder. This fork already separates them (`altoro_recaptcha`); make sure you deployed **this** war, not an older copy. |
| `javax.xml.bind` / JAXB warning on JDK 17 | Harmless; JSON endpoints still work. |

---

## About AltoroJ

AltoroJ is a sample banking J2EE web application built with plain Java & JSP (no frameworks). It intentionally
contains real, well-known web-application vulnerabilities so that application-security testing tools and
training can demonstrate finding and exploiting them. It stores data in an embedded Apache Derby database that
initializes automatically on first login.

## License

All files in this project are licensed under the
[Apache License 2.0](https://github.com/AppSecDev/AltoroJ/blob/master/LICENSE).
