#!/bin/sh
# The iOS shell's checks and its TestFlight release, as the Mac runner runs them (.forgejo/workflows/ios.yml) and
# as they run by hand:
#   shell/ios/ci.sh test      unit tests on a simulator, then a release build for devices, unsigned
#   shell/ios/ci.sh config    keyless diagnostic of publisher IDs and URLs (no Apple requests)
#   shell/ios/ci.sh preflight whether the release settings hold, asking App Store Connect only (nothing is uploaded):
#                             the key works, both identifiers have the expected App ID prefix and the app exists
#   shell/ios/ci.sh setup     idempotently enable the app/extension publisher capabilities through Apple
#   shell/ios/ci.sh archive   a signed archive with verified profiles, without uploading it
#   shell/ios/ci.sh release   a signed archive of a new build, uploaded to App Store Connect (TestFlight)
# A release needs, in the environment: ASC_KEY_ID, ASC_ISSUER_ID and ASC_KEY_P8 (an App Store Connect API key and
# the text of its .p8 file), IOS_TEAM_ID, IOS_APP_ID_PREFIX, IOS_BUNDLE_ID, ASSISTANT_PUSH_RELAY (https://…),
# ASSISTANT_PUBLISHER_SUPPORT_URL (public https:// contact) and BUILD_NUMBER. No installation
# FCM additionally requires ASSISTANT_PUSH_TRANSPORT=fcm, ASSISTANT_FCM_PROJECT_ID and the public
# Firebase/GoogleService-Info.plist matching IOS_BUNDLE_ID. APNs remains the default transport.
# origin is compiled into a release. BUILD_NUMBER is the unique CI run number (or an explicit manual number).
set -eu

cd "$(dirname "$0")"
PROJECT=AssistantShell.xcodeproj
SCHEME=AssistantShell
OUT=${IOS_BUILD_DIR:-$PWD/build}
SIMULATOR='AssistantShell CI'

# A simulator of the newest installed iOS on the newest numbered iPhone, made once under its own name: the ones a
# person keeps are never touched.
simulator() {
  udid=$(xcrun simctl list devices available | sed -n "s/^ *$SIMULATOR (\([0-9A-F-]*\)).*/\1/p" | head -n 1)
  if [ -z "$udid" ]; then
    runtime=$(xcrun simctl list runtimes available |
      sed -n 's/^iOS .* - \(com\.apple\.CoreSimulator\.SimRuntime\.iOS-[0-9-]*\)$/\1/p' | tail -n 1)
    device=$(xcrun simctl list devicetypes |
      sed -n 's/^iPhone [0-9][0-9]* (\(com\.apple\.CoreSimulator\.SimDeviceType\.iPhone-[0-9]*\))$/\1/p' | head -n 1)
    if [ -z "$runtime" ] || [ -z "$device" ]; then
      echo "no iOS simulator runtime: add one in Xcode > Settings > Components" >&2
      exit 1
    fi
    udid=$(xcrun simctl create "$SIMULATOR" "$device" "$runtime")
  fi
  echo "$udid"
}

check() {
  python3 -B -m unittest discover -s tools/tests -p 'test_*.py' -q
  udid=$(simulator)
  trap '
    xcrun simctl shutdown "$udid" >/dev/null 2>&1 || true
    xcrun simctl erase "$udid" >/dev/null 2>&1 || true
  ' EXIT  # the named CI device must not keep data between runs
  xcodebuild test -quiet -project "$PROJECT" -scheme "$SCHEME" -configuration Debug \
    -destination "platform=iOS Simulator,id=$udid" -derivedDataPath "$OUT/DerivedData" ASSISTANT_PUSH_TRANSPORT=apns
  # What only a release compiles: no loopback exception, universal connection and the notification extension.
  xcodebuild build -quiet -project "$PROJECT" -scheme "$SCHEME" -configuration Release \
    -destination 'generic/platform=iOS' -derivedDataPath "$OUT/DerivedData" CODE_SIGNING_ALLOWED=NO \
    ASSISTANT_ORIGIN="" ASSISTANT_PUSH_RELAY="${ASSISTANT_PUSH_RELAY:-}" \
    ASSISTANT_PUBLISHER_SUPPORT_URL="${ASSISTANT_PUBLISHER_SUPPORT_URL:-}" \
    ASSISTANT_BUNDLE_ID="${IOS_BUNDLE_ID:-dev.assistant.shell}" \
    ASSISTANT_PUSH_TRANSPORT="${ASSISTANT_PUSH_TRANSPORT:-apns}" ASSISTANT_FCM_PROJECT_ID="${ASSISTANT_FCM_PROJECT_ID:-}"
  echo "iOS shell: tests pass, the release builds"
}

# Validate before writing the ephemeral key; never print credentials.
settings() {
  python3 publisher_preflight.py validate
  private=$(mktemp -d)
  trap 'rm -rf "$private"' EXIT
  key="$private/AuthKey_$ASC_KEY_ID.p8"
  (umask 077; printf '%s\n' "$ASC_KEY_P8" > "$key")
}

