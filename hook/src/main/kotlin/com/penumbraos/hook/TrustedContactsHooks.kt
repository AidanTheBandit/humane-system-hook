package com.penumbraos.hook

import android.util.Log
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Runtime bypass for Humane's trusted-contact filtering.
 */
object TrustedContactsHooks {
    private const val TAG = "PenumbraTrustedContacts"
    private const val SETTINGS_URL = "http://127.0.0.1:8080/api/settings"
    private const val CACHE_TTL_MS = 2_000L
    private const val HTTP_TIMEOUT_MS = 200

    @Volatile
    private var cachedTrustAllContacts = false

    @Volatile
    private var cacheExpiresAtMs = 0L

    private val refreshExecutor = Executors.newSingleThreadExecutor()
    private val refreshInFlight = AtomicBoolean(false)

    fun install(cl: ClassLoader) {
        Log.w(TAG, "Installing trusted-contact hooks")
        hookContactsManager(cl)
        hookAddressBookAccess(cl)
        hookCallUtils(cl)
    }

    private fun hookContactsManager(cl: ClassLoader) {
        try {
            val clazz = cl.loadClass("humaneinternal.system.contacts.ContactsManager")
            val method = clazz.getDeclaredMethod("disableTrustedContacts")
            method.isAccessible = true
            XposedBridge.hookMethod(method, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (trustAllContactsEnabled()) {
                        param.result = true
                    }
                }
            })
            Log.w(TAG, "  Hooked ContactsManager.disableTrustedContacts()")
        } catch (t: Throwable) {
            Log.w(TAG, "  ContactsManager hook unavailable: ${t.message}")
        }
    }

    private fun hookAddressBookAccess(cl: ClassLoader) {
        try {
            val clazz = cl.loadClass("humane.addressbook.AddressBookAccess")
            hookBooleanBypassMethod(clazz, "isNumberForTrustedContact", arrayOf(String::class.java))
            hookTrustedContactObjectMethod(cl, clazz)
        } catch (t: Throwable) {
            Log.w(TAG, "  AddressBookAccess hooks unavailable: ${t.message}")
        }
    }

    private fun hookTrustedContactObjectMethod(cl: ClassLoader, clazz: Class<*>) {
        val contactClass = cl.loadClass("humane.system.contacts.Contact")
        hookBooleanBypassMethod(clazz, "isTrusted", arrayOf(contactClass))
    }

    private fun hookCallUtils(cl: ClassLoader) {
        try {
            val clazz = cl.loadClass("humaneinternal.system.telephony.CallUtils")
            val callClass = cl.loadClass("android.telecom.Call")
            hookBooleanBypassMethod(clazz, "callIsTrustedOrLeased", arrayOf(callClass))
        } catch (t: Throwable) {
            Log.w(TAG, "  CallUtils hook unavailable: ${t.message}")
        }
    }

    private fun hookBooleanBypassMethod(
        clazz: Class<*>,
        name: String,
        paramTypes: Array<Class<*>>,
    ) {
        try {
            val method = clazz.getDeclaredMethod(name, *paramTypes)
            method.isAccessible = true
            XposedBridge.hookMethod(method, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (trustAllContactsEnabled()) {
                        param.result = true
                    }
                }
            })
            Log.w(TAG, "  Hooked ${clazz.simpleName}.$name()")
        } catch (t: Throwable) {
            Log.w(TAG, "  Failed to hook ${clazz.simpleName}.$name(): ${t.message}")
        }
    }

    private fun trustAllContactsEnabled(): Boolean {
        val now = System.currentTimeMillis()
        if (now >= cacheExpiresAtMs) {
            scheduleRefresh(now)
        }
        return cachedTrustAllContacts
    }

    private fun scheduleRefresh(now: Long) {
        if (!refreshInFlight.compareAndSet(false, true)) {
            return
        }
        cacheExpiresAtMs = now + CACHE_TTL_MS
        refreshExecutor.execute {
            try {
                cachedTrustAllContacts = fetchTrustAllContacts() ?: false
            } finally {
                refreshInFlight.set(false)
            }
        }
    }

    private fun fetchTrustAllContacts(): Boolean? {
        val connection = try {
            (URL(SETTINGS_URL).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = HTTP_TIMEOUT_MS
                readTimeout = HTTP_TIMEOUT_MS
                doInput = true
            }
        } catch (t: Throwable) {
            Log.w(TAG, "  Failed to open settings connection: ${t.message}")
            return null
        }

        return try {
            val status = connection.responseCode
            if (status !in 200..299) {
                Log.w(TAG, "  Settings endpoint returned HTTP $status")
                null
            } else {
                val body = connection.inputStream.bufferedReader().use { it.readText() }
                val contacts = JSONObject(body).optJSONObject("contacts")
                contacts?.optBoolean(
                    "trust_all_contacts",
                    contacts.optBoolean("disable_trusted_contacts", false),
                )
            }
        } catch (t: Throwable) {
            Log.w(TAG, "  Failed to read trusted-contact setting: ${t.message}")
            null
        } finally {
            connection.disconnect()
        }
    }
}
