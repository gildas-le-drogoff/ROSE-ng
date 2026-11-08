#!/bin/bash
set -euo pipefail

export JAVA_HOME="/usr/lib/jvm/java-25-openjdk"
export PATH="${JAVA_HOME}/bin:${PATH}"

PROJECT_DIR="/home/gildas/AndroidStudioProjects/ROSE-ng"
KEYSTORE_PROPS="keystore.properties"
KEYSTORE_FILE="upload-keystore.jks"
KEY_ALIAS="upload"
ARTIFACT_BASENAME="0_ROSE-ng_Android"
DATE_TAG="$(date +%F_%H)"
FINAL_APK="${ARTIFACT_BASENAME}.apk"
STAMPED_APK="${ARTIFACT_BASENAME}_${DATE_TAG}.apk"
FINAL_DEBUG_APK="${ARTIFACT_BASENAME}_debug.apk"
SYNC_DIR="/mnt/NUMBER_4/Sync_Gildas_s_Phone/Orion/0_AppDev"

RED=$'\033[0;31m'
GREEN=$'\033[0;32m'
PURPLE=$'\033[0;35m'
YELLOW=$'\033[1;33m'
NC=$'\033[0m'

log() { printf '%b\n' "$1"; }

generate_upload_keystore() {
	local password
	password="$(openssl rand -base64 48 | tr -dc 'A-Za-z0-9' | head -c 32)"

	umask 077
	if ! keytool -genkeypair -v -keystore "$KEYSTORE_FILE" -alias "$KEY_ALIAS" \
		-keyalg RSA -keysize 5120 -validity 10950 \
		-storepass "$password" -keypass "$password" \
		-dname "CN=ROSE-ng_Upload Key,O=ledrogoff,C=FR"; then
		log "${RED}keytool failed: keystore not generated.${NC}"
		exit 1
	fi

	cat > "$KEYSTORE_PROPS" << EOF
storeFile=../${KEYSTORE_FILE}
storePassword=${password}
keyAlias=${KEY_ALIAS}
keyPassword=${password}
EOF
	chmod 600 "$KEYSTORE_PROPS"

	log "${YELLOW}⚠️  NEW upload keystore generated (one-shot)."
	log "⚠️  Back up ${KEYSTORE_FILE} and ${KEYSTORE_PROPS} somewhere safe NOW."
	log "⚠️  Losing them means losing the Play upload key.${NC}"
}

push_github_secrets() {
	local ks_pass key_alias ks_b64
	ks_pass="$(grep '^storePassword=' "$KEYSTORE_PROPS" | cut -d= -f2-)"
	key_alias="$(grep '^keyAlias=' "$KEYSTORE_PROPS" | cut -d= -f2-)"
	ks_b64="$(base64 -w 0 "$KEYSTORE_FILE")"

	if [ -z "$ks_b64" ] || [ -z "$ks_pass" ] || [ -z "$key_alias" ]; then
		log "${RED}Refusing to push empty GitHub secrets.${NC}"
		exit 1
	fi

	printf '%s' "$ks_b64" | gh secret set KEYSTORE_BASE64
	printf '%s' "$ks_pass" | gh secret set KEYSTORE_PASSWORD
	printf '%s' "$ks_pass" | gh secret set KEY_PASSWORD
	printf '%s' "$key_alias" | gh secret set KEY_ALIAS
}

keystore_is_valid() {
	local ks_pass
	ks_pass="$(grep '^storePassword=' "$KEYSTORE_PROPS" | cut -d= -f2-)"
	grep -qx "storeFile=../${KEYSTORE_FILE}" "$KEYSTORE_PROPS" &&
		keytool -list -keystore "$KEYSTORE_FILE" -alias "$KEY_ALIAS" \
			-storepass "$ks_pass" > /dev/null 2>&1
}

