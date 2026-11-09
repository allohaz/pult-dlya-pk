"""
Сервер «Пульт для ПК» (Windows).
Принимает команды с телефона по домашней сети и управляет мышью, клавиатурой, медиа,
показывает экран, отправляет ссылки и буфер обмена. Живёт в трее (значок возле часов).
Настройки лежат рядом с программой: settings.json
"""
import asyncio
import base64
import ctypes
import hmac
import io
import ipaddress
import json
import os
import re
import secrets
import socket
import subprocess
import sys
import threading
import time
import webbrowser
from pathlib import Path

import pyperclip
import pystray
from PIL import Image, ImageDraw, ImageGrab
from pynput.keyboard import Controller as Keyboard, Key
from pynput.mouse import Button, Controller as Mouse

# Чтобы координаты мыши и снимок экрана совпадали на мониторах с масштабом 125–150%
try:
    ctypes.windll.shcore.SetProcessDpiAwareness(2)
except Exception:
    pass

VERSION = 1
APP_NAME = "PultDlyaPK"
NO_WINDOW = 0x08000000
BASE = Path(sys.executable).parent if getattr(sys, "frozen", False) else Path(__file__).resolve().parent
SETTINGS_FILE = BASE / "settings.json"
LOG_FILE = BASE / "server.log"

keyboard = Keyboard()
mouse = Mouse()


# ---------- настройки ----------

def new_code() -> str:
    return f"{secrets.randbelow(1_000_000):06d}"


DEFAULT_BUTTONS = [
    {"name": "Блокнот", "run": "notepad"},
    {"name": "Калькулятор", "run": "calc"},
    {"name": "Проводник", "run": "explorer"},
    {"name": "Сайт", "open": "https://www.google.com"},
]


def load_settings(prev=None) -> dict:
    s = {}
    broken = False
    if SETTINGS_FILE.exists():
        try:
            s = json.loads(SETTINGS_FILE.read_text(encoding="utf-8"))
        except Exception:
            broken = True      # файл сломан (например, правишь его в блокноте): ничего не затираем
            if prev is not None:
                return prev
    changed = False
    if "port" not in s:
        s["port"] = 8765
        changed = True
    if "code" not in s:
        s["code"] = new_code()
        changed = True
    if "buttons" not in s:
        s["buttons"] = DEFAULT_BUTTONS
        changed = True
    if changed and not broken:
        SETTINGS_FILE.write_text(json.dumps(s, indent=2, ensure_ascii=False), encoding="utf-8")
    return s


settings = load_settings()


def save_settings() -> None:
    SETTINGS_FILE.write_text(json.dumps(settings, indent=2, ensure_ascii=False), encoding="utf-8")


def reload_settings() -> None:
    """Подхватывает правки settings.json (кроме порта) без перезапуска."""
    fresh = load_settings(prev=dict(settings))
    port = settings.get("port", 8765)
    settings.clear()
    settings.update(fresh)
    settings["port"] = port


def log(text: str) -> None:
    try:
        with LOG_FILE.open("a", encoding="utf-8") as f:
            f.write(time.strftime("%Y-%m-%d %H:%M:%S ") + text + "\n")
    except OSError:
        pass


# ---------- разрешённые команды (только из этого списка) ----------

NAMED = {
    "right": Key.right, "left": Key.left, "up": Key.up, "down": Key.down,
    "enter": Key.enter, "esc": Key.esc, "tab": Key.tab, "space": Key.space,
    "backspace": Key.backspace, "delete": Key.delete,
    "home": Key.home, "end": Key.end, "pgup": Key.page_up, "pgdn": Key.page_down,
}
for _i in range(1, 13):
    NAMED[f"f{_i}"] = getattr(Key, f"f{_i}")

MODS = {"ctrl": Key.ctrl, "shift": Key.shift, "alt": Key.alt, "win": Key.cmd}

MEDIA = {
    "playpause": Key.media_play_pause, "next": Key.media_next, "prev": Key.media_previous,
    "volup": Key.media_volume_up, "voldown": Key.media_volume_down, "mute": Key.media_volume_mute,
}


def _popen(args):
    subprocess.Popen(args, creationflags=NO_WINDOW)


