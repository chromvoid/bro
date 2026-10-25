#!/usr/bin/env python3
"""Check publisher settings; optionally configure narrowly scoped Apple registrations."""

import argparse
import base64
from dataclasses import dataclass
from http.client import HTTPException
import ipaddress
import json
import os
from pathlib import Path
import plistlib
import re
import subprocess
import sys
import time
import unicodedata
from urllib.error import HTTPError, URLError
from urllib.parse import urlencode, urlsplit
from urllib.request import HTTPRedirectHandler, Request, build_opener


class PreflightError(ValueError):
    pass


class MutationUnconfirmed(PreflightError):
    """A create request needs a read before its outcome can be trusted."""


def required(environment, name):
    value = environment.get(name, "")
    if not isinstance(value, str) or not value:
        raise PreflightError(f"{name} is not set")
    return value


def identifier(environment, name):
    value = required(environment, name)
    if not re.fullmatch(r"[A-Z0-9]{10}", value):
        raise PreflightError(f"{name} must be a ten-character Apple identifier")
    return value


def https_url(value, name, *, origin=False):
    try:
        parts = urlsplit(value)
        host = parts.hostname
        valid = (len(value.encode("utf-8")) <= 2048
                 and not any(c.isspace() or unicodedata.category(c).startswith("C") for c in value)
                 and parts.scheme.lower() == "https" and bool(host)
                 and parts.username is None and parts.password is None
                 and (parts.port is None or 1 <= parts.port <= 65535)
                 and (not origin or (parts.path in ("", "/") and not parts.query and not parts.fragment)))
        if valid:
            try:
                ipaddress.ip_address(host)
            except ValueError:
                labels = host.rstrip(".").encode("idna").decode("ascii").split(".")
                valid = all(re.fullmatch(r"[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?", label) for label in labels)
    except (ValueError, UnicodeError):
        valid = False
    if not valid:
        raise PreflightError(f"{name} must be a valid HTTPS {'origin' if origin else 'contact URL'} without credentials")
    return value


@dataclass(frozen=True)
class Publisher:
    team: str
    bundle: str
    prefix: str
    relay: str
    support: str
    transport: str = "apns"
    fcm_project: str = ""


def configuration(environment):
    team = identifier(environment, "IOS_TEAM_ID")
    prefix = identifier(environment, "IOS_APP_ID_PREFIX")
    bundle = required(environment, "IOS_BUNDLE_ID")
    if len(bundle) > 255 or not re.fullmatch(r"[A-Za-z0-9-]+(?:\.[A-Za-z0-9-]+)+", bundle):
        raise PreflightError("IOS_BUNDLE_ID must be an explicit bundle identifier without wildcards")
    relay = https_url(required(environment, "ASSISTANT_PUSH_RELAY"), "ASSISTANT_PUSH_RELAY", origin=True)
    support = https_url(required(environment, "ASSISTANT_PUBLISHER_SUPPORT_URL"), "ASSISTANT_PUBLISHER_SUPPORT_URL")
    transport = environment.get("ASSISTANT_PUSH_TRANSPORT", "apns")
    if transport not in ("apns", "fcm"):
        raise PreflightError("ASSISTANT_PUSH_TRANSPORT must be apns or fcm")
    project = required(environment, "ASSISTANT_FCM_PROJECT_ID") if transport == "fcm" else ""
    if project and not re.fullmatch(r"[a-z][a-z0-9-]{4,28}[a-z0-9]", project):
        raise PreflightError("ASSISTANT_FCM_PROJECT_ID must be a Google Cloud project ID")
    return Publisher(team, bundle, prefix, relay, support, transport, project)


