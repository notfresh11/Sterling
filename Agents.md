# AGENTS.md — Sterling Ecosystem Analysis & Development Guide

Welcome to the **Sterling Ecosystem** repository. This document serves as the master specification, architectural guide, and operational instructions for developers and AI agents working on the Sterling project.

---

## 🌐 1. Project Overview & Vision

**Sterling** is a planned four-component ecosystem designed to reduce onboarding friction for Vanilla Minecraft Java players joining modded servers. The repository currently contains two active Fabric projects—Sterling Bridge and Sterling AutoMod—and an early Tauri desktop launcher prototype. The percentages below are approximate product-scope estimates, not code-coverage metrics; they are based on the stated MVP and long-term vision.

### The Problem
Over 80% of Minecraft Java players play Vanilla. Joining a modded server manually requires installing Java runtimes, Forge/Fabric loaders, matching exact `.jar` file versions, and configuring RAM—causing over 80% of prospective players to drop off.

### The Sterling Solution
The intended end-user journey is: a player attempts to join a modded server, receives an actionable message, follows the Sterling link, installs the required client mods, restarts Minecraft, and reconnects. Today, the first two steps are handled by Bridge and AutoMod can install verified mod files into the active Minecraft instance. Instance creation, one-click launch, and automatic restart are not implemented.

---

## 🛡️ 2. The 4 Components of the Sterling Ecosystem

1. **Sterling's Bridge (`sterling-bridge2`)** — *Fabric server-side mod*
   - **Current status: approximately 70% of the Bridge MVP; not a Paper/Spigot plugin or a multi-platform bridge yet.**
   - Current target is Minecraft 26.3, Fabric Loader 0.19.5, and Java 25.
   - Detects a required Fabric registry-sync incompatibility early in the server configuration phase. When a client cannot participate in Fabric registry synchronization, it disconnects the client with the configured Sterling message. It does not inspect `minecraft:brand`, classify clients as Vanilla/Fabric/Forge/Sterling, or guarantee compatibility checks for arbitrary mod protocols.
   - Creates and serves `manifest.json` from server-side `mods/*.jar`, with SHA-256 hashes and download URLs. Uses the Modrinth version-file lookup where available and its embedded HTTP server for manifest/JAR delivery.
   - `excludedMods` filters a configured JAR filename or filename stem from the generated client manifest; it is needed for server-only mods.
   - Configurable server name/address/port, HTTP port/server toggle, public base URL, download URL, kick text, URI template, modpack mode, and modpack URL. Legacy hyphenated keys are accepted for selected older settings.
   - `modpack: false` (default) preserves manifest-based synchronization. `modpack: true` omits the local `mods/` inventory from the manifest and puts the configured direct Modrinth `.mrpack` URL in the Sterling URI. The link must use HTTPS and end in `.mrpack`; CurseForge is not supported.
   - Supports `/sterling reload`, `/sterling sync`, and `/sterling status`, gated by the current Minecraft gamemaster permission.
   - Example modpack configuration:
     ```yaml
     modpack: true
     modpack-download-link: "https://cdn.modrinth.com/data/<project>/versions/<version>/<pack>.mrpack"
     ```

2. **Sterling AutoMod (`sterling-automod`)** — *Fabric client mod / in-game installer*
   - **Current status: approximately 60% of the AutoMod MVP vision.** The verified download-and-install path exists, but this is not yet a standalone launcher and requires the player to install Fabric and AutoMod into a profile first.
   - Current target is Minecraft 26.3, Fabric Loader 0.19.5+, and Java 25+.
   - Detects Sterling `sterling://connect` links on disconnect screens and Fabric registry-mismatch disconnects. Remembers the attempted server address because Minecraft may clear the active connection before showing the disconnect screen.
   - Downloads and validates Bridge manifests, downloads required JARs, verifies SHA-256, stages downloads before changing the active profile, skips files already matching the expected hash, and backs up same-name files it replaces. Downloads and installs in the background while reporting progress.
   - Supports direct Modrinth `.mrpack` downloads: reads `modrinth.index.json`, downloads client-side `mods/*.jar` entries and bundled mod JARs under `overrides/mods` / `client-overrides/mods`, and checks indexed files' SHA-1 and SHA-512. Applies client overrides over common overrides for identically named JARs. Files are staged before installation.
   - Rejects CurseForge ZIP/download links with an explanatory unsupported-platform error. It does not call CurseForge APIs.
   - After a successful install, the player must close and relaunch Minecraft manually, then reconnect. Fabric cannot load newly downloaded mods into the already-running JVM.
   - Never removes client mods not listed by the server. If required files are already present but registry mismatch remains, it warns that extra/incompatible mods may be present and recommends a clean profile.
   - Registry-mismatch fallback assumes the manifest is available from the attempted server host on port 8080. A custom manifest or pack URL should be passed in the Sterling URI.

