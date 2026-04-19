package io.logto.sdk.android.auth.logto

import android.net.Uri
import com.google.common.truth.Truth.assertThat
import io.logto.sdk.android.type.SignInOptions
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LogtoAuthManagerTest {

    @After
    fun tearDown() {
        LogtoAuthManager.logtoAuthSession = null
    }

    @Test
    fun `handleAuthStart should cache current logto auth session`() {
        val mockLogtoAuthSession: LogtoAuthSession = mockk()
        LogtoAuthManager.handleAuthStart(mockLogtoAuthSession)
        assertThat(LogtoAuthManager.logtoAuthSession).isEqualTo(mockLogtoAuthSession)
    }

    @Test
    fun `handleCallbackUri should invoke the handleCallbackUri method in the session and clear the session cache`() {
        val mockLogtoAuthSession: LogtoAuthSession = mockk()
        every { mockLogtoAuthSession.handleCallbackUri(any()) } just Runs
        val mockCallbackUri: Uri = mockk()

        LogtoAuthManager.handleAuthStart(mockLogtoAuthSession)
        LogtoAuthManager.handleCallbackUri(mockCallbackUri)

        verify {
            mockLogtoAuthSession.handleCallbackUri(any())
        }
        assertThat(LogtoAuthManager.logtoAuthSession).isNull()
    }

    @Test
    fun `handleUserCancel should invoke the handleUserCancel method in the session and clear session cache`() {
        val mockLogtoAuthSession: LogtoAuthSession = mockk()
        every { mockLogtoAuthSession.handleUserCancel() } just Runs

        LogtoAuthManager.handleAuthStart(mockLogtoAuthSession)
        LogtoAuthManager.handleUserCancel()

        verify {
            mockLogtoAuthSession.handleUserCancel()
        }
        assertThat(LogtoAuthManager.logtoAuthSession).isNull()
    }

    @Test
    fun `isLogtoAuthResult should return expected result with valid or invalid callback URI`() {
        val redirectUri = "localhost:3001/callback"
        val matchedCallbackUri = Uri.parse(redirectUri)
        val mismatchedCallbackUri = Uri.parse("logto.dev/callback")

        val logtoAuthSession = LogtoAuthSession(
            mockk(),
            mockk(),
            mockk(),
            SignInOptions(redirectUri = redirectUri),
            mockk()
        )

        LogtoAuthManager.handleAuthStart(logtoAuthSession)
        assertThat(LogtoAuthManager.isLogtoAuthResult(matchedCallbackUri)).isTrue()
        assertThat(LogtoAuthManager.isLogtoAuthResult(mismatchedCallbackUri)).isFalse()
    }

    @Test
    fun `isLogtoAuthResult should return false if no session is provided`() {
        assertThat(LogtoAuthManager.logtoAuthSession).isNull()
        assertThat(LogtoAuthManager.isLogtoAuthResult(mockk())).isFalse()
    }

    @Test
    fun `isLogtoAuthResult should reject prefix-collision host`() {
        val redirectUri = "io.logto://callback"
        val logtoAuthSession = LogtoAuthSession(
            mockk(),
            mockk(),
            mockk(),
            SignInOptions(redirectUri = redirectUri),
            mockk()
        )

        LogtoAuthManager.handleAuthStart(logtoAuthSession)
        // Same scheme, but host "callbackevil" shares a textual prefix with
        // "callback". The old `startsWith` check would have returned true.
        val impostor = Uri.parse("io.logto://callbackevil?code=stolen")
        assertThat(LogtoAuthManager.isLogtoAuthResult(impostor)).isFalse()
    }

    @Test
    fun `isLogtoAuthResult should reject prefix-collision path`() {
        val redirectUri = "io.logto://callback/auth"
        val logtoAuthSession = LogtoAuthSession(
            mockk(),
            mockk(),
            mockk(),
            SignInOptions(redirectUri = redirectUri),
            mockk()
        )

        LogtoAuthManager.handleAuthStart(logtoAuthSession)
        val impostor = Uri.parse("io.logto://callback/authextra?code=stolen")
        assertThat(LogtoAuthManager.isLogtoAuthResult(impostor)).isFalse()
    }

    @Test
    fun `isLogtoAuthResult should accept an exact scheme-host-path match`() {
        val redirectUri = "io.logto://callback/auth"
        val logtoAuthSession = LogtoAuthSession(
            mockk(),
            mockk(),
            mockk(),
            SignInOptions(redirectUri = redirectUri),
            mockk()
        )

        LogtoAuthManager.handleAuthStart(logtoAuthSession)
        val valid = Uri.parse("io.logto://callback/auth?code=ok&state=xyz")
        assertThat(LogtoAuthManager.isLogtoAuthResult(valid)).isTrue()
    }

    @Test
    fun `isLogtoAuthResult should be case-insensitive on scheme`() {
        val redirectUri = "io.logto://callback"
        val logtoAuthSession = LogtoAuthSession(
            mockk(),
            mockk(),
            mockk(),
            SignInOptions(redirectUri = redirectUri),
            mockk()
        )

        LogtoAuthManager.handleAuthStart(logtoAuthSession)
        val valid = Uri.parse("IO.LOGTO://callback?code=ok")
        assertThat(LogtoAuthManager.isLogtoAuthResult(valid)).isTrue()
    }

    @Test
    fun `isLogtoAuthResult should be case-insensitive on host`() {
        // RFC 3986 §3.2.2: the host component is case-insensitive.
        // Android's `Uri` does not normalize host case, so we must
        // compare with `ignoreCase = true` to avoid rejecting an
        // otherwise valid callback.
        val redirectUri = "io.logto://Callback"
        val logtoAuthSession = LogtoAuthSession(
            mockk(),
            mockk(),
            mockk(),
            SignInOptions(redirectUri = redirectUri),
            mockk()
        )

        LogtoAuthManager.handleAuthStart(logtoAuthSession)
        val valid = Uri.parse("io.logto://CALLBACK?code=ok")
        assertThat(LogtoAuthManager.isLogtoAuthResult(valid)).isTrue()
    }

    @Test
    fun `isAuthenticOidcState should delegate to the session's state matcher`() {
        val mockSession: LogtoAuthSession = mockk()
        val matchingUri: Uri = mockk()
        val mismatchedUri: Uri = mockk()
        every { mockSession.matchesState(matchingUri) } returns true
        every { mockSession.matchesState(mismatchedUri) } returns false

        LogtoAuthManager.handleAuthStart(mockSession)

        assertThat(LogtoAuthManager.isAuthenticOidcState(matchingUri)).isTrue()
        assertThat(LogtoAuthManager.isAuthenticOidcState(mismatchedUri)).isFalse()
        // Authenticity check is read-only — must not clear the cached
        // session, otherwise an attacker's spurious data-intent could
        // race the legitimate callback by silently nulling the pointer.
        assertThat(LogtoAuthManager.logtoAuthSession).isEqualTo(mockSession)
    }

    @Test
    fun `isAuthenticOidcState should return false when no session is active`() {
        assertThat(LogtoAuthManager.logtoAuthSession).isNull()
        assertThat(LogtoAuthManager.isAuthenticOidcState(mockk())).isFalse()
    }

    @Test
    fun `handleInvalidCallbackUri should invoke session handler and clear cache`() {
        val mockLogtoAuthSession: LogtoAuthSession = mockk()
        every { mockLogtoAuthSession.handleInvalidCallbackUri(any()) } just Runs
        val mockCallbackUri: Uri = mockk()

        LogtoAuthManager.handleAuthStart(mockLogtoAuthSession)
        LogtoAuthManager.handleInvalidCallbackUri(mockCallbackUri)

        verify {
            mockLogtoAuthSession.handleInvalidCallbackUri(mockCallbackUri)
        }
        assertThat(LogtoAuthManager.logtoAuthSession).isNull()
    }
}
