# Откат speedtest, фикс звонков сохранён

## Что в этом архиве

Только рабочий фикс звонков через embedded Yggdrasil (UDP-релей для
TURN через loopback, см. предыдущие пояснения) — всё, что касалось
speedtest, полностью убрано:

- `YggdrasilPeersActivity.java` — возвращена ваша версия как есть
  (add/edit/delete пиров, без latency-бейджа и кнопки спидометра)
- `YggdrasilSpeedTestActivity.java` — удалён
- `AndroidManifest.xml` — убрана регистрация спидтест-экрана
- `yggmobile.go` — `GetPeersJSON` вернулась к простому `{uri, up}`,
  UDP-поддержка (`DialUDP`/`YggUDPConn`) для звонков сохранена
- `YggdrasilManager.java` — `getConnectedPeers()` вернулась к простой
  версии, `getPeerStats()` убран
- `YggdrasilCallRelay.java`, `JingleRtpConnection.java` — без изменений,
  это фикс звонков, не связан со speedtest

## Сборка

Распакуйте поверх рабочей копии (это уберёт спидтест-файлы), закоммитьте
и запушьте через GitHub Desktop. GitHub Desktop покажет удаление
`YggdrasilSpeedTestActivity.java` как часть diff.