3. **Sterling Client and Launcher (`Sterling-Launcher`)** — *Early Windows desktop prototype using Tauri 2, TypeScript, and Rust; planned in-game client features are not implemented*
   - Validates pasted `sterling://connect` links in the Rust backend and displays the server and manifest addresses.
   - The Microsoft sign-in, Fabric profile installation, per-server instance, AutoMod bundling, and Minecraft launch flows are not implemented yet.
   - Microsoft OAuth requires a registered public desktop application and its Client ID; no client secret should be embedded in the launcher.
   - Features:
     - Custom Multiplayer Menu featuring **"Modded Servers"** tab and **"Featured Modded Servers"** (Sponsored Ad-Network).
     - Single-click joining for any modded server in the Sterling network.
     - Built-in Microsoft OAuth authentication.

4. **Sterling.jar (`sterling-jar`)** — *Planned custom hybrid server core; not implemented*
   - Target Tech: Advanced Java Server Core combining Fabric/Forge mod support with Spigot/Paper plugin API.
   - Proposed feature: Native out-of-the-box integration with Sterling Bridge and cloud CDN sync.

---

## 📊 3. Current Completion and Product Scope

Percentages are rough engineering/product estimates against each component's stated MVP, not test coverage or a promise of delivery.

| Component | Estimated completion | What is working | Main remaining work |
|---|---:|---|---|
| Sterling Bridge (`sterling-bridge2`) | ~70% | Fabric registry-sync kick, configurable kick/link, manifest and embedded HTTP delivery, mod exclusions, Modrinth lookup, `.mrpack` link mode, commands | Real-server testing of modpack mode; config/reload robustness; stronger compatibility and error reporting; more platforms (Paper/Spigot/Velocity/Forge) |
| Sterling AutoMod (`sterling-automod`) | ~60% | Disconnect/link detection, verified manifest downloads, Modrinth pack parsing and hash checks, safe staged installation, replacement backups, manual-restart guidance | Test against real published packs and server; secure redirect/CDN behavior; clean-profile/launcher integration; better compatibility reporting; CurseForge support only if an approved API integration is designed |
| Sterling Client and Launcher (`Sterling-Launcher`) | ~5% | Tauri desktop shell and validated Sterling-link input | Microsoft OAuth, Fabric installation, isolated instances, AutoMod bundling, game launch, deep-link handling, in-game client features |
| Sterling.jar | 0% | Product requirements only | Hybrid server core and integration |
| End-to-end Vanilla-to-server experience | ~40% | The Bridge-to-AutoMod download/install path is implemented and the user has confirmed the modded server connection works | Launcher authentication, new-profile creation, Fabric/AutoMod installation, real `.mrpack` validation, launch/reconnect UX, broader client/server compatibility |

### What “complete” means for the two current mods

- **Bridge MVP is not complete for every platform in the original vision.** The codebase is a Fabric mod, not a Paper/Spigot plugin. Its current client detection is based on Fabric registry synchronization, not general brand/channel classification.
- **AutoMod is an in-game installer, not a standalone launcher.** The player must already have a Fabric profile with AutoMod installed. It modifies that active profile and does not create a separate profile for each server.
- **Modpack mode has unit-test coverage but has not yet been verified against a real Modrinth pack URL in a live client/server test.** Actual download host behavior, pack contents, loader/version compatibility, and the complete reconnect flow remain acceptance-test items.
- **A hash from an HTTPS manifest/index verifies downloaded bytes against the publisher-provided metadata; it does not independently establish that the publisher or pack is trustworthy.** Players should only use links from server owners they trust.
- **The active-instance approach assumes a fresh profile per server**, as recommended in player-facing setup instructions. If a user reuses a profile, AutoMod does not remove unrelated mods and cannot reliably identify which extra JAR caused a mismatch.

