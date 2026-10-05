package io.logto.sdk.android.auth.logto

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Looper
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import com.google.common.truth.Truth.assertThat
import io.logto.sdk.android.exception.LogtoException
import io.logto.sdk.android.type.LogtoConfig
import io.logto.sdk.android.type.SignInOptions
import io.logto.sdk.core.Core
import io.logto.sdk.core.type.GenerateSignInUriOptions
import io.logto.sdk.core.type.OidcConfigResponse
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController

@RunWith(RobolectricTestRunner::class)
class LogtoBrowserTerminationTest {
    private val caller = mockk<Activity>()
    private lateinit var launch: Intent
    private val launches = mutableListOf<Intent>()
    private lateinit var state: String
    private val results = mutableListOf<LogtoException.Type?>()
    private val config = OidcConfigResponse(
        "https://logto.example/auth", "https://logto.example/token", "https://logto.example/end",
        "https://logto.example/me", "https://logto.example/jwks", "https://logto.example", "https://logto.example/revoke",
    )

    @Before
    fun setUp() {
        every { caller.packageName } returns "io.logto.sample"
        every { caller.runOnUiThread(any()) } answers { firstArg<Runnable>().run() }
        every { caller.startActivity(any()) } answers { launch = firstArg(); launches.add(launch) }
        mockkObject(Core)
        every { Core.generateSignInUri(any()) } answers {
            state = firstArg<GenerateSignInUriOptions>().state
            "https://logto.example/auth?state=$state"
        }
    }

    @After
    fun tearDown() {
        LogtoAuthManager.browserSession = null
        unmockkAll()
    }

    private fun start(onComplete: () -> Unit = {}): LogtoAuthSession = LogtoAuthSession(
        caller, LogtoConfig("https://logto.example", "app", usingPersistStorage = false), config,
        SignInOptions("io.logto.android://io.logto.sample/callback"),
    ) { exception, _ ->
        results.add(exception?.message?.let(LogtoException.Type::valueOf))
        onComplete()
    }.also { it.start() }

    private fun activity(intent: Intent = launch, savedState: Bundle? = null) =
        Robolectric.buildActivity(LogtoBrowserAuthActivity::class.java, intent).create(savedState).start()

    private fun destroy(controller: ActivityController<LogtoBrowserAuthActivity>) {
        controller.pause().stop()
        controller.get().finish()
        assertThat(controller.get().isFinishing).isTrue()
        controller.destroy()
    }

    @Test
    fun `current worker launch reaches Android only when main dispatch runs`() {
        start()
        val id = requireNotNull(launch.getStringExtra("EXTRA_ATTEMPT_ID"))
        val realCaller = Robolectric.buildActivity(Activity::class.java).create().start().resume().get()
        val worker = thread {
            LogtoBrowserAuthActivity.launch(realCaller, "https://logto.example/current", id)
        }
        worker.join(5000)
        assertThat(worker.isAlive).isFalse()
        assertThat(shadowOf(realCaller).nextStartedActivity).isNull()
        shadowOf(Looper.getMainLooper()).idle()
        val submitted = shadowOf(realCaller).nextStartedActivity
        assertThat(submitted.getStringExtra("EXTRA_ATTEMPT_ID")).isEqualTo(id)
        assertThat(submitted.getStringExtra("EXTRA_AUTH_URI")).isEqualTo("https://logto.example/current")
    }

    @Test
    fun `queued worker launch cannot start an attempt replaced before main dispatch`() {
        start()
        val oldId = requireNotNull(launch.getStringExtra("EXTRA_ATTEMPT_ID"))
        val realCaller = Robolectric.buildActivity(Activity::class.java).create().start().resume().get()
        val workerError = AtomicReference<Throwable?>()
        val worker = thread {
            try {
                LogtoBrowserAuthActivity.launch(realCaller, "https://logto.example/old", oldId)
            } catch (error: Throwable) {
                workerError.set(error)
            }
        }
        worker.join(5000)
        assertThat(worker.isAlive).isFalse()
        assertThat(workerError.get()).isNull()
        val replacement = start()
        shadowOf(Looper.getMainLooper()).idle()
        assertThat(shadowOf(realCaller).nextStartedActivity).isNull()
        assertThat(LogtoAuthManager.browserSession).isSameInstanceAs(replacement)
        assertThat(results).isEmpty()
    }

    @Test
    fun `permanent destruction cancels the launched owned session exactly once`() {
        val session = start()
        val controller = activity().resume()
        assertThat(shadowOf(controller.get()).nextStartedActivity.action).isEqualTo(Intent.ACTION_VIEW)
        assertThat(LogtoAuthManager.browserSession).isSameInstanceAs(session)
        destroy(controller)
        assertThat(results).containsExactly(LogtoException.Type.USER_CANCELED)
        assertThat(LogtoAuthManager.browserSession).isNull()
    }

    @Test
    fun `ordinary nonfinishing destruction retains the owned session`() {
        val session = start()
        val controller = activity().resume()
        controller.pause().stop().destroy()
        assertThat(results).isEmpty()
        assertThat(LogtoAuthManager.browserSession).isSameInstanceAs(session)
    }

    @Test
    fun `configuration destruction retains ownership until recreated owner finishes`() {
        val session = start()
        val controller = activity().resume()
        val saved = Bundle()
        controller.pause().saveInstanceState(saved).stop()
        org.robolectric.util.ReflectionHelpers.setField(controller.get(), "mChangingConfigurations", true)
        assertThat(controller.get().isChangingConfigurations).isTrue()
        controller.get().finish()
        controller.destroy()
        assertThat(results).isEmpty()
        assertThat(LogtoAuthManager.browserSession).isSameInstanceAs(session)
        val restored = activity(savedState = saved)
        restored.get().finish()
        restored.stop().destroy()
        assertThat(results).containsExactly(LogtoException.Type.USER_CANCELED)
    }

