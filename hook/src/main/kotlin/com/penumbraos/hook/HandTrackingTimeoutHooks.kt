package com.penumbraos.hook

import android.app.Application
import android.content.Context
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Log
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.time.Instant
import java.util.UUID

/**
 * Replaces Humane's low-power hand-tracking lifetime policy.
 *
 * Humane's original HandTrackingManager uses indefinite "timer exceptions" for
 * narration, calls, music, and laser/projection state. In practice that can leave
 * the ToF/hand scanner searching for a hand long after the interaction ended.
 *
 * Amended policy:
 * - Only touchpad activity starts a tracking session from idle
 * - TTS keeps tracking alive for the duration of the narration
 * - Projection callbacks refresh a short timeout while a projected hand is present
 * - Music/call/alert/sound state does not start or hold hand tracking by default
 */
object HandTrackingTimeoutHooks {

    private const val TAG = "PenumbraHook"

    private const val MANAGER_CLASS = "humaneinternal.system.coordination.HandTrackingManager"
    private const val REASON_CLASS = "humaneinternal.system.coordination.HandTrackingManager\$Reason"
    private const val MAIN_APPLICATION_CLASS = "humaneinternal.system.MainApplication"
    private const val HAND_TRACKING_SERVICE_CLASS = "humaneinternal.system.coordination.HandTrackingService"
    private const val SYSTEM_MODE_STUB_CLASS = "humane.sysmode.ISystemModeService\$Stub"
    private const val SYSTEM_MODE_SERVICE_NAME = "humane.service.SystemModeService"
    private const val NARRATION_HATS_LOCK_REASON = "PenumbraNarration"
    private const val FLAT_HAND_CALLBACK_CLASS = "humaneinternal.system.hats.FlatHandService\$FlatHandCallback"
    private const val ARBITRATOR_CLASS = "humaneinternal.system.tao.Arbitrator"

    private const val DEFAULT_TIMEOUT_MS = 10_000L
    private const val KEY_TIMEOUT_MS = "penumbra.hand_tracking.timeout_ms"
    private const val KEY_ALLOW_ALERT_START = "penumbra.hand_tracking.allow_alert_start"
    private const val KEY_ALLOW_SOUND_START = "penumbra.hand_tracking.allow_sound_start"

    @Volatile
    private var installed = false

    @Volatile
    private var sessionArmed = false

    @Volatile
    private var aiResponseActive = false

    @Volatile
    private var narrationActive = false

    @Volatile
    private var projectionActive = false

    @Volatile
    private var managerRef: Any? = null

    @Volatile
    private var narrationHatsToken: IBinder? = null

    @Volatile
    private var systemModeServiceRef: Any? = null

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var runtimeInitialized = false

    @Volatile
    private var timerGeneration = 0

    private val timerHandler = Handler(Looper.getMainLooper())

    private lateinit var managerClass: Class<*>
    private lateinit var reasonClass: Class<*>
    private lateinit var sharedInstanceMethod: Method
    private lateinit var startIfNeededMethod: Method
    private lateinit var cancelTimerMethod: Method
    private lateinit var stopMethod: Method
    private lateinit var timerExceptionsField: Field
    private lateinit var contextField: Field
    private lateinit var timeoutIntentCounterField: Field
    private lateinit var lastPendingTimeoutField: Field
    private lateinit var isHandTrackingRunningField: Field
    private lateinit var handTrackingServiceClass: Class<*>
    private lateinit var systemModeStubClass: Class<*>

    fun install(cl: ClassLoader) {
        if (installed) return

        try {
            loadManagerSymbols(cl)
            hookApplicationContext(cl)
            hookUpdate()
            hookStop()
            hookProjectionCallbacks(cl)
            hookArbitratorActiveResponse(cl)
            installed = true
            Log.w(TAG, "  Hand tracking timeout hooks installed")
        } catch (t: Throwable) {
            Log.e(TAG, "  Failed to install hand tracking timeout hooks", t)
        }
    }

