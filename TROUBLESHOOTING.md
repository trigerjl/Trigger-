# Triger POC: подключение и DNS

## Симптомы

В Logcat видны ошибки:

```text
No addressing information available
Failed to resolve TXT record
```

Это значит, что Iroh не смог получить адрес пира через DNS/Pkarr.
Сам узел при этом может быть запущен нормально.

## Private DNS на Android

Android может использовать Private DNS (DNS-over-TLS), который не резолвит
TXT-записи, нужные Iroh/Pkarr.

Если приложение не подключается:

1. Откройте **Настройки → Сеть и интернет → Private DNS**
   (на некоторых прошивках: **Настройки → Подключения → Другие настройки
   подключения → Private DNS**).
2. Выберите **Выкл.** / **Off**.
3. Перезапустите Triger POC.
4. Повторите подключение к пиру.

После проверки Private DNS можно включить обратно.

## Что ещё проверить

- Убедитесь, что интернет действительно работает и не включён VPN,
  блокирующий DNS/relay-трафик.
- Подключение к новому пиру может занимать несколько секунд: приложению нужно
  опубликовать свою Pkarr-запись и дождаться записи пира.
- Если DNS/Pkarr недоступен, приложение попробует fallback через известный
  home relay. Для этого должен быть установлен relay-контакт с пиром.
- На мобильном интернете (4G) Iroh не перечисляет всех узлов сети. Нужен
  известный Endpoint ID, контакт из адресной книги или seed-узел.

## Диагностика через Logcat

```bash
adb logcat -s IrohNode P2PNode MainActivity
```

Полезные сообщения:

```text
Waiting for discovery...
Relay: reserved slot on ...
Relay: fallback relay = ...
Addr cache: ...
Fallback: using home relay ...
mDNS: found peer ...
Dial: trying to connect to ...
Dial failed: ...
```

Если после отключения Private DNS всё равно есть `No addressing information`,
пришлите фрагмент Logcat с этими строками.
