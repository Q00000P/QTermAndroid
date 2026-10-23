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
   4б. **Release-сборка — обычно тот же клиент.** Вход идёт через браузер (Custom Tab) по custom
   URI scheme с PKCE — в этом потоке Google подпись приложения не проверяет, поэтому release-сборка
   берёт тот же `qterm.gdriveClientId`. Только если Google откажет release-сборке (`invalid_request`,
   `unauthorized_client`, «doesn't comply with OAuth 2.0 policy») — второй Android-клиент в том же
   проекте: Clients → Create client → Android, Package name `org.qterm.android`, SHA-1 release-ключа
   (`keytool -list -v -keystore ~/.android/qterm-release.jks -alias qterm | grep SHA1`), в нём
   Advanced settings → Enable custom URI scheme; его id — в `qterm.gdriveClientIdRelease` (шаг 5).
   Токен Диска переживает только сборки с той же подписью: после перехода с debug на release один
   раз заново «Войти в Google».
5. Client ID (вида `1234-abc.apps.googleusercontent.com`) — в `~/.gradle/gradle.properties`
   (в репо файла нет):
   ```
   qterm.gdriveClientId=1234-abc.apps.googleusercontent.com          # debug-клиент
   qterm.gdriveClientIdRelease=5678-def.apps.googleusercontent.com   # только если понадобился (шаг 4б)
   ```
   Debug-сборка берёт `qterm.gdriveClientId`, release — `qterm.gdriveClientIdRelease`
   (если его нет — тот же `qterm.gdriveClientId`).
   Для CI-релиза id — секрет `QTERM_GDRIVE_CLIENT_ID_RELEASE` репо QTermAndroid (обычно тот же `qterm.gdriveClientId`).
6. `./gradlew installDebug` → в приложении: ⋮ → Синхронизация → Google Drive →
   Войти в Google → выбрать папку (по имени, по умолчанию QTerm) → Синк сейчас.

Скоуп `drive.file`: приложение видит ТОЛЬКО файлы, созданные им самим.
Папку и `vault.qtsync` создаёт само; чужие файлы Диска недоступны в принципе.
