package com.sailpoint.intellij.api

import org.junit.Assert.assertEquals
import org.junit.Test

class IscConnectionTest {
    @Test
    fun `bare tenant name expands to the production API host`() {
        assertEquals("https://acme.api.identitynow.com", IscConnection.normalizeBaseUrl(" acme "))
    }

    @Test
    fun `host without scheme gets https`() {
        assertEquals("https://acme.api.identitynow-demo.com", IscConnection.normalizeBaseUrl("acme.api.identitynow-demo.com"))
    }

    @Test
    fun `full URL is kept without trailing slash`() {
        assertEquals("https://acme.api.identitynow.com", IscConnection.normalizeBaseUrl("https://acme.api.identitynow.com/"))
    }
}
