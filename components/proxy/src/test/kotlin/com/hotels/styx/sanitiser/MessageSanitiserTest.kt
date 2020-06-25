package com.hotels.styx.sanitiser

import com.hotels.styx.api.HttpResponseStatus.OK
import com.hotels.styx.api.LiveHttpRequest
import com.hotels.styx.api.LiveHttpResponse.response
import com.hotels.styx.api.RequestCookie.requestCookie
import com.hotels.styx.api.ResponseCookie
import com.hotels.styx.api.ResponseCookie.responseCookie
import io.kotlintest.shouldBe
import io.kotlintest.specs.FeatureSpec
import io.netty.handler.codec.http.cookie.Cookie
import java.util.*

class MessageSanitiserTest : FeatureSpec({

    feature("Sanitises HTTP Request Headers") {
        val sanitiser = MessageSanitiser(
                setOf("header1", "header2", "header3"),
                setOf())

        val view = sanitiser.view(LiveHttpRequest.get("/")
                .header("Content-Length", "123")
                .header("header1", "header1-value")
                .addHeader("header3", "header3-value-1")
                .addHeader("header3", "header3-value-2")
                .header("User-Agent", "Mozilla 32.53")
                .build())

        scenario("view.headers() method") {
            view.headers()
                    .let { headers ->
                        headers["Content-Length"] shouldBe Optional.of("123")
                        headers["header1"] shouldBe Optional.of("***")
                        headers.getAll("header3") shouldBe listOf("***", "***")
                        headers["User-Agent"] shouldBe Optional.of("Mozilla 32.53")
                    }
        }

        scenario("retains original headers only") {
            view.headers().names() shouldBe setOf("Content-Length", "header1", "header3", "User-Agent")
        }

        scenario("view.header(CharSequence) method") {
            view.header("Content-Length") shouldBe Optional.of("123")
            view.header("header1") shouldBe Optional.of("***")
            view.header("header3") shouldBe Optional.of("***")
            view.header("User-Agent") shouldBe Optional.of("Mozilla 32.53")
        }

        scenario("view.headers(CharSequence) method") {
            view.headers("Content-Length") shouldBe listOf("123")
            view.headers("header1") shouldBe listOf("***")
            view.headers("header3") shouldBe listOf("***", "***")
            view.headers("User-Agent") shouldBe listOf("Mozilla 32.53")
        }
    }

    feature("Sanitises HTTP Response Headers") {
        val sanitiser = MessageSanitiser(
                setOf("header1", "header2", "header3"),
                setOf())

        val view = sanitiser.view(response(OK)
                .header("Content-Length", "123")
                .header("header1", "header1-value")
                .addHeader("header3", "header3-value-1")
                .addHeader("header3", "header3-value-2")
                .header("X-Origin-Id", "Remote-Server")
                .build())

        scenario("view.headers() method") {
            view.headers()
                    .let { headers ->
                        headers["Content-Length"] shouldBe Optional.of("123")
                        headers["header1"] shouldBe Optional.of("***")
                        headers.getAll("header3") shouldBe listOf("***", "***")
                        headers["X-Origin-Id"] shouldBe Optional.of("Remote-Server")
                    }
        }

        scenario("retains original headers only") {
            view.headers().names() shouldBe setOf("Content-Length", "header1", "header3", "X-Origin-Id")
        }

        scenario("view.header(CharSequence) method") {
            view.header("Content-Length") shouldBe Optional.of("123")
            view.header("header1") shouldBe Optional.of("***")
            view.header("header3") shouldBe Optional.of("***")
            view.header("X-Origin-Id") shouldBe Optional.of("Remote-Server")
        }

        scenario("view.headers(CharSequence) method") {
            view.headers("Content-Length") shouldBe listOf("123")
            view.headers("header1") shouldBe listOf("***")
            view.headers("header3") shouldBe listOf("***", "***")
            view.headers("X-Origin-Id") shouldBe listOf("Remote-Server")
        }
    }

    feature("Sanitises HTTP request cookies") {
        val sanitiser = MessageSanitiser(
                setOf(),
                setOf("__Secure-A", "SSID"))

        val message = LiveHttpRequest.get("/")
                .header("Content-Length", "123")
                .header("User-Agent", "Mozilla 32.53")
                .addCookies(
                        requestCookie("SID", "123"),
                        requestCookie("__Secure-A", "foo"),
                        requestCookie("__Secure-B", "bar"),
                        requestCookie("__Secure-C", "baz"),
                        requestCookie("SSID", "abc"),
                        requestCookie("APISID", "def")
                )
                .build()

        val view = sanitiser.view(message)

        scenario("view.cookies() method") {
            view.cookies() shouldBe setOf(
                    requestCookie("SID", "123"),
                    requestCookie("__Secure-A", "***"),
                    requestCookie("__Secure-B", "bar"),
                    requestCookie("__Secure-C", "baz"),
                    requestCookie("SSID", "***"),
                    requestCookie("APISID", "def")
            )
        }

        scenario("view.cookie(String) method") {
            view.cookie("SID") shouldBe Optional.of(requestCookie("SID", "123"))
            view.cookie("__Secure-A") shouldBe Optional.of(requestCookie("__Secure-A", "***"))
            view.cookie("__Secure-B") shouldBe Optional.of(requestCookie("__Secure-B", "bar"))
            view.cookie("__Secure-C") shouldBe Optional.of(requestCookie("__Secure-C", "baz"))
            view.cookie("SSID") shouldBe Optional.of(requestCookie("SSID", "***"))
            view.cookie("APISID") shouldBe Optional.of(requestCookie("APISID", "def"))
        }
    }

    feature("Sanitises HTTP response cookies") {
        val sanitiser = MessageSanitiser(
                setOf(),
                setOf("__Secure-A", "SSID"))

        val view = sanitiser.view(response(OK)
                .header("Content-Length", "123")
                .header("User-Agent", "Mozilla 32.53")
                .addCookies(
                        SetCookie("SID",
                                "123",
                                "mydomain",
                                123L,
                                "/mypath",
                                true,
                                true,
                                "samesite-abc")
                                .asJava(),
                        SetCookie("__Secure-A",
                                "foo",
                                "mydomain",
                                123L,
                                "/mypath",
                                true,
                                true,
                                "samesite-abc")
                                .asJava(),
                        SetCookie("__Secure-B", "bar", "mydomain").asJava(),
                        SetCookie("__Secure-C",
                                "baz",
                                "mydomain",
                                123L,
                                "/mypath",
                                true,
                                true,
                                "samesite-abc")
                                .asJava(),
                        SetCookie("SSID", "abc", "mydomain").asJava(),
                        SetCookie("APISID", "def", "mydomain").asJava()
                )
                .build())

        scenario("view.cookies() method") {
            view.cookies()
                    .map { it.asKotlin() }
                    .toSet() shouldBe setOf(
                    SetCookie("SID",
                            "123",
                            "mydomain",
                            123L,
                            "/mypath",
                            true,
                            true,
                            "samesite-abc"),
                    SetCookie("__Secure-A",
                            "***",
                            "mydomain",
                            123L,
                            "/mypath",
                            true,
                            true,
                            "samesite-abc"),
                    SetCookie("__Secure-B", "bar", "mydomain"),
                    SetCookie("__Secure-C",
                            "baz",
                            "mydomain",
                            123L,
                            "/mypath",
                            true,
                            true,
                            "samesite-abc"),
                    SetCookie("SSID", "***", "mydomain"),
                    SetCookie("APISID", "def", "mydomain"))
        }

        scenario("view.cookie(String) method") {
            view.cookie("SID").get().asKotlin() shouldBe SetCookie(
                    "SID",
                    "123",
                    "mydomain",
                    123L,
                    "/mypath",
                    true,
                    true,
                    "samesite-abc")
            view.cookie("__Secure-A").get().asKotlin() shouldBe SetCookie(
                    "__Secure-A",
                    "***",
                    "mydomain",
                    123L,
                    "/mypath",
                    true,
                    true,
                    "samesite-abc")
            view.cookie("__Secure-B").get().asKotlin() shouldBe SetCookie("__Secure-B", "bar", "mydomain")
            view.cookie("__Secure-C").get().asKotlin() shouldBe SetCookie(
                    "__Secure-C",
                    "baz",
                    "mydomain",
                    123L,
                    "/mypath",
                    true,
                    true,
                    "samesite-abc")
            view.cookie("SSID").get().asKotlin() shouldBe SetCookie("SSID", "***", "mydomain")
            view.cookie("APISID").get().asKotlin() shouldBe SetCookie("APISID", "def", "mydomain")
        }
    }
})

private data class SetCookie(
        val name: String,
        val value: String,
        val domain: String,
        val maxAge: Long = Cookie.UNDEFINED_MAX_AGE,
        val path: String? = null,
        val httpOnly: Boolean = false,
        val secure: Boolean = false,
        val sameSite: String? = null
)

private fun ResponseCookie.asKotlin() = SetCookie(
        name(),
        value(),
        domain().orElse(""),
        maxAge().orElse(Cookie.UNDEFINED_MAX_AGE),
        path().orElse(null),
        httpOnly(),
        secure(),
        sameSite().orElse(null))

private fun SetCookie.asJava() = responseCookie(name, value)
        .domain(domain)
        .maxAge(maxAge)
        .path(path)
        .httpOnly(httpOnly)
        .secure(secure)
        .sameSiteRawValue(sameSite)
        .build()
