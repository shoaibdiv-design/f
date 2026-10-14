package com.shobi.fieldtracker

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Error message sent back by the Odoo module (or a clear message about the connection). */
class ApiException(message: String, val httpCode: Int) : Exception(message)

object Api {
    const val LOGIN = "/field_tracking/api/login"
    const val CHANGE_PASSWORD = "/field_tracking/api/change_password"
    const val LOGOUT = "/field_tracking/api/logout"
    const val PUNCH = "/field_tracking/api/punch"
    const val TASKS = "/field_tracking/api/tasks"
    const val TASK_READ = "/field_tracking/api/task/read"
    const val TASK_START = "/field_tracking/api/task/start"
    const val TASK_COMPLETE = "/field_tracking/api/task/complete"
    const val STATUS = "/field_tracking/api/status"
    const val SHIFT_START = "/field_tracking/api/shift/start"
    const val SHIFT_END = "/field_tracking/api/shift/end"
    const val POINTS = "/field_tracking/api/points"

    /** Odoo answered with a plain 404 page: no database chosen, or module not installed there. */
    private class NoDatabaseException : Exception()

    private val IPV4 = Regex("""^\d{1,3}(\.\d{1,3}){3}$""")

    /** Call using the saved connection. Blocking: never run on the main thread. */
    @Throws(IOException::class, ApiException::class)
    fun post(prefs: Prefs, path: String, body: JSONObject): JSONObject =
        postRaw(
            prefs.serverUrl, prefs.dbName, path, prefs.token, body,
            getCookie = { prefs.sessionCookie },
            setCookie = { prefs.sessionCookie = it }
        )

    /**
     * When the server has several databases, Odoo picks the database from a session cookie.
     * We open a session on the chosen database once, keep its cookie, and renew it automatically
     * when Odoo forgets it (sessions expire after 7 days without use).
     */
    @Throws(IOException::class, ApiException::class)
    fun postRaw(
        baseUrl: String, db: String, path: String, token: String?, body: JSONObject,
        getCookie: () -> String, setCookie: (String) -> Unit
    ): JSONObject {
        if (db.isNotEmpty() && getCookie().isEmpty()) {
            setCookie(openDatabaseSession(baseUrl, db))
        }
        try {
            return once(baseUrl, path, token, body, getCookie())
        } catch (e: NoDatabaseException) {
            if (db.isEmpty()) {
                throw ApiException(
                    "This server has more than one database. Enter the database name, or check that the Field Tracking module is installed.",
                    404
                )
            }
            setCookie(openDatabaseSession(baseUrl, db))
            try {
                return once(baseUrl, path, token, body, getCookie())
            } catch (e2: NoDatabaseException) {
                throw ApiException(
                    "Database \"$db\" was not found, or the Field Tracking module isn't installed in it.",
                    404
                )
            }
        }
    }

    private fun once(baseUrl: String, path: String, token: String?, body: JSONObject, cookie: String): JSONObject {
        val conn = URL(baseUrl.trimEnd('/') + path).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            conn.doOutput = true
            conn.instanceFollowRedirects = false
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Accept", "application/json")
            if (!token.isNullOrEmpty()) conn.setRequestProperty("X-Tracking-Token", token)
            if (cookie.isNotEmpty()) conn.setRequestProperty("Cookie", "session_id=$cookie")
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }

            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            val json = try {
                JSONObject(text)
            } catch (e: Exception) {
                if (code == 404 || code in 300..399) throw NoDatabaseException()
                throw ApiException(
                    "The server gave an unexpected reply (HTTP $code). Check the server address.",
                    code
                )
            }
            if (code !in 200..299 || !json.optBoolean("ok", false)) {
                throw ApiException(json.optString("error", "Server error (HTTP $code)."), code)
            }
            return json
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Downloads the company logo when Odoo says it changed, and saves it on the phone.
     * Returns true when a new logo was saved (or the logo was removed in Odoo).
     */
    fun syncLogo(context: android.content.Context, prefs: Prefs): Boolean {
        val wanted = prefs.logoWanted
        if (wanted == prefs.logoSaved || prefs.companyId == 0) return false
        val file = java.io.File(context.filesDir, "company_logo")
        if (wanted.isEmpty()) {
            file.delete()
            prefs.logoSaved = ""
            return true
        }
        val conn = URL(prefs.serverUrl.trimEnd('/') + "/web/binary/company_logo?company=" + prefs.companyId)
            .openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            if (prefs.sessionCookie.isNotEmpty()) conn.setRequestProperty("Cookie", "session_id=" + prefs.sessionCookie)
            if (conn.responseCode != 200) return false
            val bytes = conn.inputStream.use { it.readBytes() }
            if (bytes.isEmpty() || bytes.size > 2_000_000) return false
            file.writeBytes(bytes)
            prefs.logoSaved = wanted
            return true
        } catch (e: Exception) {
            return false
        } finally {
            conn.disconnect()
        }
    }

    /** Renews the database session cookie (used before opening the manager screens). */
    @Throws(IOException::class, ApiException::class)
    fun refreshDatabaseSession(prefs: Prefs) {
        if (prefs.dbName.isNotEmpty()) prefs.sessionCookie = openDatabaseSession(prefs.serverUrl, prefs.dbName)
    }

    /** Opens /web/login?db=NAME and returns the session_id cookie Odoo sets for that database. */
    private fun openDatabaseSession(baseUrl: String, db: String): String {
        val url = baseUrl.trimEnd('/') + "/web/login?db=" + URLEncoder.encode(db, "UTF-8")
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            conn.instanceFollowRedirects = false
            conn.responseCode
            val cookies = conn.headerFields.entries
                .filter { it.key.equals("Set-Cookie", ignoreCase = true) }
                .flatMap { it.value }
            for (c in cookies) {
                val first = c.substringBefore(';').trim()
                if (first.startsWith("session_id=")) {
                    val value = first.removePrefix("session_id=")
                    if (value.isNotEmpty()) return value
                }
            }
            throw ApiException("Database \"$db\" was not found on this server. Check the spelling.", 404)
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Cleans what the rider typed. Without http:// or https://, an IP address (or localhost)
     * gets http://, a domain gets https://.
     */
    fun normalizeUrl(input: String): String {
        var url = input.trim().trimEnd('/')
        if (url.isEmpty()) return url
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            val host = url.substringBefore('/').substringBefore(':')
            url = if (IPV4.matches(host) || host == "localhost") "http://$url" else "https://$url"
        }
        // People often paste the full Odoo page address.
        val start = url.indexOf("//") + 2
        for (suffix in listOf("/odoo", "/web")) {
            val idx = url.indexOf(suffix, startIndex = start)
            if (idx > 0) url = url.substring(0, idx)
        }
        return url
    }
}
