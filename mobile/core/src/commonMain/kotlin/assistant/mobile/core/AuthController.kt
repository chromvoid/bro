package assistant.mobile.core

import io.ktor.http.Parameters
import io.ktor.http.Url
import io.ktor.http.parseQueryString
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.datetime.TimeZone
import kotlin.time.Clock

enum class AuthLinkKind { Invite, Reset }

/** One-time material is transient and deliberately absent from form state and diagnostics. */
class AuthLink(val kind: AuthLinkKind, internal val token: String) {
    /** For a one-shot platform process handoff only; never store this value. */
    fun tokenForProcessTransfer(): String = token

    override fun toString(): String = "AuthLink(kind=$kind, token=redacted)"
}

/** The target and its one-time material remain in memory until redeem or server switching. */
class NativeAuthDestination(val origin: String, val link: AuthLink) {
    override fun toString(): String = "NativeAuthDestination(origin=$origin, link=$link)"
}

/** Only a fragment is accepted here; the platform removes it from its launch intent immediately. */
fun parseAuthLink(fragment: String): AuthLink? = runCatching {
    val parameters = authParameters(fragment, setOf("kind", "token")) ?: return null
    authLink(parameters)
}.getOrNull()

/** Join links carry exactly a HTTPS server origin, a kind and a one-time token. */
fun parseJoinLink(fragment: String): NativeAuthDestination? = runCatching {
    val parameters = authParameters(fragment, setOf("server", "kind", "token")) ?: return null
    val origin = canonicalOrigin(parameters["server"] ?: return null)
    NativeAuthDestination(origin, authLink(parameters) ?: return null)
}.getOrNull()

fun parseNativeInviteCode(value: String): AuthLink? = value.trim().takeIf(::validAuthToken)
    ?.let { AuthLink(AuthLinkKind.Invite, it) }

/** Pasted codes belong to the selected server; only the build origin can host join links. */
fun parseNativeAuthInput(value: String, selectedOrigin: String, defaultOrigin: String): NativeAuthDestination? = runCatching {
    val input = value.trim()
    if (!input.contains("://")) {
        return NativeAuthDestination(selectedOrigin, parseNativeInviteCode(input) ?: return null)
    }
    if (input.any { it.isWhitespace() || it.isISOControl() || it == '\\' } || input.count { it == '#' } != 1) return null
    val url = Url(input)
    if (!url.parameters.isEmpty() || '?' in input.substringBefore('#')) return null
    val authorityStart = input.indexOf("://") + 3
    val authorityEnd = input.indexOf('/', authorityStart).takeIf { it >= 0 } ?: return null
    val origin = canonicalOrigin(input.substring(0, authorityEnd))
    val fragment = input.substringAfter('#')
    when (url.encodedPath) {
        "/login" -> NativeAuthDestination(origin, parseAuthLink(fragment) ?: return null)
        "/join" -> if (origin == canonicalOrigin(defaultOrigin)) parseJoinLink(fragment) else null
        else -> null
    }
}.getOrNull()

private fun authParameters(fragment: String, keys: Set<String>): Parameters? {
    if (fragment.startsWith('?') || fragment.any { it.isISOControl() || it.isWhitespace() }) return null
    val raw = fragment.removePrefix("#")
    for (index in raw.indices) {
        if (raw[index] == '%' && (index + 2 >= raw.length ||
                raw[index + 1].digitToIntOrNull(16) == null || raw[index + 2].digitToIntOrNull(16) == null)) return null
    }
    val parameters = parseQueryString(raw)
    if (parameters.names() != keys || keys.any { parameters.getAll(it)?.size != 1 }) return null
    return parameters
}

private fun authLink(parameters: Parameters): AuthLink? {
    val token = parameters["token"]?.takeIf(::validAuthToken) ?: return null
    val kind = when (parameters["kind"]) {
        "invite" -> AuthLinkKind.Invite
        "reset" -> AuthLinkKind.Reset
        else -> return null
    }
    return AuthLink(kind, token)
}

private fun validAuthToken(token: String): Boolean =
    token.isNotBlank() && token.length <= 128 && "://" !in token && token.none { it.isISOControl() || it.isWhitespace() }

enum class AuthMode { SignIn, Restore, Redeem, LinkExpired }
enum class AuthField { Login, Name, Password, Repeat, Timezone }

