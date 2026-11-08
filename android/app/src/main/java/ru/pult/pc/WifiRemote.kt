package ru.pult.pc

import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Executors

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

    private val main = Handler(Looper.getMainLooper())
    private val sender = Executors.newSingleThreadExecutor()

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
                val reader = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.UTF_8))
                writeLine(o, JSONObject().put("v", 1).put("t", "hello").put("code", code))
                val line = reader.readLine() ?: throw IOException("closed")
                val j = JSONObject(line)
                if (j.optBoolean("ok")) {
                    authed = true
                    ready = true
                    s.soTimeout = 0
                    val name = j.optString("name")
                    main.post { cb.onResult(true, name) }
                    while (reader.readLine() != null) { /* ответы сервера пока не нужны */ }
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

    override fun close() {
        closed = true
        ready = false
        closeQuiet()
        try { sender.shutdown() } catch (_: Exception) {}
    }

    private fun closeQuiet() {
        val s = socket ?: return
        socket = null
        Thread { try { s.close() } catch (_: Exception) {} }.start()
    }
}
