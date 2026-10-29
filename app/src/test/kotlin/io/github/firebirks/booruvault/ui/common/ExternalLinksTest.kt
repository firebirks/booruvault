package io.github.firebirks.booruvault.ui.common

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which links a site's page may hand to another app. */
class ExternalLinksTest {
    @Test
    fun `a web page's address goes out`() {
        assertTrue(isWebLink("https://safebooru.donmai.us/posts/1"))
        assertTrue(isWebLink("HTTP://safebooru.org/index.php?page=post&s=view&id=1"))
    }

    @Test
    fun `a link of any other kind a page carries does not`() {
        listOf(
            "tel:+10000000000",
            "sms:+10000000000?body=hi",
            "mailto:someone@example.com",
            "file:///data/data/io.github.firebirks.booruvault/files/vault.json",
            "content://media/external/images/media/1",
            "intent://scan/#Intent;scheme=zxing;package=com.example;end",
            "javascript:alert(1)",
            "market://details?id=com.example",
            "",
        ).forEach { assertFalse(it, isWebLink(it)) }
    }
}
