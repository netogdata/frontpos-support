package com.carriez.flutter_hbb

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import android.widget.Toast
import ffi.FFI
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * FrontPOS Support: tilknytning til FrontPOS365's backoffice (siden Fjernsupport).
 *
 * - register(code): supportkoden fra backoffice -> enheden knyttes til kunden og får en fast adgangskode,
 *   som sættes med "kun adgangskode, ingen Accepter" (FFI.frontposApplyAccess).
 * - heartbeat hvert 5. minut fra MainService (kører også efter genstart, hvor Flutter-delen ikke kører):
 *   melder styring/skærmdeling/version og henter en ny adgangskode, når backoffice har lavet en.
 *
 * Info : API: Backoffice-API-2.0 RemoteSupportController (/api/public/remote_support/...).
 */
object FrontPosSupport {
    private const val TAG = "FrontPosSupport"
    private const val API = "https://api.frontpos365.dk/api/public/remote_support"
    const val ACTION_LINK = "dk.frontpos.support.LINK"
    private const val PREFS = "frontpos_support"
    private const val INTERVAL_MS = 5 * 60 * 1000L
    private const val FIRST_DELAY_MS = 20 * 1000L

    private const val KEY_TOKEN = "token"
    private const val KEY_DEVICE_ID = "device_id"
    private const val KEY_TENANT = "tenant_name"
    private const val KEY_NAME = "name"
    private const val KEY_PASSWORD_VERSION = "password_version"
    private const val KEY_LAST_OK = "last_ok"
    private const val KEY_LAST_ERROR = "last_error"
    /** ID'et, adgangskoden sidst blev sat paa; et andet ID = ny/nulstillet opsaetning, saa koden hentes igen. */
    private const val KEY_APPLIED_ID = "applied_id"

    private val thread = HandlerThread("FrontPosSupport").apply { start() }
    private val handler = Handler(thread.looper)
    private var appContext: Context? = null
    private var scheduled = false

    private val tick = object : Runnable {
        override fun run() {
            appContext?.let { heartbeat(it) }
            handler.postDelayed(this, INTERVAL_MS)
        }
    }

    /** Fra MainService.onCreate: start heartbeat (én gang pr. proces). */
    fun start(context: Context) {
        appContext = context.applicationContext
        if (!scheduled) {
            scheduled = true
            handler.postDelayed(tick, FIRST_DELAY_MS)
        }
    }

    fun status(context: Context): Map<String, Any?> {
        val prefs = prefs(context)
        return mapOf(
            "registered" to (prefs.getString(KEY_TOKEN, null) != null),
            "tenantName" to prefs.getString(KEY_TENANT, null),
            "name" to prefs.getString(KEY_NAME, null),
            "lastOk" to prefs.getLong(KEY_LAST_OK, 0L),
            "lastError" to prefs.getString(KEY_LAST_ERROR, null),
        )
    }

    /** Tilknyt med supportkode; [done] kaldes på FrontPosSupport-tråden med (ok, besked). */
    fun register(context: Context, code: String, done: (Boolean, String) -> Unit) {
        val ctx = context.applicationContext
        appContext = ctx
        handler.post {
            val result = try {
                registerNow(ctx, code)
            } catch (e: Exception) {
                Log.e(TAG, "register failed", e)
                false to "Ingen forbindelse til FrontPOS. Tjek internettet og prøv igen."
            }
            done(result.first, result.second)
        }
    }

