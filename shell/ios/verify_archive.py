#!/usr/bin/env python3
"""Prepare an archive's framework metadata, then check it and its IPA.

An automatic archive may be development-signed. Only --distribution checks the
exported artifact's production APNs entitlement and App Store provisioning.
Provisioning metadata is a diagnostic gate, not a replacement for Apple's
validation or a real-device check (see Apple technote TN3125).
"""

from __future__ import annotations

import argparse
from dataclasses import dataclass
from datetime import datetime, timezone
import hashlib
from pathlib import Path, PurePosixPath
import plistlib
import re
import shutil
import stat
import struct
import subprocess
import sys
import tempfile
from urllib.parse import urlsplit
from xml.parsers.expat import ExpatError
import zipfile


APP_GROUPS = "com.apple.security.application-groups"
TEAM = "com.apple.developer.team-identifier"
ATTEST = "com.apple.developer.devicecheck.appattest-environment"


class VerificationError(ValueError):
    """The artifact cannot be used by the release workflow."""


@dataclass(frozen=True)
class Publisher:
    team: str
    bundle: str
    prefix: str
    relay: str
    support: str
    transport: str = "apns"
    fcm_project: str = ""

    def validate(self) -> None:
        require(bool(re.fullmatch(r"[A-Z0-9]{10}", self.team)), "invalid Team ID")
        require(bool(re.fullmatch(r"[A-Z0-9]{10}", self.prefix)), "invalid App ID prefix")
        require(bool(re.fullmatch(r"[A-Za-z0-9-]+(?:\.[A-Za-z0-9-]+)+", self.bundle)), "invalid bundle ID")
        require(self.transport in ("apns", "fcm"), "invalid push transport")
        if self.transport == "fcm":
            require(bool(re.fullmatch(r"[a-z][a-z0-9-]{4,28}[a-z0-9]", self.fcm_project)), "invalid Firebase project ID")
        for label, value, origin in [("relay", self.relay, True), ("support", self.support, False)]:
            try:
                parsed = urlsplit(value)
                valid = (parsed.scheme == "https" and bool(parsed.hostname)
                         and parsed.username is None and parsed.password is None
                         and (parsed.port is None or 1 <= parsed.port <= 65535)
                         and not any(c.isspace() or ord(c) < 32 or ord(c) == 127 for c in value)
                         and len(value.encode("utf-8")) <= 2048
                         and (not origin or (parsed.path in ("", "/") and not parsed.query and not parsed.fragment)))
            except ValueError:
                valid = False
            require(valid, f"invalid publisher {label} HTTPS {'origin' if origin else 'URL'}")

    @property
    def group(self) -> str:
        return f"group.{self.bundle}"

    @property
    def keychain(self) -> str:
        return f"{self.prefix}.{self.bundle}.shared"


def require(condition: bool, message: str) -> None:
    if not condition:
        raise VerificationError(message)


def authorized(claim: object, grant: object) -> bool:
    """Profile arrays are allowlists; only a final '*' is a string wildcard."""
    if isinstance(claim, list):
        return isinstance(grant, list) and all(any(authorized(item, allowed) for allowed in grant) for item in claim)
    if isinstance(grant, list):
        return any(authorized(claim, allowed) for allowed in grant)
    if isinstance(claim, str) and isinstance(grant, str):
        return claim == grant or (grant.endswith("*") and "*" not in grant[:-1] and claim.startswith(grant[:-1]))
    return type(claim) is type(grant) and claim == grant


def utc(value: object, label: str) -> datetime:
    require(isinstance(value, datetime), f"profile {label} is missing or invalid")
    assert isinstance(value, datetime)
    return value.replace(tzinfo=timezone.utc) if value.tzinfo is None else value.astimezone(timezone.utc)


