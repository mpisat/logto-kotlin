# Calido Kotlin 3 maintenance fork

Upstream base: `logto-io/kotlin` tag `v3.0.0`, commit `7fcf65a0c336847d50770f2a96fb7b36513076bc`.

Previous consumed fork: `28cff5ce7efe864cdfa49e3e511edcd5ba518745` on `native-browser`. This branch is `codex/native-browser-v3`; published baseline history is preserved.

## Retained corrections

- Resolve/cache JWKS before exchanging a rotating refresh token. Discovery/JWKS failure must not consume it. Preserve upstream credential stamps, captured refresh values, omitted-refresh-token fallback and guarded token adoption.
- Callback admission is being ported separately onto the release's exported receiver and non-exported browser launcher. Candidate shipping remains gated until that correction and native host integration are verified.

Calido continues to own encrypted Android persistence and native background authentication. The SDK uses `usingPersistStorage = false` in the host. The owner approved upstream's configurable 300-second ID-token issued-at and expiry tolerance; host direct verification must use the same value.

## Verification evidence

Tests run through Calido's source-hosted `logto-native-browser` module using arm64 Java 21, `--no-daemon`, one worker and in-process Kotlin compilation. This avoids assuming the release repository's Gradle 7.5 supports the current host JDK.

- Unmodified release hosted SDK suite: passed on 2026-10-04.
- JWKS regression against unmodified release: failed because `Core.fetchTokenByRefreshToken` ran before the JWKS failure.
- Patched client class: all 46 tests passed, including omitted-token and stale credential-generation tests. Existing asynchronous fixtures account for the new preflight before verification.
- Logs retained locally: `/private/tmp/calido-logto-kotlin-v3-baseline.log`, `/private/tmp/calido-logto-kotlin-v3-red.log`, `/private/tmp/calido-logto-kotlin-v3-jwks-green-2.log`.

Command from the prepared isolated Calido worktree:

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
(cd android && ./gradlew --no-daemon --max-workers=1 \
  -Pkotlin.compiler.execution.strategy=in-process \
  logto-native-browser:testDebugUnitTest --tests 'io.logto.sdk.android.LogtoClientTest')
bash tool/ensure-no-project-gradle-processes.sh "$PWD"
```

Run cleanup even when Gradle fails. No physical-device acceptance or shipping host pin is claimed by these SDK results. Keep the previous Calido shipping pin until signed-candidate browser, storage, process recovery and background-call gates pass.