    /**
     * FrontPOS-appen paa kassen aabner os med handlingen dk.frontpos.support.LINK og kassens supportkode
     * (backoffice: Kasser -> Fjernsupport -> Installer). Tilknytningen sker i baggrunden; resultatet vises som toast.
     */
    fun linkFromIntent(context: Context, intent: Intent?) {
        if (intent?.action != ACTION_LINK) return
        val code = intent.getStringExtra("code")?.trim().orEmpty()
        intent.action = null // ikke igen ved genskabelse af aktiviteten
        if (code.isEmpty()) return
        val ctx = context.applicationContext
        register(ctx, code) { ok, message ->
            Log.i(TAG, "link from FrontPOS app: ok=$ok $message")
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(ctx, "FrontPOS Support: $message", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun registerNow(ctx: Context, code: String): Pair<Boolean, String> {
        // Lige efter installationen er opsaetningen (og dermed ID'et) ikke klar foer Flutter-delen har startet.
        var id = FFI.frontposGetMyId()
        var waited = 0
        while (id.isBlank() && waited < 30) {
            Thread.sleep(1000)
            waited++
            id = FFI.frontposGetMyId()
        }
        if (id.isBlank()) {
            return false to "FrontPOS Support er ikke klar endnu. Tryk Start under Del skærmen og prøv igen."
        }
        val body = JSONObject()
            .put("code", code.trim())
            .put("rustdeskId", id)
            .put("platform", "Android")
            .put("model", listOf(Build.MANUFACTURER, Build.MODEL).filter { it.isNotBlank() }.joinToString(" "))
            .put("osVersion", "Android ${Build.VERSION.RELEASE}")
            .put("appVersion", appVersion(ctx))
        val (status, json) = post("$API/register", body, null)
        if (status != 200 || json == null) {
            return false to (json?.optString("message")?.takeIf { it.isNotBlank() } ?: "Tilknytningen mislykkedes ($status).")
        }
        val password = json.getString("password")
        if (!FFI.frontposApplyAccess(password)) {
            return false to "Adgangskoden kunne ikke sættes på enheden."
        }
        prefs(ctx).edit()
            .putString(KEY_TOKEN, json.getString("token"))
            .putInt(KEY_DEVICE_ID, json.getInt("deviceId"))
            .putString(KEY_TENANT, json.optString("tenantName"))
            .putString(KEY_NAME, json.optString("name"))
            .putInt(KEY_PASSWORD_VERSION, json.getInt("passwordVersion"))
            .putString(KEY_APPLIED_ID, id)
            .putLong(KEY_LAST_OK, System.currentTimeMillis())
            .remove(KEY_LAST_ERROR)
            .apply()
        Log.d(TAG, "registered as device ${json.getInt("deviceId")}")
        // Status med det samme, så backoffice viser enheden som klar.
        heartbeat(ctx)
        return true to "Tilknyttet ${json.optString("tenantName")}."
    }

    private fun heartbeat(ctx: Context) {
        val prefs = prefs(ctx)
        val token = prefs.getString(KEY_TOKEN, null) ?: return
        try {
            val id = FFI.frontposGetMyId()
            val appliedId = prefs.getString(KEY_APPLIED_ID, null)
            // Note : Ny opsaetning (fx nyt appnavn eller slettede data) har ikke den faste kode: bed om den igen.
            val passwordVersion = if (id.isNotBlank() && appliedId != id) 0
                else prefs.getInt(KEY_PASSWORD_VERSION, 0)
            val body = JSONObject()
                .put("rustdeskId", id)
                .put("inputEnabled", InputService.isOpen)
                .put("sharing", MainService.isStart)
                .put("appVersion", appVersion(ctx))
                .put("passwordVersion", passwordVersion)
            val (status, json) = post("$API/heartbeat", body, token)
            if (status == 401 && json?.optBoolean("removed") == true) {
                // Fjernet i backoffice: glem tilknytningen; enheden skal have en ny supportkode.
                Log.d(TAG, "device removed in backoffice")
                prefs.edit().clear().apply()
                return
            }
            if (status != 200 || json == null) {
                prefs.edit().putString(KEY_LAST_ERROR, "HTTP $status").apply()
                return
            }
            val edit = prefs.edit()
                .putLong(KEY_LAST_OK, System.currentTimeMillis())
                .remove(KEY_LAST_ERROR)
                .putString(KEY_TENANT, json.optString("tenantName"))
                .putString(KEY_NAME, json.optString("name"))
            if (json.has("password") && FFI.frontposApplyAccess(json.getString("password"))) {
                edit.putInt(KEY_PASSWORD_VERSION, json.getInt("passwordVersion")).putString(KEY_APPLIED_ID, id)
                Log.d(TAG, "new password applied (version ${json.getInt("passwordVersion")})")
            }
            edit.apply()
        } catch (e: Exception) {
            Log.w(TAG, "heartbeat failed: ${e.message}")
            prefs.edit().putString(KEY_LAST_ERROR, e.message ?: e.javaClass.simpleName).apply()
        }
    }

    private fun post(url: String, body: JSONObject, token: String?): Pair<Int, JSONObject?> {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 15000
            connection.readTimeout = 15000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Accept", "application/json")
            if (token != null) {
                connection.setRequestProperty("X-Device-Token", token)
            }
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            val json = try { if (text.isBlank()) null else JSONObject(text) } catch (e: Exception) { null }
            return status to json
        } finally {
            connection.disconnect()
        }
    }

    private fun appVersion(ctx: Context): String = try {
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: ""
    } catch (e: Exception) {
        ""
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
