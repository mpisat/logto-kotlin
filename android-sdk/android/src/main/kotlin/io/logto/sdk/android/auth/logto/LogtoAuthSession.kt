package io.logto.sdk.android.auth.logto

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import io.logto.sdk.android.completion.Completion
import io.logto.sdk.android.exception.LogtoException
import io.logto.sdk.android.type.LogtoConfig
import io.logto.sdk.android.type.SignInOptions
import io.logto.sdk.core.Core
import io.logto.sdk.core.exception.CallbackUriVerificationException
import io.logto.sdk.core.type.CodeTokenResponse
import io.logto.sdk.core.type.GenerateSignInUriOptions
import io.logto.sdk.core.type.OidcConfigResponse
import io.logto.sdk.core.util.CallbackUriUtils
import io.logto.sdk.core.util.GenerateUtils

class LogtoAuthSession(
    val context: Activity,
    val logtoConfig: LogtoConfig,
    val oidcConfig: OidcConfigResponse,
    val signInOptions: SignInOptions,
    private val completion: Completion<LogtoException, CodeTokenResponse>,
) {
    private val codeVerifier = GenerateUtils.generateCodeVerifier()
    private val state = GenerateUtils.generateState()

    // Per-attempt random nonce that round-trips through any genuine
    // OIDC response. Exposed via [matchesState] (not the value itself)
    // so the trampoline can authenticate a callback URI without the
    // value ever leaving this object.
    internal fun matchesState(uri: Uri): Boolean {
        val incoming = uri.getQueryParameter("state") ?: return false
        return incoming == state
    }

    fun start() {
        val parsedRedirect = Uri.parse(signInOptions.redirectUri)
        if (parsedRedirect == Uri.EMPTY) {
            completion.onComplete(LogtoException(LogtoException.Type.INVALID_REDIRECT_URI), null)
            return
        }

        // Guard against the silent dead-end case where the consuming app
        // forgot to set `logtoRedirectScheme` / `logtoRedirectHost`
        // manifest placeholders. Without this check the Custom Tabs
        // redirect never routes back, the user taps back, and the flow
        // surfaces as `USER_CANCELED` — indistinguishable from a real
        // cancel. We only check when the redirectUri has a scheme;
        // scheme-less dummy URIs (legacy tests) skip the guard and keep
        // flowing into the existing downstream validation.
        if (!parsedRedirect.scheme.isNullOrEmpty() &&
            !isRedirectUriHandledByManifest(parsedRedirect)
        ) {
            completion.onComplete(
                LogtoException(LogtoException.Type.REDIRECT_URI_NOT_REGISTERED).apply {
                    detail = "No activity in this app declares an intent filter for " +
                        "${parsedRedirect.scheme}://${parsedRedirect.host}. Set " +
                        "`manifestPlaceholders[\"logtoRedirectScheme\"]` and " +
                        "`manifestPlaceholders[\"logtoRedirectHost\"]` in the app " +
                        "module's build.gradle so they match signInOptions.redirectUri."
                },
                null,
            )
            return
        }

        LogtoAuthManager.handleAuthStart(this)

        val signInUri = Core.generateSignInUri(
            GenerateSignInUriOptions(
                authorizationEndpoint = oidcConfig.authorizationEndpoint,
                clientId = logtoConfig.appId,
                redirectUri = signInOptions.redirectUri,
                codeChallenge = GenerateUtils.generateCodeChallenge(codeVerifier),
                state = state,
                scopes = logtoConfig.scopes,
                resources = logtoConfig.resources,
                prompt = signInOptions.prompt ?: logtoConfig.prompt,
                loginHint = signInOptions.loginHint,
                firstScreen = signInOptions.firstScreen,
                identifiers = signInOptions.identifiers,
                directSignIn = signInOptions.directSignIn,
                extraParams = signInOptions.extraParams,
                includeReservedScopes = logtoConfig.includeReservedScopes,
            ),
        )

        LogtoWebViewAuthActivity.launch(context, signInUri)
    }

    fun handleCallbackUri(callbackUri: Uri) {
        val authorizationCode = try {
            CallbackUriUtils.verifyAndParseCodeFromCallbackUri(
                callbackUri.toString(),
                signInOptions.redirectUri,
                state,
            )
        } catch (exception: CallbackUriVerificationException) {
            completion.onComplete(
                LogtoException(LogtoException.Type.INVALID_CALLBACK_URI, exception),
                null,
            )
            return
        }

        Core.fetchTokenByAuthorizationCode(
            tokenEndpoint = oidcConfig.tokenEndpoint,
            clientId = logtoConfig.appId,
            redirectUri = signInOptions.redirectUri,
            codeVerifier = codeVerifier,
            code = authorizationCode,
            resource = null,
        ) { fetchTokenException, codeTokenResponse ->
            fetchTokenException?.let {
                completion.onComplete(
                    LogtoException(
                        LogtoException.Type.UNABLE_TO_FETCH_TOKEN_BY_AUTHORIZATION_CODE,
                    ),
                    null,
                )
                return@fetchTokenByAuthorizationCode
            }
            completion.onComplete(null, codeTokenResponse)
        }
    }

    fun handleUserCancel() {
        completion.onComplete(LogtoException(LogtoException.Type.USER_CANCELED), null)
    }

    fun handleNoBrowserAvailable() {
        completion.onComplete(LogtoException(LogtoException.Type.NO_BROWSER_AVAILABLE), null)
    }

    fun handleInvalidCallbackUri(uri: Uri) {
        // Redact query and fragment before exposing the URI in the
        // exception detail. OIDC callback URIs carry `code` and `state`
        // as query parameters — leaking them through consumer telemetry
        // (which commonly logs `LogtoException.detail`) would weaken
        // session integrity. The diagnostic value of the message comes
        // from scheme/host/path, which are enough to tell a config
        // error from a real cancel.
        val redacted = buildString {
            uri.scheme?.let { append(it).append("://") }
            uri.host?.let { append(it) }
            uri.path?.let { append(it) }
        }.ifEmpty { "<unparseable>" }
        completion.onComplete(
            LogtoException(LogtoException.Type.INVALID_CALLBACK_URI).apply {
                detail = "Callback URI ($redacted) did not match the registered redirectUri " +
                    "'${signInOptions.redirectUri}'. Verify the Logto application " +
                    "configuration and the `manifestPlaceholders` for " +
                    "`logtoRedirectScheme` / `logtoRedirectHost`."
            },
            null,
        )
    }

    private fun isRedirectUriHandledByManifest(redirect: Uri): Boolean {
        val pm = context.packageManager
        val probe = Intent(Intent.ACTION_VIEW, redirect).apply {
            addCategory(Intent.CATEGORY_BROWSABLE)
            addCategory(Intent.CATEGORY_DEFAULT)
            // Restrict the resolver to our own app so a browser or
            // unrelated handler on the device does not satisfy the probe.
            setPackage(context.packageName)
        }
        val matches = pm.queryIntentActivities(probe, PackageManager.MATCH_DEFAULT_ONLY)
        return matches.any { resolve ->
            resolve.activityInfo?.name == LogtoWebViewAuthActivity::class.java.name
        }
    }
}
