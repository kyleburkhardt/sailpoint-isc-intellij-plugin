# SailPoint ISC IntelliJ plugin: plan and status

This file carries the full plan, the decisions behind it and where things stand, so work can resume from here without any earlier conversation. Keep it current: tick items off, and add decisions when they're made.

_Last updated: 2026-09-29._

## Conventions and decisions (don't reopen these)

**ISC APIs**
- Use only the per-service APIs: `/{service}/v1` (or a newer version of that service, e.g. `/sources/v2/…/provisioning-policies`). **Never** v3, beta or the yearly v2024–v2026 APIs, which SailPoint is retiring (supported until Q2 2028).
- Look endpoints up in the per-service specs at `github.com/sailpoint-oss/api-specs` under `idn/apis/<service>/openapi.yaml`, not the combined `sailpoint-api.v20xx.yaml` bundles. Check each endpoint's `limit` maximum (some are 50) and whether it needs `X-SailPoint-Experimental: true`.
- If a newer version of a service is only available as experimental, point that out and let the user choose. Provisioning policies use **experimental v2** by the user's choice, because v1 allows only one policy per usage type.
- **SailPoint docs index:** `https://developer.sailpoint.com/llms.txt` lists every docs page, and each link returns raw markdown. ISC API pages are one endpoint each, as OpenAPI YAML (e.g. `/docs/api/get-source-v-1.md`), and transform operations live under `/docs/extensibility/transforms/operations/<op>.md`. Grep the index, then curl the page.
- `/connector-groups` (v1 and beta) is a **private route**: ISC rejects our token with a 401 ("cannot be accessed using external token"). Don't use it.

**Schedules and time zones**
- Aggregation schedule cron is treated as **UTC**. The schedule editor shows times in the **tenant time zone** (`GET /org-config/v1` → `timeZone`, needs `idn:org-configs:read`) for every source, by the user's choice. VA cluster offsets aren't used. The UTC assumption **is not yet confirmed on a live tenant**.

**Explorer layout under a source** (user-specified; order comes from the `ResourceKind` enum)
```
Source
├── Accounts: Schema, Correlation, Aggregation Schedule, Provisioning Policies (human), Deletion Approval, Attribute Sync, Native Change Detection
├── Machine Accounts: Provisioning Policies (CREATE_MACHINE_ACCOUNT), Deletion Approval
└── Entitlements: Aggregation Schedule (GROUP_AGGREGATION), then one folder per entitlement type (schema name) holding its Schema
```

**UX decisions**
- Menus: **Refresh** on every node, then node-specific actions (`IscExplorerPanel.nodeActions`).
- Source actions live in a **Run** submenu on the source object.
- **Source delete** and **Remove All Accounts** are confirmed by **typing the source name**.
- Deleting native change detection is **Remove Configuration**; the tree entry stays.
- **New Source** searches the server as you type (`name co "…"`, top 50) rather than loading every connector, which was too slow.
- A **guided New Source form** is wanted but not committed to yet. See phase 4.

**Git and releases**
- The branch is **`master`**, never `main`. Repo: `github.com/kyleburkhardt/sailpoint-isc-intellij-plugin` (public).
- Work goes through PRs. The ruleset on `master` blocks direct pushes and requires CodeQL results.
- Pushing: the user's SSH key has a passphrase, so from a non-interactive shell push over HTTPS with `gh`'s login: `git -c credential.helper= -c credential.helper='!gh auth git-credential' push https://github.com/kyleburkhardt/sailpoint-isc-intellij-plugin.git <branch>`. The `gh` token has the `repo` and `workflow` scopes.
- **Never `git add -A` blindly:** out-of-memory JVMs leave `java_pid*.hprof` dumps (hundreds of MB) in the project folder. `*.hprof` is now ignored, but check `git status` anyway.
- Release: bump `pluginVersion`, merge, `git tag vX.Y.Z && git push origin vX.Y.Z`. The Release workflow checks the tag matches, attaches the zip to a GitHub Release, and publishes `updatePlugins.xml` to GitHub Pages.
- Commit messages end with `Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>`. PR descriptions end with the Claude Code line.

**Build**
- JDK 25, Gradle 9.7.1, IntelliJ Platform 2026.2.3 (`pluginSinceBuild=262`), Kotlin from the `kotlinVersion` property (2.4.20).
- `./gradlew test buildPlugin` builds the zip in `build/distributions/`; `./gradlew runIde` starts a sandbox IDE. **Restart `runIde` after changes**: a running sandbox keeps the old build.
- The CodeQL workflow compiles with Kotlin **2.4.10**, because CodeQL 2.27 only supports Kotlin below 2.4.20. Raise it as CodeQL catches up.