def validate_bundle(info: dict, entitlements: dict, profile: dict, signature: dict,
                    certificate: bytes, publisher: Publisher, *, extension: bool,
                    distribution: bool, now: datetime | None = None) -> None:
    """Validate decoded, synthetic-testable metadata after codesign verification."""
    publisher.validate()
    bundle = publisher.bundle + (".notifications" if extension else "")
    label = "notification extension" if extension else "app"
    app_id = f"{publisher.prefix}.{bundle}"
    require(info.get("CFBundleIdentifier") == bundle, f"{label}: wrong bundle ID")
    require(info.get("CFBundlePackageType") == ("XPC!" if extension else "APPL"), f"{label}: wrong package type")
    require(signature.get("Identifier") == bundle, f"{label}: signature identifier mismatch")
    require(signature.get("TeamIdentifier") == publisher.team, f"{label}: signature Team ID mismatch")
    require(entitlements.get(TEAM) == publisher.team, f"{label}: entitlement Team ID mismatch")
    require(entitlements.get("application-identifier") == app_id, f"{label}: App ID prefix or identifier mismatch")
    require(profile.get("TeamIdentifier") == [publisher.team], f"{label}: provisioning Team ID mismatch")
    require(profile.get("ApplicationIdentifierPrefix") == [publisher.prefix], f"{label}: provisioning App ID prefix mismatch")
    require(isinstance(certificate, bytes) and bool(certificate)
            and certificate in profile.get("DeveloperCertificates", []), f"{label}: signing certificate is not authorized by profile")
    now = (now or datetime.now(timezone.utc)).astimezone(timezone.utc)
    require(utc(profile.get("CreationDate"), "creation date") <= now, f"{label}: provisioning profile is not yet valid")
    require(utc(profile.get("ExpirationDate"), "expiration date") > now, f"{label}: provisioning profile expired")
    require("iOS" in profile.get("Platform", []), f"{label}: provisioning profile is not for iOS")
    grants = profile.get("Entitlements")
    require(isinstance(grants, dict), f"{label}: missing provisioning entitlements")
    for name, claim in entitlements.items():
        # A missing false debugger claim cannot grant access to a debugger.
        if name == "get-task-allow" and claim is False and name not in grants:
            continue
        require(name in grants and authorized(claim, grants[name]), f"{label}: profile does not authorize {name}")
    require(entitlements.get(APP_GROUPS) == [publisher.group], f"{label}: shared App Group mismatch")
    keychain = entitlements.get("keychain-access-groups")
    require(isinstance(keychain, list) and publisher.keychain in keychain
            and set(keychain) <= {publisher.keychain, app_id}, f"{label}: shared Keychain group mismatch")
    require(info.get("AssistantAppGroup") == publisher.group, f"{label}: compiled App Group mismatch")
    require(info.get("AssistantKeychainGroup") == publisher.keychain, f"{label}: compiled Keychain group mismatch")
    require(isinstance(info.get("CFBundleShortVersionString"), str) and bool(info["CFBundleShortVersionString"]), f"{label}: missing version")
    require(bool(re.fullmatch(r"[1-9][0-9]*", str(info.get("CFBundleVersion", "")))), f"{label}: invalid build number")
    authority = signature.get("Authority", "")
    require(authority.startswith(("Apple Development:", "iPhone Developer:", "Apple Distribution:", "iPhone Distribution:")),
            f"{label}: not an Apple iOS signing identity")
    if distribution:
        require(authority.startswith(("Apple Distribution:", "iPhone Distribution:")), f"{label}: not distribution-signed")
        require(entitlements.get("get-task-allow", False) is False
                and grants.get("get-task-allow", False) is False, f"{label}: debugger access in distribution")
        require("ProvisionedDevices" not in profile and profile.get("ProvisionsAllDevices", False) is False,
                f"{label}: App Store provisioning is required")
    if extension:
        require(info.get("NSExtension", {}).get("NSExtensionPointIdentifier") == "com.apple.usernotifications.service",
                "notification extension: incorrect extension point")
    else:
        environment = entitlements.get("aps-environment")
        require(environment in ("development", "production"), "app: missing APNs entitlement")
        if distribution:
            require(environment == "production", "app: exported APNs environment must be production")
        require(entitlements.get(ATTEST) == "production", "app: App Attest environment must be production")
        require(info.get("AssistantAPNsEnvironment") == "production", "app: compiled APNs environment must be production")
        require(info.get("AssistantOrigin", "") == "", "app: installation origin was compiled into release")
        require(info.get("AssistantPushRelay") == publisher.relay, "app: publisher relay URL mismatch")
        require(info.get("AssistantPublisherSupport") == publisher.support, "app: publisher support URL mismatch")
        require(info.get("AssistantPushTransport", "apns") == publisher.transport, "app: push transport mismatch")
        require(not info.get("NSAppTransportSecurity"), "app: transport security exceptions in release")
        for name in ("NSCameraUsageDescription", "NSMicrophoneUsageDescription", "NSPhotoLibraryUsageDescription", "NSLocalNetworkUsageDescription",
                     "NSLocationWhenInUseUsageDescription", "NSLocationAlwaysAndWhenInUseUsageDescription"):
            require(isinstance(info.get(name), str) and bool(info[name].strip()), f"app: missing permission description {name}")


