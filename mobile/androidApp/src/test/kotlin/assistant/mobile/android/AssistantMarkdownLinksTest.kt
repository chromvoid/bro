package assistant.mobile.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AssistantMarkdownLinksTest {
    @Test
    fun externalLinksRejectCredentialsLocalSchemesControlsAndInvalidAuthorities() {
        listOf(
            "file:///tmp/file", "javascript:alert(1)", "/v1/private", "data:text/html,test",
            "https://owner:secret@example.test/source", "https://example.test/a\nb",
            "https://example.test:0/source", "https://example.test:65536/source", "https://example.test\\path",
        ).forEach { assertNull(it, normalizedAssistantLink(it)) }
        assertEquals("https://example.test/source", normalizedAssistantLink("HTTPS://EXAMPLE.TEST/source"))
    }
}
