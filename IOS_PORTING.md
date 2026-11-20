# Портирование Triger POC на iOS

Документ описывает подготовку Android-проекта `Triger-poc` к переносу на iOS.
Собрать и подписать iOS-приложение в AndroidIDE нельзя (нужны macOS + Xcode),
поэтому цель шага — зафиксировать архитектуру, границы модулей и список
Android-зависимостей, которые придётся абстрагировать или переписать.

Текущее состояние: один Gradle-модуль `app`, Java + Kotlin, UI на XML/Views,
сеть — Iroh через сгенерированные UniFFI-биндинги `computer.iroh.*`,
нативная библиотека `libiroh_ffi.so` под Android ABI.

---

## 1. Целевая архитектура

Рекомендуемая схема:

```
Triger-poc/
  shared/                 # Kotlin Multiplatform: чистая логика без Android API
    commonMain/           # модели, токеномика, правила PoA, кодеки, интерфейсы
    androidMain/          # реальные Android-реализации интерфейсов
    iosMain/              # реальные iOS-реализации (или Swift-реализации)
  androidApp/             # текущий app/ (Activity/Fragment/View/Service)
  iosApp/                 # SwiftUI-приложение + Iroh Swift-биндинги
```

Ключевой принцип: **UI и платформенные API — нативно, бизнес-логика — в общем
модуле**. Android-код уже разделён на слои неявно; при портировании это
разделение нужно сделать явным через интерфейсы:

| Интерфейс общего модуля | Android-реализация | iOS-реализация |
|---|---|---|
| `KeyValueStore` | `SharedPreferences` | `UserDefaults` |
| `SecureStore` | Android Keystore / EncryptedSharedPreferences | Keychain / Secure Enclave |
| `IdentityProvider` | `DeviceIdentity` (EC P-256 в Keystore) | Keychain + CryptoKit / Secure Enclave |
| `SimIdentityProvider` | `TelephonyManager` (ICCID) | нет аналога: `identifierForVendor` / device_id |
| `MessageStore` | `ChatDatabase` (SQLiteOpenHelper) | SQLite (GRDB) или CoreData |
| `BlockStore` | `BlockStorage` (SharedPreferences + JSON) | SQLite / файлы + `Codable` |
| `SettingsStore` | `SettingsActivity` + SharedPreferences | `UserDefaults` + `@AppStorage` |
| `Logger` | `android.util.Log` | `os.Logger` |
| `JsonCodec` | `org.json` | `Codable` / `JSONSerialization` |
| `NotificationSink` | `NotificationHelper` | `UNUserNotificationCenter` |
| `PeerDiscoveryTransport` | `IrohNode` (JVM/Android FFI) | `IrohNode` (Swift FFI) |

---

## 2. UI

### Варианты

1. **SwiftUI (рекомендуется)**
   - Текущий UI — императивные XML/Views, `Fragment`, `RecyclerView`,
     `BottomSheetDialogFragment`. Готового Compose-кода для переиспользования нет.
   - SwiftUI даёт нативный доступ к Keychain, PhotosUI, AVFoundation (QR),
     Swift Charts, UNUserNotificationCenter, BGTaskScheduler.
   - Бизнес-логику можно либо портировать в Swift, либо вынести в KMP `shared`
     и вызывать из Swift через сгенерированный framework.

2. **Compose Multiplatform**
   - Имеет смысл, только если приоритет — один UI-код на Android/iOS.
   - Потребует переписать текущий XML-UI на Compose целиком (на Android тоже).
   - Платформенные интеграции (Keychain, Iroh, фоновые задачи, QR) всё равно
     придётся делать через `expect/actual` или Swift-обёртки.

**Вывод:** для этого проекта быстрее и надёжнее SwiftUI + общий KMP-модуль для
чистой логики. Compose Multiplatform рассматривать как отдельный крупный
рефакторинг, а не как часть портирования.

### Что переписать в UI

Все `Activity`, `Fragment`, `View`, `Dialog`, адаптеры и XML-layout'ы:

- `MainActivity`, `MainPagerAdapter`, `OnboardingActivity`;
- `WalletFragment`, `ChatFragment`, `MiningFragment`, `ProfileAndChatFragment`;
- `ChatActivity`, `ProfileActivity`, `SettingsActivity`, `SettingsFragment`;
- `NodesListActivity`, `PinActivity`, `PinSetupActivity`, `PinSheetDialogFragment`,
  `PinSettingsDialogs`, `AboutSheetDialogFragment`, `WalletQrSheet`;
- `QrScannerActivity`, `QrCodeUtils`;
- `BalanceChartView`, `GradientTextView`;
- `res/layout/*`, `res/drawable/*`, `res/menu/*`, `res/values/*`.

На iOS им соответствуют SwiftUI-экраны (`WalletView`, `ChatView`,
`MiningView`, `ProfileView`, `SettingsView`, `PinView`, `QRScannerView` и т.д.).

---

## 3. Сеть (Iroh)

Сейчас используется:

- Rust-библиотека Iroh через UniFFI;
- сгенерированные Kotlin-биндинги `app/src/main/java/computer/iroh/iroh_ffi.kt`;
- `libiroh_ffi.so` для `arm64-v8a`, `armeabi-v7a`, `x86`, `x86_64`;
- JNI-проброс Android-контекста:
  `IrohAndroid.installAndroidContext(Context)` (`IrohNode.java:319`).

Для iOS:

1. Собрать `iroh-ffi` под `aarch64-apple-ios` (устройство) и
   `aarch64-apple-ios-sim` (симулятор), оформить как `.xcframework`.
2. **Использовать UniFFI, а не swift-bridge**: проект уже генерирует
   UniFFI-биндинги для Kotlin, тот же IDL даст Swift-биндинги без дублирования
   описания API.
3. Заменить `IrohAndroid.installAndroidContext` на iOS-аналог (если он нужен
   конкретной версии `iroh-ffi`) либо убрать Android-специфичный вызов.
4. Портировать `IrohNode.java`:
   - убрать `Context`, `SharedPreferences`, `ConnectivityManager`;
   - секретный ключ хранить в Keychain;
   - known peers, username, профиль — через `KeyValueStore`;
   - сетевые изменения — через `NWPathMonitor` вместо `ConnectivityManager`;
   - `notifyMessage` — через `NotificationSink`/`UnreadCounter`-интерфейс;
   - `org.json` — заменить на `Codable`/`kotlinx.serialization`;
   - `android.util.Log` — на `Logger`;
   - JVM-корутины (`BuildersKt.runBlocking`) заменить на Swift-обёртки
     UniFFI async-функций.
5. `IrohIo.kt` (кадрирование сообщений `uint32 length + UTF-8 payload`) —
   логика чистая, но привязана к JVM-биндингам. Портировать в Swift поверх
   сгенерированных UniFFI-типов (`Endpoint`, `Connection`, `BiStream`,
   `SendStream`, `RecvStream`), сохранив формат кадров для совместимости с
   Android-клиентами.
6. `P2PNode.java` (WebSocket, `org.java_websocket`) — legacy-транспорт для
   обмена блоками, используется `SignerService` параллельно с Iroh. На iOS либо
   портировать на `URLSessionWebSocketTask`, либо исключить, оставив только Iroh.
7. `HolePunch.java` — чистый Java-кодек DCUtR, Android API не использует,
   переиспользуется после переноса в общий модуль/Swift.

Ограничение iOS: приложение не может держать постоянный сетевой узел в фоне так,
как Android foreground service. Для POC майнинг/подпись должны работать, пока
приложение на экране; в фоне — только короткие `BGProcessingTask` и push.

---

## 4. Криптография

| Android | iOS |
|---|---|
| Android Keystore (`KeyGenParameterSpec`, EC P-256) | Keychain + Secure Enclave (`SecKeyCreateRandomKey`, `kSecAttrTokenIDSecureEnclave`) |
| `MessageDigest("SHA-256")` | `CryptoKit.SHA256` |
| BouncyCastle / web3j `ECKeyPair`, `Keys` | `Web3.swift`, `secp256k1.swift` или libsecp256k1 |
| `SharedPreferences` для приватного ключа (`KeyManager`) | **только Keychain**, никогда `UserDefaults` |
| PIN = SHA-256 в SharedPreferences (`PinManager`) | Keychain + CryptoKit; желательно PBKDF2/Argon2 вместо одиночного SHA-256 |