def validate_privacy(manifest: dict, *, extension: bool) -> None:
    require(manifest.get("NSPrivacyTracking") is False, "privacy manifest: tracking must be disabled")
    require(isinstance(manifest.get("NSPrivacyCollectedDataTypes"), list), "privacy manifest: missing data declarations")
    entries = manifest.get("NSPrivacyAccessedAPITypes")
    require(isinstance(entries, list), "privacy manifest: missing required reason APIs")
    defaults = [item for item in entries if isinstance(item, dict)
                and item.get("NSPrivacyAccessedAPIType") == "NSPrivacyAccessedAPICategoryUserDefaults"]
    reasons = {"1C8F.1"} if extension else {"1C8F.1", "CA92.1"}
    require(len(defaults) == 1 and reasons <= set(defaults[0].get("NSPrivacyAccessedAPITypeReasons", [])),
            "privacy manifest: missing UserDefaults reasons")


def validate_firebase(info: dict, config: dict | None, publisher: Publisher) -> None:
    if publisher.transport == "apns":
        require(config is None, "app: Firebase configuration bundled into an APNs build")
        return
    require(isinstance(config, dict), "app: missing Firebase public app configuration")
    assert isinstance(config, dict)
    require(config.get("BUNDLE_ID") == publisher.bundle, "app: Firebase Bundle ID mismatch")
    require(config.get("PROJECT_ID") == publisher.fcm_project
            and info.get("AssistantFirebaseProjectID") == publisher.fcm_project, "app: Firebase project mismatch")
    require(info.get("FirebaseAppDelegateProxyEnabled") is False, "app: Firebase delegate swizzling must be disabled")
    require(info.get("FirebaseMessagingAutoInitEnabled") is False, "app: Firebase token auto-init must be disabled")
    require(info.get("FirebaseMessagingInstallationIdEnabled") is False, "app: Firebase token transport must be explicit")
    for name in ("GOOGLE_APP_ID", "GCM_SENDER_ID", "API_KEY"):
        require(isinstance(config.get(name), str) and bool(config[name]) and "$(" not in config[name],
                "app: incomplete public Firebase configuration")


def plist(data: bytes, label: str) -> dict:
    try:
        value = plistlib.loads(data)
    except (ValueError, plistlib.InvalidFileException, OverflowError, ExpatError) as error:
        raise VerificationError(f"{label}: invalid property list") from error
    require(isinstance(value, dict), f"{label}: property list must be a dictionary")
    return value


