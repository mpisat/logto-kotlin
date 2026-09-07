# logto-kotlin — `native-browser` fork

This is a soft fork of [logto-io/kotlin](https://github.com/logto-io/kotlin)
that replaces the upstream embedded-`WebView` sign-in presenter with
Chrome Custom Tabs (`androidx.browser`). The rest of the SDK (PKCE,
token exchange, refresh, storage, social-result activities) is
unchanged. This file documents what the fork actually does, why each
change exists, and how to keep it in sync with upstream.

> Canonical authority for the product-side contract lives in
> [`LOGTO-FORK.md`](https://github.com/muratpisat/swift-webrtc-hdr/blob/main/LOGTO-FORK.md)
> in the consuming Calido app. This README is the engineering-side
> companion for people working inside this repo.

## 1. Why the fork exists

Upstream Logto presents the OIDC authorization page inside an Android
`WebView`. Embedded web views do not share cookies, autofill, or
Passkey state with Chrome / the user's default browser, so every sign-in
looks like a fresh login to Google/Apple/etc. and users are forced to
re-authenticate on every device.

Custom Tabs is the Android system-browser handoff API. It runs the
auth page in Chrome's process (or whichever browser the user has set
as default), which means cookies, password manager, and autofill are
shared with the rest of the system. The fork is the minimum change
needed to move sign-in onto that API without rewriting the rest of the
SDK.

See `LOGTO-FORK.md §1, §5` in the Calido repo for the full product-side
rationale and branch policy.

## 2. Branch layout

| Branch | Role |
|--------|------|
| `master` | Mirrors `logto-io/kotlin` master. Fast-forward only. Never commit our patches here. |
| `native-browser` | Permanent working branch carrying the presenter swap. Default branch on GitHub. |

Rebase `native-browser` onto upstream, never merge. Force-push with
`--force-with-lease`. Tagged releases (`native-browser-1`, `-2`, …) pin
an (upstream commit → fork commit) mapping in `LOGTO-FORK.md §7.1`.

## 3. Patch surface

The fork touches **6 files**, deletes **5 files**, and rewrites the
single activity test. Every change is one of:

- **Presenter swap** — replace the `WebView` host with a Custom Tabs
  trampoline activity.
- **Manifest & build wiring** — make the trampoline reachable as the
  OIDC redirect target while keeping the internal launch path safe
  against external explicit-starts.
- **Error surface** — distinguish "user cancelled," "no browser
  installed," "redirect URI not registered in the host app," and
  "callback URI did not match the registered redirectUri" so the app
  can react accordingly. Upstream conflated all of these into a generic
  outcome because the WebView always reported either a callback or
  `onDestroy`.
- **Native social cleanup** — delete the `WebView` JS bridge that
  upstream used to drive native WeChat / Alipay connectors. The bridge
  is physically unreachable from system-browser mode; the social
  callback flow continues to work through the existing
  `WebSocialResultActivity` / `AlipayResultActivity` (both retained).

### 3.1 `android-sdk/android/src/main/kotlin/io/logto/sdk/android/auth/logto/LogtoWebViewAuthActivity.kt`

**What changed.** Rewritten end-to-end. The file used to be a 110-line
`AppCompatActivity` that built a `WebView`, attached two
`@JavascriptInterface` bridges (`LogtoWebViewPolyfill`,
`LogtoWebViewSocialHandler`), set a custom `WebViewClient`
(`LogtoWebViewAuthClient`), themed the system bars, and `loadUrl`'d
the OIDC authorization endpoint inside its own view hierarchy. Its
`onDestroy` unconditionally called `LogtoAuthManager.handleUserCancel`
because the `WebView` itself owned the auth flow's lifetime.

It is now a UI-less trampoline that:

1. Validates an internal-only launch token (see §3.1.2 below).
2. Hands the auth URL to `CustomTabsIntent.launchUrl`, choosing the
   first `CustomTabsClient.getPackageName(...)` provider when one
   exists, falling back to `startActivity(ACTION_VIEW)` resolution
   otherwise. Logs which path it took so failed installs are
   diagnosable from remote logs (`LOGTO-FORK.md §5.2`, case 1/2/3).
3. Catches `ActivityNotFoundException` (case 3, no browser at all on
   the device) and surfaces it as `LogtoException.Type.NO_BROWSER_AVAILABLE`
   instead of silently hanging.
4. Resumes after Custom Tabs returns, either through the OIDC redirect
   intent filter (success / mismatch) or through `onResume` with no
   redirect (user cancel).

**Lifecycle invariants enforced by the rewrite.**

The activity is `singleTask` + `exported=true` (manifest §3.5). That
combination has three ways the activity can be entered, and each must
not cross-contaminate the others:

- **Case 1 — OIDC redirect intent.** `intent.data` is non-null and
  matches the registered `redirectUri`. Forward to
  `LogtoAuthManager.handleCallbackUri`, mark `completed = true`,
  `finish()`. If the URI matched the manifest filter (scheme + host)
  but failed the strict `LogtoAuthManager.isLogtoAuthResult` check
  (path mismatch, stale session, deep-link collision on the registered
  scheme), surface
  `LogtoException.Type.INVALID_CALLBACK_URI` via `handleInvalidCallbackUri`
  rather than letting the legacy onDestroy path mask it as `USER_CANCELED`.
  Reviewer-flagged regression — see *§3.1.3*.
- **Case 2 — `onNewIntent` of an already-running instance, no data.**
  Nothing to do; the in-flight Custom Tabs session owns the flow.
- **Case 3 — explicit launch from `LogtoAuthSession.start()` carrying
  the auth URL.** Launch Custom Tabs only after the launch-token guard
  succeeds (§3.1.2). Anything else with `EXTRA_URI` set is logged and
  rejected.

`onResume` distinguishes "callback already fired and we're finishing"
from "user closed Custom Tabs without completing": only the second
case fires `LogtoAuthManager.handleUserCancel`. `onPause` flips
`inflight = true` once Custom Tabs has been launched, so the next
`onResume` knows whether we're in the post-launch wait or the
post-callback teardown.

**State preservation.** `customTabsLaunched` / `inflight` / `completed`
are saved in `onSaveInstanceState` so the activity can be destroyed
under memory pressure while Chrome is foregrounded and resumed cleanly
when the user returns. The original WebView activity had no such
plumbing because the WebView itself was the lifetime anchor.

#### 3.1.1 Why the activity is exported and `singleTask`

Custom Tabs' redirect lands as a new `Intent` from Chrome's process,
not as a continuation of our task. To receive it back into our process
we declare an `<intent-filter>` keyed on the `${logtoRedirectScheme}` /
`${logtoRedirectHost}` placeholders (manifest §3.5), and the activity
owning that filter must be `exported=true`. `singleTask` collapses the
trampoline + the redirect into a single activity instance instead of
stacking a fresh one — without it, the back-stack would carry a
half-finished trampoline behind the redirect, and the user backing out
of post-sign-in screens would re-enter it.

#### 3.1.2 Internal-launch nonce (security)

Because the activity is `exported=true`, any other app on the device
can `startActivity()` it with a crafted `EXTRA_URI` and force our app
to open an arbitrary page in Custom Tabs (phishing, drive-by URL).
The fork guards the internal-launch path with a single-use random
token:

- `launch(Activity, String)` generates a `UUID`, stores it in a private
  companion field, then fires the intent with both `EXTRA_URI` and
  `EXTRA_LAUNCH_TOKEN`.
- `handleIntent` peeks the token (`consumeLaunchTokenIfMatches`) and
  consumes it **only on a successful match**. Reviewer-flagged
  regression — see *§3.1.3*.
- Mismatch / missing token → `finish()` with a warning log; Custom
  Tabs is never invoked.

The OIDC callback path does not need the token: it is gated by the
exact scheme/host declared in the intent filter, plus the strict
scheme/host/path match in `LogtoAuthManager.isLogtoAuthResult`
(§3.2).

#### 3.1.3 Reviewer-flagged regressions fixed in this revision

Nine issues found across four review passes on the fork. All have
explicit code comments at the call site explaining the guard.

1. **`onDestroy` no longer kills valid in-flight sessions.** The
   previous implementation called `LogtoAuthManager.handleUserCancel()`
   whenever `completed` was false — including when the system tore the
   activity down for a config change not covered by
   `android:configChanges`, when memory pressure reclaimed it while
   Custom Tabs was foregrounded, or when an unrelated
   `startActivity()` against our exported component triggered our own
   rejection path. All three would null
   `LogtoAuthManager.logtoAuthSession`, so the eventual real callback
   silently dropped on the floor and the app reported `USER_CANCELED`.
   The gate now requires
   `!completed && customTabsLaunched && isFinishing && !isChangingConfigurations`:
   * `customTabsLaunched` rules out the unrelated-explicit-launch case
     (we never started a browser, so there is nothing to cancel).
   * `isFinishing` + `!isChangingConfigurations` rule out config-change
     and memory-reclaim recreations whose saved instance state is
     enough to resume cleanly.
2. **Launch token is peek-then-consume.** The previous code called
   `consumeLaunchToken()` unconditionally before validating, so a
   stray or hostile explicit-start that arrived ahead of our own
   intent could drain the pending token and starve the legitimate
   launch. `consumeLaunchTokenIfMatches(candidate)` now nulls the
   field only after confirming the candidate matches.
3. **Redirect mismatch is reported as `INVALID_CALLBACK_URI`.** When
   `intent.data` is present but `isLogtoAuthResult` returns false
   (manifest filter matched scheme + host, but path or scheme-case
   diverged from the registered `redirectUri`), the activity now
   forwards the URI through `LogtoAuthManager.handleInvalidCallbackUri`
   instead of falling through to the onDestroy cancellation path.
   That preserves the diagnostic signal — a redirect-URI
   misconfiguration is no longer indistinguishable from a real user
   cancel.
4. *(Lives in `LogtoAuthManager.kt`, see §3.2.)* Host comparison is
   case-insensitive per RFC 3986 §3.2.2.
5. **Data-intent path requires `customTabsLaunched`.** Fix #3 above
   wired `intent.data` to `handleInvalidCallbackUri`, which clears
   `LogtoAuthManager.logtoAuthSession`. Because the activity is
   `exported=true`, that handed any third-party app a deterministic
   abort: explicit-start the trampoline with a synthetic
   `Intent(ACTION_VIEW, …).setComponent(ours)`, the mismatch branch
   would null the live session. The `intent.data` path now requires
   `customTabsLaunched` to be true on this instance — the only state
   in which a real callback can arrive (either same instance via
   `onNewIntent`, or a recreated instance whose `STATE_LAUNCHED` was
   restored from saved state). A fresh instance receiving `intent.data`
   is by definition not from our auth flow and is silently dropped.
   The remaining timing-attack window (attacker fires the data-intent
   while a session is genuinely in flight, hitting `onNewIntent` of
   the live trampoline) is what the OIDC `state` validation in
   `CallbackUriUtils.verifyAndParseCodeFromCallbackUri` exists to
   defend.
6. **Callback URI is redacted in `LogtoException.detail`.** *(Lives in
   `LogtoAuthSession.handleInvalidCallbackUri`, see §3.3.)* OIDC
   callback URIs carry `code` and `state` as query parameters.
   Embedding the raw URI in the exception detail leaks them to any
   consumer telemetry that logs `exception.detail` (a common pattern).
   `handleInvalidCallbackUri` now reduces the URI to scheme/host/path
   before composing the message; the activity's `Log.w` warning was
   audited at the same time and likewise carries no URI.
7. **Data-less explicit-start no longer aborts an in-flight session.**
   Regression #5 silently returned from `handleIntent` for a data-less
   intent on a `customTabsLaunched=true` instance, but the immediately
   following `onResume` read `inflight=true` as a user cancellation
   and cleared `LogtoAuthManager.logtoAuthSession`. Because the
   activity is `exported=true`, any app on the device could fire
   `Intent().setComponent(ours)` with no `data` to deterministically
   abort a live auth flow. The Case 2 branch now sets a
   `skipNextResumeCancel` flag before returning; `onResume` consumes
   the flag once and declines to cancel. The trampoline stays alive so
   the in-flight Custom Tabs session can still deliver its eventual
   redirect to this same instance (`singleTask` preserves the
   `STATE_LAUNCHED` that the real callback path needs — see §3.1.1).
   A genuine dismissal (`onResume` with no preceding `onNewIntent`)
   still cancels, and a user back-press still falls through to the
   `onDestroy` guard in §3.1.3 #1.
8. **Mismatched callback is authenticated by OIDC `state` before being
   treated as terminal.** Regression #3 routed any non-matching
   `intent.data` through `handleInvalidCallbackUri`, which clears the
   live session. An attacker who knows the registered scheme can
   explicit-start the trampoline with a crafted
   `Intent(ACTION_VIEW, "io.logto://callback/wrong-path?…")` to abort
   the auth flow. The fix adds `LogtoAuthManager.isAuthenticOidcState`,
   which delegates to `LogtoAuthSession.matchesState(uri)` — a
   comparison of `uri.getQueryParameter("state")` against the
   per-attempt random nonce generated by `GenerateUtils.generateState()`.
   * **Authentic state, mismatched path.** This is a real Logto
     response routed to a misconfigured `redirectUri`. Surface
     `INVALID_CALLBACK_URI` as before.
   * **Unauthenticated mismatch.** Silently drop and set
     `skipNextResumeCancel = true`. Do **not** `finish()` — finishing
     destroys the saved `STATE_LAUNCHED`, and the fresh instance that
     handles the eventual real callback drops it via the
     `customTabsLaunched` gate from #5.
   The random state value never leaves `LogtoAuthSession`; the
   manager exposes only the boolean decision.
9. **`skipNextResumeCancel` is set only on hot deliveries.** Fix #7
   and #8 set the flag from `handleIntent` whenever Case 2 or the
   unauthenticated mismatch branch fired. `handleIntent` runs from
   two places, though: `onCreate` (cold — fresh launch or OS-driven
   recreation) and `onNewIntent` (hot — a fresh intent on a running
   instance). When the OS recreates the trampoline mid-sign-in
   (memory pressure, or a config change not absorbed by
   `android:configChanges`), the saved state restores
   `customTabsLaunched=true` and the activity's intent is the
   original data-less launch intent (or, after process death, a
   stale redirect with no live session to authenticate against).
   `handleIntent` would fire Case 2 / the unauthenticated mismatch
   branch and set the flag, then the user's first opportunity to
   surface a browser-dismiss cancellation — the very next
   `onResume` — would be silently swallowed and the SDK left waiting
   on a callback that will never come.
   `handleIntent` now takes a `hotDelivery: Boolean`. `onCreate`
   passes `false`; `onNewIntent` passes `true`. The flag is set only
   when `hotDelivery == true`. Cold paths still stay alive (no
   `finish()`), but the next `onResume` is free to read `inflight`
   and surface the genuine cancel.

### 3.2 `android-sdk/android/src/main/kotlin/io/logto/sdk/android/auth/logto/LogtoAuthManager.kt`

**What changed.**

- Added `handleNoBrowserAvailable()` — pairs with the
  `ActivityNotFoundException` catch in the trampoline.
- Added `handleInvalidCallbackUri(uri)` — pairs with the
  redirect-mismatch branch in `handleIntent` (§3.1.3 #3). Both
  helpers null `logtoAuthSession` to match upstream's semantics for
  terminal outcomes.
- Added `isAuthenticOidcState(uri)` — delegates to the active
  session's `matchesState`. Read-only; does not clear the cached
  session. Used by the trampoline to authenticate the mismatch path
  before treating it as terminal (§3.1.3 #8).
- Replaced the `isLogtoAuthResult` `startsWith` prefix check with a
  strict scheme/host/path match. The original prefix check accepted
  any URI sharing a textual prefix with `redirectUri`, which let
  sibling-host or path-prefix URIs through (`io.logto://callbackevil`
  vs `io.logto://callback`, `…/auth` vs `…/authextra`). Test
  coverage for both collisions is in `LogtoAuthManagerTest`.
- `scheme` and `host` are compared with `ignoreCase = true` per
  RFC 3986 §3.1 / §3.2.2 (reviewer-flagged regression #4 in §3.1.3).
  `path` stays equality-checked because RFC 3986 §3.3 makes paths
  case-sensitive.

**Why.** The intent filter in `AndroidManifest.xml` already gates
scheme + host before Android dispatches the redirect into our process,
but `handleCallbackUri` is also reachable via explicit-component
launches of the exported activity. The strict re-check is the
defensive layer that prevents a deep link on our scheme from being
laundered into an OIDC callback.

### 3.3 `android-sdk/android/src/main/kotlin/io/logto/sdk/android/auth/logto/LogtoAuthSession.kt`

**What changed.**

- `start()` now runs a `PackageManager` probe before handing the URL
  to the trampoline. Without this, an app that forgets to override
  the `logtoRedirectScheme` / `logtoRedirectHost` manifest placeholders
  builds and ships clean, the user opens Custom Tabs, signs in
  successfully, and Chrome's redirect simply has nowhere to go — the
  user eventually taps back and the SDK reports `USER_CANCELED`,
  indistinguishable from a real cancel. The probe constructs the
  `ACTION_VIEW` intent that Chrome will issue for the configured
  `redirectUri`, restricts the resolver to our own package
  (`setPackage(context.packageName)`) so a browser cannot satisfy it,
  and returns a typed `REDIRECT_URI_NOT_REGISTERED` error with a
  detail string pointing at the placeholders to set. The probe is
  skipped for scheme-less URIs so legacy dummy values in tests
  continue to flow through the existing `INVALID_REDIRECT_URI` path.
- Added `handleNoBrowserAvailable()` and `handleInvalidCallbackUri(uri)`
  which complete the session with `NO_BROWSER_AVAILABLE` and
  `INVALID_CALLBACK_URI` respectively. The `INVALID_CALLBACK_URI`
  variant attaches a `detail` string identifying the offending URI's
  scheme/host/path (query and fragment redacted — see §3.1.3 #6) and
  the registered `redirectUri` so consumers can diagnose without
  re-instrumenting the SDK and without leaking OIDC `code` / `state`
  through telemetry.
- Added `matchesState(uri)` — `internal` helper that compares the
  `state` query parameter on `uri` against the per-attempt random
  nonce generated by `GenerateUtils.generateState()`. The nonce
  itself stays `private`; the helper exposes only the boolean
  decision so the trampoline can authenticate the mismatch path
  (§3.1.3 #8) without the value ever leaving the session object.

PKCE / `state` / `nonce` generation, `handleCallbackUri` token
exchange, and the overall session shape are untouched — the fork is
deliberately localized to the presenter and its error surface.

### 3.4 `android-sdk/android/src/main/kotlin/io/logto/sdk/android/exception/LogtoException.kt`

Added two new `Type` cases:

- `NO_BROWSER_AVAILABLE` — `CustomTabsIntent.launchUrl` resolved to
  no `Activity` for `ACTION_VIEW`. Surfaces from the trampoline's
  `ActivityNotFoundException` catch. The app layer is expected to
  show "install a browser to sign in."
- `REDIRECT_URI_NOT_REGISTERED` — the `PackageManager` probe in
  `LogtoAuthSession.start()` found no activity in the host package
  declaring an intent filter for the configured `redirectUri`'s
  scheme/host. Detail string names the
  `manifestPlaceholders["logtoRedirectScheme"]` /
  `manifestPlaceholders["logtoRedirectHost"]` keys to set.

These are additive enum entries; existing `when` consumers without
`else` will see compile errors and need to add branches, which is the
intended source-compat signal — they previously had no way to
distinguish these failures from `USER_CANCELED`.

### 3.5 `android-sdk/android/src/main/AndroidManifest.xml`

- `LogtoWebViewAuthActivity` flips `exported="false"` → `exported="true"`,
  `launchMode="singleTop"` → `launchMode="singleTask"`, and gains an
  `<intent-filter>` matching `${logtoRedirectScheme}` /
  `${logtoRedirectHost}`. See §3.1.1 for the lifecycle reasoning.
- `WebSocialResultActivity` and `AlipayResultActivity` declarations
  are **kept verbatim**. Custom Tabs reaches them through the same
  `logto-callback://${applicationId}/{web,alipay}/...` scheme that
  upstream uses; deleting them would actually break native social
  even though the JS bridge that drove the flow inside `WebView` is
  gone (`LOGTO-FORK.md §5.4`).
- Added an HTML comment documenting the placeholder contract so
  consumers reading the manifest see the expected
  `manifestPlaceholders` keys without grepping for them.

### 3.6 `android-sdk/android/build.gradle.kts`

Added default `manifestPlaceholders` for `logtoRedirectScheme` /
`logtoRedirectHost` (`"logto-callback"` / `"unused"`). These exist
solely so the library module builds and unit-tests on its own. Real
consumers MUST override them — the `REDIRECT_URI_NOT_REGISTERED`
guard in §3.3 is the failure mode if they don't.

The existing `androidx.browser` dependency in
`gradle/libs.versions.toml` (already present at `1.3.0` in upstream)
is reused; no new library entries.

### 3.7 Deleted files

- `android-sdk/android/src/main/kotlin/io/logto/sdk/android/auth/logto/LogtoWebViewAuthClient.kt`
- `android-sdk/android/src/main/kotlin/io/logto/sdk/android/auth/logto/LogtoWebViewPolyfill.kt`
- `android-sdk/android/src/main/kotlin/io/logto/sdk/android/auth/logto/LogtoWebViewSocialHandler.kt`
- `android-sdk/android/src/test/kotlin/io/logto/sdk/android/auth/logto/LogtoWebViewAuthClientTest.kt`
- `android-sdk/android/src/test/kotlin/io/logto/sdk/android/auth/logto/LogtoWebViewSocialHandlerTest.kt`

`LogtoWebViewAuthClient` was the upstream `WebViewClient` subclass
that intercepted `shouldOverrideUrlLoading` to detect the OIDC
callback inside the embedded WebView. Custom Tabs runs the auth page
out-of-process and delivers the redirect via Android's intent system
instead, so there is no in-process URL stream to intercept. Equivalent
gating happens in `LogtoWebViewAuthActivity.handleIntent` Case 1 plus
`LogtoAuthManager.isLogtoAuthResult`.

`LogtoWebViewPolyfill` injected a `navigator.clipboard.writeText`
shim into the upstream WebView via `@JavascriptInterface`. The shim
existed because Android `WebView`'s `navigator.clipboard` was not
exposed to JS without the bridge. Chrome / Custom Tabs ships a real
implementation of the Clipboard API natively, so the polyfill is
inert in this world.

`LogtoWebViewSocialHandler` was the JS bridge that dispatched
`window.logtoNativeSdk.getPostMessage` calls from the auth page into
the Android process so it could launch the WeChat / Alipay native
SDKs and feed results back. Custom Tabs runs the auth page in
Chrome's process, so the bridge is physically unreachable — Chrome
cannot call arbitrary Java/Kotlin code in our app, by design.
The native social handoff still works, but the wiring is different
and lives entirely in the existing scheme-routed result activities
(`WebSocialResultActivity` / `AlipayResultActivity`). Calido does
not exercise this path. `LOGTO-FORK.md §5.4` documents the full
sequence so future maintainers do not try to "restore" the deleted
bridge after seeing it missing.

The two deleted test files exercised the deleted production code and
would not compile against the new surface. They are replaced by the
trampoline + manager tests described in §3.8.

### 3.8 Tests

- `android-sdk/android/src/test/kotlin/io/logto/sdk/android/auth/logto/LogtoWebViewAuthActivityTest.kt`
  — rewritten. Upstream tested WebView mechanics (URL load, JS
  interface installation). The new file covers the trampoline's
  external-attack surface and error paths only — the happy path
  (Custom Tabs actually opens and returns a redirect) requires a
  real browser and is exercised by the on-device sign-in smoke test
  in `LOGTO-FORK.md §8`. Cases:
  * External explicit-start with `EXTRA_URI` but no launch token →
    activity finishes immediately, `handleCallbackUri` never invoked.
  * Untrusted explicit-start does NOT cancel a live session in
    `onDestroy` (regression for §3.1.3 #1).
  * Data-intent against a fresh instance is silently dropped
    (regression for §3.1.3 #5).
  * Mismatched callback with authentic OIDC `state` (saved-state-
    restored instance) invokes `handleInvalidCallbackUri`, not
    `handleUserCancel` (regression for §3.1.3 #3, exercised via
    `Robolectric.buildActivity(...).create(savedState)`).
  * Hot data-less explicit-start during an in-flight session does
    NOT cancel — set up via `create → start → resume → pause →
    newIntent → resume` so `onNewIntent` is the path that fires
    Case 2 (regression for §3.1.3 #7 + #9).
  * Hot data with mismatched OIDC `state` during an in-flight
    session does NOT cancel and does NOT surface
    `INVALID_CALLBACK_URI` — same `pause → newIntent → resume`
    chain (regression for §3.1.3 #8 + #9).
  * OS recreation during an in-flight session followed by a
    browser-dismiss `onResume` DOES surface `handleUserCancel`
    (regression for §3.1.3 #9). Drives `create(savedState=in-flight)
    → start → resume` so `onCreate` is the cold path that runs
    Case 2 with `hotDelivery=false`.
- `LogtoAuthManagerTest` — added cases for the strict scheme/host/path
  matcher: prefix-collision host rejected, prefix-collision path
  rejected, exact match accepted, scheme case-insensitive, host
  case-insensitive (§3.1.3 #4), `handleInvalidCallbackUri` invokes
  the session handler and clears the cache, and `isAuthenticOidcState`
  delegates to `LogtoAuthSession.matchesState` without clearing the
  cached session (regression for §3.1.3 #8).
- `LogtoAuthSessionTest` — added cases for the
  `REDIRECT_URI_NOT_REGISTERED` probe failure mode and for both
  `handleNoBrowserAvailable` / `handleInvalidCallbackUri` completions.
  The `handleInvalidCallbackUri` test asserts that the exception
  detail does NOT contain the URI's `code` / `state` query values
  (regression for §3.1.3 #6). A new `matchesState` test captures the
  per-attempt state from `Core.generateSignInUri`'s call args and
  verifies the helper accepts only URIs carrying that exact value
  (regression for §3.1.3 #8).

### 3.9 Tests not run on the rebase machine — known environment issue

`./gradlew :android-sdk:android:testDebugUnitTest` currently fails on
this machine with
`java.lang.NoSuchMethodError: sun.misc.Unsafe.defineAnonymousClass`
across every test in the auth.logto package. This is a JDK 17
incompatibility in the pinned Robolectric `4.6` (which still uses the
removed `Unsafe.defineAnonymousClass` method); the failure reproduces
on the unmodified `master` branch and is not introduced by the fork.
Resolving it would mean upgrading the Robolectric pin, which is a
larger change than the fork's scope.

The patches compile clean
(`./gradlew :android-sdk:android:compileDebugKotlin
:android-sdk:android:compileDebugUnitTestKotlin` — `BUILD SUCCESSFUL`).
The on-device smoke test in `LOGTO-FORK.md §8` is the authoritative
gate for releasing a fork tag.

## 4. Working on the fork

### 4.1 Local setup

```bash
cd ~/Documents/Developer/github
git clone git@github.com:mpisat/logto-kotlin.git
cd logto-kotlin
git remote add upstream https://github.com/logto-io/kotlin.git
git fetch upstream
git checkout native-browser
```

### 4.2 Compile / test

```bash
# Compile-check (always passes; this is the gate this README enforces)
./gradlew :android-sdk:android:compileDebugKotlin \
          :android-sdk:android:compileDebugUnitTestKotlin

# Unit tests (fails on JDK 17 — see §3.9)
./gradlew :android-sdk:android:testDebugUnitTest \
  --tests 'io.logto.sdk.android.auth.logto.*'
```

The mandatory release gate is the on-device sign-in smoke test in
`LOGTO-FORK.md §8`, run from the consuming Calido app against the
fork via either `includeBuild` (live local edits) or a pinned
`native-browser-N` JitPack tag.

### 4.3 Rebasing onto upstream

```bash
git fetch upstream
git log --oneline upstream/master ^master        # inspect upstream drift
git checkout native-browser
git rebase upstream/master
# resolve conflicts — they almost always live outside the 6 files above
./gradlew :android-sdk:android:compileDebugKotlin \
          :android-sdk:android:compileDebugUnitTestKotlin
git push --force-with-lease origin native-browser
git tag native-browser-N && git push --tags
```

Record the mapping in `LOGTO-FORK.md §7.1` (consuming-app repo) in the
same PR that bumps the Gradle pin. Skipping the tag makes future
rollback-bisect require a diff archaeology session.

## 5. Invariants to preserve

Anything that breaks these is a rebase-blocking regression:

1. **PKCE + `state` + `nonce` stay upstream.** The presenter change
   must not move `code_verifier`, `state`, or `nonce` generation. They
   live in `LogtoAuthSession`'s init / `handleCallbackUri` for a reason
   — they are deterministic per sign-in attempt and must round-trip
   exactly on callback.
2. **Strict redirect match.** `LogtoAuthManager.isLogtoAuthResult`
   must reject any callback URI whose scheme / host / path do not
   match the registered `redirectUri`. Regressing this turns our
   scheme into an open redirect for anyone on the device. Scheme +
   host comparisons stay case-insensitive (RFC 3986); path stays
   case-sensitive.
3. **Internal-launch nonce stays peek-then-consume.** Reverting to
   eager-consume reintroduces the DoS where any explicit-start
   against our exported activity drains the pending token.
4. **`onDestroy` cancellation gate stays specific.** All four guards
   (`!completed && customTabsLaunched && isFinishing &&
   !isChangingConfigurations`) are load-bearing — see §3.1.3 #1.
   Loosening any one of them reintroduces a class of false-cancel
   bug.
5. **`exported=true` requires the launch-token guard.** If the
   activity ever becomes implicitly launchable for any extra besides
   `EXTRA_LAUNCH_TOKEN`, an external app gains the ability to drive
   our process into Custom Tabs. Re-audit the trampoline in §3.1.2
   when touching this surface.
6. **Data-intent path stays gated on `customTabsLaunched`.** Removing
   that gate (or reordering it so the manager is invoked before the
   gate) re-opens the third-party session-abort attack described in
   §3.1.3 #5.
7. **OIDC `code` / `state` never leave the SDK.** `handleInvalidCallbackUri`
   redacts the URI to scheme/host/path; the trampoline's warning logs
   carry no URI. Anything that puts a callback URI into a log line, an
   exception message, or an analytics event must redact first. The
   `state` value is exposed only as the boolean
   `LogtoAuthSession.matchesState` decision — never as the value
   itself.
8. **Spurious HOT foregrounds during in-flight auth must not cancel,
   but COLD recreations must.** The `skipNextResumeCancel` flag set
   by `handleIntent`'s data-less Case 2 (regression #7) and
   unauthenticated-mismatch Case 1 (regression #8) is load-bearing
   for external explicit-starts via `onNewIntent`. The flag must
   stay scoped to `hotDelivery == true` (regression #9) — setting it
   from the `onCreate` cold path silently swallows the genuine
   browser-dismiss cancellation after an OS-driven recreation and
   leaves the SDK with a stuck session. Any future change to
   `onResume`'s cancel gate, or to the `hotDelivery` parameter
   threading, must preserve both halves: hot stays defended, cold
   stays cancellable.
9. **Mismatched callback path stays gated on `isAuthenticOidcState`.**
   The mismatch branch in `handleIntent` is terminal only when the
   URI carries our session's OIDC `state`. Reverting to the
   unconditional `handleInvalidCallbackUri(uri)` reintroduces the DoS
   from regression #8. Any change must also avoid `finish()` on the
   unauthenticated path — finishing destroys the saved
   `STATE_LAUNCHED` and the fresh instance handling the eventual real
   callback drops it via the regression #5 gate.
10. **Native social-result activities stay in the manifest.**
    `WebSocialResultActivity` / `AlipayResultActivity` are still
    reachable via Logto's hosted page and the upstream native-SDK
    handoff. Deleting them silently breaks WeChat / Alipay even though
    the JS bridge is gone (`LOGTO-FORK.md §5.4`).

## 6. Refresh and callback ownership corrections (2026-09-07)

- Refresh preflights the cached JWKS before sending the refresh token to the
  token endpoint. A temporary JWKS failure therefore leaves the rotating
  credential unconsumed. ID-token verification still runs before saving the
  response. The JWKS cache is retained for the client lifetime; the Calido host
  serializes SDK exchanges and account mutations through callback completion.
- An exact redirect URI must also carry the active session's OAuth state before
  the trampoline completes or clears that session. A hot callback with stale or
  missing state preserves the session and suppresses only the associated resume;
  a later authentic callback or browser cancellation still completes normally.
- Deterministic regressions cover JWKS failure before exchange and retry with
  ID-token verification, plus stale/missing-state exact redirects followed by an
  authentic callback or cancellation. The consuming Calido Android module runs
  these tests with `./gradlew --no-daemon --offline
  :logto-native-browser:testDebugUnitTest` from its `android/` directory.
  Physical browser/device behavior remains a separate release gate.

## 7. References

- `LOGTO-FORK.md` (Calido repo): product-side contract, test
  checklist, rollback mapping table.
- Upstream docs: `README.md` in this repo (unchanged).
- Custom Tabs docs: [developer.android.com — androidx.browser](https://developer.android.com/reference/androidx/browser/customtabs/package-summary).
- Sibling fork: [`logto-swift` README.fork.md](https://github.com/mpisat/logto-swift/blob/native-browser/README.fork.md)
  for the iOS analog of every section here.
