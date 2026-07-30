package io.homeassistant.companion.android.util

import android.net.Uri
import android.net.http.SslError
import android.webkit.HttpAuthHandler
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient.ERROR_AUTHENTICATION
import android.webkit.WebViewClient.ERROR_CONNECT
import android.webkit.WebViewClient.ERROR_FAILED_SSL_HANDSHAKE
import android.webkit.WebViewClient.ERROR_HOST_LOOKUP
import android.webkit.WebViewClient.ERROR_PROXY_AUTHENTICATION
import android.webkit.WebViewClient.ERROR_TIMEOUT
import android.webkit.WebViewClient.ERROR_UNSUPPORTED_AUTH_SCHEME
import androidx.annotation.StringRes
import io.homeassistant.companion.android.common.R as commonR
import io.homeassistant.companion.android.common.data.keychain.KeyChainRepository
import io.homeassistant.companion.android.frontend.error.FrontendConnectionError
import io.homeassistant.companion.android.testing.unit.MainDispatcherJUnit5Extension
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlin.reflect.KClass
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertNotNull
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

@ExtendWith(MainDispatcherJUnit5Extension::class)
class HAWebViewClientTest {

    private val keyChainRepository: KeyChainRepository = mockk(relaxed = true)
    private val currentUrlFlow = MutableStateFlow<String?>(null)
    private var capturedError: FrontendConnectionError? = null
    private val subresourceSslErrorUrls = mutableListOf<String?>()

    private lateinit var webViewClient: HAWebViewClient

    @BeforeEach
    fun setup() {
        capturedError = null
        subresourceSslErrorUrls.clear()
        webViewClient = HAWebViewClient(
            keyChainRepository = keyChainRepository,
            currentUrlFlow = currentUrlFlow,
            onFrontendError = { capturedError = it },
            onCrash = null,
            onUrlIntercepted = null,
            onPageFinished = null,
            onReceivedHttpAuthRequest = null,
            onSubresourceSslError = { subresourceSslErrorUrls += it },
        )
    }

    @Test
    fun `Given onPageFinished callback when onPageFinished then invokes callback with final url`() {
        var finishedUrl: String? = null
        val client = HAWebViewClient(
            keyChainRepository = keyChainRepository,
            currentUrlFlow = currentUrlFlow,
            onFrontendError = { capturedError = it },
            onCrash = null,
            onUrlIntercepted = null,
            onPageFinished = { finishedUrl = it },
            onReceivedHttpAuthRequest = null,
        )

        client.onPageFinished(null, "http://homeassistant.local:80/onboarding")

        assertEquals("http://homeassistant.local:80/onboarding", finishedUrl)
    }

    @Test
    fun `Given SSL_DATE_INVALID error when onReceivedSslError then emits SslError`() {
        testSslError(SslError.SSL_DATE_INVALID, commonR.string.webview_error_SSL_DATE_INVALID)
    }

    @Test
    fun `Given SSL_EXPIRED error when onReceivedSslError then emits SslError`() {
        testSslError(SslError.SSL_EXPIRED, commonR.string.webview_error_SSL_EXPIRED)
    }

    @Test
    fun `Given SSL_IDMISMATCH error when onReceivedSslError then emits SslError`() {
        testSslError(SslError.SSL_IDMISMATCH, commonR.string.webview_error_SSL_IDMISMATCH)
    }

    @Test
    fun `Given SSL_INVALID error when onReceivedSslError then emits SslError`() {
        testSslError(SslError.SSL_INVALID, commonR.string.webview_error_SSL_INVALID)
    }

    @Test
    fun `Given SSL_NOTYETVALID error when onReceivedSslError then emits SslError`() {
        testSslError(SslError.SSL_NOTYETVALID, commonR.string.webview_error_SSL_NOTYETVALID)
    }

    @Test
    fun `Given SSL_UNTRUSTED error when onReceivedSslError then emits SslError`() {
        testSslError(SslError.SSL_UNTRUSTED, commonR.string.webview_error_SSL_UNTRUSTED)
    }

