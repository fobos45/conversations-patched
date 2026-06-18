# Патч: звонки через встроенный Yggdrasil между разными сетями

## Причина бага (подтверждена логом)

Встроенный Yggdrasil-клиент (`yggmobile.go`) не имеет TUN-интерфейса и умел
ходить наружу только через TCP (`DialTCP`), который используется локальным
SOCKS5-прокси (`YggdrasilManager`, порт 1080) — отсюда работающие текст и
XMPP-соединение. WebRTC же открывает свои ICE/STUN/TURN-сокеты по UDP прямо
через системный сетевой стек Android, минуя этот прокси. Поскольку UDP в
yggmobile не было реализовано вообще, ICE-агент не мог достать ни до STUN,
ни до TURN сервера, обнаруженных по адресу в сети Yggdrasil — оставались
только локальные host-кандидаты, которые совпадают только в одной Wi‑Fi сети.

## Что меняет патч

1. **`yggmobile/yggmobile.go`** — добавлена поддержка UDP поверх существующего
   userspace IP-стека (`DialUDP`, `YggUDPConn`), по той же схеме, что уже
   работает для TCP.
2. **`YggdrasilCallRelay.java`** (новый файл) — локальный UDP-релей на
   `127.0.0.1:<порт>` для каждого обнаруженного STUN/TURN-сервера. WebRTC
   общается с этим loopback-адресом как с обычным сервером; релей прозрачно
   перекидывает байты в реальный сервер через `yggmobile.DialUDP`, не трогая
   сам STUN/TURN протокол.
3. **`JingleRtpConnection.java`** (`setupWebRTC`) — если звонок идёт через
   аккаунт с включённым Yggdrasil, ICE-серверы подменяются на локальные через
   `YggdrasilCallRelay`, и принудительно включается relay-only ICE
   (`iceTransportsType = RELAY`), независимо от отдельной настройки
   "use_relays".
4. **`YggdrasilManager.java`** — при остановке Yggdrasil-узла дополнительно
   останавливаются все relay-мосты (чистое завершение потоков/сокетов).

## Как собрать через GitHub Desktop

Эти файлы повторяют пути в репозитории. Распакуйте архив поверх вашей
локальной рабочей копии (так, чтобы `yggmobile/yggmobile.go` лёг на старый
файл, и так далее — подтвердите перезапись).

В GitHub Desktop: откройте репозиторий → во вкладке Changes увидите 3
изменённых файла и 1 новый (`YggdrasilCallRelay.java`) → впишите commit
message → Commit to main → Push origin.

Дальше всё собирается автоматически в GitHub Actions (workflow
`build.yml`, триггер на push в `main`): он сам клонирует yggdrasil-go,
прогоняет `gomobile bind` по обновлённому `yggmobile.go` (так что
UDP-изменения попадут в `libs/yggdrasil.aar` без какой-либо ручной работы),
и собирает `assembleConversationsFreeDebug`. APK забирайте из Actions →
последний run → Artifacts → `conversations-patched-debug`.

Если хотите релизный APK — есть отдельный workflow `build-release.yml`,
запускается вручную (workflow_dispatch) из вкладки Actions на GitHub.

## Как проверить, что починилось

После установки нового APK на оба устройства (в разных сетях) включите
тумблер Yggdrasil и позвоните. В `adb logcat` по тем же ключевым словам,
что и раньше (`jingle`, `webrtc`, `candidate`, `yggdrasil`), теперь должны
появиться кандидаты типа `relay` с адресом `127.0.0.1:<порт>` вместо
единственного `typ host` с адресом CGNAT/LAN, и звонок должен установиться
вместо падения в `CONNECTIVITY_ERROR` через ~15 секунд.