def firebase_configuration(publisher, path=None):
    if publisher.transport != "fcm":
        return
    path = path or Path(__file__).resolve().parent / "Firebase" / "GoogleService-Info.plist"
    try:
        with Path(path).open("rb") as file:
            config = plistlib.load(file)
    except (OSError, ValueError, plistlib.InvalidFileException):
        raise PreflightError("FCM requires a valid public GoogleService-Info.plist") from None
    if not isinstance(config, dict) or config.get("BUNDLE_ID") != publisher.bundle:
        raise PreflightError("Firebase app Bundle ID does not match IOS_BUNDLE_ID")
    if config.get("PROJECT_ID") != publisher.fcm_project:
        raise PreflightError("Firebase app project does not match ASSISTANT_FCM_PROJECT_ID")
    for name in ("GOOGLE_APP_ID", "GCM_SENDER_ID", "API_KEY"):
        if not isinstance(config.get(name), str) or not config[name] or "$(" in config[name]:
            raise PreflightError("Firebase public app configuration is incomplete")


def credentials(environment):
    key_id = identifier(environment, "ASC_KEY_ID")
    issuer = required(environment, "ASC_ISSUER_ID")
    if not re.fullmatch(r"[0-9a-fA-F]{8}(?:-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}", issuer):
        raise PreflightError("ASC_ISSUER_ID must be a UUID")
    required(environment, "ASC_KEY_P8")
    return key_id, issuer


def raw_signature(der):
    """Convert OpenSSL's DER ECDSA signature to JWT's fixed-width r || s."""
    def length(at):
        if at >= len(der):
            raise PreflightError("invalid signing output")
        value = der[at]
        if value < 128:
            return value, at + 1
        count = value & 127
        if count not in (1, 2) or at + 1 + count > len(der):
            raise PreflightError("invalid signing output")
        return int.from_bytes(der[at + 1:at + 1 + count], "big"), at + 1 + count

    if not der or der[0] != 48:
        raise PreflightError("invalid signing output")
    size, at = length(1)
    if at + size != len(der):
        raise PreflightError("invalid signing output")
    values = []
    for _ in range(2):
        if at >= len(der) or der[at] != 2:
            raise PreflightError("invalid signing output")
        size, at = length(at + 1)
        value = der[at:at + size]
        if not value or len(value) != size or value[0] & 128:
            raise PreflightError("invalid signing output")
        value = value.lstrip(b"\0")
        if len(value) > 32:
            raise PreflightError("invalid signing output")
        values.append(value.rjust(32, b"\0"))
        at += size
    if at != len(der):
        raise PreflightError("invalid signing output")
    return b"".join(values)


def token(key_file, key_id, issuer, *, now=None):
    now = int(time.time() if now is None else now)
    def b64(raw):
        return base64.urlsafe_b64encode(raw).rstrip(b"=")
    header = {"alg": "ES256", "kid": key_id, "typ": "JWT"}
    claims = {"iss": issuer, "iat": now, "exp": now + 600, "aud": "appstoreconnect-v1"}
    signed = b64(json.dumps(header, separators=(",", ":")).encode()) + b"." + b64(json.dumps(claims, separators=(",", ":")).encode())
    try:
        result = subprocess.run(["openssl", "dgst", "-sha256", "-sign", str(key_file)], input=signed,
                                capture_output=True, check=True)
    except (OSError, subprocess.CalledProcessError):
        raise PreflightError("ASC_KEY_P8 could not sign an App Store Connect token") from None
    return (signed + b"." + b64(raw_signature(result.stdout))).decode("ascii")


class NoRedirects(HTTPRedirectHandler):
    def redirect_request(self, request, fp, code, message, headers, new_url):
        return None


def apple_operation(path, mutation):
    """Static categories never include an identifier or query value."""
    resource = urlsplit(path).path
    if mutation:
        return "register-bundle-id" if resource == "/v1/bundleIds" else "enable-capability"
    if resource == "/v1/bundleIds":
        return "list-bundle-ids"
    if resource == "/v1/apps":
        return "list-app-records"
    if re.fullmatch(r"/v1/bundleIds/[^/]+/bundleIdCapabilities", resource):
        return "list-bundle-capabilities"
    return "read-apple-resource"


