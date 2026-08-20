# QTerm Android — первая сборка

```bash
unzip QTermAndroid.zip -d ~/dev && cd ~/dev/QTermAndroid

# один раз: врапперу нужен jar, его генерит gradle из brew
brew install gradle
gradle wrapper --gradle-version 8.9

./gradlew installDebug
```

Альтернатива без brew: открыть папку в Android Studio (File → Open), после синка собрать оттуда.

## Важное

- `gradle.properties` пинует JVM на JBR 21 из Android Studio
  (`/Applications/Android Studio.app/Contents/jbr/Contents/Home`) —
  твой Temurin 26 Gradle 8.9 не запускает. Если Studio лежит в другом
  месте — поправить путь.
- Тесты кросс-совместимости формата: `./gradlew test`. Эталонный
  `fixture.qtvault` в test/resources сгенерирован независимой
  реализацией (python) по формату мака, пароль внутри теста.

## Проверка на телефоне

1. Скинуть на телефон экспорт с мака: «Данные → Экспорт вейлта» → `.qtvault`
   (AirDrop-аналога нет — проще `adb push vault.qtvault /sdcard/Download/`).
2. В приложении: «Импортировать .qtvault» → выбрать файл → пароль экспорта.
3. Должны появиться все ~33 ноды со значком ключа у тех, где ключ из вейлта.
