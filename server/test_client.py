"""Простой тестовый клиент: python test_client.py 127.0.0.1 8765 КОД
Подключается, двигает курсор и нажимает Win+D (показать рабочий стол)."""
import json
import socket
import sys
import time

host, port, code = sys.argv[1], int(sys.argv[2]), sys.argv[3]
s = socket.create_connection((host, port), timeout=5)
f = s.makefile("rw", encoding="utf-8", newline="\n")


def send(obj):
    f.write(json.dumps(obj) + "\n")
    f.flush()


send({"v": 1, "t": "hello", "code": code})
print("ответ:", f.readline().strip())
for _ in range(20):
    send({"v": 1, "t": "move", "dx": 10, "dy": 0})
    time.sleep(0.02)
send({"v": 1, "t": "key", "k": "win+d"})
time.sleep(0.5)
send({"v": 1, "t": "key", "k": "win+d"})
s.close()
