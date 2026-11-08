package ru.pult.pc

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothHidDeviceAppSdpSettings
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Handler
import android.os.Looper
import java.util.concurrent.Executors

/**
 * Телефон притворяется обычной Bluetooth-клавиатурой + мышью.
 * На компьютере ничего ставить не нужно.
 */
@SuppressLint("MissingPermission")
class BtRemote(private val ctx: Context, private val listener: Listener) : Remote {

    interface Listener {
        fun onState(text: String)
        fun onConnected(connected: Boolean)
    }

    private val main = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()
    private val adapter: BluetoothAdapter? =
        (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter

    @Volatile private var hid: BluetoothHidDevice? = null
    @Volatile private var host: BluetoothDevice? = null
    @Volatile private var buttons = 0
    @Volatile private var closed = false

    private val callback = object : BluetoothHidDevice.Callback() {
        override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, registered: Boolean) {
            main.post {
                if (registered) listener.onState("Готово. Выбери компьютер из списка")
                else listener.onState("Не удалось включить режим клавиатуры")
            }
        }

        override fun onConnectionStateChanged(device: BluetoothDevice?, state: Int) {
            if (state == BluetoothProfile.STATE_CONNECTED) {
                host = device
                main.post { listener.onConnected(true) }
            } else if (state == BluetoothProfile.STATE_DISCONNECTED) {
                if (device == host) host = null
                main.post { listener.onConnected(false) }
            }
        }
    }

