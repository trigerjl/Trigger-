# Triger Signaling Server

Простой WebSocket-сервер на Node.js, который знакомит два телефона (обменивается SDP-офферами и ICE-кандидатами). После установления WebRTC-соединения сервер больше не участвует в обмене данными — если он упадёт, уже подключённые устройства продолжат работать.

## Что нужно

- Node.js 18+ (https://nodejs.org)
- npm

## Запуск локально

```bash
# в корне проекта
npm install ws
node server.js
```

По умолчанию сервер слушает порт **8085**. Порт можно изменить переменной окружения `PORT`:

```bash
PORT=9000 node server.js
```

Проверка, что сервер поднялся: в консоли появится

```
Triger signaling server listening on ws://0.0.0.0:8085
```

## Как подключить приложение

1. Запустите сервер на компьютере в той же Wi-Fi сети, что и телефоны.
2. Узнайте IP компьютера в локальной сети (например, `192.168.0.100`).
3. В приложении: блок **«Настройки сети» → поле «Сигнальный сервер»**, введите
   `ws://192.168.0.100:8085` и нажмите **«Сохранить сервер»**.
4. Перезапускать сервер между подключениями не нужно.

Особые случаи:

- **Android-эмулятор**: локальный компьютер доступен как `10.0.2.2`, поэтому URL — `ws://10.0.2.2:8085`.
- **Телефон на 4G / другой сети**: нужен публичный адрес сервера (см. ниже), локальный `192.168.x.x` из мобильной сети недоступен.
- Если сервер развёрнут за HTTPS/WSS-прокси, используйте `wss://...`.

## Развёртывание на бесплатном хостинге

### Render.com

1. Залейте проект (или хотя бы `server.js` + `package.json`) в GitHub-репозиторий.
2. Создайте `package.json` (если его нет):
   ```json
   {
     "name": "triger-signaling",
     "private": true,
     "scripts": { "start": "node server.js" },
     "dependencies": { "ws": "^8.18.0" }
   }
   ```
3. В Render: **New → Web Service**, выберите репозиторий.
4. Build Command: `npm install`, Start Command: `npm start`.
5. Render задаёт `PORT` автоматически — `server.js` это учитывает.
6. После деплоя получите адрес вида `triger-signaling.onrender.com`. Render даёт HTTPS,
   поэтому в приложении укажите `wss://triger-signaling.onrender.com`
   (WebSocket идёт через тот же домен/порт 443).
7. В настройках приложения сохраните этот WSS-URL.

### Railway.app

1. Залейте проект в GitHub.
2. В Railway: **New Project → Deploy from GitHub repo**.
3. Railway сам найдёт `package.json` и выполнит `npm install` / `npm start`.
4. В разделе **Settings → Networking → Generate Domain** получите публичный домен.
5. В приложении укажите `wss://<ваш-домен>`.

> Примечание: бесплатные инстансы Render «засыпают» после простоя — первый запрос может
> занять 30–60 секунд. Уже установленные WebRTC-соединения при этом не рвутся, т.к. данные
> идут напрямую между телефонами.

### Альтернатива: VPS + systemd

```bash
npm install ws
node server.js            # проверить
# systemd unit (пример):
# ExecStart=/usr/bin/node /opt/triger/server.js
# Environment=PORT=8085
```

Для `wss://` поставьте перед Node.js nginx/caddy с TLS-сертификатом (Let's Encrypt) и проксированием WebSocket (`proxy_set_header Upgrade $http_upgrade; proxy_set_header Connection "upgrade";`).

## Протокол

Клиент → сервер:

```json
{ "type": "register",  "id": "0x1a2b3c" }
{ "type": "get_peers" }
{ "type": "offer",     "from": "0x1a2b3c", "to": "0x4d5e6f", "payload": { "sdp": "v=0..." } }
{ "type": "answer",    "from": "0x4d5e6f", "to": "0x1a2b3c", "payload": { "sdp": "v=0..." } }
{ "type": "candidate", "from": "0x4d5e6f", "to": "0x1a2b3c",
  "payload": { "sdpMid": "0", "sdpMLineIndex": 0, "candidate": "candidate:..." } }
```

Сервер:

- хранит `Map<peerId, WebSocket>`;
- `register` — регистрирует клиента (старое соединение с тем же ID закрывается) и
  рассылает остальным `{ "type": "peer_joined", "id": "new_id" }`;
- `get_peers` — отвечает списком всех активных узлов (кроме отправителя):
  `{ "type": "peers_list", "peers": ["id1", "id2"] }`;
- любое сообщение с `to` пересылает адресату, добавляя актуальный `from`;
- если адресат не в сети — отвечает отправителю
  `{ "type": "error", "payload": "peer_offline" }`;
- при отключении удаляет клиента из списка;
- каждые 30 секунд шлёт ping/pong и закрывает мёртвые соединения.

Клиент автоматически узнаёт узлы (через `get_peers` после регистрации и через
`peer_joined`) и подключается ко всем найденным без ручного ввода Peer ID.

Сервер **не хранит** блоки, цепочку и ключи — только маршрутизирует сигнальные сообщения.
