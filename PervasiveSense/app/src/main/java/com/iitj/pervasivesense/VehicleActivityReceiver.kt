package com.iitj.pervasivesense

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.google.android.gms.location.ActivityRecognitionResult
import com.google.android.gms.location.DetectedActivity

/**
 * Receives Google Activity Recognition results (requested by the service every
 * few seconds) and forwards the IN_VEHICLE confidence to the sensing engine.
 * Vehicle detection is delegated to this trained, production system because
 * no real bus/car/metro IMU recordings were available to train DeepSense on.
 */
class VehicleActivityReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!ActivityRecognitionResult.hasResult(intent)) return
        val result = ActivityRecognitionResult.extractResult(intent) ?: return
        SensingRepository.deliverVehicleConfidence(result.getActivityConfidence(DetectedActivity.IN_VEHICLE))
    }
}