SYSTEM = {
    "lock": lambda: ctypes.windll.user32.LockWorkStation(),
    "sleep": lambda: _popen("rundll32.exe powrprof.dll,SetSuspendState 0,1,0"),
    "shutdown": lambda: _popen(["shutdown", "/s", "/t", "5"]),
    "restart": lambda: _popen(["shutdown", "/r", "/t", "5"]),
    "cancel": lambda: _popen(["shutdown", "/a"]),
}

URL_RE = re.compile(r"https?://\S+")


def clamp(v: int, lo: int, hi: int) -> int:
    return max(lo, min(hi, v))


def clamp01(v) -> float:
    return max(0.0, min(1.0, float(v)))


def press_combo(spec: str) -> None:
    parts = spec.lower().split("+")
    # одна клавиша-модификатор (например, просто Win) — нажать и отпустить
    if len(parts) == 1 and parts[0] in MODS:
        keyboard.press(MODS[parts[0]])
        keyboard.release(MODS[parts[0]])
        return
    mods = []
    for p in parts[:-1]:
        if p not in MODS:
            raise ValueError("bad modifier")
        mods.append(MODS[p])
    last = parts[-1]
    if last in NAMED:
        key = NAMED[last]
    elif len(last) == 1 and last.isalnum():
        key = last
    else:
        raise ValueError("bad key")
    for m in mods:
        keyboard.press(m)
    try:
        keyboard.press(key)
        keyboard.release(key)
    finally:
        for m in reversed(mods):
            keyboard.release(m)


def screen_size():
    try:
        u = ctypes.windll.user32
        return u.GetSystemMetrics(0), u.GetSystemMetrics(1)
    except Exception:
        return 1920, 1080


def run_custom(idx: int) -> None:
    """Кнопки только из твоего settings.json: {"name": "...", "open": "путь или сайт"} или {"run": "команда"}."""
    btn = settings.get("buttons", [])[idx]
    if "open" in btn:
        os.startfile(btn["open"])
    elif "run" in btn:
        subprocess.Popen(btn["run"], shell=True, creationflags=NO_WINDOW)


def share(text: str) -> dict:
    m = URL_RE.search(text)
    if m:
        webbrowser.open(m.group(0))
        return {"t": "shared", "s": "Ссылка открыта на компьютере"}
    pyperclip.copy(text[:100000])
    return {"t": "shared", "s": "Текст скопирован в буфер компьютера"}


# ---------- игровой ввод: SendInput со сканкодами (как настоящая клавиатура и мышь) ----------
# Многие игры игнорируют обычные «виртуальные» нажатия, но видят сканкоды и относительную мышь.

ULONG_PTR = ctypes.c_size_t


class MOUSEINPUT(ctypes.Structure):
    _fields_ = [("dx", ctypes.c_int32), ("dy", ctypes.c_int32), ("mouseData", ctypes.c_uint32),
                ("dwFlags", ctypes.c_uint32), ("time", ctypes.c_uint32), ("dwExtraInfo", ULONG_PTR)]


class KEYBDINPUT(ctypes.Structure):
    _fields_ = [("wVk", ctypes.c_uint16), ("wScan", ctypes.c_uint16), ("dwFlags", ctypes.c_uint32),
                ("time", ctypes.c_uint32), ("dwExtraInfo", ULONG_PTR)]


class HARDWAREINPUT(ctypes.Structure):
    _fields_ = [("uMsg", ctypes.c_uint32), ("wParamL", ctypes.c_uint16), ("wParamH", ctypes.c_uint16)]


class _INPUT_UNION(ctypes.Union):
    _fields_ = [("mi", MOUSEINPUT), ("ki", KEYBDINPUT), ("hi", HARDWAREINPUT)]


class INPUT(ctypes.Structure):
    _anonymous_ = ("u",)
    _fields_ = [("type", ctypes.c_uint32), ("u", _INPUT_UNION)]


KEYEVENTF_EXTENDEDKEY = 0x0001
KEYEVENTF_KEYUP = 0x0002
KEYEVENTF_SCANCODE = 0x0008
MOUSEEVENTF_MOVE = 0x0001
MOUSE_BUTTON_FLAGS = {1: (0x0002, 0x0004), 2: (0x0008, 0x0010)}   # (down, up)