def apple_bad_request_diagnostic(error):
    """Keep only known structural labels from a bounded HTTP 400 response."""
    codes = {"PARAMETER_ERROR", "PARAMETER_ERROR.INVALID", "PARAMETER_ERROR.MISSING",
             "PARAMETER_ERROR.INVALID_LIMIT", "PARAMETER_ERROR.INVALID_FILTER", "PARAMETER_ERROR.INVALID_FIELD",
             "ENTITY_ERROR", "ENTITY_ERROR.ATTRIBUTE.INVALID", "ENTITY_ERROR.ATTRIBUTE.TYPE",
             "ENTITY_ERROR.ATTRIBUTE.REQUIRED", "ENTITY_ERROR.ATTRIBUTE.UNKNOWN",
             "ENTITY_ERROR.RELATIONSHIP.INVALID", "ENTITY_ERROR.RELATIONSHIP.REQUIRED",
             "ENTITY_ERROR.RELATIONSHIP.UNKNOWN", "ENTITY_ERROR.RELATIONSHIP.MISSING",
             "STATE_ERROR", "BAD_REQUEST", "JSON_PARSING_ERROR"}
    pointers = {"/data", "/data/type", "/data/attributes", "/data/attributes/name",
                "/data/attributes/platform", "/data/attributes/identifier", "/data/attributes/seedId",
                "/data/attributes/capabilityType", "/data/attributes/settings",
                "/data/relationships", "/data/relationships/bundleId", "/data/relationships/bundleId/data",
                "/data/relationships/bundleId/data/type", "/data/relationships/bundleId/data/id"}
    parameters = {"limit", "filter[identifier]", "filter[bundleId]", "fields[bundleIds]",
                  "fields[apps]", "fields[bundleIdCapabilities]", "id"}
    try:
        body = error.read(8_193)
        if len(body) > 8_192:
            return []
        response = json.loads(body)
    except (OSError, HTTPException, ValueError, UnicodeError, TypeError):
        return []
    if not isinstance(response, dict) or not isinstance(response.get("errors"), list):
        return []
    diagnostic = []
    for item in response["errors"][:3]:
        if not isinstance(item, dict):
            continue
        code = item.get("code")
        if isinstance(code, str) and code in codes:
            diagnostic.append("code=" + code)
        source = item.get("source")
        if not isinstance(source, dict):
            continue
        for field, allowed in (("pointer", pointers), ("parameter", parameters)):
            value = source.get(field)
            if isinstance(value, str) and value in allowed:
                diagnostic.append(field + "=" + value)
    return diagnostic


def apple_request(path, bearer, *, payload=None):
    if not isinstance(path, str) or not path.startswith("/v1/") or "#" in path:
        raise PreflightError("Invalid App Store Connect resource path")
    mutation = payload is not None
    headers = {"Authorization": "Bearer " + bearer, "Accept": "application/json"}
    if mutation:
        # Only these documented, additive operations are supported by setup.
        if path not in ("/v1/bundleIds", "/v1/bundleIdCapabilities"):
            raise PreflightError("Unsupported App Store Connect setup operation")
        headers["Content-Type"] = "application/json"
    request = Request("https://api.appstoreconnect.apple.com" + path,
                      headers=headers, method="POST" if mutation else "GET",
                      data=json.dumps(payload, separators=(",", ":")).encode() if mutation else None)
    response_error = MutationUnconfirmed if mutation else PreflightError
    try:
        with build_opener(NoRedirects()).open(request, timeout=20) as response:
            body = response.read(131_073)
            if len(body) > 131_072:
                raise response_error("App Store Connect response exceeded the preflight limit")
    except HTTPError as error:
        diagnostic = ["operation=" + apple_operation(path, mutation)]
        try:
            if error.code == 400:
                diagnostic.extend(apple_bad_request_diagnostic(error))
        finally:
            error.close()
        context = " [" + "; ".join(diagnostic) + "]"
        if error.code == 401:
            raise PreflightError("App Store Connect rejected the ASC key, issuer or signature") from None
        if error.code == 403:
            action = "configure" if mutation else "read"
            raise PreflightError(f"The ASC key cannot {action} app registrations; check its role and provisioning access") from None
        if mutation and (error.code in (409, 429) or error.code >= 500):
            raise MutationUnconfirmed(f"App Store Connect create answered HTTP {error.code}; a read is required") from None
        raise PreflightError(f"App Store Connect answered HTTP {error.code}" + context) from None
    except (URLError, TimeoutError, OSError):
        raise response_error("App Store Connect could not be reached") from None
    try:
        return json.loads(body)
    except (ValueError, UnicodeError):
        raise response_error("App Store Connect returned invalid JSON") from None