    private fun loadManagerSymbols(cl: ClassLoader) {
        managerClass = cl.loadClass(MANAGER_CLASS)
        reasonClass = cl.loadClass(REASON_CLASS)

        sharedInstanceMethod = managerClass.getDeclaredMethod("sharedInstance").apply { isAccessible = true }
        startIfNeededMethod = managerClass.getDeclaredMethod("startIfNeeded").apply { isAccessible = true }
        cancelTimerMethod = managerClass.getDeclaredMethod("cancelTimer").apply { isAccessible = true }
        stopMethod = managerClass.getDeclaredMethod("stop").apply { isAccessible = true }

        timerExceptionsField = managerClass.getDeclaredField("mTimerExceptions").apply { isAccessible = true }
        contextField = managerClass.getDeclaredField("mContext").apply { isAccessible = true }
        timeoutIntentCounterField = managerClass.getDeclaredField("mTimeoutIntentCounter").apply { isAccessible = true }
        lastPendingTimeoutField = managerClass.getDeclaredField("mLastPendingTimeout").apply { isAccessible = true }
        isHandTrackingRunningField = managerClass.getDeclaredField("mIsHandTrackingRunning").apply { isAccessible = true }
        handTrackingServiceClass = cl.loadClass(HAND_TRACKING_SERVICE_CLASS)
        systemModeStubClass = cl.loadClass(SYSTEM_MODE_STUB_CLASS)
    }

