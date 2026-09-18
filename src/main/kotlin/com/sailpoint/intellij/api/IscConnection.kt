package com.sailpoint.intellij.api

/** Credentials for a Personal Access Token (OAuth client-credentials) against one ISC tenant. */
data class IscConnection(val baseUrl: String, val clientId: String, val clientSecret: String) {
    companion object {
        /**
         * Accepts a bare tenant name (`acme`), a host (`acme.api.identitynow-demo.com`),
         * or a full URL, and returns the API base URL without a trailing slash.
         */
        fun normalizeBaseUrl(tenant: String): String {
            val t = tenant.trim().trimEnd('/')
            return when {
                t.isEmpty() -> ""
                "://" in t -> t
                "." in t -> "https://$t"
                else -> "https://$t.api.identitynow.com"
            }
        }
    }
}

class IscApiException(val status: Int, message: String) : Exception(message)