    fun start() {
        val a = adapter
        if (a == null) {
            listener.onState("На телефоне нет Bluetooth")
            return
        }
        if (!a.isEnabled) {
            listener.onState("Включи Bluetooth и открой экран заново")
            return
        }
        a.getProfileProxy(ctx, object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                if (profile != BluetoothProfile.HID_DEVICE || closed) return
                val h = proxy as BluetoothHidDevice
                hid = h
                val sdp = BluetoothHidDeviceAppSdpSettings(
                    "Pult PC", "Keyboard and mouse", "Pult",
                    BluetoothHidDevice.SUBCLASS1_COMBO, DESCRIPTOR
                )
                h.registerApp(sdp, null, null, executor, callback)
            }

            override fun onServiceDisconnected(profile: Int) {
                hid = null
            }
        }, BluetoothProfile.HID_DEVICE)
    }

    fun pairedDevices(): List<BluetoothDevice> =
        adapter?.bondedDevices?.toList().orEmpty()

    fun connectTo(device: BluetoothDevice) {
        hid?.connect(device)
    }

    // ---------- отправка ----------

    private fun send(id: Int, data: ByteArray) {
        val h = hid
        val d = host
        if (h != null && d != null) h.sendReport(d, id, data)
    }

    override fun move(dx: Int, dy: Int) {
        var x = dx
        var y = dy
        while (x != 0 || y != 0) {
            val sx = x.coerceIn(-127, 127)
            val sy = y.coerceIn(-127, 127)
            send(2, byteArrayOf(buttons.toByte(), sx.toByte(), sy.toByte(), 0))
            x -= sx
            y -= sy
        }
    }

    override fun mouseButton(button: Int, down: Boolean) {
        val bit = if (button == 2) 2 else 1
        buttons = if (down) buttons or bit else buttons and bit.inv()
        send(2, byteArrayOf(buttons.toByte(), 0, 0, 0))
    }

    override fun scroll(steps: Int) {
        val s = steps.coerceIn(-127, 127)
        send(2, byteArrayOf(buttons.toByte(), 0, 0, s.toByte()))
    }

    override fun key(spec: String) {
        var mods = 0
        var k = 0
        for (p in spec.lowercase().split("+")) {
            when (p) {
                "ctrl" -> mods = mods or 1
                "shift" -> mods = mods or 2
                "alt" -> mods = mods or 4
                "win" -> mods = mods or 8
                else -> k = KEYS[p] ?: 0
            }
        }
        if (k != 0) sendKey(mods, k)
    }

    private fun sendKey(mods: Int, usage: Int) {
        send(1, byteArrayOf(mods.toByte(), 0, usage.toByte(), 0, 0, 0, 0, 0))
        send(1, ByteArray(8))
    }

    override fun text(s: String) {
        if (closed) return
        executor.execute {
            for (c in s) {
                val m = charToHid(c) ?: continue
                sendKey(m[0], m[1])
                try { Thread.sleep(12) } catch (_: InterruptedException) { return@execute }
            }
        }
    }

    override fun media(name: String) {
        val usage = when (name) {
            "playpause" -> 0xCD
            "next" -> 0xB5
            "prev" -> 0xB6
            "mute" -> 0xE2
            "volup" -> 0xE9
            "voldown" -> 0xEA
            else -> return
        }
        send(3, byteArrayOf((usage and 0xFF).toByte(), (usage shr 8).toByte()))
        send(3, byteArrayOf(0, 0))
    }

    override fun close() {
        closed = true
        try { host?.let { hid?.disconnect(it) } } catch (_: Exception) {}
        try { hid?.unregisterApp() } catch (_: Exception) {}
        try { adapter?.closeProfileProxy(BluetoothProfile.HID_DEVICE, hid) } catch (_: Exception) {}
        hid = null
        host = null
        executor.shutdown()
    }

    companion object {
        // Описание устройства: клавиатура (id 1), мышь (id 2), медиаклавиши (id 3)
        private val DESCRIPTOR: ByteArray = intArrayOf(
            // Клавиатура
            0x05, 0x01, 0x09, 0x06, 0xA1, 0x01, 0x85, 0x01,
            0x05, 0x07, 0x19, 0xE0, 0x29, 0xE7, 0x15, 0x00, 0x25, 0x01, 0x75, 0x01, 0x95, 0x08, 0x81, 0x02,
            0x95, 0x01, 0x75, 0x08, 0x81, 0x01,
            0x95, 0x06, 0x75, 0x08, 0x15, 0x00, 0x25, 0x65, 0x05, 0x07, 0x19, 0x00, 0x29, 0x65, 0x81, 0x00,
            0xC0,
            // Мышь: 3 кнопки, X, Y, колесо
            0x05, 0x01, 0x09, 0x02, 0xA1, 0x01, 0x85, 0x02, 0x09, 0x01, 0xA1, 0x00,
            0x05, 0x09, 0x19, 0x01, 0x29, 0x03, 0x15, 0x00, 0x25, 0x01, 0x95, 0x03, 0x75, 0x01, 0x81, 0x02,
            0x95, 0x01, 0x75, 0x05, 0x81, 0x01,
            0x05, 0x01, 0x09, 0x30, 0x09, 0x31, 0x09, 0x38, 0x15, 0x81, 0x25, 0x7F, 0x75, 0x08, 0x95, 0x03, 0x81, 0x06,
            0xC0, 0xC0,
            // Медиаклавиши
            0x05, 0x0C, 0x09, 0x01, 0xA1, 0x01, 0x85, 0x03, 0x15, 0x00, 0x26, 0xFF, 0x03,
            0x19, 0x00, 0x2A, 0xFF, 0x03, 0x75, 0x10, 0x95, 0x01, 0x81, 0x00, 0xC0
        ).map { it.toByte() }.toByteArray()

        private val KEYS: Map<String, Int> = HashMap<String, Int>().apply {
            for (c in 'a'..'z') put(c.toString(), 0x04 + (c - 'a'))
            for (i in 1..9) put(i.toString(), 0x1D + i)
            put("0", 0x27)
            put("enter", 0x28); put("esc", 0x29); put("backspace", 0x2A)
            put("tab", 0x2B); put("space", 0x2C)
            put("right", 0x4F); put("left", 0x50); put("down", 0x51); put("up", 0x52)
            put("pgup", 0x4B); put("pgdn", 0x4E); put("home", 0x4A); put("end", 0x4D)
            put("delete", 0x4C)
            for (i in 1..12) put("f$i", 0x39 + i)
        }

        private const val PLAIN = "-=[]\\;'`,./"
        private const val SHIFTED = "_+{}|:\"~<>?"
        private val PUNCT_USAGE = intArrayOf(0x2D, 0x2E, 0x2F, 0x30, 0x31, 0x33, 0x34, 0x35, 0x36, 0x37, 0x38)
        private const val SHIFT_DIGITS = "!@#$%^&*()"

        /** Возвращает [модификаторы, код клавиши] или null, если символ не поддерживается. */
        private fun charToHid(c: Char): IntArray? {
            return when {
                c in 'a'..'z' -> intArrayOf(0, 0x04 + (c - 'a'))
                c in 'A'..'Z' -> intArrayOf(2, 0x04 + (c - 'A'))
                c in '1'..'9' -> intArrayOf(0, 0x1E + (c - '1'))
                c == '0' -> intArrayOf(0, 0x27)
                c == ' ' -> intArrayOf(0, 0x2C)
                c == '\n' -> intArrayOf(0, 0x28)
                PLAIN.indexOf(c) >= 0 -> intArrayOf(0, PUNCT_USAGE[PLAIN.indexOf(c)])
                SHIFTED.indexOf(c) >= 0 -> intArrayOf(2, PUNCT_USAGE[SHIFTED.indexOf(c)])
                SHIFT_DIGITS.indexOf(c) >= 0 -> {
                    val i = SHIFT_DIGITS.indexOf(c)
                    intArrayOf(2, if (i == 9) 0x27 else 0x1E + i)
                }
                else -> null
            }
        }
    }
}
