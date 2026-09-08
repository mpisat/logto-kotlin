package io.logto.sdk.android.auth.logto

import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

/**
 * Covers the Custom Tabs trampoline behaviour introduced by the
 * native-browser fork. Focus: the launch-token guard that rejects
 * external explicit-starts, the user-cancel path when the activity
 * is (re)entered without the OIDC redirect, the redirect-mismatch
 * error path, and the third-party data-intent abort defense. The
 * happy path (Custom Tabs actually opens and returns a redirect)
 * requires a real browser and is exercised by the on-device sign-in
 * smoke test, not here.
 */
@RunWith(RobolectricTestRunner::class)
class LogtoWebViewAuthActivityTest {

    @Before
    fun setUp() {
        mockkObject(LogtoAuthManager)
        every { LogtoAuthManager.handleUserCancel() } just Runs
        every { LogtoAuthManager.handleNoBrowserAvailable() } just Runs
        every { LogtoAuthManager.handleCallbackUri(any()) } just Runs
        every { LogtoAuthManager.handleInvalidCallbackUri(any()) } just Runs
        every { LogtoAuthManager.isLogtoAuthResult(any()) } returns false
        // Default to "not from our session." Tests that exercise the
        // legitimate config-error path override this to true.
        every { LogtoAuthManager.isAuthenticOidcState(any()) } returns false
    }

    @After
    fun tearDown() {
        unmockkObject(LogtoAuthManager)
    }

    @Test
    fun `external explicit-start with EXTRA_URI but no launch token is rejected`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val intent = Intent().apply {
            component = ComponentName(context, LogtoWebViewAuthActivity::class.java)
            putExtra("EXTRA_URI", "https://evil.example/phish")
            // No EXTRA_LAUNCH_TOKEN: this is what an external attacker
            // would look like — they don't have the companion-object
            // nonce.
        }

        androidx.test.core.app.ActivityScenario.launch<LogtoWebViewAuthActivity>(intent).use { scenario ->
            assertThat(scenario.state).isEqualTo(Lifecycle.State.DESTROYED)
        }

