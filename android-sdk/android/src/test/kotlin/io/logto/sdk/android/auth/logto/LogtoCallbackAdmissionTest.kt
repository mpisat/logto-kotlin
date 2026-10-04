package io.logto.sdk.android.auth.logto

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.logto.sdk.android.exception.LogtoException
import io.logto.sdk.android.type.LogtoConfig
import io.logto.sdk.android.type.SignInOptions
import io.logto.sdk.core.Core
import io.logto.sdk.core.http.HttpCompletion
import io.logto.sdk.core.type.CodeTokenResponse
import io.logto.sdk.core.type.GenerateSignInUriOptions
import io.logto.sdk.core.type.OidcConfigResponse
import io.mockk.Runs
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.verify
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class LogtoCallbackAdmissionTest {
    private val redirect = "io.logto.android://io.logto.sample/callback"
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val activity: Activity = mockk()
    private val results = mutableListOf<LogtoException?>()
    private lateinit var state: String
    private lateinit var session: LogtoAuthSession

    @Before
    fun setUp() {
        every { activity.packageName } returns "io.logto.sample"
        every { activity.startActivity(any()) } just Runs
        mockkObject(Core)
        every { Core.generateSignInUri(any()) } answers {
            state = firstArg<GenerateSignInUriOptions>().state
            "https://logto.example/oidc/auth"
        }
        every { Core.fetchTokenByAuthorizationCode(any(), any(), any(), any(), any(), any(), any()) } answers {
            lastArg<HttpCompletion<CodeTokenResponse>>().onComplete(null, mockk())
        }
        session = LogtoAuthSession(
            activity,
            LogtoConfig("https://logto.example", "app", usingPersistStorage = false),
            OidcConfigResponse(
                authorizationEndpoint = "https://logto.example/oidc/auth",
                tokenEndpoint = "https://logto.example/oidc/token",
                endSessionEndpoint = "https://logto.example/oidc/session/end",
                userinfoEndpoint = "https://logto.example/oidc/me",
                jwksUri = "https://logto.example/oidc/jwks",
                issuer = "https://logto.example/oidc",
                revocationEndpoint = "https://logto.example/oidc/revoke",
            ),
            SignInOptions(redirectUri = redirect),
        ) { exception, _ -> results.add(exception) }
        session.start()
    }

    @After
    fun tearDown() {
        clearAllMocks()
        LogtoAuthManager.browserSession = null
    }

    private fun receive(uri: String): Intent? {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(uri), context, LogtoRedirectReceiverActivity::class.java)
        val receiver = Robolectric.buildActivity(LogtoRedirectReceiverActivity::class.java, intent).create().get()
        assertThat(receiver.isFinishing).isTrue()
        return shadowOf(receiver).nextStartedActivity
    }

    private fun assertRejectedThenAuthenticCallback(uri: String) {
        assertThat(receive(uri)).isNull()
        assertThat(LogtoAuthManager.browserSession).isSameInstanceAs(session)
        assertThat(results).isEmpty()
        verify(exactly = 0) {
            Core.fetchTokenByAuthorizationCode(any(), any(), any(), any(), any(), any(), any())
        }
        val intent = requireNotNull(receive("$redirect?code=good&state=$state"))
        Robolectric.buildActivity(LogtoBrowserAuthActivity::class.java, intent).create().resume()
        assertThat(results).containsExactly(null)
        assertThat(LogtoAuthManager.browserSession).isNull()
        verify(exactly = 1) {
            Core.fetchTokenByAuthorizationCode(any(), any(), any(), any(), "good", any(), any())
        }
    }

    @Test
    fun `missing state preserves the pending sign-in`() {
        assertRejectedThenAuthenticCallback("$redirect?code=bad")
    }

    @Test
    fun `wrong state preserves the pending sign-in`() {
        assertRejectedThenAuthenticCallback("$redirect?code=bad&state=stale")
    }

    @Test
    fun `duplicate state preserves the pending sign-in`() {
        assertRejectedThenAuthenticCallback("$redirect?code=bad&state=$state&state=$state")
    }

    @Test
    fun `unrelated explicit callback cannot cancel sign-in`() {
        assertRejectedThenAuthenticCallback("io.logto.android://io.logto.sample.evil/callback?state=$state&code=bad")
    }

    @Test
    fun `duplicate code cannot consume the pending sign-in`() {
        assertRejectedThenAuthenticCallback("$redirect?code=bad&code=other&state=$state")
    }

    @Test
    fun `provider error without authentic state preserves sign-in`() {
        assertRejectedThenAuthenticCallback("$redirect?error=access_denied&state=stale")
    }

    @Test
    fun `fragment cannot consume the pending sign-in`() {
        assertRejectedThenAuthenticCallback("$redirect?code=bad&state=$state#fragment")
    }

    @Test
    fun `authentic provider error completes once without exchanging a code`() {
        val intent = requireNotNull(receive("$redirect?error=access_denied&state=$state"))
        Robolectric.buildActivity(LogtoBrowserAuthActivity::class.java, intent).create().resume()
        assertThat(results).hasSize(1)
        assertThat(results.single()?.message).isEqualTo(LogtoException.Type.INVALID_CALLBACK_URI.name)
        assertThat(results.single()?.cause?.message).isEqualTo("ERROR_FOUND_IN_URI")
        assertThat(LogtoAuthManager.browserSession).isNull()
        assertThat(receive("$redirect?error=access_denied&state=$state")).isNull()
        verify(exactly = 0) {
            Core.fetchTokenByAuthorizationCode(any(), any(), any(), any(), any(), any(), any())
        }
    }

    @Test
    fun `genuine cancellation completes once with USER_CANCELED`() {
        LogtoAuthManager.handleUserCancel()
        LogtoAuthManager.handleUserCancel()
        assertThat(results).hasSize(1)
        assertThat(results.single()?.message).isEqualTo(LogtoException.Type.USER_CANCELED.name)
        assertThat(LogtoAuthManager.browserSession).isNull()
    }
}
