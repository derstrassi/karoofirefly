package io.github.derstrassi.karoofirefly.datatypes

import android.content.Context
import android.widget.RemoteViews
import io.github.derstrassi.karoofirefly.R
import io.github.derstrassi.karoofirefly.engine.LightControlEngine
import io.hammerhead.karooext.extension.DataTypeImpl
import io.hammerhead.karooext.internal.Emitter
import io.hammerhead.karooext.internal.ViewEmitter
import io.hammerhead.karooext.models.DataPoint
import io.hammerhead.karooext.models.StreamState
import io.hammerhead.karooext.models.UpdateGraphicConfig
import io.hammerhead.karooext.models.ViewConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class LightStatusDataType(
    private val engine: LightControlEngine,
) : DataTypeImpl("karoo-light-controller", "light-status") {

    companion object {
        const val FIELD_FRONT_MODE = "front_mode"
        const val FIELD_REAR_MODE = "rear_mode"
        const val FIELD_ZONE = "zone"
    }

    override fun startStream(emitter: Emitter<StreamState>) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        scope.launch {
            while (isActive) {
                val values = mutableMapOf<String, Double>()
                values[FIELD_FRONT_MODE] = engine.currentFrontMode.value.modeNumber.toDouble()
                values[FIELD_REAR_MODE] = engine.currentRearMode.value.modeNumber.toDouble()
                values[FIELD_ZONE] = engine.currentZone.value.ordinal.toDouble()

                emitter.onNext(
                    StreamState.Streaming(
                        DataPoint(dataTypeId = dataTypeId, values = values),
                    ),
                )
                delay(1000)
            }
        }

        emitter.setCancellable { scope.cancel() }
    }

    override fun startView(context: Context, config: ViewConfig, emitter: ViewEmitter) {
        emitter.onNext(UpdateGraphicConfig(showHeader = false))

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

        scope.launch {
            while (isActive) {
                val remoteViews = RemoteViews(context.packageName, R.layout.light_status_view)

                val frontMode = engine.currentFrontMode.value
                val rearMode = engine.currentRearMode.value
                val state = engine.state.value

                val modeText = when (state) {
                    LightControlEngine.EngineState.IDLE -> "Lights Off"
                    else -> "F: ${frontMode.displayName} | R: ${rearMode.displayName}"
                }

                remoteViews.setTextViewText(R.id.light_mode_text, modeText)
                remoteViews.setTextViewText(R.id.light_battery_text, engine.currentZone.value.name)

                emitter.updateView(remoteViews)
                delay(1000)
            }
        }

        emitter.setCancellable { scope.cancel() }
    }
}
