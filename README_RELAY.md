# Triger Relay

Самостоятельный Relay-сервер для Triger POC на базе образа
[`n0computer/iroh-relay`](https://hub.docker.com/r/n0computer/iroh-relay).

Relay нужен, когда между устройствами не устанавливается прямое P2P-соединение
(мобильная сеть, NAT, блокировка UDP). Трафик остаётся зашифрованным end-to-end:
Relay пересылает его, но не видит содержимое.

## Файлы

- `Dockerfile` — сборка контейнера поверх `n0computer/iroh-relay:latest`.
- `relay-entrypoint.sh` — генерирует `config.toml` из переменных окружения
  и запускает `/iroh-relay`.
- `README_RELAY.md` — этот файл.

Контейнер умеет работать в двух режимах:

1. **За обратным прокси платформы** (Render/Railway): TLS завершает платформа,
   контейнер слушает обычный HTTP на выданном `PORT`.
2. **На VPS с собственным TLS**: Let's Encrypt автоматически получает
   сертификат для `RELAY_HOSTNAME` и слушает HTTPS на 443.

## Переменные окружения

| Переменная | По умолчанию | Назначение |
|---|---|---|
| `PORT` | `3340` | Порт HTTP, который выдаёт Render/Railway. |
| `RELAY_HTTP_PORT` | `PORT` или `3340` | Порт HTTP вне платформ с `PORT`. |
| `RELAY_HTTP_BIND_ADDR` | `0.0.0.0:<port>` | Полный адрес HTTP-биндинга. |
| `RELAY_HOSTNAME` | пусто | Домен Relay для режима VPS с TLS. |
| `RELAY_CONTACT` | пусто | Email для Let's Encrypt (обязателен для `LetsEncrypt`). |
| `RELAY_HTTPS_PORT` | `443` | Порт HTTPS на VPS. |
| `RELAY_CERT_MODE` | `LetsEncrypt` | `LetsEncrypt` или `Manual`. |
| `RELAY_CERT_DIR` | `/var/lib/iroh-relay/certs` | Каталог сертификатов (volume). |

Если `RELAY_HOSTNAME` не задан, Relay работает по HTTP без TLS — этот режим
предназначен для Render/Railway, где HTTPS добавляет платформа.

## Вариант 1: Render

1. Загрузите проект в Git-репозиторий (GitHub/GitLab).
2. В Render создайте **New → Web Service** и подключите репозиторий.
3. Укажите:
   - **Runtime**: Docker;
   - **Dockerfile Path**: `./Dockerfile`;
   - **Health Check Path**: `/healthz`;
   - **Region**: ближайший к пользователям.
4. Переменные окружения не нужны: Render сам передаёт `PORT`.
5. После деплоя скопируйте публичный URL вида
   `https://triger-relay.onrender.com`.
6. Впишите его в приложение:

```java
private static final String HARDCODED_SEED_RELAY_URL =
        "https://triger-relay.onrender.com";
```

Проверка:

```bash
curl --fail https://triger-relay.onrender.com/healthz
```

Ожидается JSON вида `{"status":"ok",...}`.

> На бесплатном тарифе Render сервис засыпает при простое. Для постоянной
> работы Relay используйте платный инстанс.

## Вариант 2: Railway

1. Создайте проект **Deploy from GitHub repo**.
2. Railway найдёт `Dockerfile` автоматически.
3. В **Settings → Networking** нажмите **Generate Domain**.
4. **Health Check Path**: `/healthz`.
5. Railway передаёт `PORT` автоматически; дополнительные переменные не нужны.
6. Скопируйте выданный домен вида
   `https://triger-relay.up.railway.app` и укажите его в
   `HARDCODED_SEED_RELAY_URL`.

Проверка:

```bash
curl --fail https://triger-relay.up.railway.app/healthz
```

## Вариант 3: VPS с Docker

Требования: публичный IPv4, открытые порты 80 и 443, DNS-запись
`A relay.example.com → <IP VPS>`.

```bash
docker build -t triger-relay .
docker run -d \
  --name triger-relay \
  --restart unless-stopped \
  -p 80:80 \
  -p 443:443 \
  -e RELAY_HOSTNAME=relay.example.com \
  -e RELAY_CONTACT=admin@example.com \
  -e RELAY_CERT_MODE=LetsEncrypt \
  -v triger-relay-certs:/var/lib/iroh-relay/certs \
  triger-relay
```

Проверка:

```bash
curl --fail https://relay.example.com/healthz
```

Сертификат Let's Encrypt выпускается автоматически и кэшируется в volume
`triger-relay-certs`. Порты 80/443 должны быть доступны из интернета.

Если TLS уже завершает внешний nginx/Caddy, не задавайте `RELAY_HOSTNAME`:
контейнер будет слушать HTTP, а прокси должен пробрасывать WebSocket-запросы
на `/relay` и `/ping`.

## Подключение приложения

В `app/src/main/java/com/triger/poc/IrohNode.java` замените:

```java
private static final String HARDCODED_SEED_RELAY_URL = "";
```

на URL вашего Relay:

```java
private static final String HARDCODED_SEED_RELAY_URL = "https://relay.example.com";
```

При старте приложение:

1. Настраивает Relay через `RelayMode.customFromUrls(...)` и регистрируется на нём.
2. Для seed-узла собирает `EndpointAddr` с этим `relayUrl`.
3. Показывает в профиле статус `Relay: активен`.

URL должен быть `https://` и без завершающего `/`. После изменения пересоберите APK:

```bash
./gradlew assembleDebug -Pandroid.aapt2FromMavenOverride=/usr/bin/aapt2
```

## Диагностика

- `healthz` не отвечает — проверьте, что контейнер слушает выданный `PORT`
  и health check указывает на `/healthz`.
- Ошибка TLS на VPS — проверьте DNS, доступность портов 80/443 и `RELAY_CONTACT`.
- Relay не используется — в логах приложения должно быть
  `Relay: using HARDCODED_SEED_RELAY_URL = ...` и
  `Relay: reserved slot on ...`.
- Платформа засыпает — Render Free/некоторые trial-тарифы останавливают
  контейнер при простое.

## Безопасность

По умолчанию Relay открыт для всех. Для ограничения доступа используйте
allowlist/denylist по Endpoint ID, shared token или HTTP-авторизацию в
конфигурации `iroh-relay`. Если включаете shared token, приложению также нужно
передавать его в `RelayConfig` (сейчас клиент отправляет Relay URL без токена).
