# SailPoint ISC for IntelliJ

Browse, edit and push SailPoint Identity Security Cloud (ISC) configuration without leaving your JetBrains IDE, across as many tenants as you work with.

> **In development.** This is an early build shared for testing. Expect rough edges, and try changes on a sandbox tenant before a production one. See [Known limitations](#known-limitations).

## What it does

The **SailPoint** tool window lists each tenant with its **Sources**, **Transforms**, **Connector Rules** and **Identities**. Double-click an object to open it as JSON (connector rules open as their script). Edit it, then push it back with **Push to ISC** in the editor banner or <kbd>Ctrl</kbd>+<kbd>Alt</kbd>+<kbd>Shift</kbd>+<kbd>U</kbd>.

Each source is organised the way you think about it:

```
Active Directory
├── Accounts
│   ├── Schema
│   ├── Correlation
│   ├── Aggregation Schedule
│   ├── Provisioning Policies
│   ├── Deletion Approval
│   ├── Attribute Sync
│   └── Native Change Detection
├── Machine Accounts
│   ├── Provisioning Policies
│   └── Deletion Approval
└── Entitlements
    ├── Aggregation Schedule
    └── group
        └── Schema
```

Right-click for what fits each item:

- **Create:** new transforms, provisioning policies (human or machine account), and aggregation schedules.
- **Delete:** transforms, connector rules, provisioning policies and schedules, and **Remove Configuration** for native change detection.
- **Run** (on a source): aggregate accounts or entitlements (also from a CSV file for delimited-file sources), process uncorrelated accounts, test the connection or configuration, ping the cluster, peek at raw resource objects, and synchronize attributes. Long-running tasks show progress and report their outcome when they finish.
- **Edit Schedule…** (in a schedule's banner): build aggregation schedules by picking days and times, without writing cron. The plugin converts between the tenant's time zone and the UTC cron that ISC stores, and you can still edit the cron directly.
- **New Source…** (on Sources): search the tenant's connectors. This is the first step of a guided New Source form that's still being built.

Transforms get JSON Schema completion and validation while you edit.

## Requirements

- IntelliJ IDEA **2026.2 or newer** (build 262+), or another JetBrains IDE of the same version such as PyCharm or WebStorm. The free tier works.
- An ISC tenant and a **Personal Access Token** (PAT) for a user with the admin rights for what you want to change, such as a source admin or an org admin. Reading the tenant's time zone for the schedule editor needs org admin (`idn:org-configs:read`). Without it, schedule times are shown in UTC.

## Install

### Automatic updates (recommended)

1. In the IDE, open **Settings → Plugins**, click **⚙** and choose **Manage Plugin Repositories…**.
2. Add `https://kyleburkhardt.github.io/sailpoint-isc-intellij-plugin/updatePlugins.xml`.
3. Search the **Marketplace** tab for **SailPoint Identity Security Cloud** and install it.

New releases then show up as ordinary plugin updates.

### From a zip

Download the zip from the latest [release](https://github.com/kyleburkhardt/sailpoint-isc-intellij-plugin/releases), then use **Settings → Plugins → ⚙ → Install Plugin from Disk…**. Repeat this for each new version.

## Getting started

1. Open the **SailPoint** tool window (right-hand side) and click **Add tenant**, or go to **Settings → Tools → SailPoint ISC**.
2. Enter a display name, your tenant (`acme`, or its API URL such as `https://acme.api.identitynow.com`), and your PAT's client ID and secret. The secret is kept in the IDE's password safe.
3. Expand the tenant and start browsing.

## Known limitations

- **Not yet tested against a live tenant:** the newest features, including the Run menu, deletes, and configs a source doesn't have yet.
- **Schedule times:** the plugin assumes ISC stores aggregation schedule cron expressions in UTC and shows them in the tenant's time zone. That assumption hasn't been confirmed on a live tenant yet. Compare with ISC's own UI before relying on it.
- **Experimental SailPoint APIs:** provisioning policies (v2), attribute sync and machine account subtypes use APIs SailPoint marks experimental, which can change without much notice.
- **Deletion approvals** can be changed but not created from here, because ISC only accepts updates for them.
- **Tree labels** don't update after you rename something and push. Use **Refresh**.

## Building from source

Needs JDK 25.

```
./gradlew test buildPlugin    # plugin zip in build/distributions/
./gradlew runIde              # sandbox IDE with the plugin installed
```

The plugin only calls SailPoint's per-service `/{service}/v1` APIs (or a newer version of a service), never v3, beta or the yearly v2024–v2026 APIs, which SailPoint is retiring.

## Releasing

1. Bump `pluginVersion` in `gradle.properties` and commit to `master`.
2. Tag and push: `git tag v0.1.1 && git push origin master v0.1.1`.

The **Release** workflow checks that the tag matches `pluginVersion`, builds and tests, attaches the zip to a GitHub Release, and publishes the update feed to GitHub Pages.