def run(arguments: list[str], label: str) -> subprocess.CompletedProcess:
    try:
        result = subprocess.run(arguments, capture_output=True, check=False)
    except OSError as error:
        raise VerificationError(f"{label}: required macOS tool is unavailable") from error
    # Decoded provisioning data can include device IDs. Only codesign's verify
    # operation reports public signature errors, never provisioning contents.
    if result.returncode != 0:
        message = f"{label}: verification command failed"
        if arguments[:2] == ["codesign", "--verify"]:
            diagnostic = result.stderr.decode("utf-8", "replace")
            diagnostic = diagnostic.replace(arguments[-1], Path(arguments[-1]).name)
            diagnostic = " ".join("".join(c for c in diagnostic if c.isprintable() or c.isspace()).split())[:600]
            if diagnostic:
                message += f" ({diagnostic})"
        raise VerificationError(message)
    return result


def signed_metadata(bundle: Path, publisher: Publisher) -> tuple[dict, dict, dict, bytes]:
    # codesign treats -R as a file path unless the inline expression starts with =.
    requirement = f'=anchor apple generic and certificate leaf[subject.OU] = "{publisher.team}"'
    run(["codesign", "--verify", "--deep", "--strict", "--verbose=2", "-R", requirement, str(bundle)], f"{bundle.name}: signature")
    details = run(["codesign", "--display", "--verbose=4", str(bundle)], f"{bundle.name}: signature metadata")
    signature = {}
    for line in (details.stdout + details.stderr).decode("utf-8", "replace").splitlines():
        if "=" in line:
            name, value = line.split("=", 1)
            signature.setdefault(name, value)
    claims = run(["codesign", "--display", "--entitlements", "-", "--xml", str(bundle)], f"{bundle.name}: entitlements")
    profile_path = bundle / "embedded.mobileprovision"
    require(profile_path.is_file(), f"{bundle.name}: missing embedded provisioning profile")
    decoded = run(["security", "cms", "-D", "-i", str(profile_path)], f"{bundle.name}: provisioning profile")
    # These are public certificates sealed into the artifact, never private keys.
    with tempfile.TemporaryDirectory(prefix="assistant-public-cert-") as directory:
        certificate_prefix = str(Path(directory) / "certificate")
        # Attach the optional prefix to the flag; a separate argument is treated as code.
        run(["codesign", "--display", f"--extract-certificates={certificate_prefix}", str(bundle)], f"{bundle.name}: public signing certificate")
        certificate_path = Path(certificate_prefix + "0")
        require(certificate_path.is_file(), f"{bundle.name}: signing certificate is missing")
        certificate = certificate_path.read_bytes()
    return (plist(claims.stdout, "signed entitlements"), plist(decoded.stdout, "provisioning profile"), signature, certificate)


def binary_slices(path: Path) -> list[tuple[int, int]]:
    """Read the universal binary container, including fat static archives."""
    size = path.stat().st_size
    with path.open("rb") as source:
        header = source.read(8)
        formats = {b"\xca\xfe\xba\xbe": (">", "IIIII"), b"\xbe\xba\xfe\xca": ("<", "IIIII"),
                   b"\xca\xfe\xba\xbf": (">", "IIQQII"), b"\xbf\xba\xfe\xca": ("<", "IIQQII")}
        if header[:4] not in formats:
            return [(0, size)]
        require(len(header) == 8, "truncated universal binary header")
        order, entry = formats[header[:4]]
        count = struct.unpack(order + "I", header[4:])[0]
        require(1 <= count <= 32, "invalid universal binary slice count")
        length = struct.calcsize(order + entry)
        table_end = 8 + count * length
        slices = []
        for _ in range(count):
            raw = source.read(length)
            require(len(raw) == length, "truncated universal binary table")
            fields = struct.unpack(order + entry, raw)
            offset, span = fields[2:4]
            require(offset >= table_end and span >= 8 and offset + span <= size,
                    "invalid universal binary slice bounds")
            require(all(offset + span <= start or offset >= start + extent for start, extent in slices),
                    "overlapping universal binary slices")
            slices.append((offset, span))
        return slices


