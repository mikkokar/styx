package com.hotels.styx.sanitiser

import com.hotels.styx.api.*
import com.hotels.styx.api.HttpHeaderNames.COOKIE
import com.hotels.styx.api.HttpHeaderNames.SET_COOKIE
import com.hotels.styx.api.RequestCookie.requestCookie
import com.hotels.styx.api.ResponseCookie.responseCookie
import java.util.*
import java.util.stream.Collectors

data class MessageSanitiser(
        internal val headersToHide: Set<String> = setOf(),
        internal val cookiesToHide: Set<String> = setOf()) {

    fun view(message: LiveHttpRequest) = SanitisedRequestView(
            message.headers(),
            headersToHide,
            cookiesToHide)

    fun view(message: LiveHttpResponse) = SanitisedResponseView(
            message.headers(),
            headersToHide,
            cookiesToHide)
}


sealed class SanitisedView(
        messageHeaders: HttpHeaders,
        private val headersToHide: Set<String>) {

    private val headers = sanitise(messageHeaders)

    fun headers(): HttpHeaders = headers

    fun header(name: CharSequence): Optional<String> = headers.get(name)

    fun headers(name: CharSequence): List<String> = headers.getAll(name)

    private fun sanitise(headers: HttpHeaders) = if (headersToHide.isEmpty()) {
        headers
    } else {
        headers.newBuilder()
                .let { builder ->
                    headersToHide.forEach {
                        // Only sets the value if values list is non-empty:
                        builder.set(it, builder.getAll(it).map { "***" })
                    }
                    builder.build()
                }
    }
}


class SanitisedRequestView(
        messageHeaders: HttpHeaders,
        headersToHide: Set<String>,
        cookiesToHide: Set<String>) : SanitisedView(messageHeaders, headersToHide) {

    private val cookies = messageHeaders[COOKIE]
            .map { sanitiseCookies(it, cookiesToHide) }
            .orElse(emptySet())

    fun cookies(): Set<RequestCookie> = cookies

    fun cookie(name: String): Optional<RequestCookie> = cookies.stream()
            .filter { it.name() == name }
            .findFirst()

    private fun sanitiseCookies(cookieString: String, cookiesToHide: Set<String>): Set<RequestCookie> = RequestCookie.decode(cookieString)
            .stream()
            .map { cookie ->
                if (cookiesToHide.contains(cookie.name())) {
                    requestCookie(cookie.name(), "***")
                } else {
                    cookie;
                }
            }
            .collect(Collectors.toSet())
}

class SanitisedResponseView(
        messageHeaders: HttpHeaders,
        headersToHide: Set<String>,
        cookiesToHide: Set<String>) : SanitisedView(messageHeaders, headersToHide) {

    private val cookies = sanitiseCookies(messageHeaders.getAll(SET_COOKIE), cookiesToHide)

    fun cookies(): Set<ResponseCookie> = cookies

    fun cookie(name: String): Optional<ResponseCookie> = cookies.stream()
            .filter { it.name() == name }
            .findFirst()

    private fun sanitiseCookies(cookieString: List<String>, cookiesToHide: Set<String>): Set<ResponseCookie> = ResponseCookie.decode(cookieString)
            .stream()
            .map { cookie ->
                if (cookiesToHide.contains(cookie.name())) {

                    val builder = responseCookie(cookie.name(), "***")
                            .httpOnly(cookie.httpOnly())
                            .secure(cookie.secure())

                    cookie.sameSite().ifPresent { builder.sameSiteRawValue(it) }
                    cookie.domain().ifPresent { builder.domain(it) }
                    cookie.maxAge().ifPresent { builder.maxAge(it) }
                    cookie.path().ifPresent { builder.path(it) }

                    builder.build()
                } else {
                    cookie;
                }
            }
            .collect(Collectors.toSet())
}

