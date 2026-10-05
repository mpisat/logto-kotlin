# Calido Kotlin 3 maintenance fork

Upstream base: `logto-io/kotlin` tag `v3.0.0`, commit `7fcf65a0c336847d50770f2a96fb7b36513076bc`.

Previous consumed fork: `28cff5ce7efe864cdfa49e3e511edcd5ba518745` on `native-browser`. This branch is `codex/native-browser-v3`; published baseline history is preserved.

## Retained corrections

- Resolve/cache JWKS before exchanging a rotating refresh token. Discovery/JWKS failure must not consume it. Preserve upstream credential stamps, captured refresh values, omitted-refresh-token fallback and guarded token adoption.
- Admit callbacks only after exact redirect routing, session-owned authentic OAuth state and core URI parsing. Missing, stale or duplicate state, duplicate query parameters and unrelated callbacks preserve the live sign-in; authentic provider errors and genuine dismissal complete it once. The release's exported receiver and non-exported browser launcher remain intact.
- Detach a completed manager session before invoking callbacks, so synchronous completion starting a replacement session cannot erase that replacement.

Calido continues to own encrypted Android persistence and native background authentication. The SDK uses `usingPersistStorage = false` in the host. The owner approved upstream's configurable 300-second ID-token issued-at and expiry tolerance; host direct verification must use the same value.

## Verification evidence

Tests run through Calido's source-hosted `logto-native-browser` module using arm64 Java 21, `--no-daemon`, one worker and in-process Kotlin compilation. This avoids assuming the release repository's Gradle 7.5 supports the current host JDK.

- Unmodified release hosted SDK suite: passed on 2026-10-04.
- JWKS regression against unmodified release: failed because `Core.fetchTokenByRefreshToken` ran before the JWKS failure.
- Patched client class: all 46 tests passed, including omitted-token and stale credential-generation tests. Existing asynchronous fixtures account for the new preflight before verification.
- Callback admission regressions: five expected failures against the release; manager replacement-session regressions: three expected failures before the dispatch correction. Authentic success/provider-error and genuine cancellation cases remain covered.
- Complete hosted SDK suite after both corrections: 115 tests, zero failures, errors or skips on 2026-10-04. Admission tests enter through the exported receiver and real browser activity/session path.
- Logs retained locally: `/private/tmp/calido-logto-kotlin-v3-baseline.log`, `/private/tmp/calido-logto-kotlin-v3-red.log`, `/private/tmp/calido-logto-kotlin-v3-jwks-green-2.log`.
- Additional logs: `/private/tmp/calido-logto-kotlin-v3-callback-red.log`, `/private/tmp/calido-logto-kotlin-v3-manager-red.log`, `/private/tmp/calido-logto-kotlin-v3-sdk-green.log`.

Command from the prepared isolated Calido worktree:

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
(cd android && ./gradlew --no-daemon --max-workers=1 \
  -Pkotlin.compiler.execution.strategy=in-process \
  logto-native-browser:testDebugUnitTest)