def is_static_library(path: Path) -> bool:
    slices = binary_slices(path)
    with path.open("rb") as source:
        for offset, _ in slices:
            source.seek(offset)
            if source.read(8) != b"!<arch>\n":
                return False
    return True


def macho_minimum(path: Path) -> tuple[int, int, int]:
    versions = []
    magic = {b"\xcf\xfa\xed\xfe": ("<", 32), b"\xfe\xed\xfa\xcf": (">", 32),
             b"\xce\xfa\xed\xfe": ("<", 28), b"\xfe\xed\xfa\xce": (">", 28)}
    with path.open("rb") as source:
        for offset, span in binary_slices(path):
            source.seek(offset)
            header = source.read(32)
            require(header[:4] in magic, f"{path.name}: unsupported Mach-O header {header[:8].hex()}")
            order, header_size = magic[header[:4]]
            require(len(header) >= header_size, "truncated Mach-O header")
            require(struct.unpack_from(order + "I", header, 12)[0] == 6,
                    "embedded framework executable is not a dynamic library")
            count, length = struct.unpack_from(order + "II", header, 16)
            require(count <= 65536 and length <= span - header_size, "invalid Mach-O load commands")
            source.seek(offset + header_size)
            commands = source.read(length)
            at = 0
            found = False
            for _ in range(count):
                require(at + 8 <= len(commands), "truncated Mach-O load command")
                command, size = struct.unpack_from(order + "II", commands, at)
                require(size >= 8 and at + size <= len(commands), "invalid Mach-O load command bounds")
                if command == 0x32:
                    require(size >= 24, "truncated Mach-O build version")
                    platform, minimum = struct.unpack_from(order + "II", commands, at + 8)
                    require(platform == 2, "framework has a non-iOS platform")
                    versions.append((minimum >> 16, (minimum >> 8) & 255, minimum & 255))
                    found = True
                elif command == 0x25:
                    require(size >= 16, "truncated Mach-O iOS minimum")
                    minimum = struct.unpack_from(order + "I", commands, at + 8)[0]
                    versions.append((minimum >> 16, (minimum >> 8) & 255, minimum & 255))
                    found = True
                at += size
            require(found and at == length, "missing or invalid Mach-O iOS minimum")
    return max(versions)


def os_version(value: object) -> tuple[int, int, int]:
    require(isinstance(value, str) and bool(re.fullmatch(r"[0-9]+(?:\.[0-9]+){0,2}", value)),
            "missing or invalid MinimumOSVersion")
    parts = tuple(int(p) for p in value.split("."))
    return parts + (0,) * (3 - len(parts))


def validate_framework_minimum(info: dict, minimum: tuple[int, int, int], app_minimum: tuple[int, int, int]) -> None:
    declared = os_version(info.get("MinimumOSVersion"))
    require(minimum <= declared, f"framework plist minimum {declared} is below Mach-O minimum {minimum}")
    require(declared <= app_minimum, f"framework minimum {declared} exceeds app minimum {app_minimum}")


def embedded_static_frameworks(app: Path) -> list[Path]:
    result = []
    frameworks = sorted(app.rglob("*.framework"))
    print(f"Embedded framework inventory: {[p.name for p in frameworks]}")
    for framework in frameworks:
        require(not framework.is_symlink(), "framework bundle must not be a symlink")
        metadata = framework / "Info.plist"
        require(metadata.is_file() and not metadata.is_symlink(), f"{framework.name}: unsafe Info.plist")
        info = plist(metadata.read_bytes(), f"{framework.name}: Info.plist")
        name = info.get("CFBundleExecutable")
        require(isinstance(name, str) and bool(name) and Path(name).name == name and name not in (".", ".."),
                f"{framework.name}: invalid executable name")
        executable = framework / name
        require(executable.is_file() and not executable.is_symlink(), f"{framework.name}: missing framework executable")
        if is_static_library(executable):
            result.append(framework)
    return result