GAME_VK = {"space": 0x20, "shift": 0x10, "ctrl": 0x11, "alt": 0x12, "tab": 0x09, "esc": 0x1B,
           "enter": 0x0D, "backspace": 0x08,
           "left": 0x25, "up": 0x26, "right": 0x27, "down": 0x28}
for _c in "abcdefghijklmnopqrstuvwxyz":
    GAME_VK[_c] = ord(_c.upper())
for _c in "0123456789":
    GAME_VK[_c] = ord(_c)
for _i in range(1, 13):
    GAME_VK[f"f{_i}"] = 0x6F + _i
GAME_EXTENDED = {"left", "up", "right", "down"}


def _send_input(inp: INPUT) -> None:
    ctypes.windll.user32.SendInput(1, ctypes.byref(inp), ctypes.sizeof(INPUT))


def game_key(name: str, down: bool) -> None:
    vk = GAME_VK[name]
    scan = ctypes.windll.user32.MapVirtualKeyW(vk, 0)     # виртуальный код -> сканкод
    flags = KEYEVENTF_SCANCODE | (0 if down else KEYEVENTF_KEYUP)
    if name in GAME_EXTENDED:
        flags |= KEYEVENTF_EXTENDEDKEY
    inp = INPUT(type=1)
    inp.ki = KEYBDINPUT(0, scan, flags, 0, 0)
    _send_input(inp)


def game_mouse_move(dx: int, dy: int) -> None:
    inp = INPUT(type=0)
    inp.mi = MOUSEINPUT(dx, dy, 0, MOUSEEVENTF_MOVE, 0, 0)
    _send_input(inp)


def game_mouse_button(b: int, down: bool) -> None:
    flags = MOUSE_BUTTON_FLAGS[b][0 if down else 1]
    inp = INPUT(type=0)
    inp.mi = MOUSEINPUT(0, 0, 0, flags, 0, 0)
    _send_input(inp)


def release_held(held: set) -> None:
    """Отпускает всё зажатое (при обрыве связи, чтобы клавиши не «залипали»)."""
    for h in list(held):
        try:
            kind, val = h.split(":", 1)
            if kind == "k":
                game_key(val, False)
            else:
                game_mouse_button(int(val), False)
        except Exception as e:
            log(f"отпускание {h}: {e!r}")
    held.clear()


def dispatch(m: dict, held: set):
    """Выполняет команду. Может вернуть ответ телефону (словарь) или None."""
    t = m.get("t")
    if t == "move":
        mouse.move(clamp(int(m["dx"]), -400, 400), clamp(int(m["dy"]), -400, 400))
    elif t == "abs":
        sw, sh = screen_size()
        mouse.position = (int(clamp01(m["x"]) * (sw - 1)), int(clamp01(m["y"]) * (sh - 1)))
    elif t == "btn":
        b = Button.right if int(m.get("b", 1)) == 2 else Button.left
        if m.get("down"):
            mouse.press(b)
        else:
            mouse.release(b)
    elif t == "scroll":
        mouse.scroll(0, clamp(int(m["n"]), -20, 20))
    elif t == "key":
        press_combo(str(m["k"]))
    elif t == "text":
        keyboard.type(str(m["s"])[:500])
    elif t == "media":
        k = MEDIA[str(m["k"])]
        keyboard.press(k)
        keyboard.release(k)
    elif t == "system":
        SYSTEM[str(m["a"])]()
    elif t == "custom":
        run_custom(int(m["id"]))
    elif t == "share":
        return share(str(m["s"]))
    elif t == "clip_set":
        pyperclip.copy(str(m["s"])[:100000])
    elif t == "clip_get":
        return {"t": "clip", "s": pyperclip.paste()[:100000]}
    elif t == "keyd":
        name = str(m["k"]).lower()
        if name not in GAME_VK:
            raise ValueError("bad key")
        down = bool(m.get("down"))
        game_key(name, down)
        if down:
            held.add(f"k:{name}")
        else:
            held.discard(f"k:{name}")
    elif t == "gmove":
        game_mouse_move(clamp(int(m["dx"]), -600, 600), clamp(int(m["dy"]), -600, 600))
    elif t == "gbtn":
        b = int(m["b"])
        if b not in MOUSE_BUTTON_FLAGS:
            raise ValueError("bad button")
        down = bool(m.get("down"))
        game_mouse_button(b, down)
        if down:
            held.add(f"b:{b}")
        else:
            held.discard(f"b:{b}")
    elif t == "ping":
        pass
    else:
        raise ValueError("unknown type")
    return None


