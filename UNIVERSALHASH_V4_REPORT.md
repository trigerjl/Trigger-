# UniversalHash v4 — исследование и PoC интеграции в Trigger

## 1. Что исследовалось

- Проект собирается только из `app/` (`settings.gradle:1`, `app/build.gradle`); корневые `*.java` — устаревшие копии и в сборку не попадают.
- Майнинг сейчас — PoA/independent mining с фиксированным интервалом 300 с + jitter, подписью блока и проверкой через `BlockStorage` (`app/src/main/java/com/triger/poc/SignerService.java:396-456`, `:508-606`; `app/src/main/java/com/triger/poc/BlockStorage.java:38-49`, `:113-115`, `:188-232`).
- Native-часть уже есть (`libiroh_ffi.so` + JNA), но NDK/CMake/cargo в окружении нет; `jni/` содержит только leftover JNA-библиотеки.

### Источники по uhash-core

- crates.io: https://crates.io/crates/uhash-core (latest `0.5.1`, `Unlicense`, репозиторий в метаданных https://github.com/cyberia-to/universal-hash сейчас недоступен).
- docs.rs API/исходники: https://docs.rs/uhash-core/0.5.1/uhash_core/ , https://docs.rs/crate/uhash-core/0.5.1/source/
- Эталонные векторы: https://docs.rs/crate/uhash-core/0.5.1/source/src/tests.rs
- C ABI: https://docs.rs/crate/uhash-core/0.5.1/source/src/ffi.rs (`uhash_new`, `uhash_free`, `uhash_hash`, `uhash_benchmark`, `uhash_hashrate`)
- Параметры v4: 4 цепочки, scratchpad 512 КБ × 4 = 2 МБ, 12 288 раундов на цепочку, BLOCK_SIZE=64.
- Alg: seed = BLAKE3(header || (nonce XOR c×GOLDEN_RATIO)); scratchpad инициализируется AES-экспансией; раунд = AES/SHA-256-compress/BLAKE3-compress по `(nonce+c+round+1) mod 3`; финал = BLAKE3(SHA256(XOR chain states)).
- Отдельного Java-порта не найдено; найденные бенчмарки README: Galaxy A56 5G ~185 H/s, iPhone 14 Pro ~594 H/s, Mac M1 ~700-1400 H/s. Заявленные ~500 H/s для 8-ядерного Android в публичных исходниках не подтверждены.

## 2. Сравнение вариантов интеграции

| Вариант | Плюсы | Минусы / стоимость |
|---|---|---|
| A. Rust → JNI | Максимальная скорость; на ARM используется hardware AES/SHA2; можно сразу вызывать `uhash_hash` | Нужны Rust toolchain, cargo-ndk, NDK, wrapper `cdylib` (крейт собирается только как `lib`), JNI-glue или JNA-обвязка, packaging `.so` на arm64-v8a/armeabi-v7a (+x86/x86_64 для эмулятора), сборка в CI. Оценка: **средне-высокая**, 3-7 дней инженерной работы + CI/NDK. |
| A'. Rust + JNA | Уже используется JNA в проекте для `libiroh_ffi.so`; не нужно писать JNI C-код | Надо всё равно собрать `.so` и wrapper `cdylib`; накладные расходы JNA на один вызов пренебрежимо малы при 10-100 мс на хеш. Оценка: **средняя**, предпочтительный путь при интеграции. |
| B. Чистый Java-порт | Нет нативных зависимостей, работает везде, где есть Java 17; легко тестируется; лицензия алгоритма это позволяет | В ~15-40 раз медленнее нативного Rust. Оценка: **низкая сложность**, но неподходящий кандидат для реального PoW-майнинга. |

## 3. Что сделано в PoC

- Добавлен `app/src/main/java/com/triger/poc/UniversalHashMiner.java`:
  - `hash(byte[])` / `hashOnce(byte[])`, переиспользование 2 МБ scratchpad (`UniversalHashMiner.java:87`);
  - `buildInput(seed, address, timestamp, nonce)` и `meetsDifficulty` по семантике Rust (`UniversalHashMiner.java:122`, `:135`);
  - `mine(headerWithoutNonce, difficulty, startNonce, attempts)` (`UniversalHashMiner.java:150`);
  - внутренние BLAKE3, SHA-256 compression, AES/SBOX, круги v4 (`UniversalHashMiner.java:221`, `:476`).
- Добавлены тесты `app/src/test/java/com/triger/poc/UniversalHashMinerTest.java`:
  - точный mining-вектор из `src/tests.rs`:
    `dc51f07716cefc0c583d26750332b230a679b9f0ef6cb1d95bb32549f322f537`;
  - два regression-вектора scratchpad:
    `58e342c9...117d2b63`, `1e344647...1c73eb8d`;
  - официальные BLAKE3-векторы, SHA-256-compression против JCA, difficulty semantics, реюзабельность hasher’а.
- Все тесты проходят: `./gradlew testDebugUnitTest --offline` → **BUILD SUCCESSFUL**.
- Debug-сборка проходит: `./gradlew assembleDebug --offline` → **BUILD SUCCESSFUL**.
- `./gradlew lintDebug --offline` падает на 572 существующих ошибках проекта (первая — `DeviceIdentity.java:163`, NewApi), в новых файлах замечаний lint нет.