Важно: `KeyManager` сейчас хранит приватный ключ валидатора в
`SharedPreferences` (`KeyManager.java:12-14`). Это небезопасно и на Android;
при портировании ключ должен уходить в `SecureStore` на обеих платформах.

`CryptoUtils.java` настраивает BouncyCastle и на iOS не нужен; его роль берут
на себя `CryptoKit` и выбранная secp256k1-библиотека.

`PeerNameGenerator` использует `java.security.MessageDigest` — алгоритм
(первые 4 байта SHA-256 → прилагательное + существительное) переносится без
изменений, меняется только вызов хеша на `CryptoKit`.

`DeviceIdentity`: device_id = SHA-256 от публичного EC-ключа. На iOS ключ в
Secure Enclave/Keychain; нужно решить, должен ли device_id переживать
переустановку приложения (Keychain по умолчанию переживает — при необходимости
сбрасывать его при первом запуске).

`SimIdentity`: на iOS нет доступа к ICCID/IMSI. Текущая PoA-политика
«одна SIM = один узел» непереносима; нужен другой признак уникальности
(`identifierForVendor`, device_id, серверная регистрация) или явное понижение
требования для iOS.

---

## 5. Хранение данных

| Данные | Android сейчас | iOS |
|---|---|---|
| Настройки, тема, username, интервал майнинга | `SharedPreferences` (`triger_prefs`) | `UserDefaults` / `@AppStorage` |
| Секретный ключ Iroh, приватный ключ, PIN | `SharedPreferences` (нужно исправить) | Keychain |
| Известные пиры, usernames | `SharedPreferences` (`triger_iroh_discovery`) | SQLite / `UserDefaults` (не секреты) |
| История транзакций | `SharedPreferences` + JSON (`triger_history`) | SQLite / `Codable`-файлы |
| Блоки | `SharedPreferences` + JSON (`triger_blocks`) | SQLite / файлы |
| Сообщения чата | `ChatDatabase` (`SQLiteOpenHelper`) | SQLite (GRDB) или CoreData |
| Адресная книга | `SharedPreferences` + JSON (`triger_contacts`) | SQLite / `UserDefaults` |
| Аватар | файл в `filesDir` + `Bitmap` | `FileManager` + `UIImage`/PhotosUI |
| Счётчики непрочитанного | `SharedPreferences` (`triger_unread`) | `UserDefaults` или БД |

`BlockStorage` и `TransactionHistory` хранят JSON-строки в SharedPreferences —
на iOS это лучше заменить на SQLite/`Codable`, иначе потеряется масштабируемость
и целостность.

---

## 6. Разделение логики: что можно переиспользовать

### 6.1. Переиспользуется почти без изменений (чистые алгоритмы)

| Класс | Что именно | Что заменить |
|---|---|---|
| `Tokenomics` | константы `MAX_SUPPLY`, `BASE_REWARD`, `HALVING_INTERVAL`; формулы `getBlockReward`, `mint`, `formatAmount` | `Context`/`SharedPreferences`/`Log`/`BigDecimal`-хранение → `TokenomicsStore` + `Decimal`/`Double` |
| `PeerNameGenerator` | детерминированное имя пира, `shortId` | `MessageDigest` → `CryptoKit.SHA256` |
| `HolePunch` | кодирование/разбор DCUtR-фреймов | ничего (чистый Java/Kotlin) |
| `ValidatorManager` | правила PoA: список валидаторов, round-robin, проверка SIM-hash | `Context`, `SharedPreferences`, `org.json`, `DeviceIdentity`, `SimIdentity` → интерфейсы |
| `AddressBook` | нормализация ника, защита от подмены endpoint по нику, лимиты | `Context`, `SharedPreferences`, `org.json` → `KeyValueStore` |
| `SignerService.buildAndSaveBlock` | сборка блока, хеширование, начисление награды, проверка prevHash | вынести в `BlockFactory`/`BlockValidator` без `Service`/`Notification` |
| `IrohNode` (частично) | peer exchange, профиль, known peers, поиск по username, формат сообщений | платформенные API и JVM-корутины |

