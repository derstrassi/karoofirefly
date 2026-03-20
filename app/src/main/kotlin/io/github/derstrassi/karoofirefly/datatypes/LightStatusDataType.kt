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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
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
            combine(
                engine.currentFrontMode,
                engine.currentRearMode,
                engine.currentZone,
            ) { frontMode, rearMode, zone ->
                mapOf(
                    FIELD_FRONT_MODE to frontMode.modeNumber.toDouble(),
                    FIELD_REAR_MODE to rearMode.modeNumber.toDouble(),
                    FIELD_ZONE to zone.ordinal.toDouble(),
                )
            }.distinctUntilChanged().collect { values ->
                emitter.onNext(
                    StreamState.Streaming(
                        DataPoint(dataTypeId = dataTypeId, values = values),
                    ),
                )
            }
        }

        emitter.setCancellable { scope.cancel() }
    }

    override fun startView(context: Context, config: ViewConfig, emitter: ViewEmitter) {
        emitter.onNext(UpdateGraphicConfig(showHeader = false))

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

        scope.launch {
            combine(
                engine.currentFrontMode,
                engine.currentRearMode,
                engine.state,
            ) { frontMode, rearMode, state ->
                Triple(frontMode, rearMode, state)
            }.distinctUntilChanged().collect { (frontMode, rearMode, state) ->
                val remoteViews = RemoteViews(context.packageName, R.layout.light_status_view)

                val modeText = when (state) {
                    LightControlEngine.EngineState.IDLE -> "Lights Off"
                    else -> "F: ${frontMode.displayName} | R: ${rearMode.displayName}"
                }

                remoteViews.setTextViewText(R.id.light_mode_text, modeText)
                remoteViews.setTextViewText(R.id.light_battery_text, engine.currentZone.value.name)

                emitter.updateView(remoteViews)
            }
        }

        emitter.setCancellable { scope.cancel() }
    }
}