    private fun hookApplicationContext(cl: ClassLoader) {
        try {
            val applicationClass = cl.loadClass(MAIN_APPLICATION_CLASS)
            val method = applicationClass.getDeclaredMethod("onCreate").apply { isAccessible = true }
            XposedBridge.hookMethod(method, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val application = param.thisObject as? Application ?: return
                    appContext = application.applicationContext
                    Log.w(TAG, "  Captured application context for hand tracking timeout hook")
                }
            })
            Log.w(TAG, "  Hooked MainApplication.onCreate() for hand tracking context")
        } catch (t: Throwable) {
            Log.w(TAG, "  MainApplication.onCreate hand tracking context hook unavailable: ${t.message}")
        }
    }

    private fun hookUpdate() {
        val updateMethod = managerClass.getDeclaredMethod("update", reasonClass).apply { isAccessible = true }
        XposedBridge.hookMethod(updateMethod, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val manager = param.thisObject ?: return
                val reason = (param.args.getOrNull(0) as? Enum<*>)?.name ?: return
                managerRef = manager

                try {
                    handleUpdate(manager, reason)
                } catch (t: Throwable) {
                    Log.e(TAG, "  HandTrackingManager.update($reason) hook failed", t)
                }

                // Suppress Humane's original indefinite timer-exception policy.
                param.result = null
            }
        })
        Log.w(TAG, "  Hooked HandTrackingManager.update(Reason)")
    }

    private fun hookStop() {
        XposedBridge.hookMethod(stopMethod, object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                resetHookState()
                val manager = param.thisObject
                if (manager != null) {
                    clearTimerExceptions(manager)
                }
                Log.w(TAG, "  Hand tracking session stopped; hook state cleared")
            }
        })
        Log.w(TAG, "  Hooked HandTrackingManager.stop()")
    }

    private fun hookProjectionCallbacks(cl: ClassLoader) {
        val callbackClass = try {
            cl.loadClass(FLAT_HAND_CALLBACK_CLASS)
        } catch (t: Throwable) {
            Log.w(TAG, "  $FLAT_HAND_CALLBACK_CLASS unavailable, projection refresh hooks skipped: ${t.message}")
            return
        }

        hookProjectionMethod(callbackClass, "onNewFlatHandProjection", emptyArray()) {
            handleProjectionStart()
        }
        hookProjectionMethod(callbackClass, "onFlatHandProjectionLost", emptyArray()) {
            handleProjectionLost()
        }
    }

    private fun hookArbitratorActiveResponse(cl: ClassLoader) {
        val arbitratorClass = try {
            cl.loadClass(ARBITRATOR_CLASS)
        } catch (t: Throwable) {
            Log.w(TAG, "  $ARBITRATOR_CLASS unavailable, AI response hold hooks skipped: ${t.message}")
            return
        }

        try {
            val method = arbitratorClass.getDeclaredMethod(
                "eventForTranscription",
                UUID::class.java,
                Instant::class.java,
                String::class.java,
                Boolean::class.javaPrimitiveType,
            ).apply { isAccessible = true }
            XposedBridge.hookMethod(method, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val manager = managerForActiveSession() ?: return
                    val wasHeld = isActiveHold()
                    aiResponseActive = true
                    if (!wasHeld) {
                        cancelTimer(manager)
                        logState("Hand tracking timeout held for active AI response")
                    } else {
                        logState("Active AI response observed while hand tracking already held")
                    }
                }
            })
            Log.w(TAG, "  Hooked Arbitrator.eventForTranscription()")
        } catch (t: Throwable) {
            Log.w(TAG, "  Arbitrator.eventForTranscription hook unavailable: ${t.message}")
        }

        try {
            val method = arbitratorClass.getDeclaredMethod("clearInteractiveSession").apply { isAccessible = true }
            XposedBridge.hookMethod(method, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val manager = managerForActiveSession() ?: return
                    aiResponseActive = false

                    if (narrationActive) {
                        cancelTimer(manager)
                        logState("Interactive session cleared while narration active; preserving narration hold")
                        return
                    }

                    if (projectionActive) {
                        cancelTimer(manager)
                        logState("Interactive session cleared while projection active; preserving projection hold")
                        return
                    }

                    scheduleTimeout(manager, "interactive_session_clear")
                }
            })
            Log.w(TAG, "  Hooked Arbitrator.clearInteractiveSession()")
        } catch (t: Throwable) {
            Log.w(TAG, "  Arbitrator.clearInteractiveSession hook unavailable: ${t.message}")
        }
    }

    private fun hookProjectionMethod(
        clazz: Class<*>,
        methodName: String,
        paramTypes: Array<Class<*>>,
        handler: () -> Unit,
    ) {
        try {
            val method = clazz.getDeclaredMethod(methodName, *paramTypes).apply { isAccessible = true }
            XposedBridge.hookMethod(method, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    try {
                        handler()
                    } catch (t: Throwable) {
                        Log.e(TAG, "  Projection hook $methodName failed", t)
                    }
                }
            })
            Log.w(TAG, "  Hooked FlatHandCallback.$methodName()")
        } catch (t: Throwable) {
            Log.w(TAG, "  FlatHandCallback.$methodName hook unavailable: ${t.message}")
        }
    }

    private fun handleUpdate(manager: Any, reason: String) {
        ensureRuntimeInitialized(manager)
        clearTimerExceptions(manager)

        when (reason) {
            "TOUCHPAD" -> {
                releaseNarrationHatsLock("new_touchpad")
                sessionArmed = true
                aiResponseActive = false
                narrationActive = false
                projectionActive = false
                logState("TOUCHPAD armed hand tracking session")
                startIfNeededMethod.invoke(manager)
                scheduleTimeout(manager, "touchpad")
            }

            "NARRATION_START" -> {
                if (!sessionArmed) {
                    logState("NARRATION_START ignored because no active hand tracking session")
                    return
                }
                narrationActive = true
                startIfNeededMethod.invoke(manager)
                acquireNarrationHatsLock("narration_start")
                cancelTimer(manager)
                logState("Hand tracking timeout held for narration")
            }

            "NARRATION_END" -> {
                if (!sessionArmed) {
                    logState("NARRATION_END ignored because no active hand tracking session")
                    return
                }
                releaseNarrationHatsLock("narration_end")
                aiResponseActive = false
                narrationActive = false
                ensureActualHandTrackingRunning(manager, "narration_end")
                if (projectionActive) {
                    cancelTimer(manager)
                    logState("NARRATION_END released narration hold; preserving active projection hold")
                } else {
                    logState("NARRATION_END released narration hold")
                    scheduleTimeout(manager, "narration_end")
                }
            }

            "LASER_START" -> {
                if (!sessionArmed) {
                    logState("LASER_START ignored because no active hand tracking session")
                    return
                }
                projectionActive = true
                startIfNeededMethod.invoke(manager)
                cancelTimer(manager)
                logState("Projection hold started from LASER_START")
            }

            "LASER_END" -> {
                if (!sessionArmed) {
                    logState("LASER_END ignored because no active hand tracking session")
                    return
                }
                projectionActive = false
                logState("Projection hold ended from LASER_END")
                if (!isActiveHold()) {
                    scheduleTimeout(manager, "laser_end")
                }
            }

            "ALERT" -> {
                if (allowAlertStart(manager)) {
                    sessionArmed = true
                    startIfNeededMethod.invoke(manager)
                    scheduleTimeout(manager, "alert")
                }
            }

            "SOUND" -> {
                if (allowSoundStart(manager)) {
                    sessionArmed = true
                    startIfNeededMethod.invoke(manager)
                    scheduleTimeout(manager, "sound")
                }
            }

            "CALL_START", "CALL_END", "MUSIC_START", "MUSIC_END" -> {
                // Intentionally ignored. Music/call UI interactions must be initiated by touchpad again.
                Log.w(TAG, "  Ignoring hand tracking reason $reason")
            }

            else -> {
                Log.w(TAG, "  Ignoring unknown hand tracking reason $reason")
            }
        }
    }

    private fun handleProjectionStart() {
        logState("Raw projection start callback")
    }

    private fun handleProjectionLost() {
        logState("Raw projection lost callback")
    }

    private fun managerForActiveSession(): Any? {
        if (!sessionArmed) return null
        val manager = managerRef ?: runCatching { sharedInstanceMethod.invoke(null) }.getOrNull()?.also { managerRef = it }
        if (manager != null) {
            ensureRuntimeInitialized(manager)
        }
        return manager
    }

    private fun scheduleTimeout(manager: Any, reason: String) {
        ensureRuntimeInitialized(manager)
        clearTimerExceptions(manager)
        cancelTimer(manager)

        val timeoutMs = timeoutMs(configContext(manager))
        val generation = ++timerGeneration
        val targetManager = manager
        timerHandler.postDelayed({
            if (generation != timerGeneration) {
                logState("Ignoring stale hand tracking timeout ($reason, generation=$generation)")
                return@postDelayed
            }
            if (!sessionArmed) {
                logState("Ignoring hand tracking timeout because session is no longer armed ($reason)")
                return@postDelayed
            }
            if (isActiveHold()) {
                logState("Ignoring hand tracking timeout because hold is active ($reason)")
                return@postDelayed
            }
            try {
                clearTimerExceptions(targetManager)
                logState("Hand tracking timeout elapsed; stopping session ($reason)")
                stopMethod.invoke(targetManager)
            } catch (t: Throwable) {
                Log.e(TAG, "  Failed to stop hand tracking on timeout", t)
            }
        }, timeoutMs)

        logState("Scheduled hand tracking stop in ${timeoutMs}ms ($reason, generation=$generation)")
    }

    private fun cancelTimer(manager: Any) {
        timerGeneration++
        timerHandler.removeCallbacksAndMessages(null)
        runCatching { cancelTimerMethod.invoke(manager) }
            .onFailure { Log.w(TAG, "  Failed to cancel Humane hand tracking timer: ${it.message}") }
        runCatching { lastPendingTimeoutField.set(manager, null) }
        logState("Cancelled hand tracking timeout")
    }

    private fun clearTimerExceptions(manager: Any) {
        runCatching {
            val exceptions = timerExceptionsField.get(manager)
            if (exceptions is MutableList<*>) {
                exceptions.clear()
            } else if (exceptions is java.util.Collection<*>) {
                @Suppress("UNCHECKED_CAST")
                (exceptions as java.util.Collection<Any>).clear()
            }
        }.onFailure {
            Log.w(TAG, "  Failed to clear hand tracking timer exceptions: ${it.message}")
        }
    }

    private fun acquireNarrationHatsLock(reason: String) {
        if (!sessionArmed) return
        if (narrationHatsToken != null) {
            Log.w(TAG, "Narration HATSLock already held ($reason)")
            return
        }

        val service = systemModeService() ?: run {
            Log.w(TAG, "SystemModeService unavailable; cannot acquire narration HATSLock ($reason)")
            return
        }
        val token = Binder()
        runCatching {
            val method = service.javaClass.getMethod("acquireHATSLock", IBinder::class.java, String::class.java)
            method.invoke(service, token, NARRATION_HATS_LOCK_REASON)
            narrationHatsToken = token
            Log.w(TAG, "Acquired narration HATSLock ($reason, token=$token)")
        }.onFailure {
            Log.w(TAG, "Failed to acquire narration HATSLock ($reason): ${it.message}")
        }
    }

    private fun releaseNarrationHatsLock(reason: String) {
        val token = narrationHatsToken ?: return
        narrationHatsToken = null

        val service = systemModeService() ?: run {
            Log.w(TAG, "SystemModeService unavailable; dropped narration HATSLock token locally ($reason, token=$token)")
            return
        }
        runCatching {
            val method = service.javaClass.getMethod("releaseHATSLock", IBinder::class.java)
            method.invoke(service, token)
            Log.w(TAG, "Released narration HATSLock ($reason, token=$token)")
        }.onFailure {
            Log.w(TAG, "Failed to release narration HATSLock ($reason, token=$token): ${it.message}")
        }
    }

    private fun systemModeService(): Any? {
        systemModeServiceRef?.let { return it }
        return runCatching {
            val serviceManagerClass = Class.forName("android.os.ServiceManager")
            val getServiceMethod = serviceManagerClass.getMethod("getService", String::class.java)
            val binder = getServiceMethod.invoke(null, SYSTEM_MODE_SERVICE_NAME) as? IBinder ?: return null
            val asInterfaceMethod = systemModeStubClass.getMethod("asInterface", IBinder::class.java)
            asInterfaceMethod.invoke(null, binder)?.also { systemModeServiceRef = it }
        }.onFailure {
            Log.w(TAG, "Failed to bind SystemModeService for narration HATSLock: ${it.message}")
        }.getOrNull()
    }

    private fun ensureActualHandTrackingRunning(manager: Any, reason: String): Boolean {
        ensureRuntimeInitialized(manager)
        return runCatching {
            val service = handTrackingServiceClass.getDeclaredMethod("sharedInstance").invoke(null)
            val isValid = handTrackingServiceClass.getDeclaredMethod("isValid").invoke(service) as? Boolean ?: false
            if (!isValid) {
                Log.w(TAG, "Actual hand tracking service invalid during revalidation ($reason)")
                return false
            }

            val isRunning = handTrackingServiceClass.getDeclaredMethod("isRunning").invoke(service) as? Boolean ?: false
            Log.w(TAG, "Revalidated actual hand tracking ($reason): running=$isRunning")
            if (!isRunning) {
                handTrackingServiceClass.getDeclaredMethod("start").invoke(service)
                isHandTrackingRunningField.setBoolean(manager, true)
                Log.w(TAG, "Restarted actual hand tracking for active session ($reason)")
            }
            true
        }.onFailure {
            Log.w(TAG, "Failed to revalidate actual hand tracking ($reason): ${it.message}")
        }.getOrDefault(false)
    }

    private fun isActiveHold(): Boolean {
        return aiResponseActive || narrationActive
    }

    private fun ensureRuntimeInitialized(manager: Any) {
        val context = appContext ?: return
        runCatching {
            if (contextField.get(manager) == null) {
                contextField.set(manager, context)
                Log.w(TAG, "  Backfilled HandTrackingManager.mContext from application context")
            }
        }.onFailure {
            Log.w(TAG, "  Failed to backfill HandTrackingManager.mContext: ${it.message}")
        }

        if (runtimeInitialized) return
        runCatching {
            val sharedInstance = handTrackingServiceClass.getDeclaredMethod("sharedInstance").invoke(null)
            handTrackingServiceClass.getDeclaredMethod("initialize").invoke(sharedInstance)
            runtimeInitialized = true
            Log.w(TAG, "  Initialized HandTrackingService for timeout hook")
        }.onFailure {
            Log.w(TAG, "  Failed to initialize HandTrackingService for timeout hook: ${it.message}")
        }
    }

    private fun configContext(manager: Any): Context? {
        return (runCatching { contextField.get(manager) as? Context }.getOrNull()) ?: appContext
    }

    private fun timeoutMs(context: Context?): Long {
        val configured = if (context == null) {
            DEFAULT_TIMEOUT_MS
        } else {
            runCatching {
                Settings.Global.getLong(context.contentResolver, KEY_TIMEOUT_MS, DEFAULT_TIMEOUT_MS)
            }.getOrDefault(DEFAULT_TIMEOUT_MS)
        }
        return configured.coerceIn(1_000L, 60_000L)
    }

    private fun allowAlertStart(manager: Any): Boolean {
        val context = configContext(manager) ?: return false
        return runCatching {
            Settings.Global.getInt(context.contentResolver, KEY_ALLOW_ALERT_START, 0) != 0
        }.getOrDefault(false)
    }

    private fun allowSoundStart(manager: Any): Boolean {
        val context = configContext(manager) ?: return false
        return runCatching {
            Settings.Global.getInt(context.contentResolver, KEY_ALLOW_SOUND_START, 0) != 0
        }.getOrDefault(false)
    }

    private fun logState(message: String) {
        Log.w(
            TAG,
            "$message | sessionArmed=$sessionArmed aiResponseActive=$aiResponseActive " +
                "narrationActive=$narrationActive projectionActive=$projectionActive generation=$timerGeneration",
        )
    }

    private fun resetHookState() {
        releaseNarrationHatsLock("reset_hook_state")
        sessionArmed = false
        aiResponseActive = false
        narrationActive = false
        projectionActive = false
        timerGeneration += 1
        timerHandler.removeCallbacksAndMessages(null)
    }
}
