# Preparing a Test Environment for Automated Testing of reCAPTCHA-Protected Apps

A **general-purpose** guide: how to stand up a test environment where automated tools — DAST
security scanners (e.g. HCL AppScan), Selenium/Playwright suites, or CI integration tests — can
exercise a **real** application that is protected by Google reCAPTCHA, without being blocked by the
CAPTCHA.

> This guide is version- and app-agnostic. For a concrete worked example, see this repo's AltoroJ
> setup — [README → "Two versions"](README.md#two-versions-real-recaptcha-vs-scannable-test-key-build)
> and [DEPLOY-WINDOWS.md §7](DEPLOY-WINDOWS.md#7-running-a-test-key-instance-for-appscan--dast-scanning).

## The principle

reCAPTCHA exists to **block automation** — and a scanner or test bot *is* automation, so it cannot
solve a real challenge (and the token is single-use, expiring in ~2 minutes, so a recorded login
can't be replayed). Trying to defeat the CAPTCHA is the wrong goal. Instead, in a **non-production**
environment you **neutralize** the CAPTCHA so the automation can log in and reach the real
authenticated attack surface — the CAPTCHA itself is not the test target.

Google supports this directly for reCAPTCHA v2 by publishing **official test keys** that always pass
— see the [reCAPTCHA FAQ → *"I'd like to run automated tests with reCAPTCHA. What should I do?"*](https://developers.google.com/recaptcha/docs/faq)
(*"You will always get No CAPTCHA and all verification requests will pass"*). A DAST scan is simply
one form of the automated traffic that FAQ entry covers.

> ⚠️ **Only ever do this in an isolated, non-production environment, on applications you are
> authorized to test.** Never deploy test keys or a CAPTCHA bypass to production.

---

## Step 0 — Discovery (decide the approach first)

1. **Identify the reCAPTCHA version** — it dictates the method:
   - **v2 "I'm not a robot" / v2 invisible** → Google publishes official test keys → easiest path (Step 2A).
   - **v3 (score-based)** → **no public test keys**; Google's FAQ says to *create separate keys for
     testing environments* (Step 2B).
   - **reCAPTCHA Enterprise** → use a dedicated test-environment key / lenient assessment (Step 2B).
2. **Find how the app gets its keys.** Config file / env var / secrets manager = easy swap.
   **Hardcoded in source** = make it config-driven, or add a gated bypass (Step 2C).
3. **Confirm a non-production environment** exists, separate from prod (separate host, separate
   database / test data).

## Step 1 — Stand up an isolated test instance

1. Deploy the app to an **internal-only** host (staging/test), not reachable from the public internet.
2. Give it its **own datastore / test data** — never point at production data.
3. **Firewall it** so only the scanner / test host can reach it (security group / allowlist).

## Step 2 — Neutralize reCAPTCHA in the test environment

**2A — reCAPTCHA v2 (recommended, Google-sanctioned).** Point the app's reCAPTCHA config at Google's
official test pair:

| | Value |
|---|---|
| Site key | `6LeIxAcTAAAAAJcZVRqyHh71UMIEGNQ_MXjiZKhI` |
| Secret key | `6LeIxAcTAAAAAGG-vFI1TnRWxMZNFuojJ4WifJWe` |

Both the front-end widget **and** the server-side `siteverify` call then always succeed. The widget
displays a *"for testing purposes only"* banner. No application code changes are required if the keys
are config-driven.

**2B — reCAPTCHA v3 / Enterprise.** There are no public test keys. In the reCAPTCHA / Google Cloud
console, create **dedicated test-environment keys** and configure them (or your test-env handling of
the returned score/assessment) so they don't block automated traffic.

**2C — Fallback when keys are hardcoded or the logic is complex.** Add an **environment-gated bypass**
— a config flag (e.g. `RECAPTCHA_ENFORCE=false`) that makes the server **skip `siteverify` only in the
test environment**. Guard it hard:
- Enforcement defaults to **ON**.
- The app **refuses to start** if the bypass is set while a "production" profile is active.
- CI **fails the build** if the bypass flag (or Google's test keys) appears in a production artifact.

## Step 3 — Verify neutralization before you scan

1. Load the login page → the widget renders and (v2) shows the *"testing purposes only"* banner.
2. Log in by hand → it succeeds.
3. Prove **automation** passes: replay the same login request (or send an empty / stale
   `g-recaptcha-response`) → the server still accepts it. This confirms the scanner will get through.

## Step 4 — Configure the automated tool

**DAST scanner (e.g. AppScan):**
1. **Record the login** normally (submit the form, including the checkbox). With test keys the replayed
   token passes, so the initial login *and* mid-scan re-logins both work.
2. **Untrack the CAPTCHA response parameter** (`g-recaptcha-response`) in Login/Session parameters —
   it's a one-time token, not a session identifier; tracking it re-injects a stale value.
3. **In-session detection:** use an authenticated-only marker (a string that appears only after login,
   e.g. a "Sign Off" link on a post-login page).
4. **Exclude logout** URLs / the sign-off link from scan scope so the crawler doesn't log itself out.

**Selenium / Playwright / CI:** with v2 test keys the widget auto-satisfies (no clicking needed). For
v2-invisible or v3, stub or inject the token/score in the test build.

## Step 5 — Guardrails (make it safe and repeatable)

1. **Isolation + time-box:** keep the neutralized instance internal; tear it down (or restore real
   keys) after the test/scan.
2. **Separate config paths:** test keys / bypass live only in the test profile — never in the
   production config or artifact.
3. **CI safety check:** fail the pipeline if Google's test keys or the bypass flag are present in a
   production build.
4. **Document it:** note in the project that the test environment uses test keys *by design*, so no one
   later mistakes it for a vulnerability.
5. **Authorization:** only run this against applications and environments you are authorized to test.

---

## Quick reference

| Situation | What to do |
|-----------|------------|
| App uses **reCAPTCHA v2**, keys in config | Swap in Google's public **test keys** (Step 2A) |
| App uses **reCAPTCHA v3 / Enterprise** | Create **dedicated test-env keys** (Step 2B) |
| Keys **hardcoded** / complex score logic | Add an **environment-gated bypass** (Step 2C) |
| Running a **DAST scan** | Neutralize CAPTCHA, then untrack the token + set in-session + exclude logout (Step 4) |

**Source:** Google reCAPTCHA FAQ — [*"I'd like to run automated tests with reCAPTCHA. What should I do?"*](https://developers.google.com/recaptcha/docs/faq)
