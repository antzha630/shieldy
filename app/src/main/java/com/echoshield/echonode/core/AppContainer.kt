package com.echoshield.echonode.core

import android.content.Context
import com.echoshield.echonode.comms.MeshGatewayImpl
import com.echoshield.echonode.core.contracts.MeshGateway
import com.echoshield.echonode.core.contracts.SensorGateway
import com.echoshield.echonode.sensor.LocationProvider
import com.echoshield.echonode.sensor.SensorGatewayImpl

/**
 * Integration seam for the app.
 * Only integration owners should modify this file.
 */
class AppContainer(context: Context) {
    val sensorGateway: SensorGateway = SensorGatewayImpl()
    val meshGateway: MeshGateway = MeshGatewayImpl(context)
    val locationProvider: LocationProvider = LocationProvider(context)
}
