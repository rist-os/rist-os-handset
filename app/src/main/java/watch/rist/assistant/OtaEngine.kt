package watch.rist.assistant

import android.util.Log

object OtaEngine {

    private const val TAG = "RistOtaEngine"

    // @SystemApi: absent from the public android.jar, present under system_current.
    const val UPDATE_ENGINE_CLASS = "android.os.UpdateEngine"

    const val UPDATE_ENGINE_CALLBACK_CLASS = "android.os.UpdateEngineCallback"

    // Compiled by Soong only; excluded from the Gradle build in app/build.gradle.kts.
    const val RIST_CALLBACK_CLASS = "watch.rist.assistant.OtaEngineCallback"

    // Under an SELinux denial ServiceManager.getService returns null rather than throwing.
    const val SERVICE_NAME = "android.os.UpdateEngineService"

    enum class Reason {
        NO_SYSTEM_API,
        NO_CALLBACK_CLASS,
        SERVICE_NOT_VISIBLE,
        BIND_REFUSED,
        APPLY_REFUSED,
    }

    sealed class Availability {
        object Started : Availability()

        object AlreadyRunning : Availability()

        object AlreadyStaged : Availability()

        data class Unavailable(val reason: Reason, val detail: String) : Availability()
    }

    interface Progress {
        fun onStatus(status: Int, percent: Double)
        fun onComplete(errorCode: Int)
    }

    fun probeClasses(): Availability.Unavailable? {
        val engine = try {
            Class.forName(UPDATE_ENGINE_CLASS)
        } catch (e: ClassNotFoundException) {
            return Availability.Unavailable(Reason.NO_SYSTEM_API,
                "$UPDATE_ENGINE_CLASS absent: built against the public SDK, not system_current")
        }
        try {
            Class.forName(UPDATE_ENGINE_CALLBACK_CLASS)
        } catch (e: ClassNotFoundException) {
            return Availability.Unavailable(Reason.NO_SYSTEM_API,
                "$UPDATE_ENGINE_CALLBACK_CLASS absent: built against the public SDK")
        }
        try {
            Class.forName(RIST_CALLBACK_CLASS)
        } catch (e: ClassNotFoundException) {
            return Availability.Unavailable(Reason.NO_CALLBACK_CLASS,
                "$RIST_CALLBACK_CLASS absent: this APK came from Gradle, which excludes it " +
                    "(app/build.gradle.kts). Only a Soong-built image can apply an update.")
        }
        check(engine.name == UPDATE_ENGINE_CLASS)

        return null
    }

    // Only meaningful inside the :ota process; from the main process the lookup is always denied.
    fun probeService(): Availability.Unavailable? {
        val binder = try {
            val sm = Class.forName("android.os.ServiceManager")
            sm.getMethod("getService", String::class.java).invoke(null, SERVICE_NAME)
        } catch (t: Throwable) {
            return Availability.Unavailable(Reason.SERVICE_NOT_VISIBLE,
                "ServiceManager.getService($SERVICE_NAME) threw ${t.javaClass.simpleName}: " +
                    "${t.message} (asked from process '${currentProcessName()}')")
        }
        if (binder == null) {
            return Availability.Unavailable(Reason.SERVICE_NOT_VISIBLE,
                "$SERVICE_NAME not visible from process '${currentProcessName()}'. " +
                    "update_engine grants service_manager find to rist_app, and only the " +
                    "watch.rist.assistant:ota process maps to rist_app (seapp_contexts). If the " +
                    "process name above is NOT watch.rist.assistant:ota then this check simply ran " +
                    "in the wrong place and says nothing about the device. If it IS :ota, then the " +
                    "process did not land in rist_app — check: ps -Z | grep rist; dmesg | grep avc")
        }
        return null
    }

    fun probe(): Availability.Unavailable? = probeClasses() ?: probeService()

    private fun currentProcessName(): String =
        runCatching {
            val cls = Class.forName("android.app.ActivityThread")
            cls.getMethod("currentProcessName").invoke(null) as? String
        }.getOrNull() ?: "unknown"

    fun apply(handoff: OtaApply.Handoff, progress: Progress): Availability {
        probe()?.let {
            Log.e(TAG, "cannot reach update_engine [${it.reason}]: ${it.detail}")
            return it
        }
        return try {
            val engineClass = Class.forName(UPDATE_ENGINE_CLASS)
            val callbackClass = Class.forName(UPDATE_ENGINE_CALLBACK_CLASS)
            val engine = engineClass.getConstructor().newInstance()

            val callback = Class.forName(RIST_CALLBACK_CLASS)
                .getConstructor(Progress::class.java)
                .newInstance(progress)

            // bind() throws if this process is already bound; unbind() on an unbound engine is a no-op.
            runCatching { engineClass.getMethod("unbind").invoke(engine) }

            try {
                // bind() returns false, without registering the callback, when its initial onStatusUpdate fails.
                val bound = engineClass.getMethod("bind", callbackClass).invoke(engine, callback)
                if (bound == false) {
                    return Availability.Unavailable(Reason.BIND_REFUSED,
                        "bind() returned false: update_engine accepted the call but did not " +
                            "register the callback. Usually binder_call(update_engine, rist_app) " +
                            "missing in aosp/sepolicy/rist_app.te.")
                }
            } catch (t: Throwable) {
                val cause = t.cause ?: t
                return Availability.Unavailable(Reason.BIND_REFUSED,
                    "${cause.javaClass.simpleName}: ${cause.message}. If the service resolved but " +
                        "bind failed, the missing rule is probably the CALLBACK direction — " +
                        "binder_call(update_engine, rist_app) in aosp/sepolicy/rist_app.te.")
            }

            Log.i(TAG, "applyPayload offset=${handoff.payloadOffset} size=${handoff.payloadSize} " +
                "url=${handoff.url}")
            try {
                engineClass.getMethod(
                    "applyPayload",
                    String::class.java, Long::class.javaPrimitiveType, Long::class.javaPrimitiveType,
                    Array<String>::class.java,
                ).invoke(engine, handoff.url, handoff.payloadOffset, handoff.payloadSize,
                    handoff.properties)
            } catch (t: Throwable) {
                val cause = t.cause ?: t
                val msg = cause.message.orEmpty()
                if (msg.contains("Already processing an update", ignoreCase = true)) {
                    if (handoff.attachIfRunning) {
                        Log.i(TAG, "update_engine is already applying THIS build; attaching to it")
                        return Availability.AlreadyRunning
                    }
                    Log.w(TAG, "update_engine is busy with something this device did not record; " +
                        "not attaching")
                    return Availability.Unavailable(Reason.APPLY_REFUSED,
                        "update_engine is busy with another operation (boot-time cleanup, or a " +
                            "different payload): ${cause.message}")
                }
                if (msg.contains("already applied", ignoreCase = true)) {
                    Log.i(TAG, "update_engine says a payload is staged and waiting for reboot")
                    return Availability.AlreadyStaged
                }
                return Availability.Unavailable(Reason.APPLY_REFUSED,
                    "${cause.javaClass.simpleName}: ${cause.message}")
            }
            Availability.Started
        } catch (t: Throwable) {
            Log.e(TAG, "update_engine bridge failed", t)
            Availability.Unavailable(Reason.NO_SYSTEM_API,
                "${t.javaClass.simpleName}: ${t.message}")
        }
    }
}