preflight() {
  settings
  python3 publisher_preflight.py check --key-file "$key"
}

setup() {
  settings
  python3 publisher_preflight.py configure --key-file "$key"
}

verify() {
  verification_target=$1
  shift
  python3 verify_archive.py "$verification_target" --team "$IOS_TEAM_ID" --bundle "$IOS_BUNDLE_ID" \
    --prefix "$IOS_APP_ID_PREFIX" --relay "$ASSISTANT_PUSH_RELAY" \
    --support "$ASSISTANT_PUBLISHER_SUPPORT_URL" --transport "${ASSISTANT_PUSH_TRANSPORT:-apns}" \
    --fcm-project "${ASSISTANT_FCM_PROJECT_ID:-}" "$@"
}

archive() {
  build=${BUILD_NUMBER:-${GITHUB_RUN_NUMBER:-}}
  case "$build" in
    ''|*[!0-9]*) echo "BUILD_NUMBER must be a unique positive integer" >&2; exit 1 ;;
  esac
  [ "$build" -gt 0 ] || { echo "BUILD_NUMBER must be positive" >&2; exit 1; }
  preflight
  # Automatic signing through the API key: Xcode makes or fetches the certificates and profiles it needs.
  set -- -allowProvisioningUpdates -authenticationKeyPath "$key" -authenticationKeyID "$ASC_KEY_ID" \
    -authenticationKeyIssuerID "$ASC_ISSUER_ID"
  rm -rf "$OUT/AssistantShell.xcarchive" "$OUT/export"
  xcodebuild archive -quiet -project "$PROJECT" -scheme "$SCHEME" -configuration Release \
    -destination 'generic/platform=iOS' -archivePath "$OUT/AssistantShell.xcarchive" \
    -derivedDataPath "$OUT/DerivedData" "$@" \
    DEVELOPMENT_TEAM="$IOS_TEAM_ID" CODE_SIGN_STYLE=Automatic REGISTER_APP_GROUPS=YES ASSISTANT_BUNDLE_ID="$IOS_BUNDLE_ID" \
    ASSISTANT_ORIGIN="" ASSISTANT_PUSH_RELAY="$ASSISTANT_PUSH_RELAY" \
    ASSISTANT_PUBLISHER_SUPPORT_URL="$ASSISTANT_PUBLISHER_SUPPORT_URL" CURRENT_PROJECT_VERSION="$build" \
    ASSISTANT_PUSH_TRANSPORT="${ASSISTANT_PUSH_TRANSPORT:-apns}" ASSISTANT_FCM_PROJECT_ID="${ASSISTANT_FCM_PROJECT_ID:-}"
  # Xcode links ORT's static SPM artifact into a dynamic wrapper for iOS 16,
  # but copies its upstream iOS 15.1 plist. Correct the wrapper before export.
  verify "$OUT/AssistantShell.xcarchive" --prepare-frameworks
}

release() {
  archive
  set -- -allowProvisioningUpdates -authenticationKeyPath "$key" -authenticationKeyID "$ASC_KEY_ID" \
    -authenticationKeyIssuerID "$ASC_ISSUER_ID"
  cat > "$private/export.plist" <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
	<key>method</key>
	<string>app-store-connect</string>
	<key>destination</key>
	<string>export</string>
	<key>teamID</key>
	<string>$IOS_TEAM_ID</string>
	<key>signingStyle</key>
	<string>automatic</string>
	<key>manageAppVersionAndBuildNumber</key>
	<false/>
	<key>uploadSymbols</key>
	<true/>
</dict>
</plist>
EOF
  xcodebuild -exportArchive -quiet -archivePath "$OUT/AssistantShell.xcarchive" \
    -exportOptionsPlist "$private/export.plist" -exportPath "$OUT/export" "$@"
  set -- "$OUT/export/"*.ipa
  if [ "$#" -ne 1 ] || [ ! -f "$1" ]; then
    echo "expected exactly one exported IPA; upload stopped" >&2
    exit 1
  fi
  ipa=$1
  verify "$ipa" --distribution
  # Upload the exact verified file with the same ephemeral key; no account or app record is created.
  xcrun altool --upload-package "$ipa" --api-key "$ASC_KEY_ID" --api-issuer "$ASC_ISSUER_ID" \
    --p8-file-path "$key"
  echo "iOS shell: build $build is uploaded; TestFlight offers it once Apple has processed it"
}

case "${1:-}" in
  config) python3 publisher_preflight.py config ;;
  test) check ;;
  preflight) preflight ;;
  setup) setup ;;
  archive) archive; echo "iOS shell: signed archive verified; nothing uploaded" ;;
  release) release ;;
  *) echo "usage: $0 config|test|preflight|setup|archive|release" >&2; exit 2 ;;
esac