## 4. Производительность

Измерение на доступном хост-окружении (OpenJDK 64-Bit Server VM, aarch64, 6 vCPU, JIT, 5 хешей после прогрева):

- **~12.7 H/s** (78.7 мс/хеш) для чистого Java-порта.
- `-Xint`: ~0.15 H/s (6.8 с/хеш) — показывает, насколько критичен JIT/AOT.
- Для сравнения: Rust-бэкенд в документации — ~185 H/s на Galaxy A56 5G. Java-порт примерно в 15 раз медленнее публичного Android-бенчмарка и примерно в 40 раз медленнее гипотетических 500 H/s.

Измерение на Android-устройстве/эмуляторе **не выполнялось**: в окружении нет `adb`, emulator'а и тестового устройства. Для корректной цифры нужен instrumented benchmark (например, `androidTest` с `BenchmarkRule` или простой замер в `DebugActivity`) на реальном ARM64-телефоне.

## 5. Батарея и нагрев (оценка, не измерение)

- Алгоритм CPU-bound и memory-bound (2 МБ на hasher, случайные чтения 64-байтных блоков). При непрерывной полной загрузке одного ядра типичный смартфон потребляет ~1-3 Вт; за 5-10 минут это ~0.1-0.5 Вт·ч, т.е. примерно 1-3% батареи 4000-5000 mAh, но с заметным локальным нагревом.
- Ожидаемое поведение: троттлинг big-core через несколько минут, рост температуры корпуса на несколько градусов, возможный троттлинг фоновой задачи системой. Точные цифры требуют измерения `BatteryManager`/`PowerManager` и температуры на устройстве.
- Текущий Java-порт при 12 H/s не создаст заметной нагрузки только потому, что почти не успевает считать; нативная реализация на 185+ H/s будет уже реально греть телефон.

## 6. Рекомендация по архитектуре

**Стоит ли интегрировать?** Да, но только как **гибридный режим** и только после нативной реализации (Rust + JNA/JNI). Полностью заменять PoA не рекомендуется: это ломает текущую модель independent/SIM-gated mining, интервал блоков, награды и конфликтует со старыми узлами.

Рекомендуемая схема:

- Обычные блоки остаются PoA (`SignerService.nextBlockDelayMs` + подпись `BlockStorage.signBlockHash`).
- PoW на UniversalHash v4 — только для редких checkpoint-блоков (например, каждый N-й) или как отдельный non-reward proof/anti-spam уровень.
- В блок добавляются поля `powVersion=4`, `epochSeed`, `nonce`, `powHash`, `difficulty`; все пиры обязаны проверять их до `saveBlock`.
- Старые блоки без `pow*` трактуются как PoA — нужна версия формата, иначе `isChainValid` и `verifyBlockHash` отклонят историю.

### Изменения в `SignerService`

- `attemptBlockCreation()` (`SignerService.java:424`) выбирает режим по высоте блока; для PoW строит header без nonce и запускает `UniversalHashMiner.mine` в отдельном `ExecutorService`, а не в mining-scheduler, чтобы не блокировать wake lock/timer.
- Нужны: cancellation при закрытии сервиса, лимит попыток/времени, отдельный `WakeLock` на время PoW, прогресс в `MiningFragment` (сейчас он только UI-таймер, `:220-350`).
- `buildAndSaveBlock()` (`:508`) должен класть `nonce`/`powHash`/`difficulty` в JSON **до** подписи; подпись по-прежнему закрывается через `BlockStorage.signBlockHash`, чтобы не ломать `verifyBlockSignature`.
- `handleIncomingIrohBlock()` (`:462`) и P2P-путь должны вызывать новую проверку PoW до `storage.saveBlock(...)`.

### Изменения в `BlockStorage`

- Добавить `verifyProofOfWork(JSONObject)` (проверка `powVersion`, `difficulty`, `epochSeed`, пересчёт `UniversalHashMiner.hash` и `meetsDifficulty`).
- Вызывать её из `isBlockValid()` (`:113`) и `saveBlock()` (`:188`), а также из `isChainValid()` (`:285`).
- `computeBlockHash(JSONObject)` (`:51`) должен включить PoW-поля в подписываемый hash или явно зафиксировать, что PoW проверяется отдельно; `buildHeader()` (`:129`) должен нести PoW-поля для SPV.
- SharedPreferences-схема меняется неявно (JSON в `block_<index>`), но нужна миграция/совместимость: отсутствие `pow*` = старый PoA-блок.

## 7. Итог

- PoC подтверждает корректность порта: все эталонные векторы Rust совпадают.
- Для продакшена чистый Java-порт слишком медленен; использовать только как reference/тестовую реализацию.
- Рекомендуемый путь: Rust `cdylib` wrapper + JNA (уже есть инфраструктура в проекте) → нативный `libuhash.so` на arm64-v8a/armeabi-v7a → гибрид PoA + редкий PoW с версионированием блока.
- Перед включением в основную сборку необходимо: реальный Android-бенчмарк, замер батареи/температуры и согласование консенсус-правил со всеми пирами.
