package ru.pult.pc

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * Одна Activity, интерфейс строится кодом (без XML).
 * Батарея: никаких служб; при сворачивании приложения всё отключается.
 */
class MainActivity : Activity() {

    private lateinit var root: FrameLayout
    private var remote: Remote? = null
    private var bt: BtRemote? = null
    private var wifi: WifiRemote? = null
    private var inControls = false
    private var currentTab = 0

    private var btStatus: TextView? = null
    private var btList: LinearLayout? = null
    private var wifiStatus: TextView? = null

    private val prefs by lazy { getSharedPreferences("pult", MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Colors.BG
        window.navigationBarColor = Colors.BG
        root = FrameLayout(this)
        root.setBackgroundColor(Colors.BG)
        setContentView(root)
    }

    override fun onStart() {
        super.onStart()
        if (remote == null && bt == null && wifi == null) showHome()
    }

    override fun onStop() {
        super.onStop()
        disconnect()
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (bt != null || wifi != null || remote != null || btStatus != null || wifiStatus != null) {
            disconnect()
            showHome()
        } else {
            super.onBackPressed()
        }
    }

    // Кнопки громкости листают слайды (только на вкладке «Слайды»)
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        val r = remote
        if (r != null && inControls && currentTab == 0 &&
            (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_UP)
        ) {
            if ((event?.repeatCount ?: 0) == 0) {
                r.key(if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) "right" else "left")
            }
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            showBt()
        } else {
            toast("Без доступа к Bluetooth этот режим не работает")
        }
    }

    // ---------- общее ----------

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    private fun disconnect() {
        try { bt?.close() } catch (_: Exception) {}
        try { wifi?.close() } catch (_: Exception) {}
        bt = null
        wifi = null
        remote = null
        inControls = false
        btStatus = null
        btList = null
        wifiStatus = null
    }

    private fun show(view: View) {
        root.removeAllViews()
        root.addView(view, FrameLayout.LayoutParams(-1, -1))
        inControls = false
    }

    private fun column(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(20), dp(16), dp(12))
    }

    // ---------- главный экран ----------

    private fun showHome() {
        val col = column()
        col.addView(label("Пульт для ПК", 28f), lp(-1, -2, 0f, 4))
        col.addView(label("Выбери, как подключиться", 15f, Colors.MUTED), lp(-1, -2, 0f, 4))

        col.addView(
            button("Bluetooth\nбез программ на компьютере", 18f, Colors.ACCENT) { showBt() },
            lp(-1, -2, 0f, 6).apply { topMargin = dp(28) }
        )
        col.addView(button("Wi-Fi\nнужна программа на компьютере", 18f) { showWifi() }, lp(-1, -2, 0f, 6))

        val saved = prefs.getString("addr", "") ?: ""
        if (saved.isNotEmpty()) {
            col.addView(
                button("Подключиться к $saved") { connectWifi(saved, prefs.getString("code", "") ?: "") },
                lp(-1, -2, 0f, 6)
            )
        }
        show(col)
    }

    // ---------- Wi-Fi ----------

    private fun showWifi() {
        val col = column()
        col.addView(label("Wi-Fi", 24f), lp(-1, -2, 0f, 4))
        col.addView(
            label("Запусти PultServer.exe на компьютере. Адрес и код видны, если нажать на его значок возле часов.", 14f, Colors.MUTED),
            lp(-1, -2, 0f, 4)
        )
        val addr = field("Адрес, например 192.168.1.5:8765")
        addr.setText(prefs.getString("addr", ""))
        val code = field("Код (6 цифр)", numeric = true)
        code.setText(prefs.getString("code", ""))
        col.addView(addr, lp(-1, -2, 0f, 6))
        col.addView(code, lp(-1, -2, 0f, 6))
        val status = label("", 14f, Colors.MUTED)
        col.addView(button("Подключиться", 18f, Colors.ACCENT) {
            connectWifi(addr.text.toString().trim(), code.text.toString().trim())
        }, lp(-1, -2, 0f, 6))
        col.addView(status, lp(-1, -2, 0f, 6))
        col.addView(button("Назад") { showHome() }, lp(-1, -2, 0f, 6))
        show(col)
        wifiStatus = status
    }

    private fun connectWifi(addr: String, code: String) {
        if (addr.isEmpty() || code.isEmpty()) {
            toast("Введи адрес и код")
            return
        }
        val parts = addr.split(":")
        val host = parts[0]
        val port = parts.getOrNull(1)?.toIntOrNull() ?: 8765
        if (wifiStatus == null) showWifi()
        wifiStatus?.text = "Подключаюсь…"
        wifi?.close()
        val w = WifiRemote(host, port, code, object : WifiRemote.Callback {
            override fun onResult(ok: Boolean, message: String) {
                if (ok) {
                    prefs.edit().putString("addr", addr).putString("code", code).apply()
                    remote = wifi
                    showControls()
                } else {
                    wifiStatus?.text = message
                }
            }

            override fun onClosed() {
                if (inControls) {
                    toast("Связь с компьютером потеряна")
                    disconnect()
                    showHome()
                }
            }
        })
        wifi = w
        w.connect()
    }

    // ---------- Bluetooth ----------

    private fun showBt() {
        if (Build.VERSION.SDK_INT >= 31 &&
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), 1)
            return
        }
        val col = column()
        col.addView(label("Bluetooth", 24f), lp(-1, -2, 0f, 4))
        col.addView(
            label(
                "1. Нажми «Сделать телефон видимым».\n" +
                    "2. На компьютере: Параметры → Bluetooth → Добавить устройство → найди этот телефон.\n" +
                    "3. Подтверди код на обоих устройствах.\n" +
                    "4. Вернись сюда и выбери компьютер из списка.",
                14f, Colors.MUTED
            ),
            lp(-1, -2, 0f, 4)
        )
        col.addView(button("Сделать телефон видимым (2 минуты)") {
            startActivity(
                Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE)
                    .putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 120)
            )
        }, lp(-1, -2, 0f, 6))
        val status = label("Включаю…", 15f, Colors.TEXT)
        col.addView(status, lp(-1, -2, 0f, 6))
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(this).apply { addView(list) }
        col.addView(scroll, lp(-1, 0, 1f, 0))
        col.addView(button("Назад") { disconnect(); showHome() }, lp(-1, -2, 0f, 6))
        show(col)
        btStatus = status
        btList = list

        val r = BtRemote(this, object : BtRemote.Listener {
            override fun onState(text: String) {
                btStatus?.text = text
                refreshDevices()
            }

            override fun onConnected(connected: Boolean) {
                if (connected) {
                    if (!inControls) {
                        remote = bt
                        showControls()
                    }
                } else if (inControls) {
                    toast("Связь с компьютером потеряна")
                    disconnect()
                    showHome()
                }
            }
        })
        bt = r
        r.start()
    }

    private fun refreshDevices() {
        val list = btList ?: return
        val r = bt ?: return
        list.removeAllViews()
        for (d in r.pairedDevices()) {
            val name = d.name ?: d.address
            list.addView(button(name) {
                btStatus?.text = "Подключаюсь к $name…"
                r.connectTo(d)
            }, lp(-1, -2, 0f, 4))
        }
    }

    // ---------- управление ----------

    private fun showControls() {
        val r = remote ?: return
        val container = FrameLayout(this)
        val tabs = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val names = arrayOf("Слайды", "Тачпад", "Медиа", "Клавиши")
        val tabButtons = ArrayList<Button>()

        fun select(i: Int) {
            currentTab = i
            container.removeAllViews()
            val v = when (i) {
                0 -> slidesView(r)
                1 -> touchView(r)
                2 -> mediaView(r)
                else -> keysView(r)
            }
            container.addView(v, FrameLayout.LayoutParams(-1, -1))
            tabButtons.forEachIndexed { idx, b ->
                b.background = rippleBg(if (idx == i) Colors.ACCENT else Colors.SURFACE)
            }
        }

        names.forEachIndexed { i, n ->
            val b = button(n, 14f) { select(i) }
            tabButtons.add(b)
            tabs.addView(b, lp(0, -2, 1f, 3))
        }

        val col = column()
        col.addView(container, lp(-1, 0, 1f, 0))
        col.addView(tabs, lp(-1, -2, 0f, 0))
        show(col)
        inControls = true
        select(currentTab)
    }

    private fun slidesView(r: Remote): View {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(button("Вперёд  ▶", 30f, Colors.ACCENT) { r.key("right") }, lp(-1, 0, 3f, 4))
        col.addView(button("◀  Назад", 22f) { r.key("left") }, lp(-1, 0, 2f, 4))
        col.addView(row(
            button("С начала\n(F5)") { r.key("f5") },
            button("С текущего\n(Shift+F5)") { r.key("shift+f5") },
            heightWeight = 1f
        ))
        col.addView(row(
            button("Чёрный экран") { r.key("b") },
            button("Выйти (Esc)") { r.key("esc") },
            heightWeight = 1f
        ))
        col.addView(label("Кнопки громкости на телефоне тоже листают: «−» вперёд, «+» назад", 13f, Colors.MUTED), lp(-1, -2, 0f, 4))
        return col
    }

    private fun touchView(r: Remote): View {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(TouchpadView(this, r), lp(-1, 0, 1f, 4))
        col.addView(row(
            button("Левая кнопка") { r.click(1) },
            button("Правая кнопка") { r.click(2) }
        ))
        return col
    }

    private fun mediaView(r: Remote): View {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(row(
            button("⏮", 28f) { r.media("prev") },
            button("⏯", 28f, Colors.ACCENT) { r.media("playpause") },
            button("⏭", 28f) { r.media("next") },
            heightWeight = 1f
        ))
        col.addView(row(
            button("Громче", 20f) { r.media("volup") },
            button("Тише", 20f) { r.media("voldown") },
            button("Без звука", 20f) { r.media("mute") },
            heightWeight = 1f
        ))
        return col
    }

    private fun keysView(r: Remote): View {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val input: EditText = field("Текст для компьютера")
        col.addView(input, lp(-1, -2, 0f, 4))
        if (bt != null) {
            col.addView(label("По Bluetooth печатаются только английские буквы, цифры и знаки", 12f, Colors.MUTED), lp(-1, -2, 0f, 4))
        }
        col.addView(row(
            button("Отправить", 17f, Colors.ACCENT) {
                val s = input.text.toString()
                if (s.isNotEmpty()) {
                    r.text(s)
                    input.setText("")
                }
            },
            button("Enter") { r.key("enter") }
        ))
        col.addView(row(
            button("←") { r.key("left") }, button("↑") { r.key("up") },
            button("↓") { r.key("down") }, button("→") { r.key("right") }
        ))
        col.addView(row(
            button("Esc") { r.key("esc") }, button("Tab") { r.key("tab") },
            button("⌫") { r.key("backspace") }, button("Del") { r.key("delete") }
        ))
        col.addView(row(
            button("Alt+Tab", 15f) { r.key("alt+tab") }, button("Win+D", 15f) { r.key("win+d") },
            button("Ctrl+C", 15f) { r.key("ctrl+c") }, button("Ctrl+V", 15f) { r.key("ctrl+v") }
        ))
        val scroll = ScrollView(this)
        scroll.addView(col)
        return scroll
    }
}
