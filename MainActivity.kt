package com.shobi.fieldtracker

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.text.format.DateUtils
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import android.location.Location
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private val worker = Executors.newSingleThreadExecutor()
    private val ui = Handler(Looper.getMainLooper())
    private var busy = false

    /** Temporary password typed at login, kept in memory only, so the rider isn't asked for it twice. */
    @Volatile private var tempPassword: String? = null
    /** Rider tapped Change password on the main screen. */
    private var changingPassword = false

    private lateinit var setupPanel: LinearLayout
    private lateinit var serverInput: TextInputEditText
    private lateinit var dbInput: TextInputEditText
    private lateinit var usernameInput: TextInputEditText
    private lateinit var passwordInput: TextInputEditText
    private lateinit var setupError: TextView
    private lateinit var loginButton: MaterialButton

    private lateinit var passwordPanel: LinearLayout
    private lateinit var passwordTitle: TextView
    private lateinit var passwordHelp: TextView
    private lateinit var currentPasswordLayout: TextInputLayout
    private lateinit var currentPasswordInput: TextInputEditText
    private lateinit var newPasswordInput: TextInputEditText
    private lateinit var repeatPasswordInput: TextInputEditText
    private lateinit var passwordError: TextView
    private lateinit var savePasswordButton: MaterialButton
    private lateinit var cancelPasswordButton: MaterialButton

    private lateinit var loginCompany: LinearLayout
    private lateinit var loginLogo: android.widget.ImageView
    private lateinit var loginCompanyName: TextView
    private lateinit var companyLogo: android.widget.ImageView
    private lateinit var companyNameText: TextView
    private var logoShown = "-"

    private lateinit var homeTab: MaterialButton
    private lateinit var inboxTab: MaterialButton
    private lateinit var homeContent: LinearLayout
    private lateinit var inboxContent: LinearLayout
    private lateinit var detailContent: LinearLayout
    private lateinit var inbox: InboxUi
    private var tab = "home"
    private var tickCount = 0

    private lateinit var attendanceBox: LinearLayout
    private lateinit var attendanceTitle: TextView
    private lateinit var attendanceDetail: TextView
    private lateinit var punchButton: MaterialButton
    private lateinit var punchesText: TextView
    private var punching = false

    /** What to do once location permission is granted (start shift or punch). */
    private var afterPermission: (() -> Unit)? = null

    private lateinit var trackingPanel: LinearLayout
    private lateinit var nameText: TextView
    private lateinit var infoText: TextView
    private lateinit var statusBox: LinearLayout
    private lateinit var statusTitle: TextView
    private lateinit var statusDetail: TextView
    private lateinit var shiftButton: MaterialButton
    private lateinit var batteryButton: MaterialButton
    private lateinit var batteryHelp: TextView
    private lateinit var changePasswordButton: MaterialButton
    private lateinit var logoutButton: MaterialButton

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            if (hasLocationPermission()) {
                afterPermission?.invoke()
                afterPermission = null
            } else {
                AlertDialog.Builder(this)
                    .setMessage(R.string.need_location)
                    .setPositiveButton(R.string.open_settings) { _, _ ->
                        startActivity(
                            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
                        )
                    }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
            }
        }

    private val ticker = object : Runnable {
        override fun run() {
            render()
            tickCount++
            if (tickCount % 15 == 0 && prefs.isPaired) syncNow() // about every 30 seconds while open
            ui.postDelayed(this, 2000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = Prefs(this)
        if (prefs.isManager) {
            openManagerScreens()
            return
        }

        setupPanel = findViewById(R.id.setupPanel)
        serverInput = findViewById(R.id.serverInput)
        dbInput = findViewById(R.id.dbInput)
        usernameInput = findViewById(R.id.usernameInput)
        passwordInput = findViewById(R.id.passwordInput)
        setupError = findViewById(R.id.setupError)
        loginButton = findViewById(R.id.loginButton)

        passwordPanel = findViewById(R.id.passwordPanel)
        passwordTitle = findViewById(R.id.passwordTitle)
        passwordHelp = findViewById(R.id.passwordHelp)
        currentPasswordLayout = findViewById(R.id.currentPasswordLayout)
        currentPasswordInput = findViewById(R.id.currentPasswordInput)
        newPasswordInput = findViewById(R.id.newPasswordInput)
        repeatPasswordInput = findViewById(R.id.repeatPasswordInput)
        passwordError = findViewById(R.id.passwordError)
        savePasswordButton = findViewById(R.id.savePasswordButton)
        cancelPasswordButton = findViewById(R.id.cancelPasswordButton)

        loginCompany = findViewById(R.id.loginCompany)
        loginLogo = findViewById(R.id.loginLogo)
        loginCompanyName = findViewById(R.id.loginCompanyName)
        companyLogo = findViewById(R.id.companyLogo)
        companyNameText = findViewById(R.id.companyNameText)
        homeTab = findViewById(R.id.homeTab)
        inboxTab = findViewById(R.id.inboxTab)
        homeContent = findViewById(R.id.homeContent)
        inboxContent = findViewById(R.id.inboxContent)
        detailContent = findViewById(R.id.detailContent)
        inbox = InboxUi(this, prefs, inboxContent, detailContent)
        attendanceBox = findViewById(R.id.attendanceBox)
        attendanceTitle = findViewById(R.id.attendanceTitle)
        attendanceDetail = findViewById(R.id.attendanceDetail)
        punchButton = findViewById(R.id.punchButton)
        punchesText = findViewById(R.id.punchesText)
        trackingPanel = findViewById(R.id.trackingPanel)
        nameText = findViewById(R.id.nameText)
        infoText = findViewById(R.id.infoText)
        statusBox = findViewById(R.id.statusBox)
        statusTitle = findViewById(R.id.statusTitle)
        statusDetail = findViewById(R.id.statusDetail)
        shiftButton = findViewById(R.id.shiftButton)
        batteryButton = findViewById(R.id.batteryButton)
        batteryHelp = findViewById(R.id.batteryHelp)
        changePasswordButton = findViewById(R.id.changePasswordButton)
        logoutButton = findViewById(R.id.logoutButton)

        serverInput.setText(prefs.serverUrl)
        dbInput.setText(prefs.dbName)
        usernameInput.setText(prefs.username)

        loginButton.setOnClickListener { login() }
        savePasswordButton.setOnClickListener { savePassword() }
        cancelPasswordButton.setOnClickListener {
            changingPassword = false
            clearPasswordInputs()
            render()
        }
        shiftButton.setOnClickListener { if (prefs.onShift) confirmEndShift() else startShift() }
        punchButton.setOnClickListener {
            if (prefs.punchedIn) {
                AlertDialog.Builder(this)
                    .setMessage(R.string.punch_out_confirm)
                    .setPositiveButton(R.string.punch_out) { _, _ -> withLocationPermission { punch("out") } }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
            } else {
                withLocationPermission { punch("in") }
            }
        }
        batteryButton.setOnClickListener { askBatteryExemption() }
        changePasswordButton.setOnClickListener {
            changingPassword = true
            clearPasswordInputs()
            render()
        }
        logoutButton.setOnClickListener { confirmLogout() }
        homeTab.setOnClickListener {
            tab = "home"
            render()
        }
        inboxTab.setOnClickListener {
            tab = "inbox"
            inbox.showList()
            prefs.tasksFetchedAt = 1L // fetch a fresh list now
            render()
            syncNow()
        }
    }

    override fun onResume() {
        super.onResume()
        if (prefs.isManager) return
        showNotice()
        if (prefs.isPaired) {
            // Phone restarted or app was closed during a shift: resume tracking.
            if (prefs.onShift && !TrackingService.running && hasLocationPermission()) {
                TrackingService.start(this)
            }
            refreshFromServer()
        }
        ui.post(ticker)
    }

    override fun onPause() {
        super.onPause()
        ui.removeCallbacks(ticker)
    }

    override fun onDestroy() {
        worker.shutdown()
        super.onDestroy()
    }

    // ------------------------------------------------------------------ log in

    private fun login() {
        val url = Api.normalizeUrl(serverInput.text?.toString() ?: "")
        val db = (dbInput.text?.toString() ?: "").trim()
        val username = (usernameInput.text?.toString() ?: "").trim()
        val password = passwordInput.text?.toString() ?: ""
        when {
            url.isEmpty() -> return showSetupError("Enter the server address.")
            username.isEmpty() -> return showSetupError("Enter your username.")
            password.isEmpty() -> return showSetupError("Enter your password.")
        }
        setBusy(true, loginButton, R.string.connecting)
        val body = JSONObject()
            .put("login", username)
            .put("password", password)
            .put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
        worker.execute {
            var cookie = if (url == prefs.serverUrl && db == prefs.dbName) prefs.sessionCookie else ""
            val error: String? = try {
                val reply = Api.postRaw(url, db, Api.LOGIN, null, body, { cookie }, { cookie = it })
                prefs.serverUrl = url
                prefs.dbName = db
                prefs.sessionCookie = cookie
                prefs.username = username
                prefs.token = reply.getString("token")
                prefs.role = reply.optString("role", "rider")
                prefs.saveProfile(reply)
                prefs.onShift = reply.optBoolean("on_shift", false) && prefs.onShift
                prefs.lastError = ""
                tempPassword = if (prefs.mustChangePassword) password else null
                Api.syncLogo(this, prefs)
                null
            } catch (e: ApiException) {
                e.message
            } catch (e: IOException) {
                "Can't reach the server. Check the address and your internet connection."
            } catch (e: Exception) {
                "Check the server address."
            }
            ui.post {
                setBusy(false, loginButton, R.string.login)
                if (error == null && prefs.isManager) {
                    openManagerScreens()
                    return@post
                }
                if (error != null) showSetupError(error) else {
                    passwordInput.setText("")
                    setupError.visibility = View.GONE
                    clearPasswordInputs()
                    render()
                }
            }
        }
    }

    private fun showSetupError(message: String) {
        setupError.text = message
        setupError.visibility = View.VISIBLE
    }

    // ------------------------------------------------------------------ password

    private fun clearPasswordInputs() {
        currentPasswordInput.setText("")
        newPasswordInput.setText("")
        repeatPasswordInput.setText("")
        passwordError.visibility = View.GONE
    }

    private fun showPasswordError(message: String) {
        passwordError.text = message
        passwordError.visibility = View.VISIBLE
    }

    private fun savePassword() {
        val old = tempPassword ?: (currentPasswordInput.text?.toString() ?: "")
        val new = (newPasswordInput.text?.toString() ?: "").trim()
        val repeat = (repeatPasswordInput.text?.toString() ?: "").trim()
        when {
            old.isEmpty() -> return showPasswordError("Enter your current password.")
            new.length < 6 -> return showPasswordError("The new password must have at least 6 characters.")
            new != repeat -> return showPasswordError("The two new passwords don't match.")
        }
        setBusy(true, savePasswordButton, R.string.saving)
        worker.execute {
            val body = JSONObject().put("old_password", old).put("new_password", new)
            var loggedOut = false
            val error: String? = try {
                val reply = Api.post(prefs, Api.CHANGE_PASSWORD, body)
                prefs.saveProfile(reply)
                prefs.mustChangePassword = false
                null
            } catch (e: ApiException) {
                if (e.httpCode == 401) {
                    loggedOut = true
                    prefs.unpair()
                    getString(R.string.unpaired_by_server)
                } else e.message
            } catch (e: IOException) {
                "No internet. Connect to mobile data or Wi-Fi and try again."
            }
            ui.post {
                setBusy(false, savePasswordButton, R.string.save_password)
                if (error == null) {
                    tempPassword = null
                    changingPassword = false
                    clearPasswordInputs()
                    Toast.makeText(this, R.string.password_saved, Toast.LENGTH_SHORT).show()
                } else if (loggedOut) {
                    tempPassword = null
                    changingPassword = false
                    AlertDialog.Builder(this).setMessage(error).setPositiveButton(android.R.string.ok, null).show()
                } else {
                    showPasswordError(error)
                }
                render()
            }
        }
    }

    // ------------------------------------------------------------------ server status

    private fun openManagerScreens() {
        startActivity(Intent(this, ManagerActivity::class.java))
        finish()
    }

    /** Send waiting punches, inbox actions and locations, then refresh the screen. */
    fun syncNow() {
        if (worker.isShutdown) return
        worker.execute {
            val result = Uploader.sync(this)
            if (result == Uploader.Result.UNPAIRED || result == Uploader.Result.ENDED_BY_SERVER) {
                TrackingService.stop(this)
            }
            if (prefs.isPaired) Api.syncLogo(this, prefs)
            ui.post {
                showNotice()
                inbox.refreshDetailIfChanged()
                render()
            }
        }
    }

    fun onInboxNavigation() {
        render()
    }

    private fun handleLoggedOutByServer() {
        PointQueue.get(this).clear()
        prefs.unpair()
        prefs.notice = getString(R.string.unpaired_by_server)
        TrackingService.stop(this)
    }

    private fun refreshFromServer() {
        worker.execute {
            val result = Uploader.sync(this)
            if (result == Uploader.Result.OK && prefs.isPaired && prefs.pendingEndAt == 0L) {
                try {
                    val reply = Api.post(prefs, Api.STATUS, JSONObject())
                    prefs.saveProfile(reply)
                    if (prefs.onShift && !reply.optBoolean("on_shift", true)) {
                        prefs.onShift = false
                        prefs.notice = getString(R.string.ended_by_server)
                        TrackingService.stop(this)
                    }
                } catch (e: ApiException) {
                    if (e.httpCode == 401) handleLoggedOutByServer()
                } catch (e: IOException) {
                    // Offline: keep the last known state.
                }
            }
            if (result == Uploader.Result.UNPAIRED || result == Uploader.Result.ENDED_BY_SERVER) {
                TrackingService.stop(this)
            }
            if (prefs.isPaired) Api.syncLogo(this, prefs)
            ui.post { showNotice(); render() }
        }
    }

    // ------------------------------------------------------------------ shift

    private fun startShift() {
        val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        if (!LocationManagerCompat.isLocationEnabled(lm)) {
            AlertDialog.Builder(this)
                .setMessage(R.string.location_off)
                .setPositiveButton(R.string.open_settings) { _, _ ->
                    startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
            return
        }
        withLocationPermission { beginShift() }
    }

    private fun withLocationPermission(action: () -> Unit) {
        val needed = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            needed.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        val missing = needed.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty() || hasLocationPermission() && missing.all { it == Manifest.permission.POST_NOTIFICATIONS }) {
            action()
        } else {
            afterPermission = action
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    // ------------------------------------------------------------------ attendance

    private fun punch(type: String) {
        if (punching) return
        punching = true
        val time = System.currentTimeMillis()
        punchButton.isEnabled = false
        punchButton.setText(R.string.getting_location)
        fetchLocation { loc -> savePunch(type, time, loc) }
    }

    /** One fresh GPS reading (up to 20 s); null when location is off or unavailable. */
    fun fetchLocation(callback: (Location?) -> Unit) {
        val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        if (!hasLocationPermission() || !LocationManagerCompat.isLocationEnabled(lm)) {
            callback(null)
            return
        }
        try {
            val request = CurrentLocationRequest.Builder()
                .setPriority(Priority.PRIORITY_HIGH_ACCURACY)
                .setDurationMillis(20_000)
                .setMaxUpdateAgeMillis(60_000)
                .build()
            LocationServices.getFusedLocationProviderClient(this)
                .getCurrentLocation(request, CancellationTokenSource().token)
                .addOnCompleteListener { task ->
                    callback(if (task.isSuccessful) task.result else null)
                }
        } catch (e: SecurityException) {
            callback(null)
        }
    }

    private fun savePunch(type: String, time: Long, loc: Location?) {
        val punch = JSONObject().put("type", type).put("t", time)
        if (loc != null) {
            val mock = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                loc.isMock
            } else {
                @Suppress("DEPRECATION")
                loc.isFromMockProvider
            }
            punch.put("lat", loc.latitude).put("lon", loc.longitude)
                .put("acc", if (loc.hasAccuracy()) loc.accuracy.toDouble() else 0.0)
                .put("mock", mock)
        } else {
            Toast.makeText(this, R.string.punched_no_gps, Toast.LENGTH_LONG).show()
        }
        // Save on the phone first, so the punch is kept even without internet.
        val pending = prefs.pendingPunchList()
        pending.put(punch)
        prefs.pendingPunches = pending.toString()
        val hhmm = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(time))
        val label = (if (type == "in") "In " else "Out ") + hhmm
        prefs.todayPunches = if (prefs.todayPunches.isEmpty()) label else prefs.todayPunches + " · " + label
        prefs.punchedIn = type == "in"
        prefs.punchInAt = if (type == "in") time else 0L
        punching = false
        render()
        worker.execute {
            Uploader.sync(this)
            ui.post { showNotice(); render() }
        }
    }

    private fun beginShift() {
        setBusy(true, shiftButton, R.string.working)
        worker.execute {
            // Send anything left from an earlier shift first.
            Uploader.sync(this)
            val error: String? = try {
                val reply = Api.post(prefs, Api.SHIFT_START, JSONObject().put("t", System.currentTimeMillis()))
                prefs.saveProfile(reply)
                prefs.onShift = true
                prefs.lastError = ""
                prefs.lastSentAt = 0L
                null
            } catch (e: ApiException) {
                if (e.httpCode == 401) {
                    prefs.unpair()
                    getString(R.string.unpaired_by_server)
                } else e.message
            } catch (e: IOException) {
                "No internet. Connect to mobile data or Wi-Fi to start your shift."
            }
            ui.post {
                setBusy(false, shiftButton, R.string.start_shift)
                if (error != null) {
                    AlertDialog.Builder(this).setMessage(error).setPositiveButton(android.R.string.ok, null).show()
                } else {
                    TrackingService.start(this)
                }
                render()
            }
        }
    }

    private fun confirmEndShift() {
        AlertDialog.Builder(this)
            .setMessage("End your shift? Your location will stop being shared.")
            .setPositiveButton(R.string.end_shift) { _, _ -> endShift() }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun endShift() {
        TrackingService.stop(this)
        prefs.onShift = false
        prefs.pendingEndAt = System.currentTimeMillis()
        render()
        worker.execute {
            Uploader.sync(this)
            ui.post { render() }
        }
    }

    // ------------------------------------------------------------------ other actions

    private fun isIgnoringBatteryOptimizations(): Boolean {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(packageName)
    }

    @SuppressLint("BatteryLife")
    private fun askBatteryExemption() {
        try {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
            )
        } catch (e: Exception) {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    private fun confirmLogout() {
        AlertDialog.Builder(this)
            .setMessage(R.string.logout_confirm)
            .setPositiveButton(R.string.logout) { _, _ ->
                val wasOnShift = prefs.onShift
                TrackingService.stop(this)
                if (wasOnShift) {
                    prefs.onShift = false
                    prefs.pendingEndAt = System.currentTimeMillis()
                }
                worker.execute {
                    Uploader.sync(this) // best effort: send remaining locations and the shift end
                    try {
                        Api.post(prefs, Api.LOGOUT, JSONObject())
                    } catch (e: Exception) {
                        // Offline: the manager can still log the phone out from Odoo.
                    }
                    PointQueue.get(this).clear()
                    prefs.unpair()
                    ui.post {
                        tab = "home"
                        render()
                    }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showNotice() {
        val notice = prefs.notice
        if (notice.isNotEmpty()) {
            prefs.notice = ""
            AlertDialog.Builder(this).setMessage(notice).setPositiveButton(android.R.string.ok, null).show()
        }
    }

    private fun setBusy(isBusy: Boolean, button: MaterialButton, textRes: Int) {
        busy = isBusy
        button.isEnabled = !isBusy
        button.setText(textRes)
    }

    private fun hasLocationPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    // ------------------------------------------------------------------ screen

    private fun renderTabs() {
        val open = prefs.openTaskCount()
        inboxTab.text = if (open > 0) getString(R.string.tab_inbox) + " ($open)" else getString(R.string.tab_inbox)
        val onColor = ContextCompat.getColor(this, R.color.brand)
        val offColor = ContextCompat.getColor(this, R.color.off_text)
        homeTab.setTextColor(if (tab == "home") onColor else offColor)
        inboxTab.setTextColor(if (tab == "inbox") onColor else offColor)
        homeContent.visibility = if (tab == "home") View.VISIBLE else View.GONE
        val detail = tab == "inbox" && inbox.openId != 0L
        inboxContent.visibility = if (tab == "inbox" && !detail) View.VISIBLE else View.GONE
        detailContent.visibility = if (detail) View.VISIBLE else View.GONE
        if (tab == "inbox" && !detail) inbox.renderList()
    }

    private fun renderAttendance() {
        val waiting = prefs.pendingPunchList().length()
        val fmt = SimpleDateFormat("HH:mm", Locale.getDefault())
        val (bg, fg) = when {
            waiting > 0 -> R.color.warn_bg to R.color.warn_text
            prefs.punchedIn -> R.color.ok_bg to R.color.ok_text
            else -> R.color.off_bg to R.color.off_text
        }
        attendanceBox.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, bg))
        attendanceTitle.setTextColor(ContextCompat.getColor(this, fg))
        attendanceDetail.setTextColor(ContextCompat.getColor(this, fg))
        if (prefs.punchedIn && prefs.punchInAt > 0) {
            attendanceTitle.text = "Punched in at " + fmt.format(Date(prefs.punchInAt))
            val minutes = ((System.currentTimeMillis() - prefs.punchInAt) / 60000L).coerceAtLeast(0)
            attendanceDetail.text = String.format(Locale.US, "Working %d:%02d", minutes / 60, minutes % 60)
        } else {
            attendanceTitle.setText(R.string.not_punched_in)
            attendanceDetail.text = SimpleDateFormat("EEE dd MMM", Locale.getDefault()).format(Date())
        }
        if (waiting > 0) {
            attendanceDetail.text = attendanceDetail.text.toString() + " · " +
                (if (waiting == 1) "1 punch" else "$waiting punches") + " waiting for internet"
        }
        if (!punching) {
            punchButton.isEnabled = true
            punchButton.setText(if (prefs.punchedIn) R.string.punch_out else R.string.punch_in)
            punchButton.backgroundTintList = ColorStateList.valueOf(
                ContextCompat.getColor(this, if (prefs.punchedIn) R.color.danger else R.color.brand)
            )
        }
        punchesText.text = if (prefs.todayPunches.isNotEmpty()) "Today: " + prefs.todayPunches else ""
    }

    /** Company logo and name from Odoo, on the login screen and the home header. */
    private fun renderCompany() {
        val name = prefs.companyName
        companyNameText.text = name
        loginCompanyName.text = name
        if (logoShown != prefs.logoSaved) {
            logoShown = prefs.logoSaved
            val file = java.io.File(filesDir, "company_logo")
            val bmp = if (logoShown.isNotEmpty() && file.exists()) android.graphics.BitmapFactory.decodeFile(file.path) else null
            if (bmp != null) {
                companyLogo.setImageBitmap(bmp)
                loginLogo.setImageBitmap(bmp)
            }
            companyLogo.visibility = if (bmp != null) View.VISIBLE else View.GONE
            loginLogo.visibility = if (bmp != null) View.VISIBLE else View.GONE
        }
        loginCompany.visibility = if (name.isNotEmpty()) View.VISIBLE else View.GONE
    }

    private fun render() {
        renderCompany()
        val paired = prefs.isPaired
        val showPassword = paired && (prefs.mustChangePassword || changingPassword)
        setupPanel.visibility = if (paired) View.GONE else View.VISIBLE
        passwordPanel.visibility = if (showPassword) View.VISIBLE else View.GONE
        trackingPanel.visibility = if (paired && !showPassword) View.VISIBLE else View.GONE
        if (!paired) return

        if (showPassword) {
            val firstTime = prefs.mustChangePassword
            passwordTitle.setText(if (firstTime) R.string.choose_password else R.string.change_password_title)
            passwordHelp.setText(if (firstTime) R.string.choose_password_help else R.string.change_password_help)
            currentPasswordLayout.visibility = if (tempPassword == null) View.VISIBLE else View.GONE
            cancelPasswordButton.visibility = if (firstTime) View.GONE else View.VISIBLE
            return
        }

        nameText.text = prefs.employeeName
        infoText.text = prefs.employeeInfo
        renderTabs()
        if (tab != "home") return
        renderAttendance()

        val waiting = PointQueue.get(this).count()
        val error = prefs.lastError
        val (bg, fg) = when {
            prefs.onShift && error.isNotEmpty() -> R.color.warn_bg to R.color.warn_text
            prefs.onShift -> R.color.ok_bg to R.color.ok_text
            prefs.pendingEndAt > 0 -> R.color.warn_bg to R.color.warn_text
            else -> R.color.off_bg to R.color.off_text
        }
        statusBox.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, bg))
        statusTitle.setTextColor(ContextCompat.getColor(this, fg))
        statusDetail.setTextColor(ContextCompat.getColor(this, fg))

        if (prefs.onShift) {
            statusTitle.setText(R.string.tracking_on)
            statusDetail.text = when {
                error.isNotEmpty() -> error
                prefs.lastSentAt > 0 -> "Last sent " + DateUtils.getRelativeTimeSpanString(
                    prefs.lastSentAt, System.currentTimeMillis(), DateUtils.SECOND_IN_MILLIS
                )
                else -> "Waiting for GPS…"
            }
        } else if (prefs.pendingEndAt > 0) {
            statusTitle.text = "Shift ended"
            statusDetail.text = if (waiting > 0) {
                "$waiting locations and the shift end will be sent when internet is back."
            } else {
                "Sending shift end when internet is back."
            }
        } else {
            statusTitle.text = "Off shift"
            statusDetail.setText(R.string.off_shift)
        }

        if (!busy) {
            shiftButton.setText(if (prefs.onShift) R.string.end_shift else R.string.start_shift)
            shiftButton.backgroundTintList = ColorStateList.valueOf(
                ContextCompat.getColor(this, if (prefs.onShift) R.color.danger else R.color.brand)
            )
        }
        val needBattery = !isIgnoringBatteryOptimizations()
        batteryButton.visibility = if (needBattery) View.VISIBLE else View.GONE
        batteryHelp.visibility = batteryButton.visibility
    }
}