    @Test
    fun `Given null SSL error when onReceivedSslError then emits generic SSL error`() {
        webViewClient.onReceivedSslError(null, null, null)

        assertNotNull(capturedError)
        assertTrue(capturedError is FrontendConnectionError.SslError)
        assertEquals(commonR.string.error_ssl, capturedError?.message)
    }

    @Test
    fun `Given SSL error for a subresource when onReceivedSslError then reports its url instead of failing`() {
        currentUrlFlow.value = "http://homeassistant.local:8123/auth/authorize"
        val subresourceUrl = "https://analytics.example.com/beacon.min.js"
        val sslError = mockSslError(SslError.SSL_UNTRUSTED, url = subresourceUrl)

        webViewClient.onReceivedSslError(null, null, sslError)

        assertEquals(null, capturedError)
        assertEquals(listOf(subresourceUrl), subresourceSslErrorUrls)
    }

    private fun testSslError(primaryError: Int, @StringRes expectedMessageRes: Int) {
        currentUrlFlow.value = "http://homeassistant.local:8123/auth/authorize"
        val sslError = mockSslError(primaryError, url = "http://homeassistant.local:8123/auth/authorize")

        webViewClient.onReceivedSslError(null, null, sslError)

        assertFrontendError<FrontendConnectionError.SslError>(
            expectedMessageRes,
            "SSL Error: $primaryError",
            SslError::class,
        )
        assertTrue(subresourceSslErrorUrls.isEmpty())
    }

    private fun mockSslError(primaryError: Int, url: String): SslError = mockk {
        every { this@mockk.primaryError } returns primaryError
        every { this@mockk.url } returns url
        every { this@mockk.toString() } returns "SSL Error: $primaryError"
    }

    @Test
    fun `Given expired TLS cert when onReceivedHttpError then emits TlsCertExpired`() {
        val webView = mockWebView()
        val currentUrl = "http://homeassistant.local:8123/auth/authorize"
        currentUrlFlow.value = currentUrl
        val request = mockRequest(currentUrl)

        webViewClient.isTLSClientAuthNeeded = true
        webViewClient.isCertificateChainValid = false

        webViewClient.onReceivedHttpError(webView, request, null)

        assertFrontendError<FrontendConnectionError.TlsCertExpired>(
            commonR.string.tls_cert_expired_message,
            errorDetails(null, "No description"),
            WebResourceResponse::class,
        )
    }

    @Test
    fun `Given TLS cert not found when onReceivedHttpError then emits TlsCertNotFound`() {
        val webView = mockWebView()
        val currentUrl = "http://homeassistant.local:8123/auth/authorize"
        currentUrlFlow.value = currentUrl
        val request = mockRequest(currentUrl)
        val response = mockk<WebResourceResponse> {
            every { statusCode } returns 400
            every { reasonPhrase } returns "Bad Request"
        }

        webViewClient.isTLSClientAuthNeeded = true
        webViewClient.isCertificateChainValid = true

        webViewClient.onReceivedHttpError(webView, request, response)

        assertFrontendError<FrontendConnectionError.TlsCertNotFound>(
            commonR.string.tls_cert_not_found_message,
            errorDetails(400, "Bad Request"),
            WebResourceResponse::class,
        )
    }

    @Test
    fun `Given generic HTTP error when onReceivedHttpError then emits Unknown`() {
        val webView = mockWebView()
        val currentUrl = "http://homeassistant.local:8123/auth/authorize"
        currentUrlFlow.value = currentUrl
        val request = mockRequest(currentUrl)
        val response = mockk<WebResourceResponse> {
            every { statusCode } returns 418
            every { reasonPhrase } returns "I'm a teapot"
        }

        webViewClient.isTLSClientAuthNeeded = false

        webViewClient.onReceivedHttpError(webView, request, response)

        assertFrontendError<FrontendConnectionError.Unknown>(
            commonR.string.connection_error_unknown_error,
            errorDetails(418, "I'm a teapot"),
            WebResourceResponse::class,
        )
    }