## Code map

| Package | What's there |
|---|---|
| `api` | `IscClient` (HTTP, auth, paging, `postMultipart`, `taskStatus`, `searchConnectors`, `connectorSourceConfig`, `timeZone`), `ResourceKind` (every object type: paths, flags such as `singleton`, `patchUpdated`, `experimental`, `deletable`, `hidden`, `group`, and tree order), `IscItem` |
| `explorer` | `IscExplorerPanel` (tree, lazy loading, folders, menus, refresh on changes), and the New Transform, Schedule, Provisioning Policy and Source dialogs |
| `editor` | `IscEditorService` (open, push, reload, delete, 404 starters, read-only tabs), `IscVirtualFile`, the editor banner, `IscChangeListener` (announces create and delete so the tree reloads) |
| `run` | `SourceRunner` (Run menu actions), `TaskTracker` (polls `/task-status/v1`), `PeekDialog`, `Notifier` |
| `schedule` | `CronSchedule` (cron model, UTC shifting; unit tested), `ScheduleBuilder` (point-and-click editor kept in sync with the cron), `ScheduleTimeZone`, `EditScheduleDialog` |
| `schema` | JSON Schema completion for transforms |
| `transform` | The local preview engine: `TransformCatalog` (reads `transform.schema.json` once for the form, New Transform and completion), `TransformEvaluator` (runs a transform, lists the tenant values it needs, `readsImplicitInput`/`withImplicitInput`), `Velocity`, and `ops/` (one file per group of operations) |
| `transform/ui` | The transform editor: `TransformFormEditorProvider` (split view, form left at 66%), `TransformFormPanel` (steps, settings, previews), `TestInputPanel`, `TenantNames` (source and attribute names for dropdowns, cached per tenant), `IscTest` and `IscTestDialog` (Test in ISC) |
| `settings` | Tenants, stored credentials, settings page |

## Status

### Done
- [x] Tenant explorer: sources, transforms, connector rules and identities, with JSON editing, push and reload.
- [x] Source configuration as laid out above, with JSON Patch where ISC requires it and full replace elsewhere.
- [x] Provisioning policies: v2 API, split into human and machine; New Provisioning Policy dialog (connector defaults, machine subtype picker); delete.
- [x] Aggregation schedules: New, Edit Schedule builder (tenant time zone ↔ UTC cron), delete.
- [x] **Phase 1:** tree reloads after create and delete; "None" in empty folders; configs that return 404 open a starter (PUT-saved configs) or explain that they can't be created (the patch-only approvals); deletes for transforms, connector rules and schedules; Remove Configuration for native change detection.
- [x] **Phase 2:** the Run submenu on sources (aggregate accounts or entitlements, including from a CSV for delimited-file sources, process uncorrelated accounts, test connection or configuration, ping cluster, peek resource objects, synchronize attributes) with background task tracking.
- [x] New Source step 1: server-side connector search; Next currently opens the connector's JSON and source-config XML read-only.
- [x] Repo, README, Build workflow, Release workflow with the Pages update feed, CodeQL workflow.

- [x] **PR #1** (README, CI, releases, CodeQL) merged 2026-09-18.
- [x] **GitHub Pages enabled** with GitHub Actions as the source. The `github-pages` environment allows deploys from `master` and from `v*` tags; the release deploys from its tag.

