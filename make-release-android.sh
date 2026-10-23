#!/bin/bash
# Релиз QTerm для Android: ./gradlew assembleRelease (release-подпись + вшитый токен обновлений) →
# QTerm-android-<версия>.apk → version-android.json с sha256 → релиз android-<версия> в ПРИВАТНЫЙ
# репозиторий релизов Q00000P/QTerm-releases (тот же, что у мака v* и винды win-*).
# Два токена, оба из ~/.zshenv (или окружения), в гите их нет:
#   QTERM_UPDATE_TOKEN  — только чтение, только QTerm-releases: вшивается в APK, им приложение качает обновления
#   QTERM_RELEASE_TOKEN — чтение+запись Contents в QTerm-releases: им gh публикует релиз (без входа в браузере)
# Подпись — ~/.android/qterm-release.jks и qterm.release.* в ~/.gradle/gradle.properties
# (один раз: ./make-android-keystore.sh). Без неё релиз не собираем: обновления встают только поверх той же подписи.
# Тот же релиз умеет собрать GitHub Actions: .github/workflows/android-release.yml (тег android-* или вручную).
set -euo pipefail
cd "$(dirname "$0")"
REPO="Q00000P/QTerm-releases"
GPROPS="${GRADLE_USER_HOME:-$HOME/.gradle}/gradle.properties"

zshvar() { # переменная из ~/.zshenv, если скрипт запущен не из zsh
    local v="${!1:-}"
    if [ -z "$v" ] && [ -x /bin/zsh ]; then v=$(/bin/zsh -c "printf '%s' \"\$$1\"" 2>/dev/null </dev/null || true); fi
    printf '%s' "$v"
}
gprop() { # свойство из ~/.gradle/gradle.properties
    [ -f "$GPROPS" ] || return 0
    grep -E "^[[:space:]]*$1[[:space:]]*=" "$GPROPS" | tail -1 | cut -d= -f2- | sed 's/^[[:space:]]*//;s/[[:space:]]*$//'
}
QTERM_UPDATE_TOKEN=$(zshvar QTERM_UPDATE_TOKEN)
[ -n "$QTERM_UPDATE_TOKEN" ] || QTERM_UPDATE_TOKEN=$(gprop qterm.updateToken)
QTERM_RELEASE_TOKEN=$(zshvar QTERM_RELEASE_TOKEN)
# Gradle вшивает токен только из [A-Za-z0-9_] (build.gradle.kts) — чистим так же здесь, чтобы проверка
# «токен вшит» и сборка видели одну и ту же строку (лишний \r, пробел или кавычки из ~/.zshenv)
RAW_LEN=${#QTERM_UPDATE_TOKEN}
QTERM_UPDATE_TOKEN=$(printf '%s' "$QTERM_UPDATE_TOKEN" | tr -cd 'A-Za-z0-9_')
[ "$RAW_LEN" -eq "${#QTERM_UPDATE_TOKEN}" ] || echo "⚠️  В QTERM_UPDATE_TOKEN лишние символы ($RAW_LEN → ${#QTERM_UPDATE_TOKEN}): кавычки/пробел/\\r в ~/.zshenv — вшиваю очищенный"
export QTERM_UPDATE_TOKEN

# === Токен обновлений: без него установленные копии не увидят эту версию — релиз не собираем ===
[ -n "$QTERM_UPDATE_TOKEN" ] || { echo "⛔ Нет QTERM_UPDATE_TOKEN (~/.zshenv или qterm.updateToken в $GPROPS) — без него установленные копии не увидят обновление"; exit 1; }
HDR=$(curl -s -o /dev/null -D - -H "Authorization: Bearer $QTERM_UPDATE_TOKEN" -H "Accept: application/vnd.github+json" \
      -H "X-GitHub-Api-Version: 2022-11-28" "https://api.github.com/repos/$REPO" || true)
STATUS=$(printf '%s' "$HDR" | head -1 | awk '{print $2}')
case "$STATUS" in
    200) ;;
    401) echo "⛔ GitHub не принимает токен обновлений (401): в QTERM_UPDATE_TOKEN не то значение"; exit 1 ;;
    404) echo "⛔ Токен обновлений не видит $REPO (404): при создании не выбран этот репозиторий или нет Contents: Read-only"; exit 1 ;;
    *)   echo "⛔ Токен обновлений не читает $REPO (HTTP ${STATUS:-нет ответа})"; exit 1 ;;
