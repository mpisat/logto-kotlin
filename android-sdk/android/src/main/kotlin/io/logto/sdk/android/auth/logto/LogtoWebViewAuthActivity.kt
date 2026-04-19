package io.logto.sdk.android.auth.logto

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.browser.customtabs.CustomTabsClient
import androidx.browser.customtabs.CustomTabsIntent
import java.util.UUID

/**
 * Native-browser fork: this activity used to host a [android.webkit.WebView]
 * that loaded the OIDC sign-in page in process. It now acts as a thin
 * trampoline that hands the sign-in page to Chrome Custom Tabs so that
 * Chrome's cookies, password manager, and autofill state are shared
 * with the rest of the system.
 *
 * Lifecycle:
 * - The consumer (`LogtoAuthSession`) calls [launch], which starts this
 *   activity with the generated authorization URL in `EXTRA_URI`.
 * - `onCreate` kicks off [CustomTabsIntent] and this activity stays in
 *   the task, hidden behind the browser.
 * - After the OIDC redirect (`${logtoRedirectScheme}://${logtoRedirectHost}/...`),
 *   the OS re-enters this activity through the manifest intent filter.
 *   We forward the callback URI to [LogtoAuthManager] and finish.
 * - If the user closes Custom Tabs without completing sign-in, our
 *   `onResume` fires with no callback delivered — we treat that as a
 *   user cancellation.
 */
class LogtoWebViewAuthActivity : AppCompatActivity() {
    private var customTabsLaunched = false
    private var inflight = false
    private var completed = false

    // Set when a HOT (`onNewIntent`) explicit-start brings this
    // trampoline to the foreground while a Custom Tabs session is in
    // flight. The immediate `onResume` that follows must NOT interpret
    // the foregrounding as a user cancellation; that would let any app
    // on the device deterministically abort our auth flow.
    //
    // Cold deliveries (`onCreate`) deliberately leave the flag unset
    // even when the same Case 2 / unauthenticated-mismatch branches
    // fire there. An OS-driven recreation — process restored under
    // memory pressure, or a config change not absorbed by
    // `android:configChanges` — also re-runs the original (data-less
    // or stale-redirect) intent through `handleIntent`. The first
    // `onResume` after that recreation is the user's only chance to
    // surface a genuine browser-dismiss cancellation, and silently
    // swallowing it would leave the SDK with a stuck session waiting
    // on a callback that will never come.
    //
    // Cleared after one resume. Not persisted in saved state — it
    // only guards the resume that pairs with the `onNewIntent` that
    // set it.
    private var skipNextResumeCancel = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()

        savedInstanceState?.let {
            customTabsLaunched = it.getBoolean(STATE_LAUNCHED, false)
            inflight = it.getBoolean(STATE_INFLIGHT, false)
            completed = it.getBoolean(STATE_COMPLETED, false)
        }

