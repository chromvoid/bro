#!/usr/bin/env python3
"""Reconcile public App Store metadata without submitting an app for review."""

import argparse
import hashlib
from http.client import HTTPException
import json
import os
from pathlib import Path
import re
import struct
import sys
import time
from urllib.error import HTTPError, URLError
from urllib.parse import urlencode, urlsplit
from urllib.request import Request, build_opener

from publisher_preflight import NoRedirects, PreflightError, required, token


ORIGIN = "https://api.appstoreconnect.apple.com"
EDITABLE = ("PREPARE_FOR_SUBMISSION", "DEVELOPER_REJECTED", "REJECTED",
            "METADATA_REJECTED", "INVALID_BINARY", "READY_FOR_REVIEW")
# Apple's OpenAPI 4.6 ScreenshotDisplayType enum; dimensions from the supplied store assets.
SCREENSHOT_SETS = {
    "APP_IPHONE_67": ("iphone-6.9-", (1320, 2868)),
    "APP_IPAD_PRO_3GEN_129": ("ipad-13-", (2064, 2752)),
}
REVIEW_FIELDS = "notes,demoAccountRequired,demoAccountName"


class ListingError(ValueError):
    pass


def public_detail(value):
    """Even an upload error must not expose a signed URL's query string."""
    return re.sub(r'https?://[^\s"<>]+', lambda m: m[0].split("?", 1)[0], str(value)).replace("\n", " ").replace("\r", " ")


class Api:
    def __init__(self, key_file, environment):
        self.key_file = key_file
        self.key_id = required(environment, "ASC_KEY_ID")
        self.issuer = required(environment, "ASC_ISSUER_ID")
        self.bearer = ""
        self.signed_at = 0

    def request(self, method, path, payload=None, *, missing_ok=False):
        if path.startswith(ORIGIN + "/v1/"):
            path = path[len(ORIGIN):]
        parts = urlsplit(path)
        if parts.scheme or parts.netloc or not path.startswith("/v1/") or parts.fragment:
            raise ListingError("Invalid App Store Connect resource path")
        if {"reviewSubmissions", "appStoreVersionSubmissions"}.intersection(parts.path.split("/")):
            raise ListingError("Submission endpoints are forbidden")
        if time.monotonic() - self.signed_at >= 480 or not self.bearer:
            self.bearer = token(self.key_file, self.key_id, self.issuer)
            self.signed_at = time.monotonic()
        headers = {"Authorization": "Bearer " + self.bearer, "Accept": "application/json"}
        if payload is not None:
            headers["Content-Type"] = "application/json"
        request = Request(ORIGIN + path, method=method, headers=headers,
                          data=json.dumps(payload).encode() if payload is not None else None)
        kind = parts.path.split("/")[2]
        if kind == "appStoreReviewDetails" and method != "GET":
            # Apple returns the whole resource on writes. Do not read that body: it can
            # contain the owner's password and contact details after a notes update.
            self.send(request, kind, discard_response=True)
            return payload
        return self.send(request, kind, json_response=True, missing_ok=missing_ok)

    def send(self, request, kind, *, json_response=False, missing_ok=False, discard_response=False):
        try:
            with build_opener(NoRedirects()).open(request, timeout=45) as response:
                body = b"" if discard_response else response.read()
        except HTTPError as error:
            status = error.code
            try:
                body = json.loads(error.read())
                details = "; ".join(f"{e.get('code', 'UNKNOWN')}: {e.get('detail', e.get('title', ''))}"
                                    for e in body.get("errors", []))
            except (ValueError, AttributeError):
                details = "Apple returned a non-JSON error"
            finally:
                error.close()
            if missing_ok and status == 404:
                return None
            # Do not expose credentials even if Apple echoes them in a diagnostic.
            details = public_detail(details).replace(self.bearer, "[redacted]") if self.bearer else public_detail(details)
            raise ListingError(f"HTTP {status} {request.method} {kind}: {details}") from None
        except (URLError, OSError, HTTPException):
            raise ListingError(f"HTTP unavailable {request.method} {kind}: transport failed") from None
        if json_response and body:
            return json.loads(body)
        return {}

    def upload(self, operation, content):
        parts = urlsplit(operation["url"])
        if operation["method"] != "PUT" or parts.scheme != "https" or not parts.netloc:
            raise ListingError("Invalid appScreenshots upload operation")
        offset, length = operation["offset"], operation["length"]
        if offset < 0 or length <= 0 or offset + length > len(content):
            raise ListingError("Invalid appScreenshots upload byte range")
        headers = {h["name"]: h["value"] for h in operation["requestHeaders"]}
        request = Request(operation["url"], method="PUT", headers=headers,
                          data=content[offset:offset + length])
        self.send(request, "appScreenshots")