def prepare_frameworks(app: Path, publisher: Publisher) -> None:
    """Correct Xcode's ORT wrapper metadata before export; preserve its linked code."""
    require(not embedded_static_frameworks(app), "static library embedded as a runtime framework")
    app_info = plist((app / "Info.plist").read_bytes(), "app Info.plist")
    app_minimum = os_version(app_info.get("MinimumOSVersion"))
    updates = []
    for framework in sorted(app.rglob("*.framework")):
        metadata = framework / "Info.plist"
        raw = metadata.read_bytes()
        info = plist(raw, f"{framework.name}: Info.plist")
        minimum = macho_minimum(framework / info["CFBundleExecutable"])
        declared = os_version(info.get("MinimumOSVersion"))
        if declared < minimum:
            require(framework == app / "Frameworks" / "onnxruntime.framework",
                    "unexpected framework minimum mismatch; preparation stopped")
            require(minimum <= app_minimum, "ONNX Runtime requires a newer iOS than the app")
            info["MinimumOSVersion"] = ".".join(str(p) for p in minimum)
            updates.append((framework, metadata, raw, info))
        else:
            validate_framework_minimum(info, minimum, app_minimum)
    if not updates:
        return
    # Authenticate the original sealed bundle before changing any file. Reuse its
    # authorized archive identity; export will apply the distribution identity.
    entitlements, profile, signature, certificate = signed_metadata(app, publisher)
    validate_bundle(app_info, entitlements, profile, signature, certificate, publisher,
                    extension=False, distribution=False)
    identity = hashlib.sha1(certificate).hexdigest().upper()
    for framework, metadata, raw, info in updates:
        plist_format = plistlib.FMT_BINARY if raw.startswith(b"bplist00") else plistlib.FMT_XML
        metadata.write_bytes(plistlib.dumps(info, fmt=plist_format))
        run(["codesign", "--force", "--sign", identity, "--preserve-metadata=identifier,entitlements,flags",
             "--generate-entitlement-der", str(framework)], "framework: signing prepared metadata")
        print(f"Prepared {framework.name} MinimumOSVersion={info['MinimumOSVersion']}; linked code retained")
    run(["codesign", "--force", "--sign", identity, "--preserve-metadata=identifier,entitlements,flags",
         "--generate-entitlement-der", str(app)], "app: signing prepared archive")


def verify_app(app: Path, publisher: Publisher, *, distribution: bool) -> None:
    require(app.is_dir(), "missing app bundle")
    require(not embedded_static_frameworks(app), "static library embedded as a runtime framework")
    app_info = plist((app / "Info.plist").read_bytes(), "app Info.plist")
    for framework in sorted(app.rglob("*.framework")):
        info = plist((framework / "Info.plist").read_bytes(), f"{framework.name}: Info.plist")
        minimum = macho_minimum(framework / info["CFBundleExecutable"])
        print(f"Framework metadata: {framework.name}, plist minimum={info.get('MinimumOSVersion')}, Mach-O minimum={minimum}")
        validate_framework_minimum(info, minimum, os_version(app_info.get("MinimumOSVersion")))
    extensions = list((app / "PlugIns").glob("*.appex"))
    require(len(extensions) == 1, "exactly one notification service extension is required")
    infos = []
    for bundle, extension in [(app, False), (extensions[0], True)]:
        info = plist((bundle / "Info.plist").read_bytes(), f"{bundle.name}: Info.plist")
        entitlements, profile, signature, certificate = signed_metadata(bundle, publisher)
        validate_bundle(info, entitlements, profile, signature, certificate, publisher,
                        extension=extension, distribution=distribution)
        validate_privacy(plist((bundle / "PrivacyInfo.xcprivacy").read_bytes(), f"{bundle.name}: privacy manifest"), extension=extension)
        infos.append(info)
    for name in ("CFBundleVersion", "CFBundleShortVersionString"):
        require(infos[0].get(name) == infos[1].get(name), f"app and notification extension {name} mismatch")
    firebase = app / "GoogleService-Info.plist"
    validate_firebase(infos[0], plist(firebase.read_bytes(), "Firebase configuration") if firebase.is_file() else None, publisher)
    icons = infos[0].get("CFBundleIcons", {}).get("CFBundlePrimaryIcon", {})
    require(icons.get("CFBundleIconName") == "AppIcon" and bool(icons.get("CFBundleIconFiles")), "app: compiled AppIcon is missing")
    assets = app / "Assets.car"
    require(assets.is_file() and assets.stat().st_size > 0, "app: compiled asset catalog is missing")