    @Test
    fun `Given HTTP error without reason when onReceivedHttpError then emits Unknown with no description`() {
        val webView = mockWebView()
        val currentUrl = "http://homeassistant.local:8123/auth/authorize"
        currentUrlFlow.value = currentUrl
        val request = mockRequest(currentUrl)
        val response = mockk<WebResourceResponse> {
            every { statusCode } returns 500
            every { reasonPhrase } returns ""
        }

        webViewClient.isTLSClientAuthNeeded = false

        webViewClient.onReceivedHttpError(webView, request, response)

        assertFrontendError<FrontendConnectionError.Unknown>(
            commonR.string.connection_error_unknown_error,
            errorDetails(500, "No description"),
            WebResourceResponse::class,
        )
    }

    @Test
    fun `Given HTTP error for different URL when onReceivedHttpError then does not emit error`() {
        val webView = mockWebView()
        currentUrlFlow.value = "http://homeassistant.local:8123/auth/authorize"
        val request = mockRequest("http://different-url.com/something")
        val response = mockk<WebResourceResponse> {
            every { statusCode } returns 500
            every { reasonPhrase } returns "Server Error"
        }

        webViewClient.onReceivedHttpError(webView, request, response)

        assertEquals(null, capturedError)
    }

    @Test
    fun `Given ERROR_FAILED_SSL_HANDSHAKE when onReceivedError then emits SslError`() {
        testReceivedError(
            errorCode = ERROR_FAILED_SSL_HANDSHAKE,
            expectedMessageRes = commonR.string.webview_error_FAILED_SSL_HANDSHAKE,
            expectedErrorType = FrontendConnectionError.SslError::class,
        )
    }

    @Test
    fun `Given ERROR_AUTHENTICATION when onReceivedError then emits AuthRevoked`() {
        testReceivedError(
            errorCode = ERROR_AUTHENTICATION,
            expectedMessageRes = commonR.string.webview_error_AUTHENTICATION,
            expectedErrorType = FrontendConnectionError.AuthRevoked::class,
        )
    }

    @Test
    fun `Given ERROR_PROXY_AUTHENTICATION when onReceivedError then emits AuthRevoked`() {
        testReceivedError(
            errorCode = ERROR_PROXY_AUTHENTICATION,
            expectedMessageRes = commonR.string.webview_error_PROXY_AUTHENTICATION,
            expectedErrorType = FrontendConnectionError.AuthRevoked::class,
        )
    }

    @Test
    fun `Given ERROR_UNSUPPORTED_AUTH_SCHEME when onReceivedError then emits AuthRevoked`() {
        testReceivedError(
            errorCode = ERROR_UNSUPPORTED_AUTH_SCHEME,
            expectedMessageRes = commonR.string.webview_error_AUTH_SCHEME,
            expectedErrorType = FrontendConnectionError.AuthRevoked::class,
        )
    }

    @Test
    fun `Given ERROR_HOST_LOOKUP when onReceivedError then emits Unreachable`() {
        testReceivedError(
            errorCode = ERROR_HOST_LOOKUP,
            expectedMessageRes = commonR.string.webview_error_HOST_LOOKUP,
            expectedErrorType = FrontendConnectionError.Unreachable::class,
        )
    }

    @Test
    fun `Given ERROR_TIMEOUT when onReceivedError then emits Timeout`() {
        testReceivedError(
            errorCode = ERROR_TIMEOUT,
            expectedMessageRes = commonR.string.webview_error_TIMEOUT,
            expectedErrorType = FrontendConnectionError.Timeout::class,
        )
    }

    @Test
    fun `Given ERROR_CONNECT when onReceivedError then emits Unreachable`() {
        testReceivedError(
            errorCode = ERROR_CONNECT,
            expectedMessageRes = commonR.string.webview_error_CONNECT,
            expectedErrorType = FrontendConnectionError.Unreachable::class,
        )
    }

