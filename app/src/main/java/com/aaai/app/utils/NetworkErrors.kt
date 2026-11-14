package com.aaai.app.utils

import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/** HTTP error with a user-friendly message and the original status code. */
class HttpStatusException(val code: Int, message: String) : IOException(message)

/** Turns network / HTTP failures into short messages that can be shown to the user. */
object NetworkErrors {

    /** Message for an HTTP status code returned by a download or API server. */
    fun httpMessage(code: Int): String = when (code) {
        400 -> "The server rejected the request (HTTP 400)."
        401 -> "The server requires authorization (HTTP 401)."
        403 -> "Access denied (HTTP 403). The link may have expired or a request limit was reached."
        404 -> "File not found (HTTP 404). The link may be broken or no longer available."
        410 -> "This file is no longer available (HTTP 410)."
        429 -> "Too many requests (HTTP 429). Please try again later."
        in 500..599 -> "The server is having problems (HTTP $code). Please try again later."
        else -> "Unexpected server response (HTTP $code)."
    }

    /** Short message for any exception thrown while talking to the network. */
    fun describe(e: Throwable): String = when (e) {
        is HttpStatusException -> e.message ?: httpMessage(e.code)
        is UnknownHostException -> "No internet connection or the server could not be found."
        is SocketTimeoutException -> "The server took too long to respond. Please try again."
        is ConnectException -> "Could not connect to the server."
        is SSLException -> "Secure connection failed. Check the link and your device date/time."
        else -> e.message ?: e.javaClass.simpleName
    }
}