        // Activity finished without ever calling Custom Tabs or
        // registering any session state.
        verify(exactly = 0) { LogtoAuthManager.handleCallbackUri(any()) }
    }

    @Test
    fun `untrusted explicit-start does not cancel a live session in onDestroy`() {
        // Regression: the previous safety net in onDestroy fired
        // handleUserCancel for ANY destruction with completed=false. An
        // unrelated app — or any in-app component — explicit-starting
        // our exported activity would therefore nuke a real, in-flight
        // session via LogtoAuthManager.logtoAuthSession = null. The
        // onDestroy gate now requires `customTabsLaunched`, which is
        // never set on a rejected untrusted launch.
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val intent = Intent().apply {
            component = ComponentName(context, LogtoWebViewAuthActivity::class.java)
            putExtra("EXTRA_URI", "https://evil.example/phish")
        }

        androidx.test.core.app.ActivityScenario.launch<LogtoWebViewAuthActivity>(intent).close()

        verify(exactly = 0) { LogtoAuthManager.handleUserCancel() }
        verify(exactly = 0) { LogtoAuthManager.handleInvalidCallbackUri(any()) }
    }

    @Test
    fun `data-intent against a fresh instance is silently dropped`() {
        // Regression: a previous revision of the mismatch branch
        // unconditionally invoked LogtoAuthManager.handleInvalidCallbackUri
        // for any non-null intent.data. Because this activity is exported,
        // any third-party app could explicit-start it with an arbitrary
        // ACTION_VIEW intent and deterministically abort an in-flight
        // session. The data-intent path now requires customTabsLaunched
        // (only true after this trampoline launched Chrome itself).
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val callback = Uri.parse("io.logto://callback?code=abc&state=xyz")
        val intent = Intent(Intent.ACTION_VIEW, callback).apply {
            component = ComponentName(context, LogtoWebViewAuthActivity::class.java)
        }

        androidx.test.core.app.ActivityScenario.launch<LogtoWebViewAuthActivity>(intent).close()

        verify(exactly = 0) { LogtoAuthManager.handleCallbackUri(any()) }
        verify(exactly = 0) { LogtoAuthManager.handleInvalidCallbackUri(any()) }
        verify(exactly = 0) { LogtoAuthManager.handleUserCancel() }
    }

    @Test
    fun `mismatched callback with authentic state reports INVALID_CALLBACK_URI`() {
        // Drive the Robolectric controller manually so we can inject
        // saved state where customTabsLaunched=true. This simulates the
        // real-callback case: Chrome's redirect arrives at a trampoline
        // instance that has already launched Custom Tabs (either same
        // task via onNewIntent, or a recreated instance whose state was
        // restored). The URI is path-mismatched but its OIDC `state`
        // matches our session — that means Logto issued it for our
        // attempt, the redirect just landed on a misconfigured path.
        // The authentic-state branch surfaces INVALID_CALLBACK_URI so
        // the consumer can distinguish a config error from a real cancel.
        every { LogtoAuthManager.isAuthenticOidcState(any()) } returns true

        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val callback = Uri.parse("io.logto://callback/wrong-path?code=abc&state=xyz")
        val intent = Intent(Intent.ACTION_VIEW, callback).apply {
            component = ComponentName(context, LogtoWebViewAuthActivity::class.java)
        }
        val savedState = Bundle().apply {
            putBoolean("STATE_LAUNCHED", true)
            putBoolean("STATE_INFLIGHT", true)
            putBoolean("STATE_COMPLETED", false)
        }

        Robolectric.buildActivity(LogtoWebViewAuthActivity::class.java, intent)
            .create(savedState)
            .destroy()

        verify { LogtoAuthManager.handleInvalidCallbackUri(callback) }
        verify(exactly = 0) { LogtoAuthManager.handleUserCancel() }
        verify(exactly = 0) { LogtoAuthManager.handleCallbackUri(any()) }
    }

    @Test
    fun `hot data-less explicit-start during in-flight session does not cancel`() {
        // Regression: the previous revision's Case 2 silently returned
        // from handleIntent for a data-less intent on an in-flight
        // instance, but the immediately-following onResume read
        // `inflight=true` as a user cancellation and cleared
        // LogtoAuthManager.logtoAuthSession. Because the activity is
        // exported, any third-party app could fire
        // `Intent().setComponent(ours)` to deterministically abort the
        // live auth flow. The fix sets `skipNextResumeCancel` only on
        // hot deliveries (onNewIntent), keeping the trampoline alive
        // across the spurious foreground so the in-flight Custom Tabs
        // session can still deliver its eventual redirect.
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val originalIntent = Intent().apply {
            component = ComponentName(context, LogtoWebViewAuthActivity::class.java)
        }
        // Saved state restores `customTabsLaunched=true` but leaves
        // `inflight=false` so the first resume does not cancel before
        // we have a chance to pause and re-enter via newIntent.
        val savedState = Bundle().apply {
            putBoolean("STATE_LAUNCHED", true)
            putBoolean("STATE_INFLIGHT", false)
            putBoolean("STATE_COMPLETED", false)
        }
        val attackerIntent = Intent().apply {
            component = ComponentName(context, LogtoWebViewAuthActivity::class.java)
            // No data, no extras — what an attacker would fire.
        }

        val controller = Robolectric.buildActivity(LogtoWebViewAuthActivity::class.java, originalIntent)
        controller.create(savedState).start().resume()
        // onPause flips inflight=true (matches the real-life moment
        // when Custom Tabs took the foreground from this trampoline).
        controller.pause()
        // Hot delivery: the attacker's empty intent lands on the
        // running, paused instance via the system's onNewIntent dispatch.
        controller.newIntent(attackerIntent)
        // The user comes back to the foreground (or, in the worst
        // case, the attacker's start brought us forward). The skip
        // flag set during onNewIntent must absorb this resume.
        controller.resume()

        verify(exactly = 0) { LogtoAuthManager.handleUserCancel() }
        verify(exactly = 0) { LogtoAuthManager.handleCallbackUri(any()) }
        verify(exactly = 0) { LogtoAuthManager.handleInvalidCallbackUri(any()) }
    }

    @Test
    fun `hot data with mismatched state during in-flight session does not cancel`() {
        // Regression: previously the mismatch branch unconditionally
        // routed any non-matching `intent.data` through
        // handleInvalidCallbackUri, which clears the live session.
        // Because the activity is exported, an attacker who knows our
        // scheme could explicit-start it with a crafted ACTION_VIEW
        // intent and abort the auth flow. The fix authenticates the
        // URI by its OIDC `state` (a per-attempt random nonce
        // generated by `LogtoAuthSession`) before treating mismatch as
        // terminal. An unauthenticated mismatch on a hot delivery is
        // silently dropped. isAuthenticOidcState defaults to false in
        // setUp().
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val originalIntent = Intent().apply {
            component = ComponentName(context, LogtoWebViewAuthActivity::class.java)
        }
        val savedState = Bundle().apply {
            putBoolean("STATE_LAUNCHED", true)
            putBoolean("STATE_INFLIGHT", false)
            putBoolean("STATE_COMPLETED", false)
        }
        val attackerCallback = Uri.parse("io.logto://callback/wrong-path?code=fake&state=guess")
        val attackerIntent = Intent(Intent.ACTION_VIEW, attackerCallback).apply {
            component = ComponentName(context, LogtoWebViewAuthActivity::class.java)
        }

        val controller = Robolectric.buildActivity(LogtoWebViewAuthActivity::class.java, originalIntent)
        controller.create(savedState).start().resume()
        controller.pause()
        controller.newIntent(attackerIntent)
        controller.resume()

        verify(exactly = 0) { LogtoAuthManager.handleUserCancel() }
        verify(exactly = 0) { LogtoAuthManager.handleCallbackUri(any()) }
        verify(exactly = 0) { LogtoAuthManager.handleInvalidCallbackUri(any()) }
    }

    @Test
    fun `exact redirect with wrong state preserves session for authentic callback`() {
        val stale = Uri.parse("io.logto://callback?code=stale&state=old")
        val authentic = Uri.parse("io.logto://callback?code=current&state=current")
        every { LogtoAuthManager.isLogtoAuthResult(any()) } returns true
        every { LogtoAuthManager.isAuthenticOidcState(authentic) } returns true
        val savedState = Bundle().apply {
            putBoolean("STATE_LAUNCHED", true)
        }
        val controller = Robolectric.buildActivity(LogtoWebViewAuthActivity::class.java, Intent())
        controller.create(savedState).start().resume().pause()
        controller.newIntent(Intent(Intent.ACTION_VIEW, stale)).resume()

        assertThat(controller.get().isFinishing).isFalse()
        verify(exactly = 0) { LogtoAuthManager.handleCallbackUri(any()) }
        verify(exactly = 0) { LogtoAuthManager.handleInvalidCallbackUri(any()) }
        verify(exactly = 0) { LogtoAuthManager.handleUserCancel() }

        controller.pause().newIntent(Intent(Intent.ACTION_VIEW, authentic)).resume()
        assertThat(controller.get().isFinishing).isTrue()
        verify(exactly = 1) { LogtoAuthManager.handleCallbackUri(authentic) }
        verify(exactly = 0) { LogtoAuthManager.handleUserCancel() }
        controller.pause().stop().destroy()
    }

    @Test
    fun `exact redirect with missing state still allows later browser cancellation`() {
        every { LogtoAuthManager.isLogtoAuthResult(any()) } returns true
        val savedState = Bundle().apply { putBoolean("STATE_LAUNCHED", true) }
        val controller = Robolectric.buildActivity(LogtoWebViewAuthActivity::class.java, Intent())
        controller.create(savedState).start().resume().pause()
        controller.newIntent(Intent(Intent.ACTION_VIEW, Uri.parse("io.logto://callback?code=fake"))).resume()
        verify(exactly = 0) { LogtoAuthManager.handleCallbackUri(any()) }
        verify(exactly = 0) { LogtoAuthManager.handleUserCancel() }

        controller.pause().resume()
        verify(exactly = 1) { LogtoAuthManager.handleUserCancel() }
        assertThat(controller.get().isFinishing).isTrue()
        controller.pause().stop().destroy()
        verify(exactly = 1) { LogtoAuthManager.handleUserCancel() }
    }

    @Test
    fun `OS recreation during in-flight session does not mask a genuine cancel`() {
        // Regression: a previous revision set `skipNextResumeCancel`
        // unconditionally inside `handleIntent`'s Case 2, including
        // when the path was reached from `onCreate` after the OS had
        // recreated the activity (memory pressure, or a config change
        // not absorbed by `android:configChanges`). The original
        // launch intent has no data, so Case 2 fires — and the next
        // `onResume` (the user's first chance to surface a
        // browser-dismiss cancellation after recreation) was silently
        // swallowed, leaving the SDK with a stuck session waiting on
        // a callback that would never come. The fix scopes the flag
        // to hot deliveries only; cold/onCreate re-entry must let the
        // genuine cancel through.
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val originalLaunchIntent = Intent().apply {
            component = ComponentName(context, LogtoWebViewAuthActivity::class.java)
            // No data — the system preserves the original launch
            // intent across recreation, and that intent never carried
            // data (only `EXTRA_URI` + `EXTRA_LAUNCH_TOKEN`, both
            // already consumed pre-recreation).
        }
        // Saved state captures the in-flight moment: Custom Tabs has
        // been launched and onPause has fired (`inflight=true`).
        val savedState = Bundle().apply {
            putBoolean("STATE_LAUNCHED", true)
            putBoolean("STATE_INFLIGHT", true)
            putBoolean("STATE_COMPLETED", false)
        }

        Robolectric.buildActivity(LogtoWebViewAuthActivity::class.java, originalLaunchIntent)
            .create(savedState)
            .start()
            .resume()

        verify { LogtoAuthManager.handleUserCancel() }
    }
}