        // Cold delivery: either a fresh launch or an OS-driven
        // recreation. The intent is whatever the system attached, not
        // a freshly delivered external one — see the
        // `skipNextResumeCancel` header comment.
        handleIntent(intent, hotDelivery = false)
    }

    override fun onNewIntent(newIntent: Intent) {
        super.onNewIntent(newIntent)
        setIntent(newIntent)
        // Hot delivery: a new intent landed on this running instance.
        // `singleTask` + `exported=true` makes that reachable from any
        // app on the device, so the spurious-foreground defense in
        // `handleIntent` applies.
        handleIntent(newIntent, hotDelivery = true)
    }

    override fun onResume() {
        super.onResume()

        // If the redirect intent already flowed through handleIntent this
        // activity is already finishing — skip the cancel path.
        if (isFinishing || completed) {
            return
        }

        // A spurious explicit-start (data-less, or data with mismatched
        // OIDC state) just brought us forward. Do not read this resume
        // as a user cancellation — Chrome still owns the live session
        // and a future onResume after a genuine dismissal can still
        // cancel.
        if (skipNextResumeCancel) {
            skipNextResumeCancel = false
            return
        }

        // The user closed Custom Tabs (back button, swipe away) without
        // the redirect firing. Treat as a user-initiated cancellation.
        if (inflight) {
            completed = true
            LogtoAuthManager.handleUserCancel()
            finish()
        }
    }

    override fun onPause() {
        super.onPause()
        // Once we have kicked off Custom Tabs, mark us as awaiting a
        // result. The next onResume either follows onNewIntent (callback
        // arrived and we are finishing) or signals cancellation.
        if (customTabsLaunched) {
            inflight = true
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_LAUNCHED, customTabsLaunched)
        outState.putBoolean(STATE_INFLIGHT, inflight)
        outState.putBoolean(STATE_COMPLETED, completed)
    }

    override fun onDestroy() {
        // Safety net for the rare case where Custom Tabs was launched but
        // neither onResume nor the callback intent fired before we were
        // torn down. Four guards, each rules out a class of false cancel:
        //   * `!completed`            — onResume / callback already fired.
        //   * `customTabsLaunched`    — we never started a browser, so
        //     there is no auth flow to cancel. This rules out the
        //     unrelated-explicit-launch DoS where any app on the device
        //     touches our exported activity and would otherwise nuke a
        //     real in-flight session via `LogtoAuthManager`.
        //   * `isFinishing`           — the system tearing us down for a
        //     recreation (memory reclaim / config change) is not a
        //     cancellation; saved instance state restores the new instance.
        //   * `!isChangingConfigurations` — covers config changes that the
        //     manifest's `android:configChanges` does not absorb (locale,
        //     ui mode, density, ...).
        if (!completed && customTabsLaunched && isFinishing && !isChangingConfigurations) {
            completed = true
            LogtoAuthManager.handleUserCancel()
        }
        super.onDestroy()
    }

    private fun handleIntent(intent: Intent, hotDelivery: Boolean) {
        // Case 1: we were (re)launched by the OIDC redirect intent filter.
        intent.data?.let { uri ->
            // Authenticity gate. A real callback always lands on a
            // trampoline that has already launched Custom Tabs in this
            // task — either the same instance via `onNewIntent`, or a
            // recreated instance whose `STATE_LAUNCHED` was restored
            // from saved state. Any data-intent that arrives at a fresh
            // instance is therefore not from our auth flow; because
            // this activity is `exported=true`, third-party apps can
            // explicit-start it with arbitrary `data` and would
            // otherwise be able to deterministically abort an in-flight
            // session via `LogtoAuthManager`. Silently drop those.
            if (!customTabsLaunched) {
                Log.w(TAG, "Ignoring data-intent received before Custom Tabs was launched")
                finish()
                return
            }
            if (LogtoAuthManager.isLogtoAuthResult(uri)) {
                completed = true
                LogtoAuthManager.handleCallbackUri(uri)
                finish()
                return
            }
            // Scheme/host matched but the full URI does not match the
            // session's redirectUri. This branch is reachable two ways:
            //   (a) Logto issued a real callback whose path/scheme-case
            //       diverges from the registered redirectUri — a config
            //       error worth surfacing as INVALID_CALLBACK_URI.
            //   (b) A third-party app explicit-started this exported
            //       activity with crafted `data` to abort our in-flight
            //       session.
            // The OIDC `state` parameter rides every genuine response
            // and an attacker cannot guess our per-attempt random
            // value, so authenticate by state before treating the
            // mismatch as terminal. Do NOT finish on the unauthenticated
            // path — finishing would destroy `STATE_LAUNCHED` and let
            // the fresh instance that handles the eventual real
            // callback drop it via the `customTabsLaunched` gate above.
            if (LogtoAuthManager.isAuthenticOidcState(uri)) {
                completed = true
                // Do not log the URI itself — it carries the OIDC
                // `code` and `state` as query parameters. The session
                // layer redacts before exposing the message.
                Log.w(TAG, "Callback URI did not match registered redirectUri")
                LogtoAuthManager.handleInvalidCallbackUri(uri)
                finish()
            } else {
                Log.w(TAG, "Dropping data-intent without matching session state")
                // Only suppress the next cancel for hot deliveries. A
                // cold delivery here means either a fresh launch (no
                // session yet, so the cancel is a no-op anyway) or an
                // OS-driven recreation after process death — in the
                // latter case the session pointer is gone and the
                // first onResume must finish the activity cleanly.
                if (hotDelivery) {
                    skipNextResumeCancel = true
                }
            }
            return
        }

        // Case 2: data-less re-entry on an in-flight instance.
        // Real callbacks always carry data, so this is either an
        // external explicit-start trying to abort the session (hot)
        // or an OS-driven recreation that re-runs the original launch
        // intent (cold). Stay alive in either case so the live Custom
        // Tabs session can still deliver its redirect to this same
        // instance — but only suppress the next cancel for the hot
        // case (see `skipNextResumeCancel` header comment).
        if (customTabsLaunched) {
            if (hotDelivery) {
                skipNextResumeCancel = true
            }
            return
        }

        // Case 3: explicit launch from LogtoAuthSession with the auth URL.
        // This activity has to be `exported=true` for the OIDC redirect
        // intent-filter to work, which also means external apps could
        // explicit-start it and stuff a crafted `EXTRA_URI` to trick us
        // into opening an arbitrary page in Custom Tabs. Guard the
        // internal-launch path with a one-shot nonce that only our own
        // `launch(...)` helper knows.
        val authUri = intent.getStringExtra(EXTRA_URI)
        val token = intent.getStringExtra(EXTRA_LAUNCH_TOKEN)
        // Peek-then-consume: a hostile or stray explicit-start that
        // arrives ahead of our own intent must NOT be able to drain the
        // pending token and starve the legitimate launch. Only consume
        // the token after we've confirmed it matches.
        if (authUri.isNullOrEmpty() || token == null || !consumeLaunchTokenIfMatches(token)) {
            if (authUri != null) {
                Log.w(TAG, "Rejected explicit launch with unexpected or missing launch token")
            }
            finish()
            return
        }

        launchCustomTab(Uri.parse(authUri))
    }

    private fun launchCustomTab(uri: Uri) {
        val packageName = CustomTabsClient.getPackageName(this, null)

        if (packageName != null) {
            Log.i(TAG, "Launching sign-in in Custom Tabs via $packageName")
        } else {
            Log.i(TAG, "No Custom Tabs provider; falling back to system browser")
        }

        val customTabsIntent = CustomTabsIntent.Builder().build()
        packageName?.let { customTabsIntent.intent.setPackage(it) }

        try {
            customTabsIntent.launchUrl(this, uri)
            customTabsLaunched = true
        } catch (e: ActivityNotFoundException) {
            Log.e(TAG, "No browser available to open sign-in page", e)
            completed = true
            LogtoAuthManager.handleNoBrowserAvailable()
            finish()
        }
    }

    companion object {
        private const val TAG = "LogtoCustomTabsAuth"
        private const val EXTRA_URI = "EXTRA_URI"
        private const val EXTRA_LAUNCH_TOKEN = "EXTRA_LAUNCH_TOKEN"
        private const val STATE_LAUNCHED = "STATE_LAUNCHED"
        private const val STATE_INFLIGHT = "STATE_INFLIGHT"
        private const val STATE_COMPLETED = "STATE_COMPLETED"

        @Volatile
        private var pendingLaunchToken: String? = null

        @Synchronized
        private fun consumeLaunchTokenIfMatches(candidate: String): Boolean {
            val expected = pendingLaunchToken ?: return false
            if (expected != candidate) {
                return false
            }
            pendingLaunchToken = null
            return true
        }

        fun launch(context: Activity, uri: String) {
            val token = UUID.randomUUID().toString()
            synchronized(Companion) {
                pendingLaunchToken = token
            }
            context.startActivity(
                Intent(context, LogtoWebViewAuthActivity::class.java).apply {
                    putExtra(EXTRA_URI, uri)
                    putExtra(EXTRA_LAUNCH_TOKEN, token)
                },
            )
        }
    }
}