def apple_get(path, bearer):
    return apple_request(path, bearer)


def apple_post(path, bearer, payload):
    return apple_request(path, bearer, payload=payload)


def records(response):
    if not isinstance(response, dict) or not isinstance(response.get("data"), list):
        raise PreflightError("App Store Connect returned an invalid registration response")
    rows = response["data"]
    if any(not isinstance(row, dict) or not isinstance(row.get("attributes"), dict) for row in rows):
        raise PreflightError("App Store Connect returned an invalid registration response")
    return [row["attributes"] for row in rows]


def app_record(publisher, get):
    path = "/v1/apps?" + urlencode({"filter[bundleId]": publisher.bundle, "fields[apps]": "name,bundleId"})
    matches = [row for row in records(get(path)) if row.get("bundleId") == publisher.bundle]
    if len(matches) != 1:
        raise PreflightError("Exactly one App Store Connect app record must exist for IOS_BUNDLE_ID")


def preflight(publisher, get):
    results = []
    for label, bundle in (("main app", publisher.bundle), ("notification extension", publisher.bundle + ".notifications")):
        path = "/v1/bundleIds?" + urlencode({"filter[identifier]": bundle,
                                           "fields[bundleIds]": "identifier,platform,seedId"})
        matches = [row for row in records(get(path)) if row.get("identifier") == bundle
                   and row.get("platform") in ("IOS", "UNIVERSAL")]
        if not matches:
            raise PreflightError(f"The {label} iOS identifier is not registered")
        if len(matches) != 1 or matches[0].get("seedId") != publisher.prefix:
            raise PreflightError(f"The {label} identifier does not match IOS_APP_ID_PREFIX")
        results.append(f"iOS identifier checked: {label}")
    app_record(publisher, get)
    results.append("App Store Connect app record checked")
    return results


def bundle_resource(publisher, get, bundle, *, missing=False):
    path = "/v1/bundleIds?" + urlencode({"filter[identifier]": bundle,
                                       "fields[bundleIds]": "identifier,platform,seedId", "limit": 200})
    response = get(path)
    records(response)
    rows = [row for row in response["data"] if row["attributes"].get("identifier") == bundle]
    if not rows and missing:
        return None
    if len(rows) != 1:
        raise PreflightError("Apple setup requires exactly one matching registered identifier")
    row = rows[0]
    if row.get("type") != "bundleIds" or not isinstance(row.get("id"), str) or not re.fullmatch(r"[A-Za-z0-9_-]{1,128}", row["id"]):
        raise PreflightError("App Store Connect returned an invalid bundle resource")
    if row["attributes"].get("platform") not in ("IOS", "UNIVERSAL") or row["attributes"].get("seedId") != publisher.prefix:
        raise PreflightError("Apple setup identifier platform or prefix does not match publisher settings")
    return row["id"]


def capabilities(get, resource):
    # Apple currently rejects an explicit limit here despite its published schema.
    # Use the server default; a partial response still fails closed below.
    response = get(f"/v1/bundleIds/{resource}/bundleIdCapabilities")
    records(response)
    if any(row.get("type") != "bundleIdCapabilities" or not isinstance(row["attributes"].get("capabilityType"), str)
           for row in response["data"]):
        raise PreflightError("App Store Connect returned invalid bundle capabilities")
    links = response.get("links", {})
    if not isinstance(links, dict):
        raise PreflightError("App Store Connect returned invalid capability pagination")
    if links.get("next"):
        raise PreflightError("App Store Connect capabilities require an additional page; setup stopped")
    return {row["attributes"]["capabilityType"] for row in response["data"]}


