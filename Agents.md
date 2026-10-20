# AGENTS.md — Sterling Ecosystem Specifications & Development Guide

Welcome to the **Sterling Ecosystem** repository. This document serves as the master specification, architectural guide, and operational instructions for developers and AI agents working on the Sterling project.

---

## 🌐 1. Project Overview & Vision

**Sterling** is a revolutionary 4-component ecosystem designed to eliminate onboarding friction for Vanilla Minecraft players wanting to play on modded Minecraft Java servers.

### The Problem
Over 80% of Minecraft Java players play Vanilla. Joining a modded server manually requires installing Java runtimes, Forge/Fabric loaders, matching exact `.jar` file versions, and configuring RAM—causing over 80% of prospective players to drop off.

### The Sterling Solution
With Sterling, a Vanilla player attempts to join a modded server, gets an intelligent kick message, clicks a link (or custom URI protocol `sterling://`), and **Sterling's AutoMod Launcher** downloads the exact required modpack (verifying SHA256 hashes) and launches Minecraft with auto-connect in one click.

---

## 🛡️ 2. The 4 Components of the Sterling Ecosystem

1. **Sterling's Bridge (`sterling-bridge`)** — *Server Plugin / Mod*
   - Target Platforms: Paper/Spigot, Velocity Proxy, Fabric Server-Side, Forge Server-Side.
   - Responsibilities:
     - Intercepts player login handshake packets and `minecraft:brand`.
     - Classifies players into `vanilla`, `fabric`, `forge`, or `sterling-client`.
     - Disconnects non-compliant players with formatted kick messages containing deep-link URI protocol links (`sterling://connect?ip=...&manifest=...`) and HTTPS download URLs.
     - Configurable via `config.yml` with support for Kyori Adventure text formatting and dynamic placeholders.

2. **Sterling's AutoMod Launcher (`sterling-automod-launcher`)** — *Sync Utility & Protocol Handler*
   - Target Tech: C# (.NET / WPF), Rust, or lightweight Fabric Client Mod.
   - Responsibilities:
     - Registers as OS Protocol Handler for `sterling://`.
     - Fetches `manifest.json` from server/CDN.
     - Scans local mods folder, verifies SHA256 hashes, downloads missing/outdated `.jar` files.
     - Automatically launches Minecraft with arguments `--server <IP> --port <PORT>`.

3. **Sterling Client (`sterling-client`)** — *Custom Client & Launcher Experience*
   - Target Tech: Custom Launcher / In-game Client Mod.
   - Features:
     - Custom Multiplayer Menu featuring **"Modded Servers"** tab and **"Featured Modded Servers"** (Sponsored Ad-Network).
     - Single-click joining for any modded server in the Sterling network.
     - Built-in Microsoft OAuth authentication.

4. **Sterling.jar (`sterling-jar`)** — *Custom Hybrid Server Core*
   - Target Tech: Advanced Java Server Core combining Fabric/Forge mod support with Spigot/Paper plugin API.
   - Features: Native out-of-the-box integration with Sterling's Bridge & cloud CDN sync.

---

## 💰 3. Business Model & Monetization Strategy

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

## 🗺️ 4. Development Roadmap

### Phase 1: Core MVP (Current Target)
- [ ] Build **Sterling's Bridge** Paper/Spigot Plugin (Java 21, Gradle).
- [ ] Define `manifest.json` schema and backend API specification.
- [ ] Build **Sterling's AutoMod Launcher** URI protocol handler and SHA256 downloader.

### Phase 2: Web Platform & In-Game Experience
- [ ] Web Dashboard for server owners (Stripe subscriptions, CDN upload, analytics).
- [ ] **Sterling Client** custom Multiplayer menu with "Modded Servers" and "Featured" tabs.

### Phase 3: B2B & Hybrid Core
- [ ] Pterodactyl 1-Click Addon for hosting providers.
- [ ] **Sterling.jar** Hybrid Server Core development.

---

## 🛠️ 5. Technical Specifications for Sterling's Bridge

- **Language:** Java 21
- **Build System:** Gradle (Kotlin DSL / Groovy DSL)
- **API Dependencies:** Paper API (1.20.x / 1.21.x), Kyori Adventure
- **Key Features:**
  - `config.yml` with customizable brand rules, kick messages, and placeholders (`{server_ip}`, `{server_port}`, `{manifest_url}`, `{download_url}`).
  - Command `/bridge reload` (permission: `sterling.admin`).
  - Event listener on `PlayerJoinEvent` / `AsyncPlayerPreLoginEvent` inspecting plugin channels (`minecraft:brand`).

---

## 🤖 6. Instructions for AI Agents Working on This Codebase

When developing components within this repository:
1. **Always Verify Changes:** After writing or editing code, verify file creation and modifications using read-only checks.
2. **Follow Java 21 & Modern Standards:** Use clean object-oriented architecture, proper logging, and comprehensive configuration management.
3. **Write Unit Tests:** Ensure test suites cover configuration loading, placeholder processing, and packet handling logic.
4. **Pre-Commit Checks:** Always run pre-commit verification steps before finalizing code changes.