    @Test
    fun `Given unknown error code when onReceivedError then emits Unknown`() {
        testReceivedError(
            errorCode = -999,
            expectedMessageRes = commonR.string.connection_error_unknown_error,
            expectedErrorType = FrontendConnectionError.Unknown::class,
        )
    }

    @Test
    fun `Given error without description when onReceivedError then emits error with no description`() {
        val webView = mockWebView()
        val currentUrl = "http://homeassistant.local:8123/auth/authorize"
        currentUrlFlow.value = currentUrl
        val request = mockRequest(currentUrl)
        val error = mockk<WebResourceError> {
            every { errorCode } returns -1
            every { description } returns ""
        }

        webViewClient.onReceivedError(webView, request, error)

        assertFrontendError<FrontendConnectionError.Unknown>(
            commonR.string.connection_error_unknown_error,
            errorDetails(-1, "No description"),
            WebResourceError::class,
        )
    }

    @Test
    fun `Given error for different URL when onReceivedError then does not emit error`() {
        val webView = mockWebView()
        currentUrlFlow.value = "http://homeassistant.local:8123/auth/authorize"
        val request = mockRequest("http://different-url.com/something")
        val error = mockk<WebResourceError> {
            every { errorCode } returns ERROR_HOST_LOOKUP
            every { description } returns "Host lookup failed"
        }

        webViewClient.onReceivedError(webView, request, error)

        assertEquals(null, capturedError)
    }

    private fun testReceivedError(
        errorCode: Int,
        @StringRes expectedMessageRes: Int,
        expectedErrorType: KClass<out FrontendConnectionError>,
    ) {
        val webView = mockWebView()
        val currentUrl = "http://homeassistant.local:8123/auth/authorize"
        currentUrlFlow.value = currentUrl
        val request = mockRequest(currentUrl)
        val description = "Error description"
        val error = mockk<WebResourceError> {
            every { this@mockk.errorCode } returns errorCode
            every { this@mockk.description } returns description
        }

        webViewClient.onReceivedError(webView, request, error)

        assertNotNull(capturedError)
        assertTrue(expectedErrorType.isInstance(capturedError))
        assertEquals(expectedMessageRes, capturedError?.message)
        assertEquals(errorDetails(errorCode, description), capturedError?.errorDetails)
        assertEquals(WebResourceError::class.toString(), capturedError?.rawErrorType)
    }

    private inline fun <reified T : FrontendConnectionError> assertFrontendError(
        @StringRes messageId: Int,
        errorDetails: String?,
        errorClass: KClass<*>,
    ) {
        assertNotNull(capturedError)
        assertTrue(capturedError is T, "Expected ${T::class.simpleName} but got ${capturedError?.let { it::class.simpleName }}")
        assertEquals(messageId, capturedError?.message)
        assertEquals(errorDetails, capturedError?.errorDetails)
        assertEquals(errorClass.toString(), capturedError?.rawErrorType)
    }

    private fun errorDetails(code: Int?, description: String?): String {
        return "Status Code: ${code}\nDescription: $description"
    }

    private fun mockRequest(url: String) = mockk<android.webkit.WebResourceRequest> {
        every { this@mockk.url } returns mockk {
            every { this@mockk.toString() } returns url
        }
    }

    @Test
    fun `Given main-frame https redirect when shouldOverrideUrlLoading then follows in WebView`() {
        val request = mockNavRequest(
            scheme = "https",
            targetHost = "auth.example.com",
            isForMainFrame = true,
            isRedirect = true,
        )

        val shouldOverride = webViewClient.shouldOverrideUrlLoading(mockWebView(), request)

        assertFalse(shouldOverride, "WebView should follow main-frame auth-provider redirects")
    }

    @Test
    fun `Given main-frame http redirect when shouldOverrideUrlLoading then follows in WebView`() {
        val request = mockNavRequest(
            scheme = "http",
            targetHost = "auth.example.com",
            isForMainFrame = true,
            isRedirect = true,
        )

        val shouldOverride = webViewClient.shouldOverrideUrlLoading(mockWebView(), request)

        assertFalse(shouldOverride, "WebView should follow main-frame auth-provider redirects")
    }