setup_keystore() {
	if [ -f "$KEYSTORE_FILE" ] && [ -f "$KEYSTORE_PROPS" ]; then
		if keystore_is_valid; then
			log "${GREEN}Reusing existing upload keystore.${NC}"
			return
		fi
		log "${RED}${KEYSTORE_PROPS} does not match ${KEYSTORE_FILE} (wrong storeFile, alias or password)."
		log "Restore the right ${KEYSTORE_PROPS} from backup, or move ${KEYSTORE_FILE} away to regenerate.${NC}"
		exit 1
	fi
	if [ -f "$KEYSTORE_FILE" ] && [ ! -f "$KEYSTORE_PROPS" ]; then
		log "${RED}${KEYSTORE_FILE} exists but ${KEYSTORE_PROPS} is missing."
		log "Password is unrecoverable: restore ${KEYSTORE_PROPS} from backup.${NC}"
		exit 1
	fi
	log "${GREEN}Generating upload keystore (one-shot)...${NC}"
	generate_upload_keystore
	push_github_secrets
}

cd "$PROJECT_DIR"

setup_keystore

log "${GREEN}Format code...${NC}"
# ktlint -F ./app/src 2> /dev/null || true

log "${GREEN}Build release APK + debug APK...${NC}"

# ● Bash(./gradlew :app:compileDebugKotlin --write-locks --console=plain -q 2>&1 | grep -v "^w:" | tail -30)
# ./gradlew assembleDebug --write-locks --console=plain    # --parallel --configure-on-demand

# Purge stale outputs so a failed build can never ship an old APK
rm -rf "${PROJECT_DIR}/app/build/outputs/apk"

./gradlew assembleDebug --console=plain           # --parallel --configure-on-demand
./gradlew assembleRelease --console=plain -q 2>&1 # --parallel --configure-on-demand

APK_PATH="${PROJECT_DIR}/app/build/outputs/apk/release/app-arm64-v8a-release.apk"
DEBUG_APK_PATH="$(find "${PROJECT_DIR}/app/build/outputs/apk/debug" -name '*arm64-v8a*.apk' | head -n1)"

if [ -z "$APK_PATH" ] || [ ! -f "$APK_PATH" ]; then
	log "${RED}Release APK build failed or not found.${NC}"
	exit 1
fi

cp "$APK_PATH" "${PROJECT_DIR}/${FINAL_APK}"
cp "$DEBUG_APK_PATH" "${PROJECT_DIR}/${FINAL_DEBUG_APK}"

log "${GREEN}✅ APK (release) -> ${PURPLE}${PROJECT_DIR}/${FINAL_APK}${NC}"
log "${GREEN}✅ APK (debug)   -> ${PURPLE}${PROJECT_DIR}/${FINAL_DEBUG_APK}${NC}"
log "${RED}__________________________${NC}"

CURRENT_HOST="$(hostname -s)"
PUSH="true"
if [ "$PUSH" = "true" ]; then
	if [ "$CURRENT_HOST" = "mercury" ]; then
		DEST="${SYNC_DIR}/${STAMPED_APK}"
		DEST_FINAL="${SYNC_DIR}/${FINAL_APK}"
		DEST_DEBUG="${SYNC_DIR}/${FINAL_DEBUG_APK}"
	else
		DEST="mercury:${SYNC_DIR}/${STAMPED_APK}"
		DEST_FINAL="mercury:${SYNC_DIR}/${FINAL_APK}"
		DEST_DEBUG="mercury:${SYNC_DIR}/${FINAL_DEBUG_APK}"
	fi
fi

rsync -ah -v --stats --info=progress2 "${PROJECT_DIR}/${FINAL_APK}" "$DEST"
rsync -ah -v --stats --info=progress2 "${PROJECT_DIR}/${FINAL_APK}" "$DEST_FINAL"

log "${RED}ADB INSTALL RELEASE.${NC}"

# adb install -r app/build/outputs/apk/release/app-release.apk
adb install -r "$APK_PATH"

# rsync -ah -v --stats --info=progress2 "${PROJECT_DIR}/${FINAL_DEBUG_APK}" "$DEST_DEBUG"