### 6.2. Требуют абстрагирования перед переиспользованием

| Класс | Android-зависимости | Что вынести |
|---|---|---|
| `Tokenomics` | `Context`, `SharedPreferences`, `android.util.Log` | `TokenomicsStore` |
| `ValidatorManager` | `Context`, `SharedPreferences`, `org.json`, `DeviceIdentity`, `SimIdentity` | `ValidatorStore`, `JsonCodec`, `IdentityProvider`, `SimIdentityProvider` |
| `AddressBook` | `Context`, `SharedPreferences`, `org.json` | `ContactStore`, `JsonCodec` |
| `KeyManager` | `Context`, `SharedPreferences`, web3j | `SecureStore`, `WalletCrypto` |
| `TransactionHistory` | `Context`, `SharedPreferences`, `org.json` | `TransactionStore`, `JsonCodec` |
| `BlockStorage` | `Context`, `SharedPreferences`, `org.json` | `BlockStore`, `JsonCodec` |
| `ChatDatabase` | `SQLiteOpenHelper`, `ContentValues`, `Cursor` | `MessageStore` (SQLite/SQLDelight/CoreData) |
| `DeviceIdentity` | Android Keystore, `SharedPreferences`, `Build` | `IdentityProvider`, `SecureStore` |
| `SimIdentity` | `TelephonyManager`, `SharedPreferences` | `SimIdentityProvider` (на iOS — fallback) |
| `UnreadCounter` | `Context`, `SharedPreferences`, статическая зависимость от `ChatActivity` | `UnreadStore` + событие «чат открыт» |
| `PinManager` | `Context`, `SharedPreferences`, `MessageDigest` | `SecureStore`, `Hasher` |
| `AvatarStore` | `Bitmap`, `Uri`, `ContentResolver`, `File` | `AvatarStore` на `UIImage`/`PhotosUI`/`FileManager` |
| `IrohNode` | `Context`, `SharedPreferences`, `ConnectivityManager`, `Base64`, `Log`, `org.json`, `IrohAndroid`, JVM-корутины | `KeyValueStore`, `SecretStore`, `NetworkMonitor`, `Logger`, `JsonCodec`, `IrohTransport` |
| `P2PNode` | `android.util.Log`, Java-WebSocket | `Logger` + `URLSessionWebSocketTask` или удаление |

### 6.3. Переписать полностью (нативные/UI/сервисные)

`MainActivity`, `MainPagerAdapter`, все `*Fragment`, все `*Activity`, все
`*DialogFragment`, `WalletQrSheet`, `QrCodeUtils`, `QrScannerActivity`,
`BalanceChartView`, `GradientTextView`, `NotificationHelper`, `SignerService`,
`AvatarStore`, `ChatDatabase` (или заменить на SQLDelight/CoreData).

---

## 7. Ключевые классы: Android API и точки абстракции

### 7.1. `IrohNode.java` (1847 строк)

Роль: singleton P2P-узла Iroh, соединения, приём/отправка сообщений, обмен
блоками, профили, peer discovery, known peers.

Android API и методы:

