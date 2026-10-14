package com.shobi.fieldtracker

import android.content.Context

/** Small wrapper around SharedPreferences. */
class Prefs(context: Context) {
    private val sp = context.applicationContext
        .getSharedPreferences("field_tracker", Context.MODE_PRIVATE)

    var serverUrl: String
        get() = sp.getString("server_url", "") ?: ""
        set(v) = sp.edit().putString("server_url", v).apply()

    /** Database name; empty when the server has only one database. */
    var dbName: String
        get() = sp.getString("db_name", "") ?: ""
        set(v) = sp.edit().putString("db_name", v.trim()).apply()

    var sessionCookie: String
        get() = sp.getString("session_cookie", "") ?: ""
        set(v) = sp.edit().putString("session_cookie", v).apply()

    var username: String
        get() = sp.getString("username", "") ?: ""
        set(v) = sp.edit().putString("username", v).apply()

    /** Logged in with a temporary password: must choose a new one before using the app. */
    var mustChangePassword: Boolean
        get() = sp.getBoolean("must_change_password", false)
        set(v) = sp.edit().putBoolean("must_change_password", v).apply()

    /** Attendance: punched in right now (as last known from the server or this phone). */
    var punchedIn: Boolean
        get() = sp.getBoolean("punched_in", false)
        set(v) = sp.edit().putBoolean("punched_in", v).apply()

    var punchInAt: Long
        get() = sp.getLong("punch_in_at", 0L)
        set(v) = sp.edit().putLong("punch_in_at", v).apply()

    var todayPunches: String
        get() = sp.getString("today_punches", "") ?: ""
        set(v) = sp.edit().putString("today_punches", v).apply()

    /** Punches waiting to be sent (JSON array), so punching works without internet. */
    var pendingPunches: String
        get() = sp.getString("pending_punches", "[]") ?: "[]"
        set(v) = sp.edit().putString("pending_punches", v).apply()

    fun pendingPunchList(): org.json.JSONArray = try {
        org.json.JSONArray(pendingPunches)
    } catch (e: Exception) {
        org.json.JSONArray()
    }

    /** Inbox: messages and tasks as last received from Odoo (JSON array). */
    var tasksJson: String
        get() = sp.getString("tasks_json", "[]") ?: "[]"
        set(v) = sp.edit().putString("tasks_json", v).apply()

    var tasksFetchedAt: Long
        get() = sp.getLong("tasks_fetched_at", 0L)
        set(v) = sp.edit().putLong("tasks_fetched_at", v).apply()

    var openTasks: Int
        get() = sp.getInt("open_tasks", 0)
        set(v) = sp.edit().putInt("open_tasks", v).apply()

    /** Read / start / complete actions waiting to be sent (JSON array). */
    var pendingTaskActions: String
        get() = sp.getString("pending_task_actions", "[]") ?: "[]"
        set(v) = sp.edit().putString("pending_task_actions", v).apply()

    fun pendingTaskList(): org.json.JSONArray = try {
        org.json.JSONArray(pendingTaskActions)
    } catch (e: Exception) {
        org.json.JSONArray()
    }

    fun addTaskAction(action: org.json.JSONObject) {
        val list = pendingTaskList()
        list.put(action)
        pendingTaskActions = list.toString()
    }

    /** Inbox as the rider should see it: Odoo's list with not-yet-sent actions applied on top. */
    fun taskList(): org.json.JSONArray {
        val tasks = try {
            org.json.JSONArray(tasksJson)
        } catch (e: Exception) {
            org.json.JSONArray()
        }
        val pending = pendingTaskList()
        for (i in 0 until pending.length()) {
            val a = pending.getJSONObject(i)
            for (j in 0 until tasks.length()) {
                val t = tasks.getJSONObject(j)
                if (t.optLong("id") != a.optLong("id")) continue
                when (a.optString("action")) {
                    "read" -> t.put("state", "done").put("done_t", a.optLong("t"))
                    "start" -> if (t.optString("state") == "new") t.put("state", "in_progress")
                    "complete" -> {
                        t.put("state", "done").put("done_t", a.optLong("t")).put("note", a.optString("note"))
                        val checked = a.optJSONArray("checked")
                        val ids = HashSet<Long>()
                        if (checked != null) for (k in 0 until checked.length()) ids.add(checked.optLong(k))
                        val lines = t.optJSONArray("lines")
                        if (lines != null) for (k in 0 until lines.length()) {
                            val line = lines.getJSONObject(k)
                            line.put("done", ids.contains(line.optLong("id")))
                        }
                    }
                }
            }
        }
        return tasks
    }

