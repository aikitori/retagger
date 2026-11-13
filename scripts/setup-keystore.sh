#!/usr/bin/env bash
# One-time setup: create a fixed signing key for the APK and store it as secrets in the GitHub repo.
# Run in the project folder:  bash scripts/setup-keystore.sh  (other folder: SIGNING_DIR=... bash ...)
# Needs keytool (ships with the JDK) and, to store the secrets automatically, the GitHub CLI `gh`
# (run `gh auth login` once). Without `gh` the values are printed for manual entry.
set -euo pipefail

DIR="${SIGNING_DIR:-${HOME}/retagger-signing}"
KEYSTORE="${DIR}/retagger.jks"
ALIAS="retagger"

if [ -e "$KEYSTORE" ]; then
  echo "A key already exists: $KEYSTORE"
  echo "Do not create a new one, or updates will no longer install over the existing app."
  exit 1
fi
command -v keytool >/dev/null || { echo "keytool missing: please install a JDK."; exit 1; }

mkdir -p "$DIR"
chmod 700 "$DIR"
PASSWORD="$(head -c 24 /dev/urandom | base64 | tr -dc 'A-Za-z0-9' | head -c 24)"

# PKCS12: key and keystore password are the same
keytool -genkeypair -keystore "$KEYSTORE" -storetype PKCS12 -alias "$ALIAS" \
  -keyalg RSA -keysize 2048 -validity 10000 \
  -storepass "$PASSWORD" -keypass "$PASSWORD" \
  -dname "CN=Retagger" >/dev/null
echo "$PASSWORD" > "${DIR}/password.txt"
chmod 600 "$KEYSTORE" "${DIR}/password.txt"
BASE64="$(base64 < "$KEYSTORE" | tr -d '\n')"

echo "Key created: $KEYSTORE (password in ${DIR}/password.txt)"
echo "IMPORTANT: keep this folder safe. If it is lost, the app has to be reinstalled once."

if command -v gh >/dev/null && gh auth status >/dev/null 2>&1; then
  printf '%s' "$BASE64" | gh secret set ANDROID_KEYSTORE_BASE64
  printf '%s' "$PASSWORD" | gh secret set ANDROID_KEYSTORE_PASSWORD
  printf '%s' "$ALIAS" | gh secret set ANDROID_KEY_ALIAS
  printf '%s' "$PASSWORD" | gh secret set ANDROID_KEY_PASSWORD
  echo "The four secrets are stored in the repo. The next push to main builds a signed APK."
else
  echo
  echo "The GitHub CLI is not logged in. Add these four repository secrets under"
  echo "Settings → Secrets and variables → Actions:"
  echo "  ANDROID_KEYSTORE_BASE64 = (contents of ${DIR}/keystore-base64.txt)"
  echo "  ANDROID_KEYSTORE_PASSWORD = $PASSWORD"
  echo "  ANDROID_KEY_ALIAS = $ALIAS"
  echo "  ANDROID_KEY_PASSWORD = $PASSWORD"
  printf '%s' "$BASE64" > "${DIR}/keystore-base64.txt"
  chmod 600 "${DIR}/keystore-base64.txt"
fi