| Метод/участок | Android API | Что делать на iOS |
|---|---|---|
| `loadOrCreateSecretKey(Context)` (`:198`) | `SharedPreferences`, `Base64`, `Log` | Keychain + `Data.base64EncodedString` |
| `start(Context)` (`:273`) | `Context`, `getApplicationContext`, `KeyManager.getOrCreateDeviceId` | `start()` без Context, `IdentityProvider` |
| `IrohAndroid.installAndroidContext` (`:319`) | JNI + Android Context | убрать/заменить iOS-аналогом |
| `notifyMessage` (`:537`) | `UnreadCounter`, `NotificationHelper` | `UnreadStore`, `NotificationSink` |
| `handlePeerExchange`/`handleSearch*`/`handleProfile` (`:678`, `:766`, `:876`) | `AddressBook`, `DeviceIdentity`, `appContext` | `ContactStore`, `IdentityProvider` |
| `loadKnownPeers`/`persistKnownPeers` (`:1048`, `:1066`) | `SharedPreferences` | `KeyValueStore`/SQLite |
| `ensureDefaultUsername`/`getLocalUsername` (`:1082`, `:1101`) | `SettingsActivity.PREFS_NAME`, SharedPreferences | `SettingsStore` |
| `loadPeerUsernames`/`persistPeerUsernames` (`:1162`, `:1187`) | `SharedPreferences` | `KeyValueStore` |
| `registerNetworkCallback` (`:1419`) | `ConnectivityManager`, `NetworkRequest` | `NWPathMonitor` |
| `resolvePeerByEndpointId`, `connectToPeer`, `sendMessage` | JVM-корутины `BuildersKt`, UniFFI-типы | Swift UniFFI async + `IrohIo`-аналог |
| `log` (`:1838`) | `android.util.Log` | `Logger` |

Вывод: `IrohNode` — главный кандидат на разделение на `IrohTransport`
(чистая работа с Endpoint/Connection/потоками) и платформенные адаптеры.

### 7.2. `SignerService.java` (405 строк)

Роль: Android foreground service, майнинг по интервалу, подпись блоков,
проверка PoA, начисление наград, уведомления.

| Метод/участок | Android API | Что делать на iOS |
|---|---|---|
| `onCreate` (`:58`), `acquireLocks` (`:67`) | `WifiManager.WifiLock`, `PowerManager.WakeLock` | нет аналога; убрать, использовать `BGProcessingTask`/foreground-only |
| `isCharging`/`getBatteryLevel` (`:109`, `:126`) | `BatteryManager`, `registerReceiver` | `ProcessInfo`/`UIDevice` battery monitoring |
| `onStartCommand` (`:146`) | `Service`, `Intent`, `NotificationCompat` | lifecycle-менеджер + `UNUserNotificationCenter` |
| `isMiningIntervalElapsed` (`:211`) | `SharedPreferences` | `SettingsStore` |
| `simulateBlockSigning` (`:228`) | `ValidatorManager`, `BlockStorage`, `KeyManager`, `LocalBroadcastManager` | `BlockFactory` + `BlockStore` + `WalletStore` + event bus |
| `handleIncomingIrohBlock` (`:262`) | `BlockStorage`, `ValidatorManager`, `NotificationHelper`, `LocalBroadcastManager` | `BlockValidator` + `NotificationSink` |
| `buildAndSaveBlock` (`:310`) | `BlockStorage`, `Tokenomics`, `KeyManager`, `NotificationHelper` | вынести чистую сборку/валидацию блока в shared |
| `createNotificationChannel` (`:376`) | `NotificationChannel`, `NotificationManager` | `UNNotificationCategory` |
| `onDestroy`/`onBind` (`:389`, `:402`) | `Service`, `IBinder` | нет прямого аналога |

Ключевой риск: iOS не позволяет постоянно майнить в фоне. Логику
`simulateBlockSigning`/`buildAndSaveBlock` нужно сделать управляемой из
UI-lifecycle, а не из сервиса.

### 7.3. `WalletFragment.java` (324 строки)

Роль: экран кошелька — баланс, адрес, история, график, QR.

Android API: `Fragment`, `LayoutInflater`, `View`, `Bundle`, `TextView`,
`ImageView`, `LinearLayout`, `AlertDialog`, `Toast`, `Intent`,
`startActivityForResult`, `ContextCompat`, `LayoutInflater`, `SimpleDateFormat`.

Бизнес-вызовы: `KeyManager.getLocalBalance/requireContext` (`:99`),
`Tokenomics.formatAmount`, `TransactionHistory.getTransactions` (`:192`, `:235`),
`BalanceChartView` (`:187-207`), `QrScannerActivity` (`:123`),
`WalletQrSheet` (`:119`), `MainActivity.Listener` (`:30`, `:305-321`).