def configure(publisher, get, post):
    """Add the extension and capability flags; group membership needs Xcode/portal."""
    main = bundle_resource(publisher, get, publisher.bundle)
    app_record(publisher, get)  # Never register an extension for an unknown app.
    extension_bundle = publisher.bundle + ".notifications"
    if len(extension_bundle) > 255:
        raise PreflightError("Notification extension identifier exceeds Apple's limit")
    extension = bundle_resource(publisher, get, extension_bundle, missing=True)
    main_capabilities = capabilities(get, main)
    extension_capabilities = capabilities(get, extension) if extension else set()
    results = []
    if extension is None:
        payload = {"data": {"type": "bundleIds", "attributes": {
            "name": "Assistant Notification Service", "identifier": extension_bundle,
            "platform": "IOS", "seedId": publisher.prefix}}}
        try:
            post("/v1/bundleIds", payload)
        except MutationUnconfirmed:
            pass  # A lost response or concurrent create is resolved by an exact read.
        extension = bundle_resource(publisher, get, extension_bundle, missing=True)
        if extension is None:
            raise PreflightError("Notification extension creation was not confirmed; no automatic create retry was sent")
        extension_capabilities = capabilities(get, extension)
        results.append("Notification extension registration confirmed")
    for label, resource, existing, wanted in (
            ("main app", main, main_capabilities, ("PUSH_NOTIFICATIONS", "APP_GROUPS")),
            ("notification extension", extension, extension_capabilities, ("APP_GROUPS",))):
        for capability in wanted:
            if capability in existing:
                continue
            payload = {"data": {"type": "bundleIdCapabilities", "attributes": {"capabilityType": capability},
                                "relationships": {"bundleId": {"data": {"type": "bundleIds", "id": resource}}}}}
            try:
                post("/v1/bundleIdCapabilities", payload)
            except MutationUnconfirmed:
                pass
            existing = capabilities(get, resource)
            if capability not in existing:
                raise PreflightError("Apple capability creation was not confirmed; no automatic create retry was sent")
            results.append(f"Capability confirmed for {label}: {capability}")
    # Later reads catch removals or unrelated changes before reporting success.
    preflight(publisher, get)
    if not {"PUSH_NOTIFICATIONS", "APP_GROUPS"}.issubset(capabilities(get, main)) or "APP_GROUPS" not in capabilities(get, extension):
        raise PreflightError("Required Apple capabilities changed during setup; verification failed")
    results.append("Apple identifiers and required capability flags checked")
    return results


def main(argv=None, *, environment=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("mode", choices=("config", "validate", "check", "configure"))
    parser.add_argument("--key-file", type=Path)
    args = parser.parse_args(argv)
    environment = os.environ if environment is None else environment
    try:
        publisher = configuration(environment)
        firebase_configuration(publisher)
        if args.mode == "config":
            for name in ("team", "bundle", "prefix", "relay", "support"):
                print(f"{name}: {getattr(publisher, name)}")
            print("Local publisher configuration checked; Apple credentials, registrations and signatures were not checked")
            return 0
        key_id, issuer = credentials(environment)
        if args.mode == "validate":
            print("Publisher settings present and structurally valid; Apple has not been contacted")
            return 0
        if args.key_file is None:
            raise PreflightError("--key-file is required for Apple registration checks")
        bearer = token(args.key_file, key_id, issuer)
        get = lambda path: apple_get(path, bearer)
        results = (configure(publisher, get, lambda path, payload: apple_post(path, bearer, payload))
                   if args.mode == "configure" else preflight(publisher, get))
        for result in results:
            print(result)
        if args.mode == "configure":
            print("App Group registration and membership are not checked by the ASC API; verify both signed profiles with Xcode or the Apple portal")
        else:
            print("Apple registrations checked read-only; team, capabilities, provisioning and push delivery still require archive/device verification")
        return 0
    except PreflightError as error:
        print(f"Publisher preflight failed: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