esac
EXP=$(printf '%s' "$HDR" | grep -i '^github-authentication-token-expiration:' | cut -d' ' -f2- | tr -d '\r' || true)
if [ -n "$EXP" ]; then
    EXP_TS=$(date -j -u -f "%Y-%m-%d %H:%M:%S %Z" "$EXP" +%s 2>/dev/null || date -u -d "$EXP" +%s 2>/dev/null || echo 0)
    DAYS=$(( (EXP_TS - $(date +%s)) / 86400 ))
    if [ "$EXP_TS" -gt 0 ] && [ "$DAYS" -lt 30 ]; then
        echo "⚠️  Токен обновлений истекает через $DAYS дн. ($EXP): выпусти токен №2, вшей его этой сборкой, старый отзови, когда обновятся все устройства"
    else echo "🔑 Токен обновлений: действует до $EXP"; fi
else echo "🔑 Токен обновлений: принят"; fi

# === Подпись: без стабильного release-ключа обновление поверх не встанет ===
STORE=$(gprop qterm.release.storeFile); STORE="${STORE/#\~/$HOME}"
if [ -z "$STORE" ] || [ ! -f "$STORE" ] || [ -z "$(gprop qterm.release.storePassword)" ]; then
    echo "⛔ Нет release-подписи (qterm.release.storeFile / storePassword в $GPROPS) — сначала ./make-android-keystore.sh"; exit 1
fi
[ -n "$(gprop qterm.gdriveClientIdRelease)$(gprop qterm.gdriveClientId)" ] || echo "⚠️  Нет qterm.gdriveClientId в $GPROPS — вход в Google Drive в этой сборке не заработает"

# === Версия и сборка ===
VERSION=$(grep -m1 'versionName' app/build.gradle.kts | cut -d'"' -f2)
VCODE=$(grep -m1 'versionCode' app/build.gradle.kts | grep -oE '[0-9]+')
[ -n "$VERSION" ] && [ -n "$VCODE" ] || { echo "⛔ Не нашёл versionName/versionCode в app/build.gradle.kts"; exit 1; }
echo "📦 QTerm Android $VERSION ($VCODE)"
./gradlew --console=plain clean assembleRelease
SRC="app/build/outputs/apk/release/app-release.apk"
[ -f "$SRC" ] || { echo "⛔ Нет $SRC (подписанного APK) — проверь qterm.release.* в $GPROPS"; ls app/build/outputs/apk/release/ 2>/dev/null; exit 1; }

# токен действительно вшит (BuildConfig — строковая константа в dex)
if command -v unzip >/dev/null 2>&1; then
    # grep -c, а не -q: -q выходит на первом совпадении, unzip получает SIGPIPE (141), и при pipefail
    # весь конвейер «падает» — именно так проверка ложно говорила «не вшит»
    HITS=$(unzip -p "$SRC" 'classes*.dex' 2>/dev/null | LC_ALL=C grep -acF "$QTERM_UPDATE_TOKEN" || true)
    if [ "${HITS:-0}" -eq 0 ]; then
        # что Gradle на самом деле вшил — первые 14 символов и длина (весь токен не печатаем)
        BC=$(find app/build/generated -name BuildConfig.java -path '*release*' 2>/dev/null | head -1)
        GOT=$( [ -n "$BC" ] && sed -nE 's/.*UPDATE_TOKEN = "([^"]*)".*/\1/p' "$BC" || true)
        echo "⛔ В APK не нашёлся токен обновлений"
        echo "   в окружении: ${QTERM_UPDATE_TOKEN:0:14}… (${#QTERM_UPDATE_TOKEN} симв.)"
        if [ -n "$BC" ]; then echo "   вшито Gradle: ${GOT:0:14}… (${#GOT} симв.) — $BC"
        else echo "   BuildConfig.java не найден в app/build/generated"; fi
        [ -n "$(gprop qterm.updateToken)" ] && echo "   ⚠️  в $GPROPS есть qterm.updateToken — он главнее переменной окружения"
        exit 1
    fi
