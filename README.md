# QTerm Android

SSH-клиент для Android — порт QTerm для macOS. Общий формат вейлта (.qtvault)
и шифрованная синхронизация между маком и телефоном.

- Терминал на libvterm (termlib), мультисессии, доп-ряд клавиш
- SSH через sshlib, ключи из вейлта, TOFU-проверка ключа хоста
- SFTP-проводник с редактором + фолбэк exec/base64 для dropbear
- Вейлт под ключом Android Keystore, импорт .qtvault
- Синк: WebDAV / файл в облаке через системный пикер / Google Drive API.
  В облаке только шифротекст: Argon2id + AES-256-GCM, слияние по времени
  правки записи, удаления через tombstones
- Подсказки команд (свой журнал синкается) и сниппеты
- Удержание сессий: foreground-сервис, keepalive, переподключение
  без потери содержимого терминала

## Сборка

Нужны JDK 21 и Android SDK (compileSdk 36):

    ./gradlew installDebug

Локальные настройки не в репозитории — положи их в ~/.gradle/gradle.properties:

    org.gradle.java.home=/путь/к/jdk21
    qterm.gdriveClientId=ВАШ_ID.apps.googleusercontent.com

Client id нужен только для бекенда «Drive API» (см. README-GDRIVE.md).

## Формат синка (QTS1)

    "QTS1"(4) | t(1) | p(1) | m_kib(4 LE) | salt(16) | nonce(12) | AES-256-GCM(JSON)

Ключ — Argon2id(пароль, salt) с параметрами из заголовка (t=3, m=64 МиБ,
p=2, длина 32). Настройки синка хранятся локально и в облако не уходят.

## Лицензия

MIT