# ---------- картинка экрана ----------

# уровень качества: (ширина кадра, качество JPEG, кадров в секунду)
QUALITY = {1: (800, 45, 8), 2: (1100, 60, 12), 3: (1500, 75, 15)}


def grab_frame(width: int, quality: int) -> dict:
    img = ImageGrab.grab()
    sw, sh = img.size
    if width and sw > width:
        img = img.resize((width, max(1, int(sh * width / sw))), Image.Resampling.BILINEAR)
    buf = io.BytesIO()
    img.convert("RGB").save(buf, "JPEG", quality=quality)
    x, y = mouse.position
    # ключ "t" должен идти первым: приложение по нему быстро узнаёт кадр
    return {
        "t": "frame",
        "d": base64.b64encode(buf.getvalue()).decode("ascii"),
        "cx": x / sw,
        "cy": y / sh,
    }


async def stream_screen(writer: asyncio.StreamWriter, st: dict) -> None:
    loop = asyncio.get_running_loop()
    try:
        while st["on"]:
            t0 = time.monotonic()
            width, quality, fps = QUALITY.get(st["level"], QUALITY[2])
            # если телефон не успевает принимать, кадр пропускаем
            if writer.transport.get_write_buffer_size() < 300_000:
                try:
                    frame = await loop.run_in_executor(None, grab_frame, width, quality)
                except Exception as e:
                    log(f"экран: {e!r}")
                    await asyncio.sleep(1)
                    continue
                if st["on"]:
                    writer.write((json.dumps(frame) + "\n").encode("ascii"))
            await asyncio.sleep(max(0.01, 1.0 / fps - (time.monotonic() - t0)))
    except asyncio.CancelledError:
        pass
    except Exception as e:
        log(f"экран остановлен: {e!r}")


# ---------- сеть ----------

fail_times: list = []


def locked() -> bool:
    now = time.time()
    fail_times[:] = [x for x in fail_times if now - x < 60]
    return len(fail_times) >= 5


async def send(writer: asyncio.StreamWriter, obj: dict) -> None:
    writer.write((json.dumps(obj, ensure_ascii=False) + "\n").encode("utf-8"))
    await writer.drain()


async def handle(reader: asyncio.StreamReader, writer: asyncio.StreamWriter) -> None:
    st = {"on": False, "level": 2}
    screen_task = None
    held: set = set()
    try:
        peer = writer.get_extra_info("peername")[0]
        ip = ipaddress.ip_address(peer)
        if not (ip.is_private or ip.is_loopback or ip.is_link_local):
            await send(writer, {"ok": False, "err": "NOT_LOCAL"})
            return

        line = await asyncio.wait_for(reader.readline(), 5)
        msg = json.loads(line)
        if msg.get("t") != "hello":
            await send(writer, {"ok": False, "err": "BAD_MESSAGE"})
            return
        if msg.get("v") != VERSION:
            await send(writer, {"ok": False, "err": "BAD_VERSION"})
            return
        if locked():
            await send(writer, {"ok": False, "err": "LOCKED"})
            return
        reload_settings()
        if not hmac.compare_digest(str(msg.get("code", "")), str(settings["code"])):
            fail_times.append(time.time())
            await asyncio.sleep(1)
            await send(writer, {"ok": False, "err": "BAD_CODE"})
            return

        buttons = [{"id": i, "name": str(b.get("name", "?"))} for i, b in enumerate(settings.get("buttons", []))]
        await send(writer, {"ok": True, "v": VERSION, "name": socket.gethostname(), "buttons": buttons})
        log(f"подключился {peer}")

        window_start = time.monotonic()
        count = 0
        while True:
            line = await reader.readline()
            if not line:
                break
            now = time.monotonic()
            if now - window_start >= 1:
                window_start = now
                count = 0
            count += 1
            if count > 500:      # ограничение частоты: лишнее отбрасываем
                continue
            try:
                m = json.loads(line)
                if m.get("t") == "screen":
                    st["level"] = int(m.get("q", 2))
                    if m.get("on"):
                        st["on"] = True
                        if screen_task is None or screen_task.done():
                            screen_task = asyncio.create_task(stream_screen(writer, st))
                    else:
                        st["on"] = False
                else:
                    reply = dispatch(m, held)
                    if reply:
                        await send(writer, reply)
            except Exception as e:
                log(f"команда не выполнена: {e!r}")
                await send(writer, {"ok": False, "err": "BAD_MESSAGE"})
    except Exception as e:
        log(f"соединение закрыто: {e!r}")
    finally:
        release_held(held)
        st["on"] = False
        if screen_task is not None:
            screen_task.cancel()
        try:
            writer.close()
        except Exception:
            pass