На iOS: `WalletView` (SwiftUI), `WalletViewModel` с `WalletStore`,
`TransactionStore`, `QRScannerView` (AVFoundation), `ShareLink`/`ImageRenderer`
для QR, `Charts` для графика. Логика извлечения адреса из QR
(`WalletFragment.extractAddress`, `:167`) переносится в shared.

### 7.4. `ChatFragment.java` (276 строк)

Роль: список пиров, поиск по username, подключение, копирование Endpoint ID.

Android API: `Fragment`, `Handler`/`Looper` (`:28`), `View`, `LayoutInflater`,
`TextView`, `EditText`, `Toast`, `Intent`, `ClipboardManager`,
`MaterialButton`, `requireContext`.

Бизнес-вызовы: `IrohNode.start/connectToPeer/requestPeerByUsername/
getPeerDisplayName/getSelfEndpointId` (`:99`, `:114`, `:152`, `:243`, `:201`),
`AddressBook.findByUsername` (`:148`), `PeerNameGenerator.shortId` (`:244`),
`ChatActivity` (`:120`).

На iOS: `ChatListView` + `ChatListViewModel`; вместо `Handler` — `Task`/
`@MainActor`/Combine; вместо `ClipboardManager` — `UIPasteboard`; вместо
`Intent` — навигация SwiftUI. `IrohNode`-вызовы — через Swift-обёртку.

---

## 8. Матрица готовности классов

| Класс | Роль | Android-зависимости | Вердикт |
|---|---|---|---|
| `Tokenomics` | эмиссия, халвинг | Context, SharedPreferences, Log | Формулы — shared; хранение — интерфейс |
| `ValidatorManager` | PoA-список, round-robin | Context, SharedPreferences, org.json, DeviceIdentity, SimIdentity | Абстрагировать + shared |
| `AddressBook` | ник ↔ Endpoint ID | Context, SharedPreferences, org.json | Абстрагировать + shared |
| `TransactionHistory` | история транзакций | Context, SharedPreferences, org.json | Абстрагировать + shared |
| `BlockStorage` | блоки, проверка цепи | Context, SharedPreferences, org.json | Абстрагировать + shared |
| `KeyManager` | адрес/ключ/баланс | Context, SharedPreferences, web3j | Переписать на SecureStore |
| `DeviceIdentity` | device_id, реестр пиров | Keystore, SharedPreferences, Build | Переписать на Keychain |
| `SimIdentity` | SIM-hash | TelephonyManager, SharedPreferences | Переписать/понизить требование |
| `PinManager` | PIN | Context, SharedPreferences, SHA-256 | Переписать на Keychain |
| `UnreadCounter` | непрочитанные | Context, SharedPreferences, ChatActivity | Абстрагировать + shared |
| `AvatarStore` | аватар | Bitmap, Uri, ContentResolver | Переписать на iOS |
| `ChatDatabase` | история чата | SQLiteOpenHelper/Cursor | Переписать (SQLite/CoreData) |
| `IrohNode` | P2P-узел | Context, ConnectivityManager, Base64, Log, org.json, JNI, JVM-корутины | Разделить: transport + адаптеры |
| `IrohIo` | кадрирование сообщений | JVM-биндинги Iroh | Портировать в Swift |
| `P2PNode` | legacy WebSocket | android.util.Log, Java-WebSocket | Портировать/удалить |
| `HolePunch` | DCUtR-кодек | нет | Переиспользовать |
| `PeerNameGenerator` | имена пиров | java.security.MessageDigest | Переиспользовать (CryptoKit) |
| `CryptoUtils` | BouncyCastle | BouncyCastle JVM | Не нужен на iOS |
| `SignerService` | foreground-майнинг | Service, Notification, Wifi/Power/Battery, LocalBroadcast | Переписать под iOS lifecycle |
| `NotificationHelper` | уведомления | NotificationManager | Переписать на UNUserNotificationCenter |
| UI-классы (`*Activity`, `*Fragment`, `*View`, `*Dialog`) | экраны | Android UI SDK | Переписать на SwiftUI |

---

## 9. План портирования

### Этап 1. Выделение общего ядра (без iOS-кода)
1. Создать модуль `shared` (KMP).
2. Ввести интерфейсы `KeyValueStore`, `SecureStore`, `JsonCodec`, `Logger`,
   `IdentityProvider`, `SimIdentityProvider`, `BlockStore`, `ContactStore`,
   `TransactionStore`, `SettingsStore`, `NotificationSink`.