def extract_ipa(ipa: Path, destination: Path) -> None:
    with zipfile.ZipFile(ipa) as archive:
        for entry in archive.infolist():
            name = PurePosixPath(entry.filename)
            require(not name.is_absolute() and ".." not in name.parts and "\\" not in entry.filename,
                    "IPA contains an unsafe archive path")
            mode = entry.external_attr >> 16
            require(not stat.S_ISLNK(mode), "IPA contains an unsupported symbolic link")
            output = destination.joinpath(*name.parts)
            if entry.is_dir():
                output.mkdir(parents=True, exist_ok=True)
            else:
                output.parent.mkdir(parents=True, exist_ok=True)
                with archive.open(entry) as source, output.open("wb") as target:
                    shutil.copyfileobj(source, target)
                if mode:
                    output.chmod(mode & 0o777)


def verify_artifact(path: Path, publisher: Publisher, *, distribution: bool) -> None:
    publisher.validate()
    if path.suffix == ".ipa":
        require(distribution, "exported IPA requires --distribution")
        with tempfile.TemporaryDirectory(prefix="assistant-ipa-verification-") as directory:
            destination = Path(directory)
            extract_ipa(path, destination)
            apps = list((destination / "Payload").glob("*.app"))
            require(len(apps) == 1, "IPA must contain exactly one app")
            verify_app(apps[0], publisher, distribution=True)
    elif path.suffix == ".app":
        verify_app(path, publisher, distribution=distribution)
    else:
        require(path.is_dir() and path.suffix == ".xcarchive", "expected an .xcarchive, .app or exported .ipa")
        apps = list((path / "Products" / "Applications").glob("*.app"))
        require(len(apps) == 1, "archive must contain exactly one app")
        verify_app(apps[0], publisher, distribution=distribution)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("artifact", type=Path)
    for name in ("team", "bundle", "prefix", "relay", "support"):
        parser.add_argument(f"--{name}", required=True)
    parser.add_argument("--transport", choices=("apns", "fcm"), default="apns")
    parser.add_argument("--fcm-project", default="")
    parser.add_argument("--distribution", action="store_true", help="require App Store signatures/profiles and production APNs on the exact exported IPA")
    parser.add_argument("--prepare-frameworks", action="store_true", help="prepare a generated archive before verification; never modify an exported IPA")
    args = parser.parse_args()
    try:
        publisher = Publisher(args.team, args.bundle, args.prefix, args.relay, args.support, args.transport, args.fcm_project)
        publisher.validate()
        if args.prepare_frameworks:
            require(args.artifact.suffix == ".xcarchive" and not args.distribution, "preparation requires a generated archive")
            apps = list((args.artifact / "Products" / "Applications").glob("*.app"))
            require(len(apps) == 1, "archive must contain exactly one app")
            prepare_frameworks(apps[0], publisher)
        verify_artifact(args.artifact, publisher, distribution=args.distribution)
    except (VerificationError, OSError, zipfile.BadZipFile) as error:
        print(f"iOS artifact verification failed: {error}", file=sys.stderr)
        return 1
    stage = "exported production distribution" if args.distribution else "signed archive (production distribution still requires export verification)"
    print(f"iOS artifact verified: {stage}; app and notification extension")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
