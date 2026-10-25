package assistant.mobile.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class NativeAuthInputTest {
    private val selected = "https://self-hosted.invalid"
    private val default = "https://bro.chromvoid.com"

    @Test
    fun joinFragmentDecodesAndCanonicalizesTheServerAndRedactsTheToken() {
        val destination = assertNotNull(parseJoinLink(
            "#server=https%3A%2F%2FSelf-Hosted.Invalid%3A443%2F&kind=invite&token=synthetic%2Btoken"))
        assertEquals(selected, destination.origin)
        assertEquals(AuthLinkKind.Invite, destination.link.kind)
        assertEquals("synthetic+token", destination.link.token)
        assertFalse(destination.toString().contains(destination.link.token))
        val reset = assertNotNull(parseJoinLink("token=synthetic&kind=reset&server=https%3A%2F%2Fself-hosted.invalid%3A8443"))
        assertEquals("https://self-hosted.invalid:8443", reset.origin)
        assertEquals(AuthLinkKind.Reset, reset.link.kind)
    }

    @Test
    fun joinFragmentRequiresExactlyOneServerKindAndToken() {
        val valid = "server=https%3A%2F%2Fself-hosted.invalid&kind=invite&token=synthetic"
        for (fragment in listOf(
            "kind=invite&token=synthetic",
            "server=https%3A%2F%2Fself-hosted.invalid&token=synthetic",
            "server=https%3A%2F%2Fself-hosted.invalid&kind=invite",
            "$valid&server=https%3A%2F%2Fother.invalid",
            "$valid&kind=reset",
            "$valid&token=second",
            "$valid&extra=value",
            "?$valid",
            "https://bro.chromvoid.com/join#$valid",
        )) assertNull(parseJoinLink(fragment), fragment)
    }

    @Test
    fun joinFragmentRejectsOriginsWithAnythingExceptHttpsAuthority() {
        for (origin in listOf("http://self-hosted.invalid", "self-hosted.invalid", "https://",
            "https://person@self-hosted.invalid", "https://self-hosted.invalid/path",
            "https://self-hosted.invalid?query=1", "https://self-hosted.invalid#fragment",
            "https://self-hosted.invalid:0", "https://self-hosted.invalid:65536")) {
            val encoded = origin.replace("%", "%25").replace(":", "%3A").replace("/", "%2F")
                .replace("?", "%3F").replace("#", "%23").replace("&", "%26").replace("=", "%3D")
            assertNull(parseJoinLink("server=$encoded&kind=invite&token=synthetic"), origin)
        }
    }

    @Test
    fun loginAndJoinFragmentsRejectInvalidKindsTokensAndEncoding() {
        for (suffix in listOf("kind=unknown&token=synthetic", "kind=invite&token=",
            "kind=invite&token=%20", "kind=reset&token=two+words", "kind=invite&token=two%20words",
            "kind=invite&token=%00", "kind=invite&token=%0A", "kind=invite&token=%",
            "kind=invite&token=%2", "kind=invite&token=%gg", "kind=invite&token=" + "a".repeat(129),
            "kind=invite&token=https%3A%2F%2Fother.invalid")) {
            assertNull(parseAuthLink(suffix), suffix)
            assertNull(parseJoinLink("server=https%3A%2F%2Fself-hosted.invalid&$suffix"), suffix)
        }
        assertNull(parseAuthLink("kind=invite&token=synthetic&extra=value"))
        assertNull(parseAuthLink("kind=invite&token=synthetic&kind=reset"))
        assertNull(parseAuthLink("kind=invite&token=synthetic&token=other"))
        assertNotNull(parseAuthLink("kind=invite&token=" + "a".repeat(128)))
    }

    @Test
    fun pastedLoginLinksUseTheirOwnServerAndTrimSurroundingWhitespace() {
        for (origin in listOf(selected, default, "https://other.invalid:8443")) {
            val destination = assertNotNull(parseNativeAuthInput(
                " \n$origin/login#kind=reset&token=synthetic%2Btoken\t ", selected, default))
            assertEquals(origin, destination.origin)
            assertEquals(AuthLinkKind.Reset, destination.link.kind)
            assertEquals("synthetic+token", destination.link.token)
        }
    }

    @Test
    fun pastedJoinLinksAreAcceptedOnlyFromTheBuildOrigin() {
        val suffix = "/join#server=https%3A%2F%2Fself-hosted.invalid&kind=invite&token=synthetic"
        val destination = assertNotNull(parseNativeAuthInput(" \t$default$suffix\n", selected, default))
        assertEquals(selected, destination.origin)
        assertEquals(AuthLinkKind.Invite, destination.link.kind)
        assertNotNull(parseNativeAuthInput("https://BRO.CHROMVOID.COM:443$suffix", selected, default))
        for (origin in listOf(selected, "https://other.invalid", "https://bro.chromvoid.com:8443",
            "http://bro.chromvoid.com", "https://bro.chromvoid.com.attacker.invalid")) {
            assertNull(parseNativeAuthInput("$origin$suffix", selected, default), origin)
        }
    }

    @Test
    fun pastedLinksRejectUnsafeOrUnexpectedUrlShapes() {
        for (url in listOf(
            "http://self-hosted.invalid/login#kind=invite&token=synthetic",
            "https://person@self-hosted.invalid/login#kind=invite&token=synthetic",
            "https://self-hosted.invalid/login?query=1#kind=invite&token=synthetic",
            "https://self-hosted.invalid/login?#kind=invite&token=synthetic",
            "https://self-hosted.invalid/login/#kind=invite&token=synthetic",
            "https://self-hosted.invalid/%6cogin#kind=invite&token=synthetic",
            "https://self-hosted.invalid/tasks#kind=invite&token=synthetic",
            "https://self-hosted.invalid/login",
            "https://self-hosted.invalid/login#kind=invite&token=synthetic#fragment",
            "https://self-hosted.invalid/login#kind=invite&token=two words",
            "https://self-hosted.invalid\\attacker.invalid/login#kind=invite&token=synthetic",
        )) assertNull(parseNativeAuthInput(url, selected, default), url)
    }

    @Test
    fun bareCodesAreTrimmedAndBelongToTheSelectedServer() {
        for (origin in listOf(selected, default, "http://127.0.0.1:12345")) {
            val destination = assertNotNull(parseNativeAuthInput(" \nsynthetic+token\t ", origin, default))
            assertEquals(origin, destination.origin)
            assertEquals(AuthLinkKind.Invite, destination.link.kind)
            assertEquals("synthetic+token", destination.link.token)
        }
        val code = assertNotNull(parseNativeInviteCode("  synthetic%2Btoken  "))
        assertEquals("synthetic%2Btoken", code.token)
    }

    @Test
    fun bareCodesRejectEmptyOversizedControlOrWhitespaceTokensAndLinks() {
        for (code in listOf("", " \t\n", "two words", "two\nlines", "synthetic\u0000token", "a".repeat(129),
            "$selected/login#kind=invite&token=synthetic")) {
            assertNull(parseNativeInviteCode(code), code)
            if (!code.contains("://")) assertNull(parseNativeAuthInput(code, selected, default), code)
        }
    }
}
