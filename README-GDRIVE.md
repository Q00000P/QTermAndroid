# Google Drive как бекенд синка

Один раз, ~5 минут:

1. https://console.cloud.google.com → создать проект (любое имя).
2. APIs & Services → Library → включить **Google Drive API**.
3. APIs & Services → OAuth consent screen:
   - External → имя приложения любое, свой email.
   - Публиковать в **Production** (кнопка Publish app). Скоуп drive.file
     не sensitive — верификация не требуется, просто нажать.
     В статусе Testing refresh token живёт 7 дней — не оставляй Testing.
4. Credentials → Create credentials → OAuth client ID → **Android**:
   - Package name: `org.qterm.android`
   - SHA-1 дебажного ключа:
     ```
     keytool -list -v -keystore ~/.android/debug.keystore \
       -alias androiddebugkey -storepass android | grep SHA1
     ```
   4б. **Для release-сборки — второй клиент.** Android OAuth-клиент Google привязан к паре
   «пакет + SHA-1 подписи», а у debug- и release-ключа SHA-1 разные. Добавить второй SHA-1
   в тот же клиент нельзя — нужен ещё один Android-клиент в том же проекте (QTerm):
   Create credentials → OAuth client ID → Android, Package name `org.qterm.android`,
   SHA-1 release-ключа (его печатает `make-android-keystore.sh`, или
   `keytool -list -v -keystore ~/.android/qterm-release.jks -alias qterm | grep SHA1`).
   У него будет **свой client id** — release-сборка должна использовать именно его (шаг 5).
   Токен Диска, полученный в debug-сборке, release-сборке не подойдёт: после перехода на
   release один раз заново «Войти в Google».
5. Client ID (вида `1234-abc.apps.googleusercontent.com`) — в `~/.gradle/gradle.properties`
   (в репо файла нет):
   ```
   qterm.gdriveClientId=1234-abc.apps.googleusercontent.com          # debug-клиент
   qterm.gdriveClientIdRelease=5678-def.apps.googleusercontent.com   # release-клиент (шаг 4б)
   ```
   Debug-сборка берёт `qterm.gdriveClientId`, release — `qterm.gdriveClientIdRelease`
   (если его нет — тоже `qterm.gdriveClientId`, и вход в release не заработает).
   Для CI-релиза тот же id — секрет `QTERM_GDRIVE_CLIENT_ID_RELEASE` репо QTermAndroid.
6. `./gradlew installDebug` → в приложении: ⋮ → Синхронизация → Google Drive →
   Войти в Google → выбрать папку (по имени, по умолчанию QTerm) → Синк сейчас.

Скоуп `drive.file`: приложение видит ТОЛЬКО файлы, созданные им самим.
Папку и `vault.qtsync` создаёт само; чужие файлы Диска недоступны в принципе.
