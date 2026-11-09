package ru.pult.pc

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * «Указка»: курсор двигается, когда ты ведёшь телефоном, как лазерной указкой.
 * Работает через гироскоп; влево-вправо считается вокруг вертикали (с учётом наклона телефона).
 */
class AirPointer(ctx: Context, private val remote: Remote) : SensorEventListener {

    private val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val gyro: Sensor? = sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private val accel: Sensor? = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val g = floatArrayOf(0f, 0f, 9.8f)
    private var lastT = 0L
    private var accX = 0f
    private var accY = 0f
    private val sensitivity = 1100f   // пикселей на радиан

    fun start(): Boolean {
        if (gyro == null || accel == null) return false
        lastT = 0L
        accX = 0f
        accY = 0f
        sm.registerListener(this, accel, SensorManager.SENSOR_DELAY_GAME)
        sm.registerListener(this, gyro, SensorManager.SENSOR_DELAY_GAME)
        return true
    }

    fun stop() {
        sm.unregisterListener(this)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    override fun onSensorChanged(e: SensorEvent) {
        if (e.sensor.type == Sensor.TYPE_ACCELEROMETER) {
            for (i in 0..2) g[i] = g[i] * 0.9f + e.values[i] * 0.1f
            return
        }
        val n = sqrt(g[0] * g[0] + g[1] * g[1] + g[2] * g[2])
        if (n < 1f) return
        var up = (e.values[0] * g[0] + e.values[1] * g[1] + e.values[2] * g[2]) / n
        var pitch = e.values[0]
        if (abs(up) < 0.02f) up = 0f
        if (abs(pitch) < 0.02f) pitch = 0f
        val dt = if (lastT == 0L) 0f else (e.timestamp - lastT) / 1_000_000_000f
        lastT = e.timestamp
        accX += -up * dt * sensitivity
        accY += -pitch * dt * sensitivity
        val ix = accX.toInt()
        val iy = accY.toInt()
        if (ix != 0 || iy != 0) {
            accX -= ix
            accY -= iy
            remote.move(ix, iy)
        }
    }
}
