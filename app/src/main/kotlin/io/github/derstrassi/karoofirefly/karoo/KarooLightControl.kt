package io.github.derstrassi.karoofirefly.karoo

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.IInterface
import android.os.Parcel
import android.os.Parcelable
import io.github.derstrassi.karoofirefly.light.LightController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import timber.log.Timber

class KarooLightControl(private val context: Context) : LightController {

    companion object {
        private const val TAG = "KarooLightControl"
        private const val SENSOR_DESCRIPTOR = "io.hammerhead.sensorservice.SensorServiceAIDL"
        private const val LIGHT_CMD_DESCRIPTOR = "io.hammerhead.sensorservice.LightCommandConnectionAIDL"
        private const val LISTENER_DESCRIPTOR = "io.hammerhead.aidlrx.IParcelableListener"

        // LightCommandConnectionAIDL transaction codes (unchanged so far across firmware versions).
        private const val TX_REGISTER_LIGHT_PARAMS = 1
        private const val TX_UNREGISTER_LIGHT_PARAMS = 2
        private const val TX_SET_LIGHT_MODE = 3

        // SensorServiceAIDL transaction codes as they were before Karoo OS 1.663.2548 (15 Oct 2026).
        // That release inserted getLowBatteryInfo() in the middle of the interface, which shifted
        // every later method by +1 (getLightCommand 17 -> 18, register/unregisterDeviceConnection
        // 6/7 -> 7/8). These are therefore only base values: the real codes are resolved at runtime
        // in resolveSensorServiceTransactions() by probing for the LightCommand binder.
        private const val BASE_TX_REGISTER_DEVICE_CONNECTION = 6
        private const val BASE_TX_UNREGISTER_DEVICE_CONNECTION = 7
        private const val BASE_TX_GET_LIGHT_CMD = 17

        // Shifts to probe, most likely first: +1 (Karoo OS 1.663+), 0 (older firmware, Karoo 2),
        // then a little headroom for future insertions.
        private val TX_SHIFT_CANDIDATES = listOf(1, 0, 2, 3)

        private const val PREFS_NAME = "karoo_light_control"
        private const val PREF_TX_SHIFT = "sensor_service_tx_shift"
    }

    private var sensorBinder: IBinder? = null
    @Volatile private var lightCmdBinder: IBinder? = null

    // Resolved per firmware by resolveSensorServiceTransactions(); null until the probe succeeds.
    @Volatile private var txRegisterDeviceConnection: Int? = null
    @Volatile private var txUnregisterDeviceConnection: Int? = null

    private val prefs by lazy { context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }
    private var lightModeParcelableCreator: ((String) -> Parcelable)? = null
    private var deviceCreator: ((String) -> Parcelable)? = null
    private var isBound = false
    private var bindRequested = false

    private val _connectionStates = MutableStateFlow<Map<String, String>>(emptyMap())
    val connectionStates: StateFlow<Map<String, String>> = _connectionStates
    private val _supportedModes = MutableStateFlow<Map<String, Set<String>>>(emptyMap())
    val supportedModes: StateFlow<Map<String, Set<String>>> = _supportedModes
    private var lightModeEnumClass: Class<out Enum<*>>? = null
    private val registeredListeners = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val pendingRegistrations = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val pendingLightParamRegistrations = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    var onServiceReady: (() -> Unit)? = null