---

## 💰 4. Planned Business Model & Monetization Strategy

1. **End-Users (Players):** 100% FREE (Zero friction).
2. **Server Creators (B2C):**
   - *Free Tier:* Basic plugin, client detection, kick messages.
   - *Pro Tier ($10–$30/mo):* Auto-sync from server `mods/` folder to Cloud CDN, custom branding in Sterling Client, analytics, listing in "Modded Servers" tab.
3. **Server Hosting Companies (B2B):**
   - 1-Click Pterodactyl/Multicraft Addon integration.
   - 20-30% Affiliate Revenue Share or B2B SaaS licensing.
4. **In-Game Ad Network ("Featured Modded Servers"):**
   - Weekly sponsored placement auctions ($50–$200/week per slot).

---

## 🗺️ 5. Development Roadmap

### Near-term: make the two current Fabric mods dependable
- [ ] Test `modpack: true` against real Modrinth download-button URLs, a live Fabric server, and a fresh client profile.
- [ ] Confirm that common overrides, client overrides, required/optional client files, hash verification, replacement backups, errors, and restart/reconnect guidance behave correctly in end-to-end testing.
- [ ] Test `modpack: false` with multiple server/client mod combinations and document the `excludedMods` workflow.
- [ ] Improve Bridge configuration error reporting and reload behavior; ensure network listeners/config changes have explicit lifecycle handling.
- [ ] Document server networking requirements, including public access to the manifest/mod download endpoint and HTTP port or CDN setup.
- [ ] Publish player setup instructions that clearly require a fresh Fabric profile with AutoMod for each new server.
- [ ] Define compatibility policy for Minecraft/Fabric versions and detect incompatible pack dependencies before installation.

### Next: reduce manual setup
- [ ] Implement Sterling Launcher Microsoft OAuth using a registered public desktop application and PKCE; do not ship a client secret.
- [ ] Provision a per-server Minecraft instance with a compatible Fabric Loader and the Sterling AutoMod JAR.
- [ ] Launch Minecraft with the authenticated account and handle valid Sterling connection links.
- [ ] Provide a supported restart/reconnect path, or keep the manual restart requirement explicit.
- [ ] Consider CurseForge support only after selecting a compliant and secure API/integration approach.
- [ ] Evaluate broader server support (Paper/Spigot, Velocity, Forge) as separate projects; current Fabric event handling is not portable as-is.

### Later ecosystem work
- [ ] Define a stable manifest/API schema, hosting model, trust/authenticity model, and versioning policy.
- [ ] Build the server-owner web dashboard, CDN sync, analytics, and subscription capabilities.
- [ ] Build Sterling Client, account/auth, server discovery, and featured listings.
- [ ] Investigate hosting-provider integrations and the feasibility of Sterling.jar.

---

## 🛠️ 6. Technical Specifications for the Current Projects

