"""
Сервер «Пульт для ПК» (Windows).
Принимает команды с телефона по домашней сети и управляет мышью, клавиатурой и медиа.
Живёт в трее (значок возле часов). Настройки лежат рядом с программой: settings.json
"""
import asyncio
import hmac
import ipaddress
import json
import secrets
import socket
import sys
import threading
import time
from pathlib import Path

import pystray
from PIL import Image, ImageDraw
from pynput.keyboard import Controller as Keyboard, Key
from pynput.mouse import Button, Controller as Mouse

VERSION = 1
APP_NAME = "PultDlyaPK"
BASE = Path(sys.executable).parent if getattr(sys, "frozen", False) else Path(__file__).resolve().parent
SETTINGS_FILE = BASE / "settings.json"
LOG_FILE = BASE / "server.log"

keyboard = Keyboard()
mouse = Mouse()


# ---------- настройки ----------

def new_code() -> str:
    return f"{secrets.randbelow(1_000_000):06d}"


def load_settings() -> dict:
    s = {}
    if SETTINGS_FILE.exists():
        try:
            s = json.loads(SETTINGS_FILE.read_text(encoding="utf-8"))
        except Exception:
            s = {}
    changed = False
    if "port" not in s:
        s["port"] = 8765
        changed = True
    if "code" not in s:
        s["code"] = new_code()
        changed = True
    if changed:
        SETTINGS_FILE.write_text(json.dumps(s, indent=2), encoding="utf-8")
    return s


settings = load_settings()


def save_settings() -> None:
    SETTINGS_FILE.write_text(json.dumps(settings, indent=2), encoding="utf-8")


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


def clamp(v: int, lo: int, hi: int) -> int:
    return max(lo, min(hi, v))


def press_combo(spec: str) -> None:
    parts = spec.lower().split("+")
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


def dispatch(m: dict) -> None:
    t = m.get("t")
    if t == "move":
        mouse.move(clamp(int(m["dx"]), -400, 400), clamp(int(m["dy"]), -400, 400))
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
    elif t == "ping":
        pass
    else:
        raise ValueError("unknown type")


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
        if not hmac.compare_digest(str(msg.get("code", "")), str(settings["code"])):
            fail_times.append(time.time())
            await asyncio.sleep(1)
            await send(writer, {"ok": False, "err": "BAD_CODE"})
            return

        await send(writer, {"ok": True, "v": VERSION, "name": socket.gethostname()})
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
            if count > 300:      # ограничение частоты: лишнее отбрасываем
                continue
            try:
                dispatch(json.loads(line))
            except Exception:
                await send(writer, {"ok": False, "err": "BAD_MESSAGE"})
    except Exception as e:
        log(f"соединение закрыто: {e!r}")
    finally:
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


def autostart_enabled() -> bool:
    try:
        import winreg
        with winreg.OpenKey(winreg.HKEY_CURRENT_USER, r"Software\Microsoft\Windows\CurrentVersion\Run") as k:
            winreg.QueryValueEx(k, APP_NAME)
            return True
    except Exception:
        return False


def toggle_autostart(icon, item) -> None:
    try:
        import winreg
        with winreg.OpenKey(winreg.HKEY_CURRENT_USER, r"Software\Microsoft\Windows\CurrentVersion\Run",
                            0, winreg.KEY_SET_VALUE) as k:
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
