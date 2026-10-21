# Sterling Launcher

The Sterling Launcher is an early Windows desktop application built with Tauri 2, TypeScript, and Rust. Its goal is to make the existing Bridge + AutoMod flow easier to start from a clean, server-specific Minecraft instance.

## Current prototype

- Black and silver desktop UI for the Sterling connection flow.
- Validates pasted `sterling://connect` links in the Rust backend.
- Shows the Minecraft server address and manifest URL from a valid link.
- Rejects invalid ports, unsupported manifest schemes, and manifest URLs containing credentials.
- Loads Minecraft release, snapshot, alpha, and beta version IDs from Mojang's official version manifest; experimental channels remain hidden until enabled in Settings.
- Checks available Fabric builds from Fabric Meta and Forge builds from the official Forge Maven metadata for the selected Minecraft version. The latest stable Fabric build or Forge recommended build is selected by default and remains changeable.
- Creates and stores multiple local instance profiles with a Minecraft version, compatible loader/build, and version channel.
- Includes a Settings toggle for showing snapshot, alpha, and beta channels.
- Installs Vanilla profiles into isolated folders under `%APPDATA%\.sterling\instances`, downloading Mojang metadata, client files, libraries, and assets with SHA-1 verification and visible progress.
- Signs in with Microsoft using Authorization Code + PKCE, exchanges the account through Xbox Live/XSTS, and verifies the Minecraft Java profile. OAuth tokens remain in memory for the current launcher session and are not persisted.

Instance profiles and the experimental-version setting are local UI configuration. Vanilla installation and Microsoft/Minecraft profile verification are implemented; Fabric/Forge installation, persistent secure token storage, per-server AutoMod bundling, and Minecraft launch are not connected yet.

The Microsoft app registration must support personal Microsoft accounts and have a **Mobile and desktop applications** redirect platform with `http://localhost` enabled. In Microsoft Entra, open **App registrations → Sterling Launcher → Authentication → Add a platform → Mobile and desktop applications**, add `http://localhost`, and save. Sterling uses a loopback redirect with a dynamic port and the public client ID configured in `src-tauri/src/lib.rs`; do not add a client secret. Sign-in requires a Minecraft Java profile and internet access to Microsoft, Xbox, and Minecraft services.

## Development

Requirements: Node.js LTS, Rust stable, and the Windows build tools required by Tauri.

```powershell
npm install
npm run tauri dev
```

Run the frontend type-check/build separately:

```powershell
npm run build
```

Run the Rust parser tests:

```powershell
Set-Location src-tauri
cargo test
```

Run the optional Mojang download acceptance test (downloads about 112 MiB and installs only under a temporary directory):

```powershell
cargo test installs_mojang_vanilla_files_in_a_temporary_directory -- --ignored --nocapture
```