def collection(api, path, **query):
    if query:
        path += "?" + urlencode(query)
    items = []
    while path:
        response = api.request("GET", path)
        items.extend(response["data"])
        path = response.get("links", {}).get("next")
    return items


def one(items, label):
    if len(items) != 1:
        raise ListingError(f"Expected one {label}; found {len(items)}")
    return items[0]


def editable(items, label):
    for state in EDITABLE:
        matches = [item for item in items if state in (
            item["attributes"].get("state"), item["attributes"].get("appVersionState"),
            item["attributes"].get("appStoreState"))]
        if matches:
            return one(matches, label)
    return None


def reference(kind, identifier):
    return {"type": kind, "id": identifier}


def relationship(kind, identifier):
    return {"data": reference(kind, identifier)}


def load_listing(path):
    listing = json.loads(Path(path).read_text())
    limits = {"name": 30, "subtitle": 30, "keywords": 100, "promotionalText": 170, "description": 4000}
    for locale, texts in listing["localizations"].items():
        for field, limit in limits.items():
            if not isinstance(texts[field], str) or not 0 < len(texts[field]) <= limit:
                raise ListingError(f"{locale}.{field} must contain 1–{limit} characters")
    for field in ("supportUrl", "marketingUrl", "privacyPolicyUrl"):
        url = urlsplit(listing[field])
        if url.scheme != "https" or not url.hostname or url.username or url.password:
            raise ListingError(f"{field} must be an HTTPS URL without credentials")
    if listing["primaryLocale"] not in listing["localizations"]:
        raise ListingError("primaryLocale is missing from localizations")
    return listing


def screenshots(directory):
    result = {}
    for display, (prefix, size) in SCREENSHOT_SETS.items():
        paths = list(Path(directory).glob(prefix + "*.png"))
        paths.sort(key=lambda path: int(path.name[len(prefix):].split("-", 1)[0]))
        if not 1 <= len(paths) <= 10:
            raise ListingError(f"{display} requires 1–10 PNG files; found {len(paths)}")
        result[display] = []
        for path in paths:
            content = path.read_bytes()
            if content[:16] != b"\x89PNG\r\n\x1a\n\x00\x00\x00\rIHDR" or len(content) < 24:
                raise ListingError(f"{path.name}: invalid PNG header")
            actual = struct.unpack(">II", content[16:24])
            if actual != size:
                raise ListingError(f"{path.name}: expected {size[0]}×{size[1]}, got {actual[0]}×{actual[1]}")
            result[display].append({"path": path, "content": content,
                                    "checksum": hashlib.md5(content).hexdigest()})
    return result


def snapshot(api, listing, bundle, build_number, *, output=None):
    """Finish all prerequisite reads before allowing the first write."""
    app = one(collection(api, "/v1/apps", **{"filter[bundleId]": bundle,
              "fields[apps]": "bundleId,primaryLocale"}), "app with the requested bundle ID")
    if app["attributes"]["bundleId"] != bundle:
        raise ListingError("App bundle ID did not match")
    app_path = f"/v1/apps/{app['id']}"
    info = editable(collection(api, app_path + "/appInfos"), "editable app info")
    if info is None:
        raise ListingError("No editable appInfos record")
    info_path = f"/v1/appInfos/{info['id']}"
    age = api.request("GET", info_path + "/ageRatingDeclaration")["data"]
    if output:
        output("Current ageRatingDeclaration attributes: "
               + json.dumps(age["attributes"], ensure_ascii=False, sort_keys=True))
    unknown = listing["ageRatingDeclaration"].keys() - age["attributes"].keys()
    if unknown:
        raise ListingError("Unknown age rating attributes: " + ", ".join(sorted(unknown))
                           + "; available keys: " + ", ".join(sorted(age["attributes"])))
    categories = {name: api.request("GET", info_path + "/relationships/" + name)["data"]
                  for name in ("primaryCategory", "secondaryCategory")}
    info_locales = {item["attributes"]["locale"]: item
                    for item in collection(api, info_path + "/appInfoLocalizations")}
    versions = collection(api, app_path + "/appStoreVersions", **{"filter[platform]": "IOS"})
    version = editable(versions, "editable iOS version")
    if version is None and any(v["attributes"].get("versionString") == listing["versionString"]
                               for v in versions):
        raise ListingError("The requested version already exists but is not editable")
    version_locales, sets, review, attached_build = {}, {}, None, None
    if version:
        version_path = f"/v1/appStoreVersions/{version['id']}"
        version_locales = {item["attributes"]["locale"]: item
                           for item in collection(api, version_path + "/appStoreVersionLocalizations")}
        attached_build = api.request("GET", version_path + "/relationships/build")["data"]
        # Sparse fields are essential: passwords and reviewer contacts must never be read.
        review_response = api.request("GET", version_path + "/appStoreReviewDetail?" + urlencode({
            "fields[appStoreReviewDetails]": REVIEW_FIELDS}), missing_ok=True)
        review = review_response["data"] if review_response else None
        primary = version_locales.get(listing["primaryLocale"])
        if primary:
            for item in collection(api, f"/v1/appStoreVersionLocalizations/{primary['id']}/appScreenshotSets"):
                display = item["attributes"]["screenshotDisplayType"]
                if display in SCREENSHOT_SETS:
                    if display in sets:
                        raise ListingError(f"Duplicate screenshot set: {display}")
                    sets[display] = (item, collection(api, f"/v1/appScreenshotSets/{item['id']}/appScreenshots"))
    builds = collection(api, "/v1/builds", **{"filter[app]": app["id"], "filter[version]": build_number,
                        "fields[builds]": "version,processingState,usesNonExemptEncryption"})
    valid = [b for b in builds if b["attributes"]["version"] == build_number
             and b["attributes"]["processingState"] == "VALID"]
    if not valid:
        states = ", ".join(b["attributes"]["processingState"] for b in builds) or "not found"
        raise ListingError(f"Build {build_number} must be VALID; state: {states}")
    build = one(valid, "VALID build with the requested number")
    group = one([g for g in collection(api, app_path + "/betaGroups")
                 if g["attributes"]["name"] == listing["betaGroup"]], "named beta group")
    group_has_build = group["attributes"]["hasAccessToAllBuilds"] or any(
        b["id"] == build["id"] for b in collection(api, f"/v1/betaGroups/{group['id']}/relationships/builds"))
    return {"app": app, "info": info, "age": age, "categories": categories,
            "info_locales": info_locales, "version": version, "version_locales": version_locales,
            "review": review, "attached_build": attached_build, "build": build,
            "group": group, "group_has_build": group_has_build, "sets": sets}


