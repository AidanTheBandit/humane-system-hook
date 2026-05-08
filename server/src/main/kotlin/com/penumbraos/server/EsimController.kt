package com.penumbraos.server

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log

object EsimController {

    private const val TAG = "PenumbraEsimController"
    private const val LPA_PACKAGE = "humane.connectivity.esimlpa"
    private const val FACTORY_SERVICE_CLASS = "humane.connectivity.esimlpa.factoryService"

    fun dispatch(
        context: Context,
        requestId: String,
        lpaAction: String,
        iccid: String? = null,
        activationCode: String? = null,
        nickname: String? = null,
        source: String = "server",
    ) {
        val intent = Intent().apply {
            component = ComponentName(LPA_PACKAGE, FACTORY_SERVICE_CLASS)
            action = lpaAction
            putExtra("penumbra_source", source)
            putExtra("penumbra_request_id", requestId)
            if (iccid != null) putExtra("iccid", iccid)
            if (activationCode != null) putExtra("activationCode", activationCode)
            if (nickname != null) putExtra("Nickname", nickname)
        }

        Log.w(
            TAG,
            "Dispatching LPA action=${intent.action} iccid=$iccid activationCode=${activationCode != null} nickname=$nickname requestId=$requestId source=$source"
        )
        val componentName = context.startService(intent)
        Log.w(TAG, "startService returned: $componentName")
    }
}
