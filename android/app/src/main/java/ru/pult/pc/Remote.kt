package ru.pult.pc

/**
 * Общий «пульт»: экран управления не знает, по чему идёт связь
 * (Bluetooth или Wi-Fi). Названия клавиш одинаковые для обоих.
 *
 * Клавиши: "right", "left", "up", "down", "enter", "esc", "tab", "space",
 * "backspace", "delete", "home", "end", "pgup", "pgdn", "f1".."f12", буквы и цифры,
 * плюс модификаторы через "+": "ctrl+c", "alt+tab", "win+d", "shift+f5".
 * Медиа: "playpause", "next", "prev", "volup", "voldown", "mute".
 */
interface Remote {
    fun move(dx: Int, dy: Int)
    fun mouseButton(button: Int, down: Boolean)   // 1 = левая, 2 = правая
    fun scroll(steps: Int)                        // > 0 — вверх
    fun key(spec: String)
    fun text(s: String)
    fun media(name: String)
    fun close()

    fun click(button: Int = 1) {
        mouseButton(button, true)
        mouseButton(button, false)
    }
}