    fun openTaskCount(): Int {
        if (tasksFetchedAt == 0L) return openTasks
        val list = taskList()
        var n = 0
        for (i in 0 until list.length()) {
            val st = list.getJSONObject(i).optString("state")
            if (st == "new" || st == "in_progress") n++
        }
        return n
    }

    /** "rider" or "manager": managers get the manager screens instead of the rider screens. */
    var role: String
        get() = sp.getString("role", "rider") ?: "rider"
        set(v) = sp.edit().putString("role", v).apply()

    val isManager: Boolean get() = isPaired && role == "manager"

    /** Company of the logged-in user (from Odoo): name and logo. */
    var companyName: String
        get() = sp.getString("company_name", "") ?: ""
        set(v) = sp.edit().putString("company_name", v).apply()

    var companyId: Int
        get() = sp.getInt("company_id", 0)
        set(v) = sp.edit().putInt("company_id", v).apply()

    /** Logo version wanted by Odoo, and the version saved on the phone. */
    var logoWanted: String
        get() = sp.getString("logo_wanted", "") ?: ""
        set(v) = sp.edit().putString("logo_wanted", v).apply()

    var logoSaved: String
        get() = sp.getString("logo_saved", "") ?: ""
        set(v) = sp.edit().putString("logo_saved", v).apply()

    var token: String
        get() = sp.getString("token", "") ?: ""
        set(v) = sp.edit().putString("token", v).apply()

    var employeeName: String
        get() = sp.getString("employee_name", "") ?: ""
        set(v) = sp.edit().putString("employee_name", v).apply()

    var employeeInfo: String
        get() = sp.getString("employee_info", "") ?: ""
        set(v) = sp.edit().putString("employee_info", v).apply()

    var onShift: Boolean
        get() = sp.getBoolean("on_shift", false)
        set(v) = sp.edit().putBoolean("on_shift", v).apply()

    var intervalSeconds: Int
        get() = sp.getInt("interval_seconds", 30)
        set(v) = sp.edit().putInt("interval_seconds", v.coerceIn(10, 600)).apply()

    var lastSentAt: Long
        get() = sp.getLong("last_sent_at", 0L)
        set(v) = sp.edit().putLong("last_sent_at", v).apply()

    /** Time the rider tapped End shift while offline; sent once internet is back. */
    var pendingEndAt: Long
        get() = sp.getLong("pending_end_at", 0L)
        set(v) = sp.edit().putLong("pending_end_at", v).apply()

    var lastError: String
        get() = sp.getString("last_error", "") ?: ""
        set(v) = sp.edit().putString("last_error", v).apply()

    /** One-time message for the rider (e.g. shift ended from Odoo). */
    var notice: String
        get() = sp.getString("notice", "") ?: ""
        set(v) = sp.edit().putString("notice", v).apply()

    val isPaired: Boolean get() = token.isNotEmpty() && serverUrl.isNotEmpty()

    fun saveProfile(json: org.json.JSONObject) {
        val emp = json.optJSONObject("employee")
        if (emp != null) {
            employeeName = emp.optString("name")
            employeeInfo = emp.optString("job")
        }
        json.optJSONObject("company")?.let {
            companyName = it.optString("name")
            companyId = it.optInt("id")
            logoWanted = it.optString("logo_v")
        }
        if (json.has("must_change_password")) {
            mustChangePassword = json.optBoolean("must_change_password", false)
        }
        json.optString("username").takeIf { it.isNotEmpty() }?.let { username = it }
        json.optJSONObject("attendance")?.let {
            // While punches are still waiting to be sent, this phone knows better than the server.
            if (pendingPunchList().length() == 0) {
                punchedIn = it.optBoolean("punched_in", false)
                punchInAt = it.optLong("punch_in_t", 0L)
                todayPunches = it.optString("today", "")
            }
        }
        if (json.has("open_tasks") && pendingTaskList().length() == 0) {
            openTasks = json.optInt("open_tasks", openTasks)
        }
        json.optJSONObject("settings")?.let {
            intervalSeconds = it.optInt("interval_seconds", intervalSeconds)
        }
    }

    fun unpair() {
        sp.edit()
            .remove("token").remove("role").remove("employee_name").remove("employee_info").remove("must_change_password")
            .remove("punched_in").remove("punch_in_at").remove("today_punches").remove("pending_punches")
            .remove("tasks_json").remove("tasks_fetched_at").remove("open_tasks").remove("pending_task_actions")
            .putBoolean("on_shift", false).remove("pending_end_at")
            .remove("last_error").remove("last_sent_at")
            .apply()
    }
}
