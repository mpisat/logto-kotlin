package io.logto.sdk.android.auth.logto

import android.app.Activity
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.net.Uri
import com.google.common.truth.Truth.assertThat
import io.logto.sdk.android.completion.Completion
import io.logto.sdk.android.exception.LogtoException
import io.logto.sdk.android.type.LogtoConfig
import io.logto.sdk.android.type.SignInOptions
import io.logto.sdk.core.Core
import io.logto.sdk.core.http.HttpCompletion
import io.logto.sdk.core.type.CodeTokenResponse
import io.logto.sdk.core.type.GenerateSignInUriOptions
import io.logto.sdk.core.type.OidcConfigResponse
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.slot
import io.mockk.unmockkObject
import io.mockk.verify
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController

/**
 * Drives a callback through the real trampoline activity, the real
 * [LogtoAuthManager] and the real [LogtoAuthSession]. Only the network
 * (`Core`) and the host activity that starts the session are stubbed.
 *
 * The trampoline is exported, so any app can deliver an arbitrary
 * `data` URI to the running instance. An opaque URI such as `mailto:x`
 * has no query component, and `Uri.getQueryParameter` throws
 * `UnsupportedOperationException` on it (FLUT-268).
 */
@RunWith(RobolectricTestRunner::class)
class LogtoAuthCallbackPathTest {

    private val redirectUri = "logto-callback://unused/callback"
    private val hostActivity: Activity = mockk()
    private val packageManager: PackageManager = mockk()
    private val completion: Completion<LogtoException, CodeTokenResponse> = mockk()
    private val tokenResponse: CodeTokenResponse = mockk()

    private lateinit var session: LogtoAuthSession
    private lateinit var sessionState: String
    private lateinit var controller: ActivityController<LogtoWebViewAuthActivity>

    @Before
    fun setUp() {
        every { hostActivity.packageName } returns "logto.test"
        every { hostActivity.packageManager } returns packageManager
        every { packageManager.queryIntentActivities(any(), any<Int>()) } returns listOf(
            ResolveInfo().apply {
                activityInfo = ActivityInfo().apply {
                    name = LogtoWebViewAuthActivity::class.java.name
                }
            },
        )
        val launchIntent = slot<Intent>()
        every { hostActivity.startActivity(capture(launchIntent)) } just Runs
        every { completion.onComplete(any(), any()) } just Runs

        mockkObject(Core)
        val signInOptions = slot<GenerateSignInUriOptions>()
        every { Core.generateSignInUri(capture(signInOptions)) } returns
            "https://logto.example/oidc/auth"
        every {
            Core.fetchTokenByAuthorizationCode(any(), any(), any(), any(), any(), any(), any())
        } answers {
            lastArg<HttpCompletion<CodeTokenResponse>>().onComplete(null, tokenResponse)
        }

        session = LogtoAuthSession(
            hostActivity,
            LogtoConfig("https://logto.example", "appId"),
            OidcConfigResponse(
                authorizationEndpoint = "https://logto.example/oidc/auth",
                tokenEndpoint = "https://logto.example/oidc/token",
                endSessionEndpoint = "https://logto.example/oidc/session/end",
                userinfoEndpoint = "https://logto.example/oidc/me",
                jwksUri = "https://logto.example/oidc/jwks",
                issuer = "https://logto.example/oidc",
                revocationEndpoint = "https://logto.example/oidc/token/revocation",
            ),
            SignInOptions(redirectUri = redirectUri),
            completion,
        )
        session.start()
        sessionState = signInOptions.captured.state

        // Launch the trampoline with the exact intent the session built,
        // so the launch-token path opens the browser and the activity
        // then waits behind it, as on a device.
        controller = Robolectric.buildActivity(LogtoWebViewAuthActivity::class.java, launchIntent.captured)
        controller.create().start().resume().pause()
    }

    @After
    fun tearDown() {
        LogtoAuthManager.logtoAuthSession = null
        unmockkObject(Core)
    }

    @Test
    fun `opaque data URI delivered to the waiting activity is ignored and keeps the session`() {
        for (opaque in listOf("mailto:x", "calido:foo")) {
            controller.newIntent(Intent(Intent.ACTION_VIEW, Uri.parse(opaque))).resume()

            assertThat(controller.get().isFinishing).isFalse()
            assertThat(LogtoAuthManager.logtoAuthSession).isSameInstanceAs(session)
            controller.pause()
        }
        verify(exactly = 0) { completion.onComplete(any(), any()) }
    }

    @Test
    fun `authentic callback still completes after an opaque data URI`() {
        controller.newIntent(Intent(Intent.ACTION_VIEW, Uri.parse("mailto:x"))).resume().pause()

        val authentic = Uri.parse("$redirectUri?code=authentic-code&state=$sessionState")
        controller.newIntent(Intent(Intent.ACTION_VIEW, authentic)).resume()

        assertThat(controller.get().isFinishing).isTrue()
        verify(exactly = 1) { completion.onComplete(any(), any()) }
        verify(exactly = 1) { completion.onComplete(null, tokenResponse) }
        verify(exactly = 1) {
            Core.fetchTokenByAuthorizationCode(
                any(),
                any(),
                redirectUri,
                any(),
                "authentic-code",
                any(),
                any(),
            )
        }
        assertThat(LogtoAuthManager.logtoAuthSession).isNull()
        controller.pause().stop().destroy()
        verify(exactly = 1) { completion.onComplete(any(), any()) }
    }
}