bash tool/ensure-no-project-gradle-processes.sh "$PWD"
```

Run cleanup even when Gradle fails. No physical-device acceptance or shipping host pin is claimed by these SDK results. Keep the previous Calido shipping pin until signed-candidate browser, storage, process recovery and background-call gates pass.

## Final verification continuation: 2026-10-05

The receiver/signature snapshot suite passed 141 tests, zero failures/errors/skips: 118 consumed Android SDK
cases and 23 signed core verifier cases executed against official Maven core 3.0.0. Additional
receiver-entry cases reject path suffixes, unexpected ports and user info, preserve the pending
attempt, then accept its authentic callback exactly once. The core test addition corrupts only
signature bytes while retaining valid claims/header/matching key and requires specifically
`SIGNATURE_INVALID`, rather than accepting any rejection. Production core remains unchanged.

The hosted harness stages `TokenUtilsTest.kt` and isolates test classes in separate JVMs because
upstream MockK teardown clears answers but leaves instrumentation installed. The cause-specific
core assertions passed independently and in the isolated combined suite. Final log:
`/private/tmp/calido-logto-kotlin-v3-signature-final.log`; Gradle cleanup passed.

GPT-6-astra reviewed the complete release-to-correction diff at `a24158a`, then separately reviewed
the final receiver and signature test additions. No verified actionable findings were returned.
These reviews and software passes do not close the physical acceptance gates above.

## Minified consumer verification: 2026-10-05

The first signed Calido release build failed in R8 on the missing optional
`org.slf4j.impl.StaticLoggerBinder`. Maven core 3.0.0 removed Logback, while jose4j
still brings SLF4J API 1.7.36. Inspection of that exact API JAR's `LoggerFactory`
confirmed its specific missing-binding catch selects the NOP fallback. Added only
that class's `-dontwarn` consumer rule; the existing Gson model keep rule remains
unchanged. No logger backend or broad missing-class suppression was added.

Red: `/private/tmp/calido-logto-kotlin-v3-apk.log`. Green:
`/private/tmp/calido-logto-kotlin-v3-apk-green.log`, running
`make android-apk ANDROID_BUILD_NUMBER=30004` in the isolated host. R8 and signed
production-ID APK packaging passed, with Dart, R8 and native symbols retained and
Gradle process cleanup confirmed. GPT-6-astra separately reviewed this narrow rule
and returned no actionable concerns. Packaging is not device/runtime acceptance.

## Completed controlled-state matrices: 2026-10-05

The expanded hosted suite passed 156 SDK/core tests, zero failures/errors/skips
(133 Android SDK plus 23 real core verifier cases). Log:
`/private/tmp/calido-logto-kotlin-matrix-final.log`; Gradle cleanup passed.

Nine additional refresh cases control discovery, pre-exchange JWKS, token response
and verification JWKS. They enter through public `getAccessToken`/`signOut`, require
specifically `NOT_AUTHENTICATED` for stale completion, and inspect real SDK test
preferences for absent stale persistence. Replacement cases hydrate new credentials
AFTER SDK sign-out invalidates its generation, then verify the next refresh selects
that replacement token. They do not claim arbitrary protected-field assignment
invalidates the private SDK guard. The normal case proves successful persistence
and cache reuse. Calido still disables SDK persistence and owns its encrypted store.

Six additional callback cases exercise implicit exact-route resolution and unrelated
route rejection, real-session callbacks after a paused/recreated browser Activity,
genuine browser dismissal once, and null in-memory session after modeled process
loss. The process-loss case is a controlled model, not a physical OS-kill result.
The first matrix run passed 154 of 156 cases; two lifecycle assertions read the
initial Custom Tab from Robolectric's shared started-activity queue. Consuming and
asserting that initial launch corrected the fixture; no production change resulted.

GPT-6-astra reviewed both frozen test diffs and the R8 maintenance evidence with no
verified actionable findings. Production SDK/core source is unchanged by this
matrix addition. Physical browser, process, encrypted-storage and background-call
acceptance remain open.

## Browser-return correction: 2026-10-05

M23 Calido 30005 exposed a real browser-return failure after cancelled Google
login and retry: the same account picker remained visible, while Back showed
the app was already signed in. The waiting auth activity and Samsung Custom Tab
were in one task; the callback created and finished an auth instance in another
task because Calido's host has empty affinity. CLEAR_TOP/SINGLE_TOP alone did
not locate the waiting activity across tasks.

The candidate declares the private browser-auth activity singleTask and resets
authUri/authStarted only for a trusted fresh authorization delivered through
onNewIntent, preserving the validated callback path. Calido's hosted manifest
must mirror the launch mode; its source hosting does not consume this manifest.

Two regressions failed first: merged launch mode expected 2 but was 0, and a
fresh authorization delivered to a reused instance launched no replacement URI.
The full hosted rerun passed 158 SDK/core cases and 1080 host cases with no
failures/errors/skips; Gradle cleanup passed. Logs are retained privately under
/private/tmp/calido-logto-physical-2026-10-05/m23-task-routing*.log.

GPT-6-astra reviewed only these routing changes. It found one conditional P2
concern requiring physical verification: if the auth activity is a separate
root task and the browser opens its own independent task, finishing auth may
foreground the browser rather than the original caller. Samsung's supplied
trace uses a same-task Custom Tab; it does not prove the independent-browser
case. Test success/cancel/retry, Back, recreation and rejected callbacks on
Samsung, Chrome and an independent ordinary-browser task before claiming
browser-wide compatibility. This candidate is not shipping acceptance.

Intentional cancellation still uses the existing USER_CANCELED callback.
Calido's generic sign-in error mapping is a separate host concern; this patch
does not change the UI/error policy or provider cookies/prompts.
