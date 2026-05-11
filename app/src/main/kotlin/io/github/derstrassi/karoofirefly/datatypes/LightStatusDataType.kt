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
        const val FIELD_ZONE = "zone"
        const val FIELD_ACTIVE = "active"
    }

    override fun startStream(emitter: Emitter<StreamState>) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        scope.launch {
            combine(
                engine.activeZone,
                engine.currentZone,
            ) { activeZone, currentZone ->
                mapOf(
                    FIELD_ZONE to currentZone.ordinal.toDouble(),
                    FIELD_ACTIVE to if (activeZone != null) 1.0 else 0.0,
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
                engine.activeZone,
                engine.state,
            ) { activeZone, state ->
                Pair(activeZone, state)
            }.distinctUntilChanged().collect { (activeZone, state) ->
                val remoteViews = RemoteViews(context.packageName, R.layout.light_status_view)

                val modeText = when (state) {
                    LightControlEngine.EngineState.IDLE -> "Lights Off"
                    else -> if (activeZone != null) "Zone: ${activeZone.name}" else "Lights Off"
                }

                remoteViews.setTextViewText(R.id.light_mode_text, modeText)
                remoteViews.setTextViewText(R.id.light_battery_text, engine.currentZone.value.name)

                emitter.updateView(remoteViews)
            }
        }

        emitter.setCancellable { scope.cancel() }
    }
}
