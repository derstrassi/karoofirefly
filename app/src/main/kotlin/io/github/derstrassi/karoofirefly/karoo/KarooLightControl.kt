package io.github.derstrassi.karoofirefly.karoo

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.os.Parcel
import android.os.Parcelable
import timber.log.Timber

/**
 * Controls ANT+ bike lights through Karoo's internal SensorService AIDL.
 *
 * SensorServiceAIDL (descriptor: io.hammerhead.sensorservice.SensorServiceAIDL):
 *   Transaction 17: getLightCommandConnection() → returns IBinder
 *
 * LightCommandConnectionAIDL (descriptor: io.hammerhead.sensorservice.LightCommandConnectionAIDL):
 *   Transaction 3: setLightMode(id: String, device: Device?, bundle: Bundle)
 *     - Bundle contains LightMode parcelable (writeToParcel writes enum name as string)
 */
class KarooLightControl(private val context: Context) {

    companion object {
        private const val TAG = "KarooLightControl"
        private const val SENSOR_DESCRIPTOR = "io.hammerhead.sensorservice.SensorServiceAIDL"
        private const val LIGHT_CMD_DESCRIPTOR = "io.hammerhead.sensorservice.LightCommandConnectionAIDL"
        private const val TX_GET_LIGHT_CMD = 17
        private const val TX_SET_LIGHT_MODE = 3
    }

    private var sensorBinder: IBinder? = null
    private var lightCmdBinder: IBinder? = null
    private var lightModeParcelableCreator: ((String) -> Parcelable)? = null
    private var deviceCreator: ((String) -> Parcelable)? = null
    private var isBound = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            sensorBinder = service
            isBound = true
            Timber.d("$TAG: Connected to SensorService")
            if (service != null) {
                getLightCommandBinder(service)
                loadLightModeClass()
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            sensorBinder = null
            lightCmdBinder = null
            isBound = false
            Timber.d("$TAG: Disconnected from SensorService")
        }
    }

    private fun getLightCommandBinder(service: IBinder) {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(SENSOR_DESCRIPTOR)
            // Transaction 17 is a simple getter — no params after enforceInterface
            service.transact(TX_GET_LIGHT_CMD, data, reply, 0)
            reply.readException()
            lightCmdBinder = reply.readStrongBinder()
            Timber.d("$TAG: Got LightCommand binder: $lightCmdBinder")
        } catch (e: Exception) {
            Timber.e(e, "$TAG: Failed to get LightCommand binder")
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
            lightModeParcelableCreator = { modeName ->
                java.lang.Enum.valueOf(enumClass, modeName) as Parcelable
            }

            // Load Device class — use the constructor with DefaultConstructorMarker
            val deviceClass = sensorCtx.classLoader.loadClass(
                "io.hammerhead.datamodels.timeseriesData.models.Device",
            )
            val defaultMarkerClass = sensorCtx.classLoader.loadClass(
                "kotlin.jvm.internal.DefaultConstructorMarker",
            )
            // Find the constructor with (String, ..., int, DefaultConstructorMarker)
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
                    params[0] = uid                    // uid: String
                    params[1] = deviceInfo             // info: DeviceInfo (non-null)
                    // params[2] = null               // supportedDataTypes: List
                    // params[3] = null               // blockedDataTypes: List
                    params[4] = false                  // isConnectedAtLeastOnce: boolean
                    params[5] = true                   // isEnabled: boolean
                    // params[6..16] = null            // nullable fields
                    params[params.size - 2] = 0x1FFFC  // defaults: skip all except uid(0), info(1), bool(4), bool(5)
                    params[params.size - 1] = null     // DefaultConstructorMarker
                    deviceConstructor.newInstance(*params) as Parcelable
                }
                Timber.d("$TAG: Device creator ready")
            } else {
                Timber.w("$TAG: Could not find Device constructor with DefaultConstructorMarker")
            }

            Timber.d("$TAG: LightMode class loaded")
        } catch (e: Exception) {
            Timber.e(e, "$TAG: Failed to load LightMode class")
        }
    }

    fun bind() {
        val intent = Intent().apply {
            component = ComponentName(
                "io.hammerhead.sensorservice",
                "io.hammerhead.sensorservice.service.SensorService",
            )
        }
        try {
            context.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
            Timber.d("$TAG: Binding to SensorService")
        } catch (e: Exception) {
            Timber.e(e, "$TAG: Failed to bind")
        }
    }

    fun unbind() {
        if (isBound) {
            try { context.unbindService(serviceConnection) } catch (_: Exception) {}
            isBound = false
            sensorBinder = null
            lightCmdBinder = null
        }
    }

    /**
     * Set the light mode for a Karoo-paired ANT+ light.
     *
     * @param deviceId Device UID, e.g. "28691-35-5"
     * @param modeName LightMode enum name, e.g. "SLOW_FLASH"
     */
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
            // Param 1: id (String)
            data.writeString(deviceId)
            // Param 2: Device (Parcelable)
            val device = deviceCreator?.invoke(deviceId)
            if (device != null) {
                data.writeInt(1)
                device.writeToParcel(data, 0)
            } else {
                data.writeInt(0)
            }
            // Param 3: Bundle with LightMode
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

    fun isConnected(): Boolean = lightCmdBinder != null
}
