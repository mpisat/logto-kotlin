package io.logto.sdk.android.auth.logto

import android.annotation.SuppressLint
import android.net.Uri

internal object LogtoAuthManager {
    @SuppressLint("StaticFieldLeak")
    internal var logtoAuthSession: LogtoAuthSession? = null

    fun handleAuthStart(authSession: LogtoAuthSession) {
        logtoAuthSession = authSession
    }

    fun handleCallbackUri(uri: Uri) {
        logtoAuthSession?.handleCallbackUri(uri)
        logtoAuthSession = null
    }

    fun handleUserCancel() {
        logtoAuthSession?.handleUserCancel()
        logtoAuthSession = null
    }

    fun handleNoBrowserAvailable() {
        logtoAuthSession?.handleNoBrowserAvailable()
        logtoAuthSession = null
    }

    fun handleInvalidCallbackUri(uri: Uri) {
        logtoAuthSession?.handleInvalidCallbackUri(uri)
        logtoAuthSession = null
    }

    // Authenticity gate for the mismatch path. A real OIDC response —
    // even one routed to a misconfigured redirectUri — round-trips the
    // `state` value the session generated. A third-party explicit-start
    // with crafted `data` cannot guess that random per-attempt value
    // (`GenerateUtils.generateState`), so a state mismatch is the
    // signal that an `intent.data` is not from our auth flow and the
    // active session must be left alone.
    fun isAuthenticOidcState(uri: Uri): Boolean =
        logtoAuthSession?.matchesState(uri) ?: false

    fun isLogtoAuthResult(uri: Uri) = logtoAuthSession?.let { session ->
        // Strict scheme/host/path match. The older `startsWith` check
        // accepted any URI that simply shared a string prefix with the
        // configured redirectUri, which collides with sibling hosts or
        // path prefixes. The intent filter in `AndroidManifest.xml`
        // already gates scheme + host, but we re-check here defensively
        // because `handleCallbackUri` can also be reached via explicit
        // component launches of the exported activity.
        val expected = Uri.parse(session.signInOptions.redirectUri)
        // RFC 3986 §3.1/§3.2.2 — scheme and host are case-insensitive;
        // path is case-sensitive and stays equality-checked.
        uri.scheme.equals(expected.scheme, ignoreCase = true) &&
            uri.host.equals(expected.host, ignoreCase = true) &&
            uri.path == expected.path
    } ?: false
}