    @Test
    fun `admitted callback followed by destruction completes only once`() {
        start()
        val controller = activity().resume()
        val callback = Uri.parse("io.logto.android://io.logto.sample/callback?error=access_denied&state=$state")
        controller.pause().newIntent(LogtoBrowserAuthActivity.createRedirectHandlingIntent(caller, callback)).resume()
        destroy(controller)
        assertThat(results).containsExactly(LogtoException.Type.INVALID_CALLBACK_URI)
        assertThat(LogtoAuthManager.browserSession).isNull()
    }

    @Test
    fun `rejected callback followed by destruction neither consumes nor cancels session`() {
        val session = start()
        val controller = activity().resume()
        val callback = Uri.parse("io.logto.android://io.logto.sample/callback?code=stale&state=stale")
        controller.pause().newIntent(LogtoBrowserAuthActivity.createRedirectHandlingIntent(caller, callback)).resume()
        destroy(controller)
        assertThat(results).isEmpty()
        assertThat(LogtoAuthManager.browserSession).isSameInstanceAs(session)
    }

    @Test
    fun `old activity destruction and saved recreation cannot cancel replacement`() {
        start()
        val oldIntent = launch
        val controller = activity().resume()
        val saved = Bundle()
        controller.saveInstanceState(saved)
        val replacement = start()
        destroy(controller)
        val restored = activity(oldIntent, saved)
        restored.get().finish()
        restored.stop().destroy()
        assertThat(results).isEmpty()
        assertThat(LogtoAuthManager.browserSession).isSameInstanceAs(replacement)
    }

    @Test
    fun `reentrant cancellation completion retains replacement after old destruction`() {
        lateinit var replacement: LogtoAuthSession
        start { replacement = start() }
        val controller = activity().resume()
        controller.pause().resume()
        destroy(controller)
        assertThat(results).containsExactly(LogtoException.Type.USER_CANCELED)
        assertThat(LogtoAuthManager.browserSession).isSameInstanceAs(replacement)
    }

    @Test
    fun `reentrant admitted callback retains replacement after old destruction`() {
        lateinit var replacement: LogtoAuthSession
        start { replacement = start() }
        val controller = activity().resume()
        val callback = Uri.parse("io.logto.android://io.logto.sample/callback?error=access_denied&state=$state")
        controller.pause().newIntent(LogtoBrowserAuthActivity.createRedirectHandlingIntent(caller, callback)).resume()
        destroy(controller)
        assertThat(results).containsExactly(LogtoException.Type.INVALID_CALLBACK_URI)
        assertThat(LogtoAuthManager.browserSession).isSameInstanceAs(replacement)
    }

    @Test
    fun `old saved activity resuming without callback cannot cancel replacement`() {
        start()
        val oldIntent = launch
        val controller = activity().resume()
        val saved = Bundle()
        controller.pause().saveInstanceState(saved).stop().destroy()
        val replacement = start()
        val restored = activity(oldIntent, saved).resume()
        destroy(restored)
        assertThat(results).isEmpty()
        assertThat(LogtoAuthManager.browserSession).isSameInstanceAs(replacement)
    }

    @Test
    fun `replaced session cannot submit its delayed launch`() {
        lateinit var replacement: LogtoAuthSession
        var replace = true
        every { Core.generateSignInUri(any()) } answers {
            if (replace) {
                replace = false
                replacement = start()
            }
            "https://logto.example/auth"
        }
        start()
        assertThat(launches).hasSize(1)
        assertThat(launch.getStringExtra("EXTRA_ATTEMPT_ID")).isEqualTo(LogtoAuthManager.browserAttemptId)
        assertThat(results).isEmpty()
        assertThat(LogtoAuthManager.browserSession).isSameInstanceAs(replacement)
    }

    @Test
    fun `delivered stale launch retains current owner for terminal task return`() {
        start()
        val oldLaunch = launch
        start()
        val controller = activity().resume()
        shadowOf(controller.get()).nextStartedActivity
        controller.pause().newIntent(oldLaunch).resume()
        // singleTask may already have cleared the browser before delivering this intent.
        assertThat(results).containsExactly(LogtoException.Type.USER_CANCELED)
        assertThat(shadowOf(controller.get()).nextStartedActivity).isNull()
        destroy(controller)
        assertThat(results).containsExactly(LogtoException.Type.USER_CANCELED)
        assertThat(LogtoAuthManager.browserSession).isNull()
    }

    @Test
    fun `trusted new launch delivered to existing activity binds replacement owner`() {
        start()
        val controller = activity().resume()
        start()
        controller.pause().newIntent(launch).resume()
        assertThat(results).isEmpty()
        destroy(controller)
        assertThat(results).containsExactly(LogtoException.Type.USER_CANCELED)
        assertThat(LogtoAuthManager.browserSession).isNull()
    }

    @Test
    fun `permanent logout destruction completes best effort logout once`() {
        var completions = 0
        LogtoSignOutSession(caller, "https://logto.example/end", null) { exception ->
            assertThat(exception).isNull()
            completions++
        }.start()
        destroy(activity().resume())
        assertThat(completions).isEqualTo(1)
        assertThat(LogtoAuthManager.browserSession).isNull()
    }
}