def run_server() -> None:
    loop = asyncio.new_event_loop()
    asyncio.set_event_loop(loop)
    try:
        loop.run_until_complete(asyncio.start_server(handle, "0.0.0.0", int(settings["port"])))
    except OSError as e:
        log(f"не удалось открыть порт {settings['port']}: {e!r}")
        return
    loop.run_forever()


def local_ips() -> list:
    ips = set()
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(("8.8.8.8", 80))      # пакеты не отправляются, так узнаём свой адрес
        ips.add(s.getsockname()[0])
        s.close()
    except OSError:
        pass
    try:
        for ip in socket.gethostbyname_ex(socket.gethostname())[2]:
            ips.add(ip)
    except OSError:
        pass
    return sorted(i for i in ips if ipaddress.ip_address(i).is_private and not i.startswith("127."))


# ---------- значок в трее ----------

def make_icon() -> Image.Image:
    img = Image.new("RGBA", (64, 64), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    d.rounded_rectangle((4, 4, 60, 60), radius=14, fill=(124, 92, 255, 255))
    d.polygon([(26, 20), (26, 44), (46, 32)], fill=(255, 255, 255, 255))
    return img


RUN_KEY = r"Software\Microsoft\Windows\CurrentVersion\Run"


def autostart_enabled() -> bool:
    try:
        import winreg
        with winreg.OpenKey(winreg.HKEY_CURRENT_USER, RUN_KEY) as k:
            winreg.QueryValueEx(k, APP_NAME)
            return True
    except Exception:
        return False


def toggle_autostart(icon, item) -> None:
    try:
        import winreg
        with winreg.OpenKey(winreg.HKEY_CURRENT_USER, RUN_KEY, 0, winreg.KEY_SET_VALUE) as k:
            if autostart_enabled():
                winreg.DeleteValue(k, APP_NAME)
            else:
                winreg.SetValueEx(k, APP_NAME, 0, winreg.REG_SZ, f'"{sys.executable}"')
    except Exception as e:
        log(f"автозапуск: {e!r}")


def renew_code(icon, item) -> None:
    settings["code"] = new_code()
    save_settings()
    icon.update_menu()
    icon.notify(f"Новый код: {settings['code']}", "Пульт для ПК")


def open_settings(icon, item) -> None:
    try:
        subprocess.Popen(["notepad.exe", str(SETTINGS_FILE)])
    except Exception as e:
        log(f"настройки: {e!r}")


def quit_app(icon, item) -> None:
    icon.stop()


def noop(icon, item) -> None:
    pass


def menu_items():
    ips = local_ips() or ["нет сети"]
    for ip in ips:
        yield pystray.MenuItem(f"Адрес: {ip}:{settings['port']}", noop, enabled=False)
    yield pystray.MenuItem(f"Код: {settings['code']}", noop, enabled=False)
    yield pystray.Menu.SEPARATOR
    yield pystray.MenuItem("Новый код", renew_code)
    yield pystray.MenuItem("Настройки и мои кнопки…", open_settings)
    if getattr(sys, "frozen", False):
        yield pystray.MenuItem("Запускать вместе с Windows", toggle_autostart,
                               checked=lambda item: autostart_enabled())
    yield pystray.MenuItem("Выход", quit_app)


def main() -> None:
    threading.Thread(target=run_server, daemon=True).start()
    icon = pystray.Icon(APP_NAME, make_icon(), "Пульт для ПК", pystray.Menu(menu_items))

    def setup(i):
        i.visible = True
        ips = local_ips()
        where = f"{ips[0]}:{settings['port']}" if ips else "нет сети"
        i.notify(f"Адрес {where}, код {settings['code']}", "Пульт для ПК запущен")

    icon.run(setup)


if __name__ == "__main__":
    main()
