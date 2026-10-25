# Bro Licensing

Bro follows ChromVoid's `AGPL-3.0-only + Commercial` model, with standalone SDKs
and shared contracts under `MIT`. Copyright belongs to the respective holders.

| Path | License | Scope |
| --- | --- | --- |
| `web/` | AGPL-3.0-only | Web client and product UI |
| `mobile/` | AGPL-3.0-only | Shared mobile core, Android client and design system |
| `shell/` | AGPL-3.0-only | iOS client, ring core and native build helpers |
| `firmware/` | AGPL-3.0-only | Project-owned display firmware |
| `plugins/*/web/`, `plugins/*/mobile/` | AGPL-3.0-only | Product client integrations |
| `plugins/*/package/` | MIT | Plugin manifests and interface contracts |
| `sdk/`, `contracts/` | MIT | Standalone SDKs, shared contracts and fixtures |
| `tools/android_ci.py`, `tools/android_release.py`, `tools/android-upgrade/` | AGPL-3.0-only | Native build and upgrade helpers |
| `README.md`, `docs/public-source/README.md`, `.gitignore`, `.gitattributes` | AGPL-3.0-only | Client documentation and build metadata |
| `server/` | Commercial / Proprietary | Backend; third-party model manifests/notices retain their own terms |
| `plugins/*/service/` | Commercial / Proprietary | Hosted plugin services |
| `deploy/`, `.forgejo/`, remaining project-owned tools/docs | Commercial / Proprietary | Operations and internal materials |

Public export scope is enforced separately by `tools/public_source.py`.
The backend, operations code and internal documents are not included in GitHub.
Their presence in the private repository grants no open-source rights.

Existing third-party licenses take precedence for their respective files,
including vendored firmware components, fonts, media and model notices.
See `THIRD_PARTY_NOTICES.md` and the license files accompanying those assets.
The public mirror preserves the required license texts, including retired notices.

License references: `LICENSES/AGPL-3.0.txt`, `LICENSES/MIT.txt` and
`LICENSES/COMMERCIAL.txt`. This path matrix adapts the owner's chosen
[ChromVoid model](https://github.com/chromvoid/chromvoid/blob/main/LICENSING.md).
