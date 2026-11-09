package ru.pult.pc

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import kotlin.math.abs

/**
 * Одна Activity, интерфейс строится кодом (без XML).
 * Соединение живёт, пока приложение на экране; при сворачивании всё отключается.
 */
class MainActivity : Activity() {

    private val main = Handler(Looper.getMainLooper())
    private lateinit var root: FrameLayout

    private var remote: Remote? = null
    private var bt: BtRemote? = null
    private var wifi: WifiRemote? = null
    private var inControls = false
    private var currentTab = 0
    private var session = 0
    private var pendingShare: String? = null

    private var btStatus: TextView? = null
    private var btList: LinearLayout? = null
    private var wifiStatus: TextView? = null
    private var statusLabel: TextView? = null
    private var container: FrameLayout? = null
    private var controlsFrame: FrameLayout? = null
    private var nav: NavBar? = null
    private var pointer: AirPointer? = null
    private var gameView: GameView? = null
    private var inGame = false

    // таймер доклада
    private var planMin = 10
    private var timerRunning = false
    private var timerStart = 0L
    private var timerAccum = 0L
    private var timerText: TextView? = null
    private val tick = object : Runnable {
        override fun run() {
            updateTimer()
            main.postDelayed(this, 250)
        }
    }

    private val prefs by lazy { getSharedPreferences("pult", MODE_PRIVATE) }

