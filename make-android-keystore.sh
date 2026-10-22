#!/bin/bash
# Один раз: release-ключ QTerm Android → ~/.android/qterm-release.jks (RSA 4096, 10000 дней, alias qterm).
#  1) создаёт keystore (пароль спрашивает скрыто, в историю и в гит не попадает);
#  2) печатает SHA-1 — для ВТОРОГО Android OAuth-клиента Google (README-GDRIVE.md);
#  3) дописывает qterm.release.* в ~/.gradle/gradle.properties (права 600);
#  4) по желанию кладёт 4 секрета в GitHub Actions репо Q00000P/QTermAndroid (gh secret set).
# ВАЖНО: ключ терять нельзя — без него обновления поверх установленной копии не встанут. Сделай бэкап jks и пароля.
set -euo pipefail
cd "$(dirname "$0")"
STORE="$HOME/.android/qterm-release.jks"
ALIAS="qterm"
GPROPS="${GRADLE_USER_HOME:-$HOME/.gradle}/gradle.properties"
CODE_REPO="Q00000P/QTermAndroid"

command -v keytool >/dev/null 2>&1 || {
    JBR="/Applications/Android Studio.app/Contents/jbr/Contents/Home/bin"
    if [ -x "$JBR/keytool" ]; then PATH="$JBR:$PATH"; else echo "⛔ Нет keytool (JDK): brew install --cask temurin@21 или Android Studio"; exit 1; fi
}

ask_pw() { # скрытый ввод с подтверждением
    local a b
    while :; do
        read -r -s -p "$1: " a; echo
        [ ${#a} -ge 6 ] || { echo "   минимум 6 символов"; continue; }
        read -r -s -p "   ещё раз: " b; echo
        [ "$a" = "$b" ] && break
        echo "   не совпало"
    done
    PW="$a"
}

if [ -f "$STORE" ]; then
    echo "🔑 $STORE уже есть — новый не создаю (иначе установленные копии не обновятся)"
    read -r -s -p "Пароль keystore: " PW; echo
    keytool -list -keystore "$STORE" -storepass "$PW" -alias "$ALIAS" >/dev/null || { echo "⛔ Пароль не подошёл или нет alias $ALIAS"; exit 1; }
else
    mkdir -p "$HOME/.android"
    ask_pw "Пароль нового keystore (он же пароль ключа)"
    keytool -genkeypair -v -keystore "$STORE" -storetype PKCS12 -alias "$ALIAS" \
        -keyalg RSA -keysize 4096 -validity 10000 \
        -storepass "$PW" -keypass "$PW" \
        -dname "CN=QTerm, O=Q00000P, C=FI" >/dev/null
    chmod 600 "$STORE"
    echo "✅ Создан $STORE"
fi

echo
echo "=== Отпечатки ключа (SHA-1 — во второй Android OAuth-клиент Google, пакет org.qterm.android) ==="
keytool -list -v -keystore "$STORE" -storepass "$PW" -alias "$ALIAS" | grep -E 'SHA1:|SHA256:' | sed 's/^[[:space:]]*/   /'
echo

# === ~/.gradle/gradle.properties ===
mkdir -p "$(dirname "$GPROPS")"; touch "$GPROPS"; chmod 600 "$GPROPS"
tmp=$(mktemp)
grep -vE '^[[:space:]]*qterm\.release\.(storeFile|storePassword|keyAlias|keyPassword)[[:space:]]*=' "$GPROPS" > "$tmp" || true
{
    cat "$tmp"
    echo "qterm.release.storeFile=$STORE"
    echo "qterm.release.storePassword=$PW"
    echo "qterm.release.keyAlias=$ALIAS"
    echo "qterm.release.keyPassword=$PW"
} > "$GPROPS"
rm -f "$tmp"
echo "✅ qterm.release.* записаны в $GPROPS"
grep -q '^qterm.updateToken=' "$GPROPS" || [ -n "${QTERM_UPDATE_TOKEN:-}" ] || \
    echo "ℹ️  Токен обновлений — QTERM_UPDATE_TOKEN в ~/.zshenv (как для мака) или qterm.updateToken= в $GPROPS"

# === Секреты GitHub Actions (для .github/workflows/android-release.yml) ===
echo
read -r -p "Положить секреты подписи в GitHub Actions $CODE_REPO (gh secret set)? [y/N] " yn
if [[ "$yn" =~ ^[YyДд] ]]; then
    command -v gh >/dev/null 2>&1 || { echo "⛔ Нет gh: brew install gh"; exit 1; }
    base64 < "$STORE" | tr -d '\n' | gh secret set QTERM_KEYSTORE_B64 -R "$CODE_REPO"
    printf '%s' "$PW" | gh secret set QTERM_KEYSTORE_PASSWORD -R "$CODE_REPO"
    printf '%s' "$ALIAS" | gh secret set QTERM_KEY_ALIAS -R "$CODE_REPO"
    printf '%s' "$PW" | gh secret set QTERM_KEY_PASSWORD -R "$CODE_REPO"
    echo "✅ QTERM_KEYSTORE_B64, QTERM_KEYSTORE_PASSWORD, QTERM_KEY_ALIAS, QTERM_KEY_PASSWORD → $CODE_REPO"
    echo "   Ещё нужны (если не заданы): QTERM_UPDATE_TOKEN, QTERM_RELEASE_TOKEN, QTERM_GDRIVE_CLIENT_ID_RELEASE —"
    echo "   gh secret set QTERM_UPDATE_TOKEN -R $CODE_REPO --body \"\$QTERM_UPDATE_TOKEN\""
    echo "   gh secret set QTERM_RELEASE_TOKEN -R $CODE_REPO --body \"\$QTERM_RELEASE_TOKEN\""
fi
unset PW
echo
echo "⚠️  Сделай бэкап $STORE и пароля (менеджер паролей): потеря ключа = переустановка QTerm на всех телефонах."