data class AuthState(
    val mode: AuthMode = AuthMode.SignIn,
    val linkKind: AuthLinkKind? = null,
    val login: String = "",
    val name: String = "",
    val timezone: String = TimeZone.currentSystemDefault().id,
    val busy: Boolean = false,
    val signingOut: Boolean = false,
    val error: LocalizedString? = null,
    val fieldErrors: Map<AuthField, LocalizedString> = emptyMap(),
    val retryAtMillis: Long? = null,
    val expired: Boolean = false,
    val signedIn: Boolean = false,
    val welcome: Boolean = false,
) {
    fun retrySeconds(nowMillis: Long): Long =
        retryAtMillis?.let { ((it - nowMillis + 999) / 1_000).coerceAtLeast(0) } ?: 0
}

/** Passwords are call arguments only: neither controller state nor durable stores own them. */
class AuthController(
    scope: CoroutineScope,
    private val sessions: SessionManager,
    private val clockMs: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    private val mutableState = MutableStateFlow(AuthState())
    val state: StateFlow<AuthState> = mutableState.asStateFlow()
    private var link: AuthLink? = null
    private var attempt = 0L
    private var controlledLogout = false
    private var expectLeaseClear = false

    init {
        scope.launch {
            var previouslySignedIn = sessions.lease() != null
            sessions.leases.collect { lease ->
                if (lease == null && expectLeaseClear) {
                    expectLeaseClear = false
                } else if (lease == null && previouslySignedIn && !state.value.busy && !controlledLogout) {
                    attempt++
                    link = null
                    mutableState.value = AuthState(expired = true)
                } else if (lease != null) {
                    expectLeaseClear = false
                    mutableState.update { it.copy(signedIn = true, expired = false) }
                }
                previouslySignedIn = lease != null
            }
        }
    }

    fun updateLogin(value: String) = edit { copy(login = value.take(40)) }
    fun updateName(value: String) = edit { copy(name = value.take(100)) }
    fun updateTimezone(value: String) = edit { copy(timezone = value) }

    fun acceptLink(value: AuthLink) {
        attempt++
        link = value
        mutableState.value = AuthState(mode = AuthMode.Redeem, linkKind = value.kind)
    }

    fun showSignIn() {
        attempt++
        link = null
        mutableState.update { AuthState(login = it.login, expired = it.expired, retryAtMillis = it.retryAtMillis) }
    }

    fun showRestore() {
        if (state.value.busy) return
        link = null
        mutableState.update { it.copy(mode = AuthMode.Restore, linkKind = null, error = null, fieldErrors = emptyMap()) }
    }

    fun dismissWelcome() {
        mutableState.update { it.copy(welcome = false) }
    }

    /** Explicit logout has different product meaning from a 401/expiry. */
    fun signedOut() {
        attempt++
        link = null
        mutableState.value = AuthState()
    }

    /** Logout finishes erasure/revocation even when removing the private screen cancels its UI. */
    suspend fun signOut(): HttpOutcome {
        attempt++
        link = null
        controlledLogout = true
        expectLeaseClear = sessions.lease() != null
        mutableState.value = AuthState(busy = true, signingOut = true)
        return try {
            withContext(NonCancellable) { sessions.logout() }
        } finally {
            signedOut()
            controlledLogout = false
        }
    }

    fun restored(result: SessionResult) {
        when (result) {
            SessionResult.StorageUnavailable, SessionResult.InvalidReply ->
                mutableState.update { it.copy(error = GeneratedStrings.Errors.server) }
            is SessionResult.Rejected -> mutableState.update { it.copy(error = nativeError(result.outcome)) }
            else -> Unit
        }
    }

    suspend fun signIn(password: String) {
        val form = state.value
        if (form.mode != AuthMode.SignIn || blocked(form)) return
        if (form.login.isBlank() || password.isEmpty()) {
            mutableState.update { it.copy(error = GeneratedStrings.Auth.checkText) }
            return
        }
        authenticate(false) { sessions.login(form.login.trim(), password) }
    }

    suspend fun redeem(password: String, repeat: String) {
        val form = state.value
        val currentLink = link ?: return
        if (form.mode != AuthMode.Redeem || blocked(form)) return
        val errors = buildMap {
            if (currentLink.kind == AuthLinkKind.Invite) {
                if (form.name.isBlank()) put(AuthField.Name, GeneratedStrings.Auth.nameRequired)
                if (!validLogin(form.login.trim())) put(AuthField.Login, GeneratedStrings.Auth.loginRule)
                if (!validTimezone(form.timezone)) put(AuthField.Timezone, GeneratedStrings.Auth.timezoneUnknown)
            }
            if (password.length < 12) put(AuthField.Password, GeneratedStrings.Auth.passwordRule("12"))
            if (repeat != password) put(AuthField.Repeat, GeneratedStrings.Auth.repeatMismatch)
        }
        if (errors.isNotEmpty()) {
            mutableState.update { it.copy(fieldErrors = errors, error = null) }
            return
        }
        authenticate(currentLink.kind == AuthLinkKind.Invite) {
            sessions.redeem(
                currentLink.token, password,
                login = if (currentLink.kind == AuthLinkKind.Invite) form.login.trim() else null,
                name = if (currentLink.kind == AuthLinkKind.Invite) form.name.trim() else null,
                timezone = if (currentLink.kind == AuthLinkKind.Invite) form.timezone else "Europe/Moscow",
            )
        }
    }

    private suspend fun authenticate(welcome: Boolean, action: suspend () -> SessionResult) {
        val captured = ++attempt
        expectLeaseClear = sessions.lease() != null
        mutableState.update { it.copy(busy = true, error = null, fieldErrors = emptyMap(), welcome = false) }
        try {
            val result = action()
            if (captured != attempt) return
            when (result) {
                is SessionResult.SignedIn -> {
                    link = null
                    mutableState.update { it.copy(mode = AuthMode.SignIn, linkKind = null, busy = false,
                        signedIn = true, welcome = welcome, retryAtMillis = null) }
                }
                is SessionResult.Rejected -> {
                    val outcome = result.outcome
                    when {
                        outcome is HttpOutcome.RateLimited -> mutableState.update {
                            it.copy(busy = false, retryAtMillis = outcome.retryAtMillis)
                        }
                        state.value.mode == AuthMode.Redeem && outcome is HttpOutcome.Conflict -> mutableState.update {
                            it.copy(busy = false, fieldErrors = mapOf(AuthField.Login to GeneratedStrings.Auth.loginTaken))
                        }
                        state.value.mode == AuthMode.Redeem && outcome is HttpOutcome.Failure && outcome.status == 422 -> {
                            link = null
                            mutableState.update { it.copy(mode = AuthMode.LinkExpired, busy = false, linkKind = null) }
                        }
                        else -> mutableState.update { it.copy(busy = false, error =
                            if (outcome == HttpOutcome.Unauthorized) GeneratedStrings.Auth.checkText else nativeError(outcome)) }
                    }
                }
                else -> mutableState.update { it.copy(busy = false, error = GeneratedStrings.Errors.server) }
            }
        } catch (cancelled: CancellationException) {
            if (captured == attempt) mutableState.update { it.copy(busy = false) }
            throw cancelled
        } catch (_: Exception) {
            if (captured == attempt) mutableState.update { it.copy(busy = false, error = GeneratedStrings.Errors.unknown) }
        }
    }

    private fun blocked(form: AuthState) = form.busy || form.retrySeconds(clockMs()) > 0
    private fun edit(change: AuthState.() -> AuthState) {
        if (!state.value.busy) mutableState.update { it.change().copy(error = null, fieldErrors = emptyMap()) }
    }
}

