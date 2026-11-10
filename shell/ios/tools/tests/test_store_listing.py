import contextlib
import copy
import importlib.util
import io
import json
from pathlib import Path
import struct
import sys
import tempfile
import unittest
from unittest.mock import MagicMock, Mock, patch
from urllib.error import HTTPError
from urllib.parse import parse_qs, urlsplit


ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT))
spec = importlib.util.spec_from_file_location("store_listing", ROOT / "store_listing.py")
store = importlib.util.module_from_spec(spec)
spec.loader.exec_module(store)
LISTING = ROOT.parents[1] / "docs/app-store/listing.json"
BUNDLE = "com.synthetic.assistant"


class FakeApi:
    """A small JSON:API store with ordered relationships and multipart uploads."""

    def __init__(self, listing, files):
        self.items = {}
        self.links = {}
        self.writes = []
        self.reads = []
        self.uploads = []
        self.next_id = 0
        self.mirror_locales = False
        self.expected_files = {f["path"].name: f["content"] for group in files.values() for f in group}
        self.add("apps", "app", {"bundleId": BUNDLE, "primaryLocale": "en-US"})
        self.add("appInfos", "info", {"state": "PREPARE_FOR_SUBMISSION"})
        self.add("ageRatingDeclarations", "age", {key: None for key in listing["ageRatingDeclaration"]})
        self.add("builds", "build", {"version": "4806", "processingState": "VALID", "usesNonExemptEncryption": None})
        self.add("betaGroups", "group", {"name": listing["betaGroup"], "hasAccessToAllBuilds": False})
        self.links.update({("apps", "app", "appInfos"): [store.reference("appInfos", "info")],
            ("apps", "app", "appStoreVersions"): [],
            ("apps", "app", "betaGroups"): [store.reference("betaGroups", "group")],
            ("appInfos", "info", "ageRatingDeclaration"): store.reference("ageRatingDeclarations", "age"),
            ("appInfos", "info", "primaryCategory"): None,
            ("appInfos", "info", "secondaryCategory"): None,
            ("appInfos", "info", "appInfoLocalizations"): [],
            ("betaGroups", "group", "builds"): []})

    def add(self, kind, identifier, attributes):
        item = {**store.reference(kind, identifier), "attributes": attributes}
        self.items[kind, identifier] = item
        return item

    def resolve(self, refs):
        if isinstance(refs, list):
            return [self.resolve(r) for r in refs]
        return self.items[refs["type"], refs["id"]] if refs else None

    def request(self, method, path, payload=None, *, missing_ok=False):
        parts = urlsplit(path)
        assert "reviewSubmissions" not in path and "appStoreVersionSubmissions" not in path
        segments = parts.path.strip("/").split("/")
        kind = segments[1]
        if method == "GET":
            self.reads.append(path)
            if len(segments) == 2:
                data = [item for (item_kind, _), item in self.items.items() if item_kind == kind]
            elif len(segments) == 3:
                data = self.items[kind, segments[2]]
            elif segments[3] == "relationships":
                data = self.links[kind, segments[2], segments[4]]
            else:
                name = segments[3]
                if name == "appStoreReviewDetail":
                    assert parse_qs(parts.query) == {"fields[appStoreReviewDetails]": [store.REVIEW_FIELDS]}
                    assert missing_ok
                data = self.resolve(self.links.get((kind, segments[2], name),
                                    None if name == "appStoreReviewDetail" else []))
            return {"data": copy.deepcopy(data), "links": {"next": None}}
        self.writes.append((method, path, copy.deepcopy(payload)))
        if method == "DELETE":
            ref = store.reference(kind, segments[2])
            del self.items[kind, segments[2]]
            for values in self.links.values():
                if isinstance(values, list) and ref in values:
                    values.remove(ref)
            return {}
        data = payload["data"]
        if len(segments) == 5:
            key = kind, segments[2], segments[4]
            if method == "POST":
                self.links[key].extend(data)
            else:
                self.links[key] = copy.deepcopy(data)
            return {}
        if method == "POST" and kind == "appStoreVersionLocalizations":
            parent = data["relationships"]["appStoreVersion"]["data"]["id"]
            existing = self.resolve(self.links.get(("appStoreVersions", parent, kind), []))
            if any(item["attributes"]["locale"] == data["attributes"]["locale"] for item in existing):
                raise store.ListingError("HTTP 409 POST appStoreVersionLocalizations: DUPLICATE")
        if method == "POST" and kind == "appInfoLocalizations" and self.mirror_locales:
            # Apple adds the version localization of a new app info locale by itself.
            for (item_kind, version), _ in list(self.items.items()):
                if item_kind == "appStoreVersions":
                    self.next_id += 1
                    mirrored = self.add("appStoreVersionLocalizations", f"new-{self.next_id}",
                                        {"locale": data["attributes"]["locale"]})
                    self.links.setdefault(("appStoreVersions", version, "appStoreVersionLocalizations"), []).append(
                        store.reference("appStoreVersionLocalizations", mirrored["id"]))
        if method == "POST":
            self.next_id += 1
            item = self.add(kind, f"new-{self.next_id}", copy.deepcopy(data.get("attributes", {})))
            ref = store.reference(kind, item["id"])
            for rel in data.get("relationships", {}).values():
                parent = rel["data"]
                name = "appStoreReviewDetail" if kind == "appStoreReviewDetails" else kind
                key = parent["type"], parent["id"], name
                if kind == "appStoreReviewDetails":
                    self.links[key] = ref
                else:
                    self.links.setdefault(key, []).append(ref)
            if kind == "appStoreVersions":
                item["attributes"]["appVersionState"] = "PREPARE_FOR_SUBMISSION"
                self.links[kind, item["id"], "build"] = None
            if kind == "appScreenshotSets":
                self.links[kind, item["id"], "appScreenshots"] = []
            if kind == "appScreenshots":
                length = item["attributes"]["fileSize"]
                middle = length // 2
                item["attributes"]["uploadOperations"] = [
                    {"method": "PUT", "url": "https://upload.synthetic.example/asset?signature=private",
                     "offset": offset, "length": size, "requestHeaders": [{"name": "X-Part", "value": str(offset)}]}
                    for offset, size in [(0, middle), (middle, length - middle)]]
                item["attributes"]["assetDeliveryState"] = {"state": "AWAITING_UPLOAD"}
            return {"data": copy.deepcopy(item)}
        item = self.items[kind, segments[2]]
        item["attributes"].update(data.get("attributes", {}))
        for name, rel in data.get("relationships", {}).items():
            self.links[kind, segments[2], name] = rel["data"]
        if kind == "appScreenshots":
            item["attributes"]["assetDeliveryState"] = {"state": "COMPLETE"}
        return {"data": copy.deepcopy(item)}

    def upload(self, operation, content):
        assert content in self.expected_files.values()
        self.uploads.append((copy.deepcopy(operation), content))