    // Serializes SensorService binder work off the main thread — ServiceConnection
    // callbacks arrive on the main thread, and these transactions can block.
    private val serviceExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            sensorBinder = service
            isBound = true
            Timber.d("$TAG: Connected to SensorService")
            if (service != null) {
                serviceExecutor.execute {
                    resolveSensorServiceTransactions(service)
                    loadLightModeClass()
                }
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            sensorBinder = null
            lightCmdBinder = null
            txRegisterDeviceConnection = null
            txUnregisterDeviceConnection = null
            isBound = false
            bindRequested = false
            registeredListeners.clear()
            Timber.d("$TAG: Disconnected from SensorService")
        }
    }

    /**
     * Resolves the SensorServiceAIDL transaction codes for the running firmware and fetches
     * the LightCommandConnection binder.
     *
     * The codes are not stable across Karoo OS releases: 1.663.2548 added a method in the
     * middle of the interface and shifted everything after it by +1, which made the previously
     * hard-coded codes call the wrong methods. So instead of trusting a constant, call
     * getLightCommand() at a few candidate codes and accept the first reply whose binder
     * identifies itself as LightCommandConnectionAIDL. The register/unregisterDeviceConnection
     * codes sit at a fixed distance from getLightCommand() and follow the same shift.
     *
     * The last working shift is cached so the usual case costs a single transaction.
     */
    private fun resolveSensorServiceTransactions(service: IBinder) {
        lightCmdBinder = null
        txRegisterDeviceConnection = null
        txUnregisterDeviceConnection = null

        val cachedShift = prefs.getInt(PREF_TX_SHIFT, -1).takeIf { it >= 0 }
        val candidates = (listOfNotNull(cachedShift) + TX_SHIFT_CANDIDATES).distinct()
        for (shift in candidates) {
            val code = BASE_TX_GET_LIGHT_CMD + shift
            val binder = probeLightCommandBinder(service, code) ?: continue
            lightCmdBinder = binder
            txRegisterDeviceConnection = BASE_TX_REGISTER_DEVICE_CONNECTION + shift
            txUnregisterDeviceConnection = BASE_TX_UNREGISTER_DEVICE_CONNECTION + shift
            if (cachedShift != shift) prefs.edit().putInt(PREF_TX_SHIFT, shift).apply()
            Timber.i(
                "$TAG: SensorService transaction shift=$shift " +
                    "(getLightCommand=$code, registerDeviceConnection=$txRegisterDeviceConnection), " +
                    "got LightCommand binder: $binder",
            )
            return
        }
        Timber.e(
            "$TAG: getLightCommand() not found on SensorService (probed shifts $candidates). " +
                "Unsupported Karoo firmware — ANT+ light control unavailable",
        )
    }

    /**
     * Calls transaction [code] on the SensorService as if it were getLightCommand() and returns
     * the reply binder only if it really is a LightCommandConnectionAIDL. Anything else — an
     * unhandled code, an exception from a method that expected arguments, or a binder for a
     * different interface — yields null.
     */
    private fun probeLightCommandBinder(service: IBinder, code: Int): IBinder? {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(SENSOR_DESCRIPTOR)
            if (!service.transact(code, data, reply, 0)) {
                Timber.d("$TAG: TX $code not handled by SensorService")
                return null
            }
            reply.readException()
            val binder = reply.readStrongBinder() ?: return null
            val descriptor = binder.interfaceDescriptor
            if (descriptor != LIGHT_CMD_DESCRIPTOR) {
                Timber.d("$TAG: TX $code returned a $descriptor binder, not LightCommandConnection")
                return null
            }
            return binder
        } catch (e: Exception) {
            Timber.d("$TAG: TX $code is not getLightCommand(): ${e.javaClass.simpleName}: ${e.message}")
            return null
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    private fun loadLightModeClass() {
        try {
            val sensorCtx = context.createPackageContext(
                "io.hammerhead.sensorservice",
                Context.CONTEXT_INCLUDE_CODE or Context.CONTEXT_IGNORE_SECURITY,
            )
            val cls = sensorCtx.classLoader.loadClass(
                "io.hammerhead.datamodels.timeseriesData.models.LightMode",
            )
            @Suppress("UNCHECKED_CAST")
            val enumClass = cls as Class<out Enum<*>>
            val enumConstants = enumClass.enumConstants
            Timber.i("$TAG: Karoo LightMode enum values (${enumConstants?.size}): ${enumConstants?.joinToString { it.name }}")
            lightModeEnumClass = enumClass
            lightModeParcelableCreator = { modeName ->
                java.lang.Enum.valueOf(enumClass, modeName) as Parcelable
            }

            val deviceClass = sensorCtx.classLoader.loadClass(
                "io.hammerhead.datamodels.timeseriesData.models.Device",
            )
            val defaultMarkerClass = sensorCtx.classLoader.loadClass(
                "kotlin.jvm.internal.DefaultConstructorMarker",
            )
            val deviceConstructor = deviceClass.constructors.find { c ->
                c.parameterTypes.lastOrNull() == defaultMarkerClass &&
                    c.parameterTypes[c.parameterTypes.size - 2] == Int::class.javaPrimitiveType
            }
            if (deviceConstructor != null) {
                Timber.d("$TAG: Device constructor: ${deviceConstructor.parameterTypes.map { it.simpleName }}")
                val deviceInfoConstructor = sensorCtx.classLoader.loadClass(
                    "io.hammerhead.datamodels.timeseriesData.models.DeviceInfo",
                ).getDeclaredConstructor()

                deviceCreator = { uid ->
                    val deviceInfo = deviceInfoConstructor.newInstance()
                    val params = arrayOfNulls<Any>(deviceConstructor.parameterTypes.size)
                    params[0] = uid
                    params[1] = deviceInfo
                    params[4] = false
                    params[5] = true
                    params[params.size - 2] = 0x1FFFC
                    params[params.size - 1] = null
                    deviceConstructor.newInstance(*params) as Parcelable
                }
                Timber.d("$TAG: Device creator ready")
            } else {
                Timber.w("$TAG: Could not find Device constructor with DefaultConstructorMarker")
            }

            Timber.d("$TAG: LightMode class loaded")

            pendingRegistrations.toList().forEach { registerConnectionState(it) }
            pendingRegistrations.clear()
            pendingLightParamRegistrations.toList().forEach { registerForLightParameters(it) }
            pendingLightParamRegistrations.clear()
            onServiceReady?.invoke()
        } catch (e: Exception) {
            Timber.e(e, "$TAG: Failed to load LightMode class")
        }
    }

    /** Binds to Karoo's SensorService to claim the light-command session. Idempotent. */
    fun bind() {
        if (bindRequested) return
        val intent = Intent().apply {
            component = ComponentName(
                "io.hammerhead.sensorservice",
                "io.hammerhead.sensorservice.service.SensorService",
            )
        }
        try {
            context.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
            bindRequested = true
            Timber.d("$TAG: Binding to SensorService")
        } catch (e: Exception) {
            Timber.e(e, "$TAG: Failed to bind")
        }
    }

    /**
     * Releases the light-command session so Karoo's own light control regains it.
     * Unbinding drops our SensorService connection, which releases the light-command
     * binder and our listener registrations on the service side.
     */
    fun unbind() {
        if (!bindRequested) return
        // Unbinding drops our SensorService connection, which releases the light-command
        // binder and our listener registrations service-side — no synchronous unregister
        // transactions needed (those would block the caller, often the main thread).
        try { context.unbindService(serviceConnection) } catch (_: Exception) {}
        bindRequested = false
        isBound = false
        sensorBinder = null
        lightCmdBinder = null
        registeredListeners.clear()
        pendingRegistrations.clear()
        pendingLightParamRegistrations.clear()
        Timber.d("$TAG: Released SensorService light control")
    }

    fun setLightMode(deviceId: String, modeName: String): Boolean {
        val binder = lightCmdBinder ?: run {
            Timber.w("$TAG: LightCommand binder not available")
            return false
        }
        val creator = lightModeParcelableCreator ?: run {
            Timber.w("$TAG: LightMode class not loaded")
            return false
        }

        val lightMode = try {
            creator(modeName)
        } catch (e: Exception) {
            Timber.e(e, "$TAG: Invalid mode: $modeName")
            return false
        }

        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(LIGHT_CMD_DESCRIPTOR)
            data.writeString(deviceId)
            val device = deviceCreator?.invoke(deviceId)
            if (device != null) {
                data.writeInt(1)
                device.writeToParcel(data, 0)
            } else {
                data.writeInt(0)
            }
            val bundle = Bundle()
            bundle.classLoader = lightMode.javaClass.classLoader
            bundle.putParcelable("value", lightMode)
            data.writeInt(1)
            bundle.writeToParcel(data, 0)

            binder.transact(TX_SET_LIGHT_MODE, data, reply, 0)
            reply.readException()
            Timber.d("$TAG: setLightMode($deviceId, $modeName) OK")
            return true
        } catch (e: Exception) {
            Timber.e(e, "$TAG: setLightMode($deviceId, $modeName) failed")
            return false
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    override fun setMode(deviceId: String, modeName: String) {
        setLightMode(deviceId, modeName)
    }

    fun registerForLightParameters(deviceId: String) {
        val binder = lightCmdBinder
        val creator = deviceCreator
        if (binder == null || creator == null) {
            pendingLightParamRegistrations.add(deviceId)
            return
        }
        val device = creator(deviceId)
        val listenerId = "light-params-$deviceId"
        if (listenerId in registeredListeners) return

        val enumClass = lightModeEnumClass

        val iface = IInterface { null }
        val listenerBinder = object : Binder() {
            init { attachInterface(iface, LISTENER_DESCRIPTOR) }
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                if (code in 1..16777215) data.enforceInterface(LISTENER_DESCRIPTOR)
                when (code) {
                    1 -> {
                        data.readString()
                        data.readString()
                        val bytes = data.createByteArray()
                        data.readInt()
                        if (bytes != null && enumClass != null) {
                            parseLightParameters(deviceId, bytes, enumClass)
                        }
                        reply?.writeNoException()
                    }
                    2 -> reply?.writeNoException()
                    3 -> reply?.writeNoException()
                    else -> return super.onTransact(code, data, reply, flags)
                }
                return true
            }
        }

        val callData = Parcel.obtain()
        val callReply = Parcel.obtain()
        try {
            callData.writeInterfaceToken(LIGHT_CMD_DESCRIPTOR)
            callData.writeString(deviceId)
            callData.writeInt(1)
            device.writeToParcel(callData, 0)
            callData.writeStrongBinder(listenerBinder)
            binder.transact(TX_REGISTER_LIGHT_PARAMS, callData, callReply, 0)
            callReply.readException()
            registeredListeners.add(listenerId)
            Timber.d("$TAG: Registered light parameters listener for $deviceId")
        } catch (e: Exception) {
            Timber.e(e, "$TAG: Failed to register light parameters for $deviceId")
        } finally {
            callData.recycle()
            callReply.recycle()
        }
    }

    private fun parseLightParameters(deviceId: String, bytes: ByteArray, enumClass: Class<out Enum<*>>) {
        try {
            val parcel = Parcel.obtain()
            try {
                parcel.unmarshall(bytes, 0, bytes.size)
                parcel.setDataPosition(0)

                // LightParameters parcel layout:
                // 1. mode: LightMode written as name string
                // 2. location: written as name string
                // 3. supportedModes: size int + each mode as name string
                val modeName = parcel.readString()
                val locationName = parcel.readString()
                val modesCount = parcel.readInt()
                val modes = mutableSetOf<String>()
                for (i in 0 until modesCount) {
                    val name = parcel.readString()
                    if (name != null && name != "UNKNOWN") {
                        modes.add(name)
                    }
                }

                if (modes.isNotEmpty()) {
                    modes.add("OFF")
                    val existing = _supportedModes.value[deviceId]
                    if (existing != modes) {
                        Timber.d("$TAG: LightParameters for $deviceId: mode=$modeName, location=$locationName, supportedModes=$modes")
                        _supportedModes.update { it + (deviceId to modes) }
                    }
                }
            } finally {
                parcel.recycle()
            }
        } catch (e: Exception) {
            Timber.e(e, "$TAG: Failed to parse LightParameters for $deviceId")
        }
    }

    fun registerConnectionState(deviceId: String) {
        val binder = sensorBinder
        val creator = deviceCreator
        val txRegister = txRegisterDeviceConnection
        if (binder == null || creator == null || txRegister == null) {
            pendingRegistrations.add(deviceId)
            return
        }
        val device = creator(deviceId)
        val listenerId = "light-conn-$deviceId"
        if (listenerId in registeredListeners) return

        val iface = IInterface { null }
        val listenerBinder = object : Binder() {
            init { attachInterface(iface, LISTENER_DESCRIPTOR) }
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                if (code in 1..16777215) data.enforceInterface(LISTENER_DESCRIPTOR)
                when (code) {
                    1 -> {
                        data.readString() // transactionId
                        data.readString() // className
                        val bytes = data.createByteArray()
                        data.readInt() // done
                        if (bytes != null && bytes.size >= 4) {
                            val parcel = Parcel.obtain()
                            try {
                                parcel.unmarshall(bytes, 0, bytes.size)
                                parcel.setDataPosition(0)
                                val stateOrdinal = parcel.readInt()
                                val stateName = when (stateOrdinal) {
                                    0 -> "CONNECTED"
                                    1 -> "SEARCHING"
                                    2 -> "DISABLED"
                                    3 -> "DISCONNECTED"
                                    4 -> "NOT_AVAILABLE"
                                    else -> "UNKNOWN"
                                }
                                Timber.d("$TAG: Connection state $deviceId: $stateName")
                                _connectionStates.update { it + (deviceId to stateName) }
                            } finally {
                                parcel.recycle()
                            }
                        }
                        reply?.writeNoException()
                    }
                    2 -> reply?.writeNoException()
                    3 -> reply?.writeNoException()
                    else -> return super.onTransact(code, data, reply, flags)
                }
                return true
            }
        }

        val callData = Parcel.obtain()
        val callReply = Parcel.obtain()
        try {
            callData.writeInterfaceToken(SENSOR_DESCRIPTOR)
            callData.writeString(listenerId)
            callData.writeInt(1)
            device.writeToParcel(callData, 0)
            callData.writeStrongBinder(listenerBinder)
            binder.transact(txRegister, callData, callReply, 0)
            callReply.readException()
            registeredListeners.add(listenerId)
            Timber.d("$TAG: Registered connection state listener for $deviceId")
        } catch (e: Exception) {
            Timber.e(e, "$TAG: Failed to register connection state for $deviceId")
        } finally {
            callData.recycle()
            callReply.recycle()
        }
    }

    fun unregisterConnectionState(deviceId: String) {
        val binder = sensorBinder ?: return
        val txUnregister = txUnregisterDeviceConnection ?: return
        val listenerId = if (deviceId.startsWith("light-conn-")) deviceId else "light-conn-$deviceId"
        if (listenerId !in registeredListeners) return

        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(SENSOR_DESCRIPTOR)
            data.writeString(listenerId)
            binder.transact(txUnregister, data, reply, 0)
            reply.readException()
            registeredListeners.remove(listenerId)
        } catch (e: Exception) {
            Timber.e(e, "$TAG: Failed to unregister connection state for $listenerId")
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    fun isConnected(): Boolean = lightCmdBinder != null
}
