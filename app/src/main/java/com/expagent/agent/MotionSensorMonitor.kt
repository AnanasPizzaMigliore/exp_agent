/*
* Copyright (C) 2026 exp agent contributors
*
* This program is free software: you can redistribute it and/or modify
* it under the terms of the GNU General Public License as published by
* the Free Software Foundation, version 3.
*
* This program is distributed in the hope that it will be useful,
* but WITHOUT ANY WARRANTY; without even the implied warranty of
* MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
* GNU General Public License for more details.
*
* You should have received a copy of the GNU General Public License
* along with this program. If not, see <https://www.gnu.org/licenses/>.
*/

package com.expagent.agent

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import kotlin.math.sqrt

/** Rolling summary of how much the phone itself is moving. */
data class PhoneMotion(
    val available: Boolean,
    val angularSpeedRms: Double,
    val angularSpeedMax: Double,
    val samples: Int,
    val ageMs: Long,
    ) {

    /** No sensor, or no recent samples: say so rather than reporting stillness. */
    val unknown: Boolean
    get() = !available||samples<3||ageMs>400
    }

/**
* Gyroscope, summarised over a short window.
*
* The gyroscope earns its place by being content-independent. The frame measures
* in [FrameMotion] cannot tell a genuinely still plain lid from a smeared one
* with confidence; angular velocity can, and it costs almost nothing because the
* sensor is already running for the system.
*
* It is not sufficient on its own, which is the reason [FrameMotion] exists too:
* a user can hold the phone perfectly still and rotate the package with their
* other hand, and no gyroscope will ever see that.
*/
class MotionSensorMonitor(context: Context) {

    private val manager=context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val gyroscope=manager?.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

    private var thread: HandlerThread?=null
    private var handler: Handler?=null

    /** |w| samples with their arrival time, newest last. Guarded by itself. */
    private val samples=ArrayDeque<Pair<Long, Double>>()

    val available: Boolean
    get() = gyroscope!=null

    private val listener=object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            val x=event.values[0].toDouble()
            val y=event.values[1].toDouble()
            val z=event.values[2].toDouble()

            // Windowed on arrival time, not on event.timestamp. The event clock
            // is documented as elapsedRealtimeNanos but vendors have shipped
            // other bases, and a wrong base here would silently mean "no recent
            // samples" forever.
            val now=SystemClock.elapsedRealtimeNanos()
            val speed=sqrt(x*x+y*y+z*z)

            synchronized(samples) {
                samples.addLast(Pair(now, speed))
                val cutoff=now-WINDOW_MS*1_000_000L
                while (samples.isNotEmpty()&&samples.first().first<cutoff)
                samples.removeFirst()
                }
            }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {

            }
        }

    fun start() {
        val sensor=gyroscope ?: return
        if (thread!=null)
        return

        val worker=HandlerThread("vscan-motion").apply { start() }
        val target=Handler(worker.looper)

        thread=worker
        handler=target

        // SENSOR_DELAY_GAME is about 50 Hz, which is plenty to characterise a
        // settling hand and cheap enough to leave running for a session.
        manager?.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_GAME, target)
        }

    fun stop() {
        manager?.unregisterListener(listener)
        thread?.quitSafely()
        thread=null
        handler=null

        synchronized(samples) { samples.clear() }
        }

    fun summary(): PhoneMotion {
        if (gyroscope==null)
        return PhoneMotion(false, 0.0, 0.0, 0, Long.MAX_VALUE)

        val now=SystemClock.elapsedRealtimeNanos()

        val snapshot=synchronized(samples) { samples.toList() }
        if (snapshot.isEmpty())
        return PhoneMotion(true, 0.0, 0.0, 0, Long.MAX_VALUE)

        var sumSq=0.0
        var peak=0.0
        for ((_, speed) in snapshot) {
            sumSq+=speed*speed
            if (speed>peak) peak=speed
            }

        return PhoneMotion(
            available=true,
            angularSpeedRms=sqrt(sumSq/snapshot.size),
            angularSpeedMax=peak,
            samples=snapshot.size,
            ageMs=(now-snapshot.last().first)/1_000_000L,
            )
        }

    companion object {

        const val WINDOW_MS=250L
        }
    }
