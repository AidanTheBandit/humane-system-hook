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
 * Runtime bypasses for Humane's inbound call/message contact filtering.
 */
object InboundFilteringHooks {
    private const val TAG = "PenumbraInbound"
    private const val SETTINGS_URL = "http://127.0.0.1:8080/api/settings"
    private const val CACHE_TTL_MS = 2_000L
    private const val HTTP_TIMEOUT_MS = 200
    private const val MESSAGE_SOUND_KEY = "ns"
    private const val TRUSTED_SMS_SOUND = "trusted_or_leased_sms"

    @Volatile
    private var cachedTrustAllContacts = false

    @Volatile
    private var cachedAllowAllInbound = false

    @Volatile
    private var cacheExpiresAtMs = 0L

    private val refreshExecutor = Executors.newSingleThreadExecutor()
    private val refreshInFlight = AtomicBoolean(false)

    fun install(cl: ClassLoader) {
        Log.w(TAG, "Installing inbound filtering hooks")
        hookContactsManager(cl)
        hookAddressBookAccess(cl)
        hookCallUtils(cl)
        hookMessageNotifications(cl)
    }

    private fun hookContactsManager(cl: ClassLoader) {
        try {
            val clazz = cl.loadClass("humaneinternal.system.contacts.ContactsManager")
            val method = clazz.getDeclaredMethod("disableTrustedContacts")
            method.isAccessible = true
            XposedBridge.hookMethod(method, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (contactBypassEnabled()) {
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
                    if (contactBypassEnabled()) {
                        param.result = true
                    }
                }
            })
            Log.w(TAG, "  Hooked ${clazz.simpleName}.$name()")
        } catch (t: Throwable) {
            Log.w(TAG, "  Failed to hook ${clazz.simpleName}.$name(): ${t.message}")
        }
    }

    private fun hookMessageNotifications(cl: ClassLoader) {
        if (!isMessagesExperience(cl)) {
            return
        }
        hookNotificationAccess(cl)
        hookAlertAccess(cl)
    }

    private fun isMessagesExperience(cl: ClassLoader): Boolean {
        return try {
            cl.loadClass("humane.experience.messages.BuildConfig")
            true
        } catch (_: Throwable) {
            false
        }
    }

    private fun hookNotificationAccess(cl: ClassLoader) {
        try {
            val clazz = cl.loadClass("humaneinternal.system.notifications.NotificationAccess")
            val notificationClass = cl.loadClass("humaneinternal.system.notifications.Notification")
            val method = clazz.getDeclaredMethod("notify", notificationClass, java.lang.Boolean::class.java)
            method.isAccessible = true
            XposedBridge.hookMethod(method, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (allowAllInboundEnabled()) {
                        param.args[1] = java.lang.Boolean.TRUE
                    }
                }
            })
            Log.w(TAG, "  Hooked NotificationAccess.notify(Notification, Boolean)")
        } catch (t: Throwable) {
            Log.w(TAG, "  NotificationAccess hook unavailable: ${t.message}")
        }
    }

    private fun hookAlertAccess(cl: ClassLoader) {
        try {
            val clazz = cl.loadClass("humane.system.AlertAccess")
            val notificationTypeClass = cl.loadClass("humane.system.AlertAccess\$NotificationType")
            val mapClass = Map::class.java
            val method = clazz.getDeclaredMethod("notify", notificationTypeClass, mapClass)
            method.isAccessible = true
            XposedBridge.hookMethod(method, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (!allowAllInboundEnabled()) return
                    val type = param.args.getOrNull(0) as? Enum<*> ?: return
                    if (type.name != "NOTIFICATION_TYPE_MESSAGING") return
                    @Suppress("UNCHECKED_CAST")
                    val metadata = param.args.getOrNull(1) as? MutableMap<String, String> ?: return
                    metadata[MESSAGE_SOUND_KEY] = TRUSTED_SMS_SOUND
                }
            })
            Log.w(TAG, "  Hooked AlertAccess.notify(NotificationType, Map)")
        } catch (t: Throwable) {
            Log.w(TAG, "  AlertAccess hook unavailable: ${t.message}")
        }
    }

    private fun contactBypassEnabled(): Boolean {
        refreshIfNeeded()
        return cachedTrustAllContacts || cachedAllowAllInbound
    }

    private fun allowAllInboundEnabled(): Boolean {
        refreshIfNeeded()
        return cachedAllowAllInbound
    }

    private fun refreshIfNeeded() {
        val now = System.currentTimeMillis()
        if (now >= cacheExpiresAtMs) {
            scheduleRefresh(now)
        }
    }

    private fun scheduleRefresh(now: Long) {
        if (!refreshInFlight.compareAndSet(false, true)) {
            return
        }
        cacheExpiresAtMs = now + CACHE_TTL_MS
        refreshExecutor.execute {
            try {
                val settings = fetchInboundSettings()
                cachedTrustAllContacts = settings?.trustAllContacts ?: false
                cachedAllowAllInbound = settings?.allowAllInbound ?: false
            } finally {
                refreshInFlight.set(false)
            }
        }
    }

    private fun fetchInboundSettings(): InboundSettings? {
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
                val contacts = JSONObject(body).optJSONObject("contacts") ?: return null
                InboundSettings(
                    trustAllContacts = contacts.optBoolean("trust_all_contacts", false),
                    allowAllInbound = contacts.optBoolean("allow_all_inbound", false),
                )
            }
        } catch (t: Throwable) {
            Log.w(TAG, "  Failed to read inbound filtering settings: ${t.message}")
            null
        } finally {
            connection.disconnect()
        }
    }

    private data class InboundSettings(
        val trustAllContacts: Boolean,
        val allowAllInbound: Boolean,
    )
}
