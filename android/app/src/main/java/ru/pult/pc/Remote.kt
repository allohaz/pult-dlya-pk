package ru.pult.pc

/**
 * Общий «пульт»: экран управления не знает, по чему идёт связь
 * (Bluetooth или Wi-Fi). Названия клавиш одинаковые для обоих.
 *
 * Клавиши: "right", "left", "up", "down", "enter", "esc", "tab", "space",
 * "backspace", "delete", "home", "end", "pgup", "pgdn", "f1".."f12", буквы и цифры,
 * плюс модификаторы через "+": "ctrl+c", "alt+tab", "win+d", "shift+f5".
 * Медиа: "playpause", "next", "prev", "volup", "voldown", "mute".
 *
 * Всё, что ниже «close», умеет только Wi-Fi (по Bluetooth ничего не делает).
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

    val isWifi: Boolean get() = false
    fun moveTo(nx: Float, ny: Float) {}            // точка на экране, 0..1
    fun system(action: String) {}                  // lock, sleep, shutdown, restart, cancel
    fun custom(id: Int) {}                         // «Мои кнопки» из settings.json
    fun share(s: String) {}                        // ссылка откроется на ПК, текст попадёт в буфер
    fun clipSet(s: String) {}
    fun clipGet() {}
    fun screen(on: Boolean, quality: Int) {}       // картинка экрана ПК, качество 1..3

    // --- игровой режим (работает и по Wi-Fi, и по Bluetooth) ---
    fun keyHold(name: String, down: Boolean) {}    // зажать / отпустить клавишу
    fun look(dx: Int, dy: Int) { move(dx, dy) }    // поворот камеры (относительное движение мыши)
    fun gameButton(b: Int, down: Boolean) { mouseButton(b, down) }   // 1 левая, 2 правая
}