fi
# подпись — сверка с keystore (если есть apksigner из SDK)
APKSIGNER=$(ls -d "${ANDROID_HOME:-$HOME/Library/Android/sdk}"/build-tools/*/apksigner 2>/dev/null | sort -V | tail -1 || true)
if [ -n "$APKSIGNER" ]; then
    "$APKSIGNER" verify --print-certs "$SRC" | grep -m1 'SHA-256 digest' | sed 's/^/🔏 /'
fi

# === APK и манифест ===
TAG="android-$VERSION"
APK="QTerm-android-$VERSION.apk"
cp -f "$SRC" "$APK"
SHA=$(shasum -a 256 "$APK" | awk '{print $1}')
echo "✅ APK: $APK"
echo "   SHA256: $SHA"
cat > version-android.json <<JSON
{
  "version": "$VERSION",
  "versionCode": $VCODE,
  "sha256": "$SHA",
  "url": "https://github.com/$REPO/releases/download/$TAG/$APK",
  "page": "https://github.com/$REPO/releases/tag/$TAG",
  "published": "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
}
JSON

# === Публикация: gh по токену записи (QTERM_RELEASE_TOKEN → GH_TOKEN), иначе — вход gh auth login ===
command -v gh >/dev/null 2>&1 || { echo "⛔ Нет gh: brew install gh"; exit 1; }
if [ -n "$QTERM_RELEASE_TOKEN" ]; then export GH_TOKEN="$QTERM_RELEASE_TOKEN"; fi
gh auth status >/dev/null 2>&1 || { echo "⛔ gh не авторизован: задай QTERM_RELEASE_TOKEN в ~/.zshenv (Contents: Read and write на $REPO) или gh auth login"; exit 1; }
gh repo view "$REPO" >/dev/null 2>&1 || { echo "⛔ Репозитория $REPO нет или токен его не видит"; exit 1; }
if [ "$(gh repo view "$REPO" --json visibility -q .visibility 2>/dev/null)" != "PRIVATE" ]; then
    echo "⛔ $REPO не приватный — публиковать туда не буду"; exit 1
fi
# Пустой репозиторий (ни одного коммита) — релиз в нём GitHub не создаёт (422 «Repository is empty»):
# кладём стартовый README через API, это и есть первый коммит
if ! gh api "repos/$REPO/commits?per_page=1" >/dev/null 2>&1; then
    echo "📦 $REPO пуст — кладу стартовый README"
    gh api -X PUT "repos/$REPO/contents/README.md" -f message="init: релизы QTerm" \
        -f content="$(printf '# QTerm releases\n\nРелизы QTerm: macOS — теги v*, Windows — win-*, Android — android-*. Приватный; приложение читает по токену.\n' | base64)" >/dev/null
fi
NOTES="release-notes/android-$VERSION.md"
[ -f "$NOTES" ] || NOTES="release-notes/$VERSION.md"
if gh release view "$TAG" -R "$REPO" >/dev/null 2>&1; then
    echo "🚀 Релиз $TAG уже есть — заменяю файлы"
elif [ -f "$NOTES" ]; then
    gh release create "$TAG" -R "$REPO" --title "QTerm для Android $VERSION" --notes-file "$NOTES"
else
    gh release create "$TAG" -R "$REPO" --title "QTerm для Android $VERSION" --notes "QTerm $VERSION для Android."
fi
gh release upload "$TAG" -R "$REPO" "$APK" version-android.json --clobber
rm -f "$APK" version-android.json
echo "✅ Опубликовано: https://github.com/$REPO/releases/tag/$TAG"
echo "   Установленные копии увидят $VERSION на старте или по «О программе → Проверить обновления…»"
