# Патч: логирование менеджера пиров + смены сети

## Что добавлено

### YggdrasilPeersActivity.java
Каждое действие пользователя в окне «Пиры Yggdrasil» теперь пишет в лог:
- `UI: add peer uri=...`
- `UI: edit peer uri=OLD -> NEW`
- `UI: delete peer uri=...`
- `UI: enable peer uri=...` / `UI: disable peer uri=...`
- `UI: persist() -> saving N peer(s), requesting Yggdrasil restart`
- `status: peer URI -> ONLINE` / `OFFLINE` — пишется один раз именно
  в момент смены состояния пира (не на каждый 3-секундный опрос),
  чтобы лог не захламлялся повторами.

### YggdrasilManager.java
- `startInternal:` — полный список включённых пиров построчно при
  каждом старте/рестарте узла, плюс адрес и статус SOCKS-прокси.
- `updatePeers:` — список пиров, с которым будет произведён рестарт,
  построчно, до фактического stop()/start().
- `stop:` — явные метки начала/конца остановки узла.
- **Новое:** подписка на `ConnectivityManager.NetworkCallback`,
  активна всё время пока узел запущен:
  - `[net] onAvailable` / `[net] onLost` — появление/пропажа сети
  - `[net] onCapabilitiesChanged transport=WIFI|CELLULAR validated=...`
    — смена транспорта (это и есть переключение Wi-Fi ↔ мобильный)
  - `[net] peer snapshot (reason): [{"uri":...,"up":...}, ...]` —
    полный дамп таблицы пиров Yggdrasil сразу в момент сетевого
    события, чтобы видеть как именно линки к пирам пережили переход
    (упали/остались живы/переподключились)

## Как смотреть логи

```
adb logcat -c
adb logcat -v time YggdrasilManager:I YggdrasilPeersActivity:I *:S
```

Сценарий для диагностики смены сети: запустите эту команду, затем на
телефоне переключитесь с Wi-Fi на мобильный интернет (или наоборот)
вручную (отключив Wi-Fi в шторке) — в логе появится последовательность
`[net] onLost` → `[net] onCapabilitiesChanged transport=CELLULAR` →
снапшот пиров сразу после, и затем повторные строки `status: peer ...`
по мере того, как каждый пир либо переподключается, либо остаётся
недоступен.

## Изменённые файлы
- `src/.../ui/YggdrasilPeersActivity.java`
- `src/.../utils/YggdrasilManager.java`