    // ---------- жизненный цикл ----------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Colors.BG
        window.navigationBarColor = Colors.BG
        root = FrameLayout(this)
        root.setBackgroundColor(Colors.BG)
        setContentView(root)
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        handleIntent(intent)
        if (inControls) flushShare()
    }

    private fun handleIntent(i: Intent?) {
        if (i != null && i.action == Intent.ACTION_SEND && i.type?.startsWith("text/") == true) {
            val t = i.getStringExtra(Intent.EXTRA_TEXT)
            if (!t.isNullOrBlank()) pendingShare = t
        }
    }

    override fun onStart() {
        super.onStart()
        if (remote == null && bt == null && wifi == null) {
            val a = prefs.getString("addr", "") ?: ""
            if (prefs.getString("mode", "") == "wifi" && a.isNotEmpty()) {
                startWifi(a, prefs.getString("code", "") ?: "", 0)
            } else {
                showHome()
            }
        }
    }

    override fun onStop() {
        super.onStop()
        disconnect()
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (inGame) {
            exitGame()
        } else if (bt != null || wifi != null || remote != null || btStatus != null || wifiStatus != null) {
            disconnect()
            showHome()
        } else {
            super.onBackPressed()
        }
    }

    // Кнопки громкости листают слайды (на вкладке «Доклад», в том числе при блокировке)
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
        if (inGame) {
            gameView?.releaseAll()
            gameView = null
            inGame = false
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            hideSystemUi(false)
        }
        session++
        main.removeCallbacks(tick)
        stopPointer()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        try { bt?.close() } catch (_: Exception) {}
        try { wifi?.close() } catch (_: Exception) {}
        bt = null
        wifi = null
        remote = null
        inControls = false
        btStatus = null
        btList = null
        wifiStatus = null
        statusLabel = null
        container = null
        controlsFrame = null
        nav = null
        timerText = null
    }

    private fun show(view: View) {
        root.removeAllViews()
        root.addView(view, FrameLayout.LayoutParams(-1, -1))
        view.fadeIn()
        inControls = false
    }

    private fun column(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(20), dp(16), dp(12))
    }

    // ---------- главный экран ----------

    private fun showHome() {
        val col = column()
        col.addView(label("Пульт для ПК", 32f), lp(-1, -2, 0f, 4))
        col.addView(label("Управляй компьютером с телефона", 15f, Colors.MUTED), lp(-1, -2, 0f, 4))

        val saved = prefs.getString("addr", "") ?: ""
        val savedCode = prefs.getString("code", "") ?: ""
        val first = lp(-1, -2, 0f, 6).apply { topMargin = dp(32) }

        col.addView(
            button("Wi-Fi  ·  основной режим\nэкран, доклад, отправка на ПК", 18f, Colors.ACCENT) {
                if (saved.isNotEmpty() && savedCode.isNotEmpty()) startWifi(saved, savedCode, 0) else showWifi()
            }, first
        )
        if (saved.isNotEmpty()) {
            col.addView(button("Wi-Fi: другой компьютер или новый код", 14f) { showWifi() }, lp(-1, -2, 0f, 6))
        }
        col.addView(
            button("Bluetooth\nбез программ на компьютере", 18f) { showBt() },
            lp(-1, -2, 0f, 6).apply { topMargin = dp(18) }
        )
        show(col)
    }

    private fun showConnecting(text: String) {
        val col = column()
        col.gravity = Gravity.CENTER
        col.addView(label(text, 18f).apply { gravity = Gravity.CENTER }, lp(-1, -2, 0f, 8))
        col.addView(button("Отмена", 15f) { disconnect(); showHome() }, lp(-2, -2, 0f, 8))
        show(col)
    }

    // ---------- Wi-Fi ----------

    private fun showWifi(error: String = "") {
        val col = column()
        col.addView(label("Wi-Fi", 26f), lp(-1, -2, 0f, 4))
        col.addView(
            label("Запусти PultServer.exe на компьютере. Адрес и код видны в меню его значка возле часов.", 14f, Colors.MUTED),
            lp(-1, -2, 0f, 4)
        )
        val addr = field("Адрес, например 192.168.1.5:8765")
        addr.setText(prefs.getString("addr", ""))
        val code = field("Код (6 цифр)", numeric = true)
        code.setText(prefs.getString("code", ""))
        col.addView(addr, lp(-1, -2, 0f, 6))
        col.addView(code, lp(-1, -2, 0f, 6))
        col.addView(button("Подключиться", 18f, Colors.ACCENT) {
            startWifi(addr.text.toString().trim(), code.text.toString().trim(), 0)
        }, lp(-1, -2, 0f, 6))
        val status = label(error, 14f, Colors.WARN)
        col.addView(status, lp(-1, -2, 0f, 6))
        col.addView(button("Назад", 15f) { showHome() }, lp(-1, -2, 0f, 6))
        show(col)
        wifiStatus = status
    }

    /** attempt = 0 — обычное подключение, больше нуля — автоматическое переподключение. */
    private fun startWifi(addr: String, code: String, attempt: Int) {
        if (addr.isEmpty() || code.isEmpty()) {
            showWifi("Введи адрес и код")
            return
        }
        val parts = addr.split(":")
        val host = parts[0]
        val port = parts.getOrNull(1)?.toIntOrNull() ?: 8765
        if (attempt == 0) {
            disconnect()
            showConnecting("Подключаюсь к $addr…")
        }
        val my = session
        wifi?.close()
        val w = WifiRemote(host, port, code, object : WifiRemote.Callback {
            override fun onResult(ok: Boolean, message: String) {
                if (my != session) return
                if (ok) {
                    prefs.edit().putString("addr", addr).putString("code", code).putString("mode", "wifi").apply()
                    remote = wifi
                    showControls()
                } else if (attempt == 0) {
                    disconnect()
                    showWifi(message)
                } else if (attempt < 8) {
                    main.postDelayed({
                        if (my == session && inControls) startWifi(addr, code, attempt + 1)
                    }, 1500)
                } else {
                    toast("Связь с компьютером потеряна")
                    disconnect()
                    showHome()
                }
            }

            override fun onClosed() {
                if (my != session) return
                if (inControls) {
                    statusLabel?.text = "Переподключаюсь…"
                    startWifi(addr, code, 1)
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
        disconnect()
        val col = column()
        col.addView(label("Bluetooth", 26f), lp(-1, -2, 0f, 4))
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
        col.addView(button("Сделать телефон видимым (2 минуты)", 15f) {
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
        col.addView(button("Назад", 15f) { disconnect(); showHome() }, lp(-1, -2, 0f, 6))
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
                        prefs.edit().putString("mode", "bt").apply()
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
            list.addView(button(name, 16f) {
                btStatus?.text = "Подключаюсь к $name…"
                r.connectTo(d)
            }, lp(-1, -2, 0f, 4))
        }
    }

    // ---------- экран управления ----------

    private fun showControls() {
        val r = remote ?: return

        (r as? WifiRemote)?.let { w ->
            w.onInfo = { toast(it) }
            w.onClip = { text ->
                copyToPhone(text)
                toast("Буфер компьютера скопирован на телефон")
            }
        }

        val frame = FrameLayout(this)
        val col = column()

        // шапка: статус, блокировка, выход
        val dot = View(this).apply { background = rounded(Colors.OK, 5) }
        val name = (r as? WifiRemote)?.serverName?.takeIf { it.isNotEmpty() } ?: if (r.isWifi) "Компьютер" else "Bluetooth"
        val status = label(name, 16f)
        statusLabel = status
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(dot, lp(dp(10), dp(10), 0f, 6).also { it.width = dp(10); it.height = dp(10) })
        header.addView(status, lp(0, -2, 1f, 4))
        header.addView(button("🔒", 15f) { lockScreen() }, lp(-2, -2, 0f, 3))
        header.addView(button("Выйти", 13f) { disconnect(); showHome() }, lp(-2, -2, 0f, 3))
        col.addView(header, lp(-1, -2, 0f, 0))

        val cont = FrameLayout(this)
        container = cont
        col.addView(cont, lp(-1, 0, 1f, 2))

        val bar = NavBar(this, listOf("Доклад", "Экран", "Игра", "Медиа", "Клавиши", "Ещё")) { select(it) }
        nav = bar
        col.addView(bar, lp(-1, -2, 0f, 0).apply { topMargin = dp(6) })

        frame.addView(col, FrameLayout.LayoutParams(-1, -1))
        controlsFrame = frame
        show(frame)
        inControls = true
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        bar.choose(currentTab, false)
        select(currentTab)
        flushShare()
    }

    private fun select(i: Int) {
        val r = remote ?: return
        val c = container ?: return
        stopScreen()
        stopPointer()
        main.removeCallbacks(tick)
        timerText = null
        currentTab = i
        c.removeAllViews()
        val v = when (i) {
            0 -> presenterView(r)
            1 -> screenView(r)
            2 -> gameTabView(r)
            3 -> mediaView(r)
            4 -> keysView(r)
            else -> moreView(r)
        }
        c.addView(v, FrameLayout.LayoutParams(-1, -1))
        v.fadeIn()
    }

    private fun lockScreen() {
        val f = controlsFrame ?: return
        lateinit var lv: LockView
        lv = LockView(this) { f.removeView(lv) }
        f.addView(lv, FrameLayout.LayoutParams(-1, -1))
        lv.fadeIn()
    }

    private fun flushShare() {
        val t = pendingShare ?: return
        val r = remote ?: return
        pendingShare = null
        if (r.isWifi) {
            r.share(t)
            toast("Отправляю на компьютер…")
        } else {
            toast("Отправка на компьютер работает только по Wi-Fi")
        }
    }

    // ---------- картинка экрана ----------

    private fun startScreen(level: Int, handler: (Bitmap, Float, Float) -> Unit) {
        val w = remote as? WifiRemote ?: return
        w.onFrame = handler
        w.screen(true, level)
    }

    private fun stopScreen() {
        val w = remote as? WifiRemote ?: return
        w.onFrame = null
        w.screen(false, 1)
    }

    private fun stopPointer() {
        pointer?.stop()
        pointer = null
    }

    // ---------- вкладка «Доклад» ----------

    private fun elapsed(): Long =
        timerAccum + if (timerRunning) SystemClock.elapsedRealtime() - timerStart else 0L

    private fun updateTimer() {
        val l = timerText ?: return
        val remaining = planMin * 60_000L - elapsed()
        val s = abs(remaining) / 1000
        l.text = (if (remaining < 0) "+" else "") + "%02d:%02d".format(s / 60, s % 60)
        l.setTextColor(
            when {
                remaining > 60_000 -> Colors.TEXT
                remaining >= 0 -> Colors.WARN
                else -> Colors.DANGER
            }
        )
    }

    private fun presenterView(r: Remote): View {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        // таймер
        val tText = label("", 34f)
        tText.gravity = Gravity.CENTER_VERTICAL
        timerText = tText
        val startBtn = button(if (timerRunning) "⏸" else "▶", 18f) {}
        startBtn.setOnClickListener {
            if (timerRunning) {
                timerAccum += SystemClock.elapsedRealtime() - timerStart
                timerRunning = false
            } else {
                timerStart = SystemClock.elapsedRealtime()
                timerRunning = true
            }
            startBtn.text = if (timerRunning) "⏸" else "▶"
            updateTimer()
        }
        val resetBtn = button("⟲", 18f) {
            timerRunning = false
            timerAccum = 0L
            startBtn.text = "▶"
            updateTimer()
        }
        val planBtn = button("План\n$planMin мин", 12f) {}
        planBtn.setOnClickListener {
            planMin = when (planMin) {
                5 -> 10
                10 -> 15
                15 -> 20
                20 -> 30
                else -> 5
            }
            planBtn.text = "План\n$planMin мин"
            updateTimer()
        }
        val timerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        timerRow.addView(tText, lp(0, -2, 1.5f, 4))
        timerRow.addView(startBtn, lp(0, -2, 1f, 3))
        timerRow.addView(resetBtn, lp(0, -2, 1f, 3))
        timerRow.addView(planBtn, lp(0, -2, 1f, 3))
        col.addView(timerRow, lp(-1, -2, 0f, 0))
        updateTimer()
        main.postDelayed(tick, 250)

        // превью экрана компьютера (только Wi-Fi)
        if (r is WifiRemote) {
            val pv = FrameView(this)
            col.addView(pv, lp(-1, 0, 2f, 4))
            startScreen(1) { b, _, _ -> pv.frame = b }
        } else {
            col.addView(
                label("Превью экрана компьютера доступно по Wi-Fi", 13f, Colors.MUTED).apply { gravity = Gravity.CENTER },
                lp(-1, 0, 1f, 4)
            )
        }

        // листание
        val navRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        navRow.addView(button("◀", 30f) { r.key("left") }, lp(0, -1, 1f, 4))
        navRow.addView(button("Вперёд  ▶", 26f, Colors.ACCENT) { r.key("right") }, lp(0, -1, 2f, 4))
        col.addView(navRow, lp(-1, 0, 2f, 0))

        col.addView(
            row(
                button("С начала", 12f) { r.key("f5") },
                button("С текущего", 12f) { r.key("shift+f5") },
                button("Чёрный", 12f) { r.key("b") },
                button("Выход", 12f) { r.key("esc") }
            )
        )

        // указка
        val ptr = button("Указка: удерживай и води телефоном", 14f) {}
        val p = AirPointer(this, r)
        pointer = p
        ptr.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    if (p.start()) v.background = rippleBg(Colors.ACCENT) else toast("В телефоне нет гироскопа")
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    p.stop()
                    v.background = rippleBg(Colors.SURFACE)
                    true
                }
                else -> true
            }
        }
        col.addView(ptr, lp(-1, -2, 0f, 4))
        return col
    }

    // ---------- вкладка «Экран» ----------

    private fun screenView(r: Remote): View {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val pad = TouchpadView(this, r)
        col.addView(pad, lp(-1, 0, 1f, 4))

        val buttons = ArrayList<View>()
        buttons.add(button("Левая", 14f) { r.click(1) })
        buttons.add(button("Правая", 14f) { r.click(2) })
        if (r is WifiRemote) {
            val names = arrayOf("Лёгкое", "Обычное", "Чёткое")
            var level = prefs.getInt("q", 2).coerceIn(1, 3)
            startScreen(level) { b, cx, cy ->
                pad.frame = b
                pad.cursorX = cx
                pad.cursorY = cy
            }
            val modeBtn = button("Режим:\nтачпад", 13f) {}
            modeBtn.setOnClickListener {
                pad.direct = !pad.direct
                modeBtn.text = if (pad.direct) "Режим:\nкасание" else "Режим:\nтачпад"
            }
            val qBtn = button("Качество:\n${names[level - 1]}", 13f) {}
            qBtn.setOnClickListener {
                level = if (level >= 3) 1 else level + 1
                prefs.edit().putInt("q", level).apply()
                qBtn.text = "Качество:\n${names[level - 1]}"
                r.screen(true, level)
            }
            buttons.add(modeBtn)
            buttons.add(qBtn)
        }
        col.addView(row(*buttons.toTypedArray()))
        return col
    }

    // ---------- вкладка «Игра» ----------

    @Suppress("DEPRECATION")
    private fun hideSystemUi(hide: Boolean) {
        window.decorView.systemUiVisibility = if (hide) {
            View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        } else {
            0
        }
    }

    private fun gameTabView(r: Remote): View {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(label("Игровой режим", 22f), lp(-1, -2, 0f, 4))
        col.addView(
            label(
                "Телефон станет геймпадом: слева стик движения (WASD), справа камера, " +
                    "а вокруг кнопки огня, прицела, прыжка и другие. Экран повернётся на бок.\n\n" +
                    "Картинку игры смотри на мониторе компьютера. Режим работает и по Wi-Fi, и по Bluetooth.\n\n" +
                    "В онлайн-играх с защитой от читов такое управление могут заблокировать, " +
                    "лучше пробовать в одиночных играх.",
                14f, Colors.MUTED
            ),
            lp(-1, -2, 0f, 4)
        )
        val values = floatArrayOf(0.5f, 0.75f, 1f, 1.5f, 2f, 3f)
        var idx = values.indexOfFirst { it == prefs.getFloat("gs", 1f) }
        if (idx < 0) idx = 2
        val sensBtn = button("Чувствительность камеры: ×${values[idx]}", 15f) {}
        sensBtn.setOnClickListener {
            idx = (idx + 1) % values.size
            prefs.edit().putFloat("gs", values[idx]).apply()
            sensBtn.text = "Чувствительность камеры: ×${values[idx]}"
        }
        col.addView(sensBtn, lp(-1, -2, 0f, 6).apply { topMargin = dp(12) })
        col.addView(button("Запустить игровой режим", 18f, Colors.ACCENT) { showGame() }, lp(-1, -2, 0f, 6))
        return ScrollView(this).apply { addView(col) }
    }

    private fun showGame() {
        val r = remote ?: return
        stopScreen()
        stopPointer()
        main.removeCallbacks(tick)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        hideSystemUi(true)
        val gv = GameView(this, r) { main.post { exitGame() } }
        gv.sens = prefs.getFloat("gs", 1f)
        gameView = gv
        inGame = true
        root.removeAllViews()
        root.addView(gv, FrameLayout.LayoutParams(-1, -1))
    }

    private fun exitGame() {
        if (!inGame) return
        gameView?.releaseAll()
        gameView = null
        inGame = false
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        hideSystemUi(false)
        showControls()
    }

    // ---------- вкладка «Медиа» ----------

    private fun mediaView(r: Remote): View {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(
            row(
                button("⏮", 30f) { r.media("prev") },
                button("⏯", 30f, Colors.ACCENT) { r.media("playpause") },
                button("⏭", 30f) { r.media("next") },
                heightWeight = 1f
            )
        )
        col.addView(
            row(
                button("Громче", 20f) { r.media("volup") },
                button("Тише", 20f) { r.media("voldown") },
                button("Без звука", 20f) { r.media("mute") },
                heightWeight = 1f
            )
        )
        return col
    }

    // ---------- вкладка «Клавиши» ----------

    private fun keysView(r: Remote): View {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val input = field("Текст для компьютера")
        col.addView(input, lp(-1, -2, 0f, 4))
        if (!r.isWifi) {
            col.addView(
                label("По Bluetooth печатаются только английские буквы, цифры и знаки", 12f, Colors.MUTED),
                lp(-1, -2, 0f, 4)
            )
        }
        col.addView(
            row(
                button("Отправить", 17f, Colors.ACCENT) {
                    val s = input.text.toString()
                    if (s.isNotEmpty()) {
                        r.text(s)
                        input.setText("")
                    }
                },
                button("Enter") { r.key("enter") }
            )
        )
        col.addView(row(button("←") { r.key("left") }, button("↑") { r.key("up") }, button("↓") { r.key("down") }, button("→") { r.key("right") }))
        col.addView(row(button("Esc") { r.key("esc") }, button("Tab") { r.key("tab") }, button("⌫") { r.key("backspace") }, button("Del") { r.key("delete") }))
        col.addView(row(button("Alt+Tab", 14f) { r.key("alt+tab") }, button("Win+D", 14f) { r.key("win+d") }, button("Win+Tab", 14f) { r.key("win+tab") }, button("Alt+F4", 14f) { r.key("alt+f4") }))
        col.addView(row(button("Ctrl+C", 14f) { r.key("ctrl+c") }, button("Ctrl+V", 14f) { r.key("ctrl+v") }, button("Ctrl+Z", 14f) { r.key("ctrl+z") }, button("Ctrl+A", 14f) { r.key("ctrl+a") }))
        col.addView(row(button("Ctrl+S", 14f) { r.key("ctrl+s") }, button("F11", 14f) { r.key("f11") }, button("Ctrl+W", 14f) { r.key("ctrl+w") }, button("Win", 14f) { r.key("win") }))
        return ScrollView(this).apply { addView(col) }
    }

    // ---------- вкладка «Ещё» ----------

    private fun confirm(msg: String, onYes: () -> Unit) {
        AlertDialog.Builder(this)
            .setMessage(msg)
            .setPositiveButton("Да") { _, _ -> onYes() }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun clipboard(): ClipboardManager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

    private fun copyToPhone(text: String) {
        clipboard().setPrimaryClip(ClipData.newPlainText("pc", text))
    }

    private fun sendClipboard(r: Remote) {
        val cm = clipboard()
        val clip = cm.primaryClip
        val t = if (clip != null && clip.itemCount > 0) clip.getItemAt(0).coerceToText(this)?.toString() else null
        if (t.isNullOrEmpty()) {
            toast("Буфер телефона пуст")
        } else {
            r.clipSet(t)
            toast("Отправлено в буфер компьютера")
        }
    }

    private fun moreView(r: Remote): View {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        fun section(t: String) {
            col.addView(label(t, 13f, Colors.MUTED), lp(-1, -2, 0f, 4).apply { topMargin = dp(14) })
        }

        if (r is WifiRemote) {
            section("БУФЕР ОБМЕНА")
            col.addView(
                row(
                    button("Мой буфер → ПК", 14f) { sendClipboard(r) },
                    button("Буфер ПК → мой", 14f) { r.clipGet() }
                )
            )
            section("КОМПЬЮТЕР")
            col.addView(
                row(
                    button("Заблокировать", 15f) { r.system("lock") },
                    button("Сон", 15f) { confirm("Перевести компьютер в сон?") { r.system("sleep") } }
                )
            )
            col.addView(
                row(
                    button("Выключить", 15f, Colors.SURFACE, Colors.DANGER) {
                        confirm("Выключить компьютер через 5 секунд?") { r.system("shutdown") }
                    },
                    button("Перезагрузить", 15f, Colors.SURFACE, Colors.DANGER) {
                        confirm("Перезагрузить компьютер через 5 секунд?") { r.system("restart") }
                    }
                )
            )
            col.addView(row(button("Отменить выключение", 14f) { r.system("cancel") }))
            if (r.buttons.isNotEmpty()) {
                section("МОИ КНОПКИ")
                for (chunk in r.buttons.chunked(2)) {
                    val views = chunk.map { b -> button(b.second, 15f) { r.custom(b.first) } }
                    col.addView(row(*views.toTypedArray()))
                }
                col.addView(
                    label("Кнопки настраиваются в файле settings.json рядом с PultServer.exe", 12f, Colors.MUTED),
                    lp(-1, -2, 0f, 4)
                )
            }
        } else {
            section("КОМПЬЮТЕР")
            col.addView(row(button("Заблокировать (Win+L)", 15f) { r.key("win+l") }))
        }
        section("ПОДКЛЮЧЕНИЕ")
        col.addView(row(button("Отключиться", 15f) { disconnect(); showHome() }))
        return ScrollView(this).apply { addView(col) }
    }
}