    @Test
    fun `Given WebView currently off HA anchor when isAuthProviderNavigation then true`() {
        webViewClient.serverHost = "ha.example.com"
        val request = mockNavRequest(
            scheme = "https",
            targetHost = "accounts.google.com",
            isForMainFrame = true,
            isRedirect = false,
        )

        val webView = mockWebView(currentUrl = "https://myorg.cloudflareaccess.com/login")

        assertTrue(
            webViewClient.isAuthProviderNavigation(webView, request),
            "Click-initiated navigation from an auth proxy page should stay inside the WebView",
        )
    }

    @Test
    fun `Given WebView on HA anchor when user taps external link then isAuthProviderNavigation is false`() {
        webViewClient.serverHost = "ha.example.com"
        val request = mockNavRequest(
            scheme = "https",
            targetHost = "external.example.com",
            isForMainFrame = true,
            isRedirect = false,
        )

        val webView = mockWebView(currentUrl = "https://ha.example.com/lovelace")

        assertFalse(
            webViewClient.isAuthProviderNavigation(webView, request),
            "Links tapped on the HA frontend should fall through to the system browser",
        )
    }

    @Test
    fun `Given no serverHost set when user taps non-redirect link then isAuthProviderNavigation is false`() {
        val request = mockNavRequest(
            scheme = "https",
            targetHost = "external.example.com",
            isForMainFrame = true,
            isRedirect = false,
        )

        val webView = mockWebView(currentUrl = "https://ha.example.com/lovelace")

        assertFalse(
            webViewClient.isAuthProviderNavigation(webView, request),
            "Without an anchor the existing behaviour (system browser) is preserved for non-redirects",
        )
    }

    @Test
    fun `Given sub-frame redirect then isAuthProviderNavigation is false`() {
        val request = mockNavRequest(
            scheme = "https",
            targetHost = "iframe.example.com",
            isForMainFrame = false,
            isRedirect = true,
        )

        assertFalse(
            webViewClient.isAuthProviderNavigation(mockWebView(), request),
            "Only main-frame redirects represent an auth handshake; iframes should not short-circuit",
        )
    }

    @Test
    fun `Given non-http scheme main-frame redirect then isAuthProviderNavigation is false`() {
        val request = mockNavRequest(
            scheme = "homeassistant",
            targetHost = null,
            isForMainFrame = true,
            isRedirect = true,
        )

        assertFalse(
            webViewClient.isAuthProviderNavigation(mockWebView(), request),
            "Non-http(s) schemes (e.g. app OAuth callbacks) must still reach the URL interceptor",
        )
    }

    @Test
    fun `Given null request then isAuthProviderNavigation is false`() {
        assertFalse(webViewClient.isAuthProviderNavigation(mockWebView(), null))
    }

    private fun mockNavRequest(
        scheme: String,
        targetHost: String?,
        isForMainFrame: Boolean,
        isRedirect: Boolean,
    ): WebResourceRequest {
        val uri = mockk<Uri> {
            every { this@mockk.scheme } returns scheme
            every { this@mockk.host } returns targetHost
            every { this@mockk.toString() } returns "$scheme://${targetHost ?: ""}/"
        }
        return mockk {
            every { this@mockk.url } returns uri
            every { this@mockk.isForMainFrame } returns isForMainFrame
            every { this@mockk.isRedirect } returns isRedirect
        }
    }

    private fun mockWebView(currentUrl: String? = null): WebView {
        return mockk {
            every { url } returns currentUrl
            every { context } returns mockk {
                val code = slot<String>()
                val detail = slot<String>()
                every { getString(any(), capture(code), capture(detail)) } answers {
                    errorDetails(code.captured.toIntOrNull(), detail.captured)
                }
                every { getString(commonR.string.no_description) } returns "No description"
            }
        }
    }
}