3. Перенести чистые алгоритмы: `Tokenomics` (формулы), `PeerNameGenerator`,
   `HolePunch`, нормализацию username/адресов, round-robin и проверку блоков.
4. Обернуть текущие Android-реализации в эти интерфейсы и убедиться, что
   Android-приложение собирается и ведёт себя как раньше.

### Этап 2. Iroh на iOS
1. Собрать `iroh-ffi` как XCFramework, сгенерировать Swift-биндинги UniFFI.
2. Реализовать Swift `IrohTransport` с тем же протоколом кадров, что
   `IrohIo.kt`.
3. Перенести логику `IrohNode`: known peers, профиль, peer exchange, поиск.
4. Проверить совместимость Android ↔ iOS: одинаковый ALPN, формат сообщений,
   формат блоков JSON.

### Этап 3. Платформенные сервисы iOS
1. Keychain/Secure Enclave для ключей и PIN.
2. `UserDefaults`/SQLite/CoreData для настроек, истории, чата, блоков.
3. `NWPathMonitor` вместо `ConnectivityManager`.
4. `UNUserNotificationCenter` вместо `NotificationHelper`.
5. `AVFoundation` для QR, PhotosUI для аватара, Swift Charts для графика.
6. Пересмотреть фоновый майнинг: foreground-only или BGProcessingTask.

### Этап 4. UI
1. SwiftUI-экраны: Onboarding, Main/Tabs, Wallet, Chat, Mining, Profile,
   Settings, PIN, QR scanner.
2. ViewModel'и поверх shared-логики и platform-сервисов.
3. Тёмная/светлая тема, локализация RU/EN.

### Этап 5. Проверка совместимости
1. Один и тот же endpoint/кошелёк на Android и iOS.
2. Обмен сообщениями и подписанными блоками между платформами.
3. Импорт/экспорт адреса, QR-совместимость.
4. Одинаковые правила PoA/токеномики на обеих платформах.

---

## 10. Ограничения и риски

1. **Фоновый майнинг.** Android foreground service нельзя один в один
   перенести на iOS. Нужно менять продуктовую модель: майнинг только на
   переднем плане или редкие фоновые задачи.
2. **SIM-идентичность.** `SimIdentity` на iOS невозможен в текущем виде;
   политика «одна SIM = один узел» требует пересмотра.
3. **Безопасность ключей.** Приватный ключ в SharedPreferences — проблема
   уже на Android; на iOS обязателен Keychain, желательно исправить и Android.
4. **Iroh-версия.** Нужно проверить, что используемая версия `iroh-ffi`
   собирается под iOS и поддерживает n0 discovery/relay так же, как Android.
5. **Размер и лицензии.** Нативные iOS-библиотеки (Iroh, secp256k1) увеличат
   размер `.ipa`; проверить лицензии и App Store-ограничения.
6. **Формат данных.** JSON-схемы блоков, сообщений и адресной книги нужно
   зафиксировать как общий контракт до начала портирования.
7. **Push-уведомления.** Для сообщений в фоне понадобится APNs и серверная
   часть; текущие локальные уведомления работают только при живом процессе.

---

## 11. Быстрый чек-лист портирования

- [ ] Вынесен `shared`-модуль с чистой логикой.
- [ ] Введены интерфейсы хранилищ/идентичности/логирования.
- [ ] Android-реализации переведены на интерфейсы без изменения поведения.
- [ ] Собран iOS XCFramework `iroh-ffi`, сгенерированы Swift-биндинги.
- [ ] Реализован Swift `IrohTransport` с совместимым форматом кадров.
- [ ] Ключи и PIN перенесены в Keychain.
- [ ] Настройки/история/чат/блоки — в UserDefaults/SQLite/CoreData.
- [ ] Реализованы уведомления, QR, аватар, график на нативных API.
- [ ] Пересмотрена модель фонового майнинга.
- [ ] Проверена совместимость Android ↔ iOS.
