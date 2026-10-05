package io.logto.sdk.android.auth.logto

import android.net.Uri
import com.google.common.truth.Truth.assertThat
import io.logto.sdk.android.exception.LogtoException
import io.logto.sdk.android.type.SignInOptions
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LogtoAuthManagerTest {

    @After
    fun tearDown() {
        LogtoAuthManager.browserSession = null
    }

    @Test
    fun `owned cancellation and failure racing complete only once`() {
        val session: LogtoBrowserSession = mockk(relaxed = true)
        val completions = AtomicInteger()
        every { session.handleUserCancel() } answers { completions.incrementAndGet(); Unit }
        every { session.handleFailure(any()) } answers { completions.incrementAndGet(); Unit }
        val id = LogtoAuthManager.handleAuthStart(session)
        val release = CountDownLatch(1)
        val workers = listOf(
            thread { check(release.await(5, TimeUnit.SECONDS)); LogtoAuthManager.cancelOwnedAttempt(id) },
            thread {
                check(release.await(5, TimeUnit.SECONDS))
                LogtoAuthManager.failOwnedAttempt(id, LogtoException(LogtoException.Type.UNABLE_TO_LAUNCH_BROWSER))
            },
        )
        release.countDown()
        workers.forEach { it.join(5000); assertThat(it.isAlive).isFalse() }
        assertThat(completions.get()).isEqualTo(1)
        assertThat(LogtoAuthManager.browserSession).isNull()
    }

    @Test
    fun `owned cancellation completion permits replacement on another thread`() {
        val session: LogtoBrowserSession = mockk()
        val replacement: LogtoBrowserSession = mockk()
        every { session.handleUserCancel() } answers {
            val worker = thread { LogtoAuthManager.handleAuthStart(replacement) }
            worker.join(5000)
            assertThat(worker.isAlive).isFalse()
        }
        val id = LogtoAuthManager.handleAuthStart(session)
        LogtoAuthManager.cancelOwnedAttempt(id)
        assertThat(LogtoAuthManager.browserSession).isSameInstanceAs(replacement)
    }

    @Test
    fun `replacement during callback admission cannot be detached by the old callback`() {
        val session: LogtoBrowserSession = mockk(relaxed = true)
        val replacement: LogtoBrowserSession = mockk(relaxed = true)
        val validating = CountDownLatch(1)
        val release = CountDownLatch(1)
        val workerError = AtomicReference<Throwable?>()
        every { session.acceptsCallbackUri(any()) } answers {
            validating.countDown()
            check(release.await(5, TimeUnit.SECONDS))
            true
        }
        LogtoAuthManager.handleAuthStart(session)
        val worker = thread {
            try {
                LogtoAuthManager.handleCallbackUri(Uri.parse("io.logto.android://io.logto.sample/callback"))
            } catch (error: Throwable) {
                workerError.set(error)
            }
        }
        try {
            assertThat(validating.await(5, TimeUnit.SECONDS)).isTrue()
            LogtoAuthManager.handleAuthStart(replacement)
        } finally {
            release.countDown()
            worker.join(5000)
        }
        assertThat(worker.isAlive).isFalse()
        assertThat(workerError.get()).isNull()
        assertThat(LogtoAuthManager.browserSession).isSameInstanceAs(replacement)
        verify(exactly = 0) { session.handleCallbackUri(any()) }
        verify(exactly = 0) { replacement.handleCallbackUri(any()) }
    }

    @Test
    fun `callback completion cannot clear a newly started session`() {
        val session: LogtoBrowserSession = mockk()
        val replacement: LogtoBrowserSession = mockk()
        every { session.acceptsCallbackUri(any()) } returns true
        every { session.handleCallbackUri(any()) } answers {
            LogtoAuthManager.handleAuthStart(replacement)
        }
        LogtoAuthManager.handleAuthStart(session)
        LogtoAuthManager.handleCallbackUri(Uri.parse("io.logto.android://io.logto.sample/callback"))
        assertThat(LogtoAuthManager.browserSession).isSameInstanceAs(replacement)
    }

    @Test
    fun `cancellation completion cannot clear a newly started session`() {
        val session: LogtoBrowserSession = mockk()
        val replacement: LogtoBrowserSession = mockk()
        every { session.handleUserCancel() } answers {
            LogtoAuthManager.handleAuthStart(replacement)
        }
        LogtoAuthManager.handleAuthStart(session)
        LogtoAuthManager.handleUserCancel()
        assertThat(LogtoAuthManager.browserSession).isSameInstanceAs(replacement)
    }

    @Test
    fun `failure completion cannot clear a newly started session`() {
        val session: LogtoBrowserSession = mockk()
        val replacement: LogtoBrowserSession = mockk()
        every { session.handleFailure(any()) } answers {
            LogtoAuthManager.handleAuthStart(replacement)
        }
        LogtoAuthManager.handleAuthStart(session)
        LogtoAuthManager.handleFailure(LogtoException(LogtoException.Type.UNABLE_TO_LAUNCH_BROWSER))
        assertThat(LogtoAuthManager.browserSession).isSameInstanceAs(replacement)
    }

    @Test
    fun `handleAuthStart should cache current browser session`() {
        val mockBrowserSession: LogtoBrowserSession = mockk()
        LogtoAuthManager.handleAuthStart(mockBrowserSession)
        assertThat(LogtoAuthManager.browserSession).isEqualTo(mockBrowserSession)
    }

    @Test
    fun `handleCallbackUri should invoke the handleCallbackUri method in the session and clear the session cache`() {
        val mockBrowserSession: LogtoBrowserSession = mockk()
        every { mockBrowserSession.acceptsCallbackUri(any()) } returns true
        every { mockBrowserSession.handleCallbackUri(any()) } just Runs
        val mockCallbackUri: Uri = mockk()

        LogtoAuthManager.handleAuthStart(mockBrowserSession)
        LogtoAuthManager.handleCallbackUri(mockCallbackUri)

        verify {
            mockBrowserSession.handleCallbackUri(any())
        }
        assertThat(LogtoAuthManager.browserSession).isNull()
    }

    @Test
    fun `handleUserCancel should invoke the handleUserCancel method in the session and clear session cache`() {
        val mockBrowserSession: LogtoBrowserSession = mockk()
        every { mockBrowserSession.handleUserCancel() } just Runs

        LogtoAuthManager.handleAuthStart(mockBrowserSession)
        LogtoAuthManager.handleUserCancel()

        verify {
            mockBrowserSession.handleUserCancel()
        }
        assertThat(LogtoAuthManager.browserSession).isNull()
    }

    @Test
    fun `handleFailure should invoke the handleFailure method in the session and clear session cache`() {
        val mockBrowserSession: LogtoBrowserSession = mockk()
        every { mockBrowserSession.handleFailure(any()) } just Runs

        LogtoAuthManager.handleAuthStart(mockBrowserSession)
        LogtoAuthManager.handleFailure(LogtoException(LogtoException.Type.UNABLE_TO_LAUNCH_BROWSER))

        verify {
            mockBrowserSession.handleFailure(any())
        }
        assertThat(LogtoAuthManager.browserSession).isNull()
    }

    @Test
    fun `isLogtoAuthResult should return expected result with valid or invalid callback URI`() {
        val redirectUri = "io.logto.android://io.logto.sample/callback"
        val matchedCallbackUri = Uri.parse("$redirectUri?state=state&code=code")
        val mismatchedCallbackUri = Uri.parse("io.logto.android://io.logto.sample/another-callback")

        val logtoAuthSession = LogtoAuthSession(
            mockk(),
            mockk(),
            mockk(),
            SignInOptions(redirectUri = redirectUri),
            mockk(),
        )

        LogtoAuthManager.handleAuthStart(logtoAuthSession)
        assertThat(LogtoAuthManager.isLogtoAuthResult(matchedCallbackUri)).isTrue()
        assertThat(LogtoAuthManager.isLogtoAuthResult(mismatchedCallbackUri)).isFalse()
    }

    @Test
    fun `isLogtoAuthResult should return false when the session expects no redirect`() {
        val mockBrowserSession: LogtoBrowserSession = mockk()
        every { mockBrowserSession.redirectUri } returns null

        LogtoAuthManager.handleAuthStart(mockBrowserSession)

        assertThat(
            LogtoAuthManager.isLogtoAuthResult(Uri.parse("io.logto.android://io.logto.sample/callback")),
        ).isFalse()
    }

    @Test
    fun `isLogtoAuthResult should return false when callback path is only a prefix match`() {
        val redirectUri = "io.logto.android://io.logto.sample/callback"
        val callbackUri = Uri.parse("io.logto.android://io.logto.sample/callback2?state=state&code=code")

        val logtoAuthSession = LogtoAuthSession(
            mockk(),
            mockk(),
            mockk(),
            SignInOptions(redirectUri = redirectUri),
            mockk(),
        )

        LogtoAuthManager.handleAuthStart(logtoAuthSession)
        assertThat(LogtoAuthManager.isLogtoAuthResult(callbackUri)).isFalse()
    }

    @Test
    fun `isLogtoAuthResult should match redirect URI query parameters`() {
        val redirectUri = "io.logto.android://io.logto.sample/callback?connector_id=foo"
        val matchedCallbackUri = Uri.parse("$redirectUri&state=state&code=code")
        val mismatchedCallbackUri = Uri.parse(
            "io.logto.android://io.logto.sample/callback?connector_id=bar&state=state&code=code",
        )

        val logtoAuthSession = LogtoAuthSession(
            mockk(),
            mockk(),
            mockk(),
            SignInOptions(redirectUri = redirectUri),
            mockk(),
        )

        LogtoAuthManager.handleAuthStart(logtoAuthSession)
        assertThat(LogtoAuthManager.isLogtoAuthResult(matchedCallbackUri)).isTrue()
        assertThat(LogtoAuthManager.isLogtoAuthResult(mismatchedCallbackUri)).isFalse()
    }

    @Test
    fun `isLogtoAuthResult should normalize URI scheme host and path`() {
        val redirectUri = "io.logto.android://IO.LOGTO.SAMPLE/callback%7E"
        val callbackUri = Uri.parse("IO.LOGTO.ANDROID://io.logto.sample/callback~?state=state&code=code")

        val logtoAuthSession = LogtoAuthSession(
            mockk(),
            mockk(),
            mockk(),
            SignInOptions(redirectUri = redirectUri),
            mockk(),
        )

        LogtoAuthManager.handleAuthStart(logtoAuthSession)
        assertThat(LogtoAuthManager.isLogtoAuthResult(callbackUri)).isTrue()
    }

    @Test
    fun `isLogtoAuthResult should keep user info case-sensitive when normalizing authority`() {
        val redirectUri = "io.logto.android://User@IO.LOGTO.SAMPLE/callback"
        val callbackUri = Uri.parse("io.logto.android://user@io.logto.sample/callback?state=state&code=code")

        val logtoAuthSession = LogtoAuthSession(
            mockk(),
            mockk(),
            mockk(),
            SignInOptions(redirectUri = redirectUri),
            mockk(),
        )

        LogtoAuthManager.handleAuthStart(logtoAuthSession)
        assertThat(LogtoAuthManager.isLogtoAuthResult(callbackUri)).isFalse()
    }

    @Test
    fun `isLogtoAuthResult should return false if callback or redirect URI contains fragment`() {
        val redirectUri = "io.logto.android://io.logto.sample/callback"
        val callbackUriWithFragment = Uri.parse("$redirectUri?state=state&code=code#fragment")

        val logtoAuthSession = LogtoAuthSession(
            mockk(),
            mockk(),
            mockk(),
            SignInOptions(redirectUri = redirectUri),
            mockk(),
        )

        LogtoAuthManager.handleAuthStart(logtoAuthSession)
        assertThat(LogtoAuthManager.isLogtoAuthResult(callbackUriWithFragment)).isFalse()

        val redirectUriWithFragment = "$redirectUri#fragment"
        val fragmentLogtoAuthSession = LogtoAuthSession(
            mockk(),
            mockk(),
            mockk(),
            SignInOptions(redirectUri = redirectUriWithFragment),
            mockk(),
        )

        LogtoAuthManager.handleAuthStart(fragmentLogtoAuthSession)
        val callbackUri = Uri.parse("$redirectUri?state=state&code=code")
        assertThat(LogtoAuthManager.isLogtoAuthResult(callbackUri)).isFalse()
    }

    @Test
    fun `isLogtoAuthResult should return false if no session is provided`() {
        assertThat(LogtoAuthManager.browserSession).isNull()
        assertThat(LogtoAuthManager.isLogtoAuthResult(mockk())).isFalse()
    }
}