internal fun validTimezone(value: String): Boolean = runCatching { TimeZone.of(value) }.isSuccess

private fun validLogin(value: String): Boolean = value.length in 3..40 && value.first().isLatinOrDigit() &&
    value.all { it.isLatinOrDigit() || it in "_.-" }

private fun Char.isLatinOrDigit() = this in 'a'..'z' || this in 'A'..'Z' || this in '0'..'9'

fun nativeError(outcome: HttpOutcome): LocalizedString = when (outcome) {
    HttpOutcome.NetworkFailure -> GeneratedStrings.Errors.network
    HttpOutcome.Unauthorized, HttpOutcome.StaleSession -> GeneratedStrings.Errors.session
    HttpOutcome.Forbidden -> GeneratedStrings.Errors.forbidden
    is HttpOutcome.Conflict -> GeneratedStrings.Errors.conflict
    is HttpOutcome.RateLimited -> GeneratedStrings.Errors.limit
    is HttpOutcome.Failure -> when (outcome.status) {
        404 -> GeneratedStrings.Errors.notFound
        413 -> GeneratedStrings.Errors.tooLarge
        400, 422 -> GeneratedStrings.Errors.invalid
        else -> GeneratedStrings.Errors.server
    }
    else -> GeneratedStrings.Errors.server
}