class Reconciler:
    def __init__(self, api, apply):
        self.api, self.apply = api, apply
        self.changes = []

    def upsert(self, kind, current, attributes, *, relationships=None, label=None):
        label = label or kind
        old = current["attributes"] if current else {}
        changed = {key: value for key, value in attributes.items() if key not in old or old[key] != value}
        for key, value in changed.items():
            self.changes.append(f"{label}.{key}: {json.dumps(old.get(key), ensure_ascii=False)} -> "
                                + json.dumps(value, ensure_ascii=False))
        if current and not changed:
            return current
        if not current:
            self.changes.append(f"{label}: create")
        if not self.apply:
            return current or {"type": kind, "id": f"new-{label}", "attributes": attributes}
        data = {"type": kind, "attributes": changed}
        if current:
            data["id"] = current["id"]
            method, path = "PATCH", f"/v1/{kind}/{current['id']}"
        else:
            data["relationships"] = relationships or {}
            method, path = "POST", f"/v1/{kind}"
        return self.api.request(method, path, {"data": data})["data"]

    def link(self, kind, parent, name, current, desired, *, method="PATCH"):
        if current == desired:
            return
        self.changes.append(f"{kind}.{name}: {json.dumps(current)} -> {json.dumps(desired)}")
        if self.apply:
            self.api.request(method, f"/v1/{kind}/{parent['id']}/relationships/{name}", {"data": desired})

    def replace_screenshots(self, display, current, files, locale):
        screenshot_set, existing = current if current else (None, [])
        checksums = [s["attributes"].get("sourceFileChecksum") for s in existing]
        desired = [f["checksum"] for f in files]
        if screenshot_set and checksums == desired:
            return
        self.changes.append(f"screenshots.{display}: {json.dumps(checksums)} -> {json.dumps(desired)}")
        if not self.apply:
            return
        if screenshot_set is None:
            screenshot_set = self.upsert("appScreenshotSets", None, {"screenshotDisplayType": display},
                relationships={"appStoreVersionLocalization": relationship("appStoreVersionLocalizations", locale["id"])})
        for screenshot in existing:
            self.api.request("DELETE", f"/v1/appScreenshots/{screenshot['id']}")
        uploaded = []
        for file in files:
            screenshot = self.upsert("appScreenshots", None,
                {"fileName": file["path"].name, "fileSize": len(file["content"])},
                relationships={"appScreenshotSet": relationship("appScreenshotSets", screenshot_set["id"])})
            for operation in screenshot["attributes"]["uploadOperations"]:
                self.api.upload(operation, file["content"])
            path = f"/v1/appScreenshots/{screenshot['id']}"
            self.api.request("PATCH", path, {"data": {**reference("appScreenshots", screenshot["id"]),
                "attributes": {"uploaded": True, "sourceFileChecksum": file["checksum"]}}})
            deadline = time.monotonic() + 300
            while True:
                state = self.api.request("GET", path)["data"]["attributes"]["assetDeliveryState"]
                if state["state"] == "COMPLETE":
                    break
                if state["state"] == "FAILED":
                    raise ListingError("appScreenshots FAILED: " + public_detail(json.dumps(state.get("errors", []))))
                if time.monotonic() >= deadline:
                    raise ListingError(f"appScreenshots {file['path'].name}: delivery timed out after 300 seconds")
                time.sleep(2)
            uploaded.append(reference("appScreenshots", screenshot["id"]))
        self.api.request("PATCH", f"/v1/appScreenshotSets/{screenshot_set['id']}/relationships/appScreenshots",
                         {"data": uploaded})

    def run(self, state, listing, files):
        app, info, build = state["app"], state["info"], state["build"]
        category_changes = {}
        for name in ("primaryCategory", "secondaryCategory"):
            desired = reference("appCategories", listing[name])
            if state["categories"][name] != desired:
                self.changes.append(f"appInfos.{name}: {state['categories'][name]} -> {desired}")
                category_changes[name] = {"data": desired}
        if category_changes and self.apply:
            self.api.request("PATCH", f"/v1/appInfos/{info['id']}", {"data": {
                **reference("appInfos", info["id"]), "relationships": category_changes}})
        self.upsert("ageRatingDeclarations", state["age"], listing["ageRatingDeclaration"])
        for locale, texts in listing["localizations"].items():
            self.upsert("appInfoLocalizations", state["info_locales"].get(locale),
                {"locale": locale, "name": texts["name"], "subtitle": texts["subtitle"],
                 "privacyPolicyUrl": listing["privacyPolicyUrl"]}, label=f"appInfoLocalizations.{locale}",
                relationships={"appInfo": relationship("appInfos", info["id"])})
        attributes = {key: listing[key] for key in ("versionString", "copyright")}
        if not state["version"]:
            attributes["platform"] = "IOS"
        version = self.upsert("appStoreVersions", state["version"], attributes,
                             relationships={"app": relationship("apps", app["id"])})
        for locale, texts in listing["localizations"].items():
            attributes = {key: texts[key] for key in ("description", "keywords", "promotionalText")}
            attributes.update({key: listing[key] for key in ("supportUrl", "marketingUrl")})
            attributes["locale"] = locale
            localization = self.upsert("appStoreVersionLocalizations", state["version_locales"].get(locale),
                attributes, label=f"appStoreVersionLocalizations.{locale}",
                relationships={"appStoreVersion": relationship("appStoreVersions", version["id"])})
            if locale == listing["primaryLocale"]:
                for display, screenshots in files.items():
                    self.replace_screenshots(display, state["sets"].get(display), screenshots, localization)
        self.upsert("apps", app, {"primaryLocale": listing["primaryLocale"]})
        if build["attributes"].get("usesNonExemptEncryption") is None:
            self.upsert("builds", build, {"usesNonExemptEncryption": False})
        self.link("appStoreVersions", version, "build", state["attached_build"], reference("builds", build["id"]))
        if not state["group_has_build"]:
            self.link("betaGroups", state["group"], "builds", [], [reference("builds", build["id"])], method="POST")
        review = {key: listing["review"][key] for key in ("notes", "demoAccountName")}
        self.upsert("appStoreReviewDetails", state["review"], {**review, "demoAccountRequired": True},
                    relationships={"appStoreVersion": relationship("appStoreVersions", version["id"])})
        return self.changes


def reconcile(api, listing, files, bundle, build_number, *, apply=False, output=print):
    state = snapshot(api, listing, bundle, build_number, output=output if not apply else None)
    if apply:
        Reconciler(api, True).run(state, listing, files)
        state = snapshot(api, listing, bundle, build_number, output=output)
    changes = Reconciler(api, False).run(state, listing, files)
    for change in changes:
        output(change)
    if not changes:
        output("No changes.")
    if apply and changes:
        raise ListingError("App Store Connect still differs after apply; see the remaining changes above")
    return changes


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("mode", choices=("plan", "apply"))
    parser.add_argument("--key-file", required=True, type=Path)
    parser.add_argument("--build", required=True)
    parser.add_argument("listing", type=Path)
    args = parser.parse_args(argv)
    try:
        listing = load_listing(args.listing)
        files = screenshots(args.listing.parent / "screenshots")
        api = Api(args.key_file, os.environ)
        reconcile(api, listing, files, required(os.environ, "IOS_BUNDLE_ID"), args.build, apply=args.mode == "apply")
    except (ListingError, PreflightError, OSError, ValueError) as error:
        print(public_detail(error), file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