### Sterling Bridge
- **Project path:** `sterling-bridge2/`
- **Implementation:** Java 25, Gradle, Fabric Loader 0.19.5, Fabric API, Minecraft 26.3.
- **Runtime flow:** loads `config/sterling/sterling.yml`, generates `config/sterling/manifest.json`, optionally serves `/manifest.json` and `/mods/<filename>` through an embedded HTTP server, and registers registry-compatibility handling before server configuration.
- **Manifest mode:** scans top-level `mods/*.jar`, computes SHA-256, attempts a Modrinth direct-file lookup, and falls back to the configured public base URL. `excludedMods` is matched case-insensitively against the JAR filename or its filename stem.
- **Modpack mode:** `modpack: true` stops scanning server JARs for the manifest, includes `modpackDownloadLink` in the generated manifest for registry-mismatch fallback, and sets the Sterling URI's `manifest` parameter to the URL-encoded `modpack-download-link`. The default kick message's `{uri_protocol}` uses this URI. `{modpack_download_link}` is available for custom messages; `{download_url}` resolves to the pack link in this mode.
- **Commands:** `/sterling reload`, `/sterling sync`, and `/sterling status`. Current permission gate is Minecraft's `COMMANDS_GAMEMASTER`, not a custom `sterling.admin` node.
- **Known operational constraints:** Bridge is currently Fabric-only. The embedded HTTP server needs to be reachable by clients or replaced by a reachable CDN/public base URL. Configuration load/save error handling and reload of HTTP listener lifecycle need further hardening. A direct Modrinth pack URL must be a downloadable HTTPS `.mrpack`; a project webpage is not sufficient.

### Sterling AutoMod
- **Project path:** `sterling-automod/`
- **Implementation:** Java 25, Gradle, Fabric client mod, Minecraft 26.3.
- **Manifest mode:** expects a Sterling JSON manifest with Fabric metadata and file entries that include a filename, download URL, and SHA-256. It bounds manifest size, mod size, and mod count.
- **Modrinth pack mode:** accepts a direct `.mrpack` URL; reads the `modrinth.index.json`; installs client entries whose paths are top-level `mods/*.jar`; downloads those entries over HTTPS and verifies SHA-1/SHA-512 and declared size; installs bundled top-level JARs in `overrides/mods` and `client-overrides/mods`. Client overrides replace common overrides with the same filename.
- **Installation safety:** stages the download before touching the active `mods` folder; rejects duplicate filenames; skips matching hashes; backs up overwritten same-name files beneath `sterling-mod-backups`; attempts to roll back installation failures. It does not delete unrelated JARs.
- **Unsupported pack behavior:** CurseForge ZIPs and non-direct Modrinth pack links are rejected with an explanatory error. AutoMod does not use CurseForge APIs.
- **UX:** English progress screen shows completed/total mods and the current mod filename above a short, thin progress bar. On success a full-height, translucent centered panel has an `Installed Mods` heading and a scrollable list of newly installed/already-current mods. A centered restart instruction appears above `Exit Game` and `Back to Server List` buttons. The user manually closes and relaunches Minecraft, then reconnects. It does not create a profile, install Fabric/AutoMod for a Vanilla user, restart the game, or launch the server automatically.
- **Known format/compatibility constraints:** installs only mod JARs, not pack configs, resource packs, shader packs, or other override files. It does not currently enforce the Minecraft/Fabric dependency versions declared by the pack before installing. Optional client entries are skipped. HTTP redirects are disabled, so a pack or file host that requires redirects may fail. It relies on the pack/index publisher's hashes and the user trusting the configured URL.
- **Registry mismatch fallback:** recognizes both “registry entries” and “registries” Fabric disconnect wording. Without a Sterling URI it guesses `http://<server-host>:8080/manifest.json`; in modpack mode the Bridge manifest provides the configured pack URL for AutoMod to follow.

### Build and test commands
- In `sterling-bridge2/`: `.\gradlew.bat clean build`
- In `sterling-automod/`: `.\gradlew.bat clean build`
- Both projects have unit tests for configuration, link/placeholder handling, manifest generation, hash validation, installation, and Modrinth pack parsing. Passing unit tests does not replace a live server/client acceptance test.

---

## 🤖 7. Instructions for AI Agents Working on This Codebase

When developing components within this repository:
1. **Always Verify Changes:** After writing or editing code, verify file creation and modifications using read-only checks.
2. **Follow the project's actual Java target:** Current Fabric projects target Java 25 and Minecraft 26.3; do not apply the original Java 21/Paper design as if it were already implemented.
3. **Write Unit Tests:** Ensure tests cover configuration loading, placeholder processing, URI encoding, manifests, download validation, modpack parsing, backup/rollback, and compatibility handling.
4. **Pre-Commit Checks:** Always run pre-commit verification steps before finalizing code changes.