class StoreListingTests(unittest.TestCase):
    def setUp(self):
        self.listing = store.load_listing(LISTING)
        self.files = store.screenshots(LISTING.parent / "screenshots")
        self.api = FakeApi(self.listing, self.files)

    def reconcile(self, apply=False):
        return store.reconcile(self.api, self.listing, self.files, BUNDLE, "4806", apply=apply, output=lambda _: None)

    def test_repository_listing_meets_apple_limits_and_has_ten_ordered_screenshots(self):
        self.assertEqual(self.listing["primaryLocale"], "ru")
        self.assertEqual(set(self.listing["localizations"]), {"ru", "en-US"})
        for texts in self.listing["localizations"].values():
            for key, limit in {"name": 30, "subtitle": 30, "keywords": 100, "promotionalText": 170, "description": 4000}.items():
                self.assertLessEqual(len(texts[key]), limit)
        for field in ("supportUrl", "marketingUrl", "privacyPolicyUrl"):
            self.assertEqual(urlsplit(self.listing[field]).scheme, "https")
        self.assertEqual([len(files) for files in self.files.values()], [5, 5])
        for files in self.files.values():
            self.assertEqual([int(f["path"].name.split("-")[2]) for f in files], [1, 2, 3, 4, 5])

    def test_plan_is_read_only_and_matching_plan_is_empty(self):
        changes = self.reconcile()
        self.assertTrue(any("appInfoLocalizations.ru.name" in change for change in changes))
        self.assertTrue(any("screenshots.APP_IPHONE_67" in change for change in changes))
        self.assertEqual(self.api.writes, [])
        self.assertEqual(self.api.uploads, [])
        self.reconcile(apply=True)
        self.api.writes.clear()
        self.api.uploads.clear()
        output = []
        self.assertEqual(store.reconcile(self.api, self.listing, self.files, BUNDLE, "4806", output=output.append), [])
        self.assertIn('"messagingAndChat": true', output[0])
        self.assertEqual(output[-1], "No changes.")
        self.assertEqual(self.api.writes, [])
        self.assertEqual(self.api.uploads, [])

    def test_apply_writes_once_then_is_empty_and_never_submits_or_accesses_private_review_fields(self):
        self.assertEqual(self.reconcile(apply=True), [])
        writes = self.api.writes
        creates = [payload["data"]["type"] for method, _, payload in writes
                   if method == "POST" and isinstance(payload["data"], dict)]
        self.assertEqual(creates.count("appStoreVersions"), 1)
        self.assertEqual(creates.count("appInfoLocalizations"), 2)
        self.assertEqual(creates.count("appStoreVersionLocalizations"), 2)
        self.assertEqual(creates.count("appScreenshots"), 10)
        self.assertEqual(len(self.api.uploads), 20)
        self.assertEqual(self.api.links["appStoreVersions", next(i for (k, i) in self.api.items if k == "appStoreVersions"), "build"], store.reference("builds", "build"))
        self.assertEqual(self.api.links["betaGroups", "group", "builds"], [store.reference("builds", "build")])
        self.assertIs(self.api.items["builds", "build"]["attributes"]["usesNonExemptEncryption"], False)
        reviews = [p["data"]["attributes"] for _, _, p in writes
                   if p and isinstance(p["data"], dict) and p["data"].get("type") == "appStoreReviewDetails"]
        self.assertEqual(reviews, [{**self.listing["review"], "demoAccountRequired": True}])
        serialized = json.dumps(writes) + json.dumps(self.api.reads)
        for forbidden in ("reviewSubmissions", "appStoreVersionSubmissions", "demoAccountPassword", "contactFirstName", "contactLastName", "contactPhone", "contactEmail", "releaseType", "whatsNew"):
            self.assertNotIn(forbidden, serialized)
        self.api.writes.clear()
        self.api.uploads.clear()
        self.assertEqual(self.reconcile(apply=True), [])
        self.assertEqual(self.api.writes, [])
        self.assertEqual(self.api.uploads, [])
        # Reuse editable records, replace only the out-of-order screenshot set,
        # and retain membership when the group grants access to every build.
        version = next(v for (kind, _), v in self.api.items.items() if kind == "appStoreVersions")
        version["attributes"]["versionString"] = "0.1.0"
        review = next(v for (kind, _), v in self.api.items.items() if kind == "appStoreReviewDetails")
        review["attributes"]["notes"] = "Outdated notes"
        info_ru = next(v for (kind, _), v in self.api.items.items()
                       if kind == "appInfoLocalizations" and v["attributes"]["locale"] == "ru")
        info_ru["attributes"]["subtitle"] = "Outdated subtitle"
        iphone_set = next(v for (kind, _), v in self.api.items.items()
                          if kind == "appScreenshotSets" and v["attributes"]["screenshotDisplayType"] == "APP_IPHONE_67")
        self.api.links["appScreenshotSets", iphone_set["id"], "appScreenshots"].reverse()
        self.api.items["betaGroups", "group"]["attributes"]["hasAccessToAllBuilds"] = True
        self.api.links["betaGroups", "group", "builds"] = []
        self.assertEqual(self.reconcile(apply=True), [])
        self.assertEqual(sum(method == "DELETE" for method, _, _ in self.api.writes), 5)
        self.assertEqual(len(self.api.uploads), 10)
        self.assertEqual(sum(method == "POST" and path == "/v1/appStoreVersions" for method, path, _ in self.api.writes), 0)
        self.assertFalse(any("betaGroups" in path for _, path, _ in self.api.writes))
        self.api.writes.clear()
        self.api.uploads.clear()
        self.assertEqual(self.reconcile(apply=True), [])
        self.assertEqual(self.api.writes, [])
        self.assertEqual(self.api.uploads, [])

    def test_version_localization_apple_adds_with_app_info_one_is_updated_not_created_again(self):
        self.api.mirror_locales = True
        self.api.add("appStoreVersions", "version", {"versionString": "1.0", "appVersionState": "PREPARE_FOR_SUBMISSION"})
        self.api.links["apps", "app", "appStoreVersions"] = [store.reference("appStoreVersions", "version")]
        self.api.links["appStoreVersions", "version", "build"] = None
        self.assertEqual(self.reconcile(apply=True), [])
        locales = [item["attributes"]["locale"] for (kind, _), item in self.api.items.items()
                   if kind == "appStoreVersionLocalizations"]
        self.assertEqual(sorted(locales), ["en-US", "ru"])

    def test_reads_are_repeated_after_a_transient_failure_and_writes_are_not(self):
        api = store.Api(Path("unused.p8"), {"ASC_KEY_ID": "SYNTHETIC", "ASC_ISSUER_ID": "synthetic"})
        def failure():
            return HTTPError("https://api.appstoreconnect.apple.com/v1/apps", 500, "error", {},
                             io.BytesIO(b'{"errors": [{"code": "UNEXPECTED_ERROR"}]}'))
        response = MagicMock()
        response.__enter__.return_value = response
        response.read.return_value = b'{"data": []}'
        opener = Mock()
        opener.open.side_effect = [failure(), OSError("reset"), response]
        with patch.object(store, "build_opener", return_value=opener), \
                patch.object(store, "token", return_value="synthetic-token"), patch.object(store.time, "sleep"):
            self.assertEqual(api.request("GET", "/v1/apps"), {"data": []})
            opener.open.side_effect = [failure()]
            with self.assertRaisesRegex(store.ListingError, "HTTP 500 PATCH apps"):
                api.request("PATCH", "/v1/apps/app", {"data": {}})
        self.assertEqual(opener.open.call_count, 4)

    def test_unknown_age_key_and_nonvalid_build_fail_before_any_write(self):
        self.listing["ageRatingDeclaration"]["unknownAppleField"] = False
        with self.assertRaisesRegex(store.ListingError, "unknownAppleField.*available keys:.*advertising"):
            self.reconcile(apply=True)
        self.assertEqual(self.api.writes, [])
        del self.listing["ageRatingDeclaration"]["unknownAppleField"]
        self.api.items["builds", "build"]["attributes"]["processingState"] = "PROCESSING"
        with self.assertRaisesRegex(store.ListingError, "4806.*PROCESSING"):
            self.reconcile(apply=True)
        self.assertEqual(self.api.writes, [])

    def test_wrong_png_size_fails_before_upload(self):
        with tempfile.TemporaryDirectory() as temporary:
            listing_path = Path(temporary) / "listing.json"
            listing_path.write_text(json.dumps(self.listing))
            directory = Path(temporary) / "screenshots"
            directory.mkdir()
            for files in self.files.values():
                for file in files:
                    (directory / file["path"].name).write_bytes(file["content"])
            path = directory / "iphone-6.9-1-chat.png"
            content = path.read_bytes()
            path.write_bytes(content[:16] + struct.pack(">II", 100, 100) + content[24:])
            error = io.StringIO()
            with patch.object(store, "Api", return_value=self.api) as api, contextlib.redirect_stderr(error):
                self.assertEqual(store.main(["apply", "--key-file", "unused.p8", "--build", "4806", str(listing_path)]), 1)
            self.assertIn("expected 1320×2868, got 100×100", error.getvalue())
            api.assert_not_called()
        self.assertEqual(self.api.uploads, [])
        self.assertEqual(self.api.writes, [])

    def test_transport_guards_submissions_uses_upload_ranges_and_does_not_read_review_write_bodies(self):
        api = store.Api(Path("unused.p8"), {"ASC_KEY_ID": "SYNTHETIC", "ASC_ISSUER_ID": "synthetic"})
        with patch.object(store, "token", side_effect=AssertionError("must not sign")):
            for kind in ("reviewSubmissions", "appStoreVersionSubmissions"):
                with self.assertRaisesRegex(store.ListingError, "Submission endpoints are forbidden"):
                    api.request("POST", f"/v1/{kind}", {"data": {}})
        operation, content = {"method": "PUT", "url": "https://upload.synthetic.example/asset?signature=private",
            "offset": 2, "length": 4, "requestHeaders": [{"name": "X-Part", "value": "part"}]}, b"0123456789"
        with patch.object(api, "send", return_value={}) as send:
            api.upload(operation, content)
        request = send.call_args.args[0]
        self.assertEqual(request.data, b"2345")
        self.assertEqual(request.get_header("X-part"), "part")
        self.assertIsNone(request.get_header("Authorization"))
        response = MagicMock()
        response.__enter__.return_value = response
        response.read.side_effect = AssertionError("must not read back reviewer secrets")
        opener = Mock()
        opener.open.return_value = response
        with patch.object(store, "build_opener", return_value=opener), patch.object(store, "token", return_value="synthetic-token"):
            api.request("PATCH", "/v1/appStoreReviewDetails/review", {"data": {"type": "appStoreReviewDetails", "id": "review", "attributes": {"notes": "public"}}})
        response.read.assert_not_called()
        self.assertEqual(store.public_detail("upload https://apple.example/asset?signature=private failed"), "upload https://apple.example/asset failed")


if __name__ == "__main__":
    unittest.main()
