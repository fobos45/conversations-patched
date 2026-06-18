# Патч v2: звонки через встроенный Yggdrasil — fix ICE gathering never completes

## Что было не так (v1 → v2)

В v1 relay-кандидат успешно собирался через TURN-сервер в Yggdrasil, но
`onIceGatheringChange(COMPLETE)` так и не приходил — gathering висел вечно.
Без COMPLETE WebRTC не запускает финальный DTLS-хендшейк, звонок застревает
в "Соединение" навсегда.

Причина: XEP-0215 возвращает два ICE-сервера с одним Yggdrasil-адресом:
`stun:[200:f28e:...]` и `turn:[200:f28e:...]`. Оба переписывались в один
и тот же bridge (`127.0.0.1:38029`). WebRTC открывал ДВА внутренних ICE-
сокета — один для STUN, один для TURN — и оба писали на один порт с разных
ephemeral-портов. Поле `lastWebRtcEndpoint` перезаписывалось последним
пишущим, ответы уходили не на тот сокет. TURN-сокет получал ответ,
STUN-сокет — нет, gathering никогда не считался завершённым.

## Что исправлено

**YggdrasilCallRelay.java** — два изменения:

1. `stun`/`stuns` серверы теперь **исключаются** из списка полностью
   (не перезаписываются, а просто не добавляются в результат). При
   relay-only политике ICE они дают только srflx-кандидатов, которые
   сразу выбрасываются — они бесполезны и только мешали.

2. Вместо `lastWebRtcEndpoint` (одна переменная на всех) добавлен роутинг
   по STUN Transaction ID: для каждого исходящего STUN/TURN Request/
   Indication в `txMap` запоминается пара TxID → WebRTC endpoint. Ответ
   (Success/Error response) по TxID находит нужный сокет. Для не-STUN
   фреймов (TURN ChannelData) используется fallback на `lastWebRtcEndpoint`.
   Это делает bridge корректным даже если в будущем несколько сокетов снова
   окажутся на одном порте.

## Файлы в этом архиве

- `yggmobile/yggmobile.go` — добавлен UDP (`DialUDP`, `YggUDPConn`)
- `src/.../utils/YggdrasilCallRelay.java` — основной relay (v2, исправлен)
- `src/.../utils/YggdrasilManager.java` — shutdownAll при выключении Yggdrasil
- `src/.../xmpp/jingle/JingleRtpConnection.java` — вызов relay + relay-only ICE

## Сборка

Распакуйте поверх рабочей копии → GitHub Desktop покажет 3 изменённых + 1
новый файл → commit → push → GitHub Actions соберёт APK автоматически.