- [x] **v0.1.0 released** 2026-09-18: the zip is on the [GitHub Release](https://github.com/kyleburkhardt/sailpoint-isc-intellij-plugin/releases/tag/v0.1.0), and the update feed `https://kyleburkhardt.github.io/sailpoint-isc-intellij-plugin/updatePlugins.xml` is live and points at it.

- [x] **PR #2** (this plan) and **PR #3** (phase 3) merged 2026-09-18.

### In progress
- [x] **Transform editor** ([PR #4](https://github.com/kyleburkhardt/sailpoint-isc-intellij-plugin/pull/4)) merged 2026-09-29: a form-first editor with a live local preview, next to the JSON. See "Transform editor" below.
- [ ] **v0.2.0 release:** `pluginVersion` is bumped on branch `release-0.2.0`. Once that's merged, tag `v0.2.0` on `master` and push the tag.
- [ ] **Repo settings suggested but not applied:**
  - Make `build` (and `analyze`) required checks in the ruleset.
  - Turn on **Automatically delete head branches** and **Always suggest updating pull request branches**.
  - Optionally: pick one merge method, add a tag ruleset for `v*`.
  - If PRs stay blocked with CodeQL passing, check the ruleset's **code quality** rule.

### Needs a live-tenant test (user)
- [ ] **Schedule times:** open a schedule in the plugin and in ISC's UI and confirm they match. This settles the UTC assumption.
- [ ] **Missing configs:** opening correlation or native change detection on a source that has none gives a starter, and creating it works.
- [ ] **Tree reloads:** creating a transform or schedule, or deleting one, updates the tree without Refresh.
- [ ] **Aggregation:** Aggregate Accounts on a small source shows progress, and the final notification's counts are right (read from the task's `returns` and `attributes`).
- [ ] **Connector checks:** Test Connection on a good and a broken source; check what Show details gives.
- [ ] **Peek:** on `account` and on an entitlement type.
- [ ] **CSV aggregation:** Aggregate Accounts from File on a delimited-file source. This is the first real multipart upload.
- [ ] **Test in ISC** (transform banner): the first run settles
  - whether the client has `idn:identity-profile:manage` and `idn:transform:manage` (for the scratch copy);
  - that the scratch copy (`zz-plugin-test-…`) is deleted afterwards;
  - that the filled test values match the identity (source matched by `sourceName`, `sourceId`, or `Name [source]`), and the client can read `/identities/v1` and `/accounts/v1`;
  - whether "Compute as" accepts any identity attribute or only mapped ones;
  - that the identity search (`/search/v1`, `name*`) finds people;
  - how a `reference` to another transform behaves.
- [ ] **Transform dropdowns:** the source, account attribute and identity attribute lists fill from the tenant. `/identity-attributes/v1` needs `idn:identity-profile-attribute:read`.

## To do

### Phase 3: structure changes (built in PR #3; tick these once tested on a live tenant)
- [ ] **New Entitlement Type…** on the Entitlements folder: a dialog for name, native object type, identity attribute and display attribute, then `POST /sources/v1/{sourceId}/schemas`, then reload the folder.
- [ ] **Delete schema** on an entitlement type's Schema (`DELETE …/schemas/{id}`). **Never offer it on the account schema.**
- [ ] **Delete source:** load `GET /sources/v1/{id}/connections` first and list what uses the source (identity profiles, transforms, rules); confirm by **typing the source name**; `DELETE /sources/v1/{id}` returns 202 with a task, so follow it with `TaskTracker`; reload Sources when it finishes.
- [ ] **Remove All Accounts…** (Run menu): warning plus typing the source name; `POST /sources/v1/{id}/remove-accounts` returns 202 with a task; track it.
- [ ] **Upload Connector File…** (Run menu): file chooser (e.g. a JDBC driver `.jar`), then a multipart `POST /sources/v1/{sourceId}/upload-connector-file` (field `file`). It returns the source; refresh its editor if open.

### Phase 4: New Source guided form
- [ ] **Spike:** get the source-config XML for **Delimited File, a JDBC connector and Web Services** (New Source → pick connector → Next shows it), saved as sample files. Decide whether it describes the form well enough (fields, types, labels, required flags, defaults, sections) to render any connector generically.
- [ ] **If yes:** replace "Next" with a guided form built from `source-config`:
  - Name, owner (identity search), VA cluster when needed, a Delimited File (CSV) checkbox that sets `provisionAsCsv`.
  - Then the connector's fields, mapped into `connectorAttributes`.
  - Create with `POST /sources/v1` (required: `name`, `owner`, `connector`), then open the new source.
- [ ] **If no:** a minimal dialog (name, owner, cluster), then edit the JSON.

### Web Services operations editor (primary connector for the user)
- [ ] **Spike:** export one working Web Services source's JSON to confirm the operation fields (`httpMethodType`, `operationType`, `contextUrl`, body, headers, `rootPath`, `resMappingObj`, `paginationSteps`, `sequenceNumberForEndpoint`, `parentEndpointName`, …; these are from memory and unverified).
- [ ] **"HTTP Operations" editor** under Web Services sources:
  - A list of operations, and a Postman-style panel for method, URL, headers and body, plus response mapping and pagination tabs.
  - Saves back into the source's operation list.
- [ ] **Built-in Send** (`java.net.http`), with a response pane (status, headers, body). It must work in every IntelliJ edition, because **IntelliJ's HTTP Client needs a paid licence**: the free tier shows "Activate IDE license to use HTTP Client".
- [ ] **Mapping preview:** apply the root path and attribute mapping to the last response, and show the accounts it would produce.
- [ ] **Test through ISC:** Peek Resource Objects, which runs the real connector on the VA.
- [ ] **Optional "Open in HTTP Client"** when that plugin is licensed. Never a required dependency.
- [ ] **Later: a fully functional REST client of our own** (user decision): environments and variables, OAuth helpers, request history, chaining, cURL and Postman import.

### Remaining source configuration
- [ ] **Password policies** (Accounts folder): `GET/PATCH /sources/v1/{sourceId}/password-policies`.
- [ ] **Entitlement request config** (Entitlements folder): `GET/PUT /sources/v1/{id}/entitlement-request-config`.
- [ ] **Machine account subtypes** (Machine Accounts folder): stable `/sources/v1/{sourceId}/subtypes` in the machine-accounts API. Also switch the New Provisioning Policy subtype picker to it from the experimental `/source-subtypes/v1`.
- [ ] **Entitlements** inside each entitlement type folder: `/entitlements/v1?filters=source.id eq "…"`. Each entitlement also has a request config.
- [ ] **Read-only views:** source health (`/sources/v1/{id}/source-health`) and connections (`/sources/v1/{id}/connections`).

### Transform editor
Built so far (2026-09-22 to 2026-09-29):
- **Local preview engine:** there's no preview endpoint for a single transform, so transforms run locally, checked against SailPoint's documented examples.
  - Operations covered:
    - `concat`, `conditional`, `firstValid`, `join`, `lookup`, `lower`, `replace`, `split`, `static`, `substring`, `trim`, `upper`
    - `identityAttribute`, `accountAttribute`, `getReferenceIdentityAttribute`
    - `uuid`, `randomAlphaNumeric`, `randomNumeric`
    - `indexOf`, `lastIndexOf`, `leftPad`, `rightPad`, `replaceAll`, `getEndOfString` (as its own type and as the Cloud Services Deployment Utility rule, which also previews `generateRandomString`; any other rule says it only runs in ISC)
    - `dateCompare`, `dateFormat`, `dateMath` (in UTC; dates are read as loosely as ISO8601 allows, and date math writes `yyyy-MM-dd'T'HH:mmZ`, e.g. `2025-01-14T06:00Z`, going by what ISC returned)
    - `reference`: the named transform is loaded from the tenant (`/transforms/v1?filters=name eq …`, cached per tenant for the session) and run on the incoming value. Tenant values read inside it get their own fields under "Read inside referenced transforms", and Test in ISC fills them.
  - Anything else shows "not previewed yet".
- **Form layout:**
  - The steps are listed in the order they run, and each shows its result.
  - Steps move up and down with arrows in a margin down the right. (Showing them on hover put mouse listeners on the row's labels, which swallowed the clicks that open a step.)
  - Account, identity and reference steps have dropdowns filled from the tenant, with the test value on the same line.
  - "Input" appears at the top only when the transform reads the value ISC passes in.
  - A static's variables come before its value.
- **Test in ISC:** runs the transform in the editor on a real identity through `POST /identity-profiles/v1/identity-preview`, which is stable v1. The preview only takes a mapping an identity profile could hold (a `reference` to a saved transform); an inline operation fails with e.g. `"Transform Type" value does not exist: "displayName.static"`. So the editor's JSON is saved as a scratch transform (`zz-plugin-test-<8 hex>`), referenced, and deleted afterwards; a failed delete is reported. Before the preview, the identity's real values are read (`GET /identities/v1/{id}` for identity attributes and the manager, `GET /accounts/v1?filters=identityId eq …` for account attributes and the input, `alias eq` for other `getReferenceIdentityAttribute` uids) and fill the local test fields; values that are empty in ISC count as nothing rather than untyped. The result shows under the local one in a banner: green when they match, red with both values when they differ, amber when the preview still needs a value.

To do:
- [ ] **Phase B:** the remaining operations, which currently show "not previewed yet":
  - base64 decode and encode
  - decompose diacritical marks, normalize names
  - display name
  - E.164 phone, ISO 3166, RFC 5646
  - username generator
- [ ] **Phase C:** a way to reload a referenced transform after it's edited, and remembering test inputs and the Test in ISC setup between sessions (it's per open tab now).
- [ ] Reordering entries inside a step (e.g. First Valid's values). Only the main chain moves now.

### Deferred (user's call to pick these up)
- [ ] **Provisioning policy JSON schema:** completion and validation for `fields`, reusing the transform schema for each field's `transform`.

### Smaller known issues
- [ ] Tree labels don't update after a rename and push; Refresh fixes them.
- [ ] Plugin Verifier (`./gradlew verifyPlugin`) hasn't been run. It needs IDE downloads, so it can't run offline.
- [ ] Deletion approvals can't be created (ISC only accepts patches for them); if one ever returns 404, the plugin says so.

### Left out on purpose
- Datasets and resources APIs (experimental, for newer connector types).
- v1 bulk provisioning-policy update (doesn't fit v2 IDs).
- CSV schema templates (`schemas/accounts|entitlements`): file uploads, not JSON config.
- Connector translations (`connectors/source-config` with languages): read-only translation data.
