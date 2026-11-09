package ru.pult.pc

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.Base64
import org.json.JSONObject
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Связь по Wi-Fi: короткие JSON-сообщения, по одному в строке (см. README).
 * Соединение живёт, пока открыт экран приложения.
 */
class WifiRemote(
    private val host: String,
    private val port: Int,
    private val code: String,
    private val cb: Callback
) : Remote {

    interface Callback {
        fun onResult(ok: Boolean, message: String)
        fun onClosed()
    }

    // то, что присылает компьютер (всё вызывается в главном потоке)
    var onFrame: ((Bitmap, Float, Float) -> Unit)? = null
    var onClip: ((String) -> Unit)? = null
    var onInfo: ((String) -> Unit)? = null
    var serverName: String = ""
        private set
    var buttons: List<Pair<Int, String>> = emptyList()
        private set

    override val isWifi: Boolean get() = true

    private val main = Handler(Looper.getMainLooper())
    private val sender = Executors.newSingleThreadExecutor()
    private val framePending = AtomicBoolean(false)

    @Volatile private var socket: Socket? = null
    @Volatile private var out: OutputStream? = null
    @Volatile private var ready = false
    @Volatile private var closed = false

    fun connect() {
        Thread {
            var authed = false
            try {
                val s = Socket()
                s.connect(InetSocketAddress(host, port), 4000)
                s.tcpNoDelay = true
                s.soTimeout = 5000
                socket = s
                val o = s.getOutputStream()
                out = o
                val reader = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.UTF_8), 1 shl 16)
                writeLine(o, JSONObject().put("v", 1).put("t", "hello").put("code", code))
                val line = reader.readLine() ?: throw IOException("closed")
                val j = JSONObject(line)
                if (j.optBoolean("ok")) {
                    authed = true
                    serverName = j.optString("name")
                    val list = ArrayList<Pair<Int, String>>()
                    val arr = j.optJSONArray("buttons")
                    if (arr != null) {
                        for (i in 0 until arr.length()) {
                            val b = arr.optJSONObject(i) ?: continue
                            list.add(Pair(b.optInt("id"), b.optString("name")))
                        }
                    }
                    buttons = list
                    ready = true
                    s.soTimeout = 0
                    main.post { cb.onResult(true, serverName) }
                    while (true) {
                        val l = reader.readLine() ?: break
                        handleServer(l)
                    }
                } else {
                    val text = errorText(j.optString("err"))
                    main.post { cb.onResult(false, text) }
                }
            } catch (e: Exception) {
                if (!authed && !closed) {
                    main.post {
                        cb.onResult(false, "Не получилось подключиться. Проверь адрес, и что телефон и компьютер в одной сети.")
                    }
                }
            } finally {
                ready = false
                closeQuiet()
                if (authed && !closed) main.post { cb.onClosed() }
            }
        }.start()
    }

    private fun handleServer(line: String) {
        try {
            if (line.startsWith("{\"t\": \"frame\"")) {
                // картинка: если предыдущая ещё рисуется, эту пропускаем
                if (framePending.get()) return
                val j = JSONObject(line)
                val bytes = Base64.decode(j.getString("d"), Base64.DEFAULT)
                val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return
                val cx = j.optDouble("cx", -1.0).toFloat()
                val cy = j.optDouble("cy", -1.0).toFloat()
                framePending.set(true)
                main.post {
                    framePending.set(false)
                    onFrame?.invoke(bmp, cx, cy)
                }
                return
            }
            val j = JSONObject(line)
            when (j.optString("t")) {
                "clip" -> {
                    val s = j.optString("s")
                    main.post { onClip?.invoke(s) }
                }
                "shared" -> {
                    val s = j.optString("s")
                    main.post { onInfo?.invoke(s) }
                }
            }
        } catch (e: Exception) {
            // непонятное сообщение просто пропускаем
        }
    }

    private fun errorText(code: String): String = when (code) {
        "BAD_CODE" -> "Неверный код. Он показан в значке программы на компьютере."
        "LOCKED" -> "Слишком много попыток. Подожди минуту."
        "NOT_LOCAL" -> "Компьютер принимает подключения только из домашней сети."
        "BAD_VERSION" -> "Программа на компьютере другой версии. Обнови обе."
        else -> "Компьютер отказал в подключении ($code)."
    }

    private fun writeLine(o: OutputStream, j: JSONObject) {
        o.write((j.toString() + "\n").toByteArray(Charsets.UTF_8))
        o.flush()
    }

    private fun send(j: JSONObject) {
        if (!ready || closed) return
        try {
            sender.execute {
                try {
                    val o = out
                    if (o != null) writeLine(o, j)
                } catch (e: Exception) {
                    closeQuiet()
                }
            }
        } catch (e: Exception) {
            // уже закрыто
        }
    }

    private fun msg(t: String) = JSONObject().put("v", 1).put("t", t)

    override fun move(dx: Int, dy: Int) = send(msg("move").put("dx", dx).put("dy", dy))
    override fun mouseButton(button: Int, down: Boolean) =
        send(msg("btn").put("b", button).put("down", down))
    override fun scroll(steps: Int) = send(msg("scroll").put("n", steps))
    override fun key(spec: String) = send(msg("key").put("k", spec))
    override fun text(s: String) = send(msg("text").put("s", s))
    override fun media(name: String) = send(msg("media").put("k", name))

    override fun moveTo(nx: Float, ny: Float) =
        send(msg("abs").put("x", nx.toDouble()).put("y", ny.toDouble()))
    override fun system(action: String) = send(msg("system").put("a", action))
    override fun custom(id: Int) = send(msg("custom").put("id", id))
    override fun share(s: String) = send(msg("share").put("s", s))
    override fun clipSet(s: String) = send(msg("clip_set").put("s", s))
    override fun clipGet() = send(msg("clip_get"))
    override fun screen(on: Boolean, quality: Int) =
        send(msg("screen").put("on", on).put("q", quality))

    override fun keyHold(name: String, down: Boolean) =
        send(msg("keyd").put("k", name).put("down", down))
    override fun look(dx: Int, dy: Int) = send(msg("gmove").put("dx", dx).put("dy", dy))
    override fun gameButton(b: Int, down: Boolean) =
        send(msg("gbtn").put("b", b).put("down", down))

    override fun close() {
        closed = true
        ready = false
        onFrame = null
        onClip = null
        onInfo = null
        closeQuiet()
        try { sender.shutdown() } catch (_: Exception) {}
    }

    private fun closeQuiet() {
        val s = socket ?: return
        socket = null
        Thread { try { s.close() } catch (_: Exception) {} }.start()
    }
}
