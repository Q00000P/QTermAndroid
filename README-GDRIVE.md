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
5. Полученный Client ID (вида `1234-abc.apps.googleusercontent.com`) —
   в `gradle.properties` проекта:
   ```
   qterm.gdriveClientId=1234-abc.apps.googleusercontent.com
   ```
6. `./gradlew installDebug` → в приложении: ⋮ → Синхронизация → Google Drive →
   Войти в Google → выбрать папку (по имени, по умолчанию QTerm) → Синк сейчас.

Скоуп `drive.file`: приложение видит ТОЛЬКО файлы, созданные им самим.
Папку и `vault.qtsync` создаёт само; чужие файлы Диска недоступны в принципе.
