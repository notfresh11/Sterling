use base64::Engine;
use base64::engine::general_purpose::URL_SAFE_NO_PAD;
use futures_util::{StreamExt, stream};
use rand::RngCore;
use serde::{Deserialize, Serialize};
use sha1::{Digest, Sha1};
use sha2::Sha256;
use std::path::{Component, Path, PathBuf};
use std::sync::{
    Arc,
    atomic::{AtomicUsize, Ordering},
};
use std::sync::{Mutex, OnceLock};
use tauri::{AppHandle, Emitter};
use tauri_plugin_opener::OpenerExt;
use url::Url;

const MICROSOFT_CLIENT_ID: &str = "77d65f6f-9a27-4f46-a5f4-f95bb30e3cd1";
const MICROSOFT_AUTHORITY: &str = "https://login.microsoftonline.com/consumers/oauth2/v2.0";

#[derive(Debug, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
struct ServerLink {
    server_address: String,
    server_port: u16,
    manifest_url: String,
}

#[derive(Debug, Deserialize)]
struct MojangVersionManifest {
    versions: Vec<MojangVersion>,
}

#[derive(Debug, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
struct MojangVersion {
    id: String,
    #[serde(rename(deserialize = "type", serialize = "versionType"))]
    version_type: String,
}

#[derive(Debug, Deserialize)]
struct FabricLoaderVersion {
    loader: FabricLoader,
    #[serde(default)]
    stable: bool,
}

#[derive(Debug, Deserialize)]
struct FabricLoader {
    version: String,
}

#[derive(Debug, Deserialize)]
struct ForgeMetadata {
    versioning: ForgeVersioning,
}

#[derive(Debug, Deserialize)]
struct ForgeVersioning {
    versions: ForgeVersions,
}

#[derive(Debug, Deserialize)]
struct ForgeVersions {
    #[serde(rename = "version", default)]
    version: Vec<String>,
}

#[derive(Debug, Deserialize)]
struct ForgePromotions {
    promos: std::collections::HashMap<String, String>,
}

#[derive(Debug, Deserialize, Serialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
struct LoaderCatalog {
    versions: Vec<String>,
    default_version: Option<String>,
}

#[derive(Debug, Clone)]
struct DownloadJob {
    url: String,
    relative_path: PathBuf,
    sha1: String,
    size: Option<u64>,
}

#[derive(Debug, Serialize, Clone)]
#[serde(rename_all = "camelCase")]
struct InstallProgress {
    instance_id: String,
    completed: usize,
    total: usize,
    current_file: String,
    phase: String,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
struct InstallResult {
    instance_id: String,
    version: String,
    install_directory: String,
}

#[derive(Debug, Serialize, Clone)]
#[serde(rename_all = "camelCase")]
struct MinecraftAccount {
    name: String,
    uuid: String,
}

struct MinecraftSession {
    _access_token: String,
    account: MinecraftAccount,
}

#[derive(Deserialize)]
struct MicrosoftTokenResponse {
    access_token: String,
}

static MINECRAFT_SESSION: OnceLock<Mutex<Option<MinecraftSession>>> = OnceLock::new();

#[tauri::command]
fn parse_sterling_link(raw_link: String) -> Result<ServerLink, String> {
    parse_link(&raw_link)
}

#[tauri::command]
async fn sign_in_with_microsoft(app: AppHandle) -> Result<MinecraftAccount, String> {
    let listener = tokio::net::TcpListener::bind(("127.0.0.1", 0))
        .await
        .map_err(|error| format!("Could not start the sign-in callback: {error}"))?;
    let port = listener
        .local_addr()
        .map_err(|error| format!("Could not prepare the sign-in callback: {error}"))?
        .port();
    let redirect_uri = format!("http://localhost:{port}");
    let verifier = random_url_safe(32);
    let challenge = URL_SAFE_NO_PAD.encode(Sha256::digest(verifier.as_bytes()));
    let state = random_url_safe(32);
    let mut authorization_url = Url::parse(&format!("{MICROSOFT_AUTHORITY}/authorize"))
        .map_err(|error| format!("Could not prepare Microsoft sign-in: {error}"))?;
    authorization_url.query_pairs_mut().extend_pairs([
        ("client_id", MICROSOFT_CLIENT_ID),
        ("response_type", "code"),
        ("redirect_uri", redirect_uri.as_str()),
        ("response_mode", "query"),
        ("scope", "XboxLive.signin offline_access"),
        ("code_challenge", challenge.as_str()),
        ("code_challenge_method", "S256"),
        ("state", state.as_str()),
        ("prompt", "select_account"),
    ]);
    app.opener()
        .open_url(authorization_url.as_str(), None::<&str>)
        .map_err(|error| format!("Could not open the Microsoft sign-in page: {error}"))?;

    let callback = tokio::time::timeout(std::time::Duration::from_secs(180), async {
        let (mut stream, _) = listener
            .accept()
            .await
            .map_err(|error| format!("Could not receive the Microsoft sign-in response: {error}"))?;
        use tokio::io::{AsyncReadExt, AsyncWriteExt};

        let mut request = vec![0; 8192];
        let read = tokio::time::timeout(
            std::time::Duration::from_secs(10),
            stream.read(&mut request),
        )
        .await
        .map_err(|_| "The Microsoft sign-in response timed out.".to_string())?
        .map_err(|error| format!("Could not read the Microsoft sign-in response: {error}"))?;
        request.truncate(read);
        let request = String::from_utf8(request)
            .map_err(|_| "Microsoft returned an invalid sign-in response.".to_string())?;
        let callback_result = parse_oauth_callback(&request, &state);
        let (status, message) = if callback_result.is_ok() {
            ("200 OK", "Sign-in completed. You can close this window and return to Sterling.")
        } else {
            (
                "400 Bad Request",
                "Sign-in could not be completed. Return to Sterling and try again.",
            )
        };
        let response = format!(
            "HTTP/1.1 {status}\r\nContent-Type: text/html; charset=utf-8\r\nConnection: close\r\nContent-Length: {}\r\n\r\n{message}",
            message.len()
        );
        let _ = stream.write_all(response.as_bytes()).await;
        callback_result
    })
    .await
    .map_err(|_| "Microsoft sign-in timed out. Please try again.".to_string())??;

    let client = reqwest::Client::builder()
        .timeout(std::time::Duration::from_secs(30))
        .build()
        .map_err(|error| format!("Could not prepare Microsoft sign-in: {error}"))?;
    let token_form = url::form_urlencoded::Serializer::new(String::new())
        .extend_pairs([
            ("client_id", MICROSOFT_CLIENT_ID),
            ("grant_type", "authorization_code"),
            ("code", callback.as_str()),
            ("redirect_uri", redirect_uri.as_str()),
            ("code_verifier", verifier.as_str()),
            ("scope", "XboxLive.signin offline_access"),
        ])
        .finish();
    let token_response = client
        .post(format!("{MICROSOFT_AUTHORITY}/token"))
        .header("Content-Type", "application/x-www-form-urlencoded")
        .body(token_form)
        .send()
        .await
        .map_err(|error| format!("Could not contact Microsoft sign-in: {error}"))?;
    if !token_response.status().is_success() {
        return Err(
            "Microsoft could not complete sign-in. Check the app registration and try again."
                .to_string(),
        );
    }
    let microsoft_token: MicrosoftTokenResponse = token_response
        .json()
        .await
        .map_err(|error| format!("Microsoft returned an invalid sign-in response: {error}"))?;

    let xbox_response = client
        .post("https://user.auth.xboxlive.com/user/authenticate")
        .json(&serde_json::json!({
            "Properties": {
                "AuthMethod": "RPS",
                "SiteName": "user.auth.xboxlive.com",
                "RpsTicket": format!("d={}", microsoft_token.access_token),
            },
            "RelyingParty": "http://auth.xboxlive.com",
            "TokenType": "JWT",
        }))
        .send()
        .await
        .map_err(|error| format!("Could not contact Xbox sign-in: {error}"))?;
    let xbox_status = xbox_response.status();
    let xbox_body = xbox_response
        .bytes()
        .await
        .map_err(|error| format!("Could not read the Xbox sign-in response: {error}"))?;
    if !xbox_status.is_success() {
        return Err(format_xbox_auth_error(xbox_status, &xbox_body));
    }
    let xbox_token_json: serde_json::Value = serde_json::from_slice(&xbox_body)
        .map_err(|error| format!("Xbox returned an invalid sign-in response: {error}"))?;
    let user_hash = xbox_token_user_hash(&xbox_token_json)?;
    let xbox_token = xbox_token_json["Token"]
        .as_str()
        .ok_or_else(|| "Xbox did not return a sign-in token.".to_string())?
        .to_string();

    let xsts_response = client
        .post("https://xsts.auth.xboxlive.com/xsts/authorize")
        .json(&serde_json::json!({
            "Properties": {
                "SandboxId": "RETAIL",
                "UserTokens": [xbox_token],
            },
            "RelyingParty": "rp://api.minecraftservices.com/",
            "TokenType": "JWT",
        }))
        .send()
        .await
        .map_err(|error| format!("Could not verify the Xbox account: {error}"))?;
    let xsts_status = xsts_response.status();
    let xsts_body = xsts_response
        .bytes()
        .await
        .map_err(|error| format!("Could not read the Xbox authorization response: {error}"))?;
    if !xsts_status.is_success() {
        return Err(format_xbox_auth_error(xsts_status, &xsts_body));
    }
    let xsts_token: serde_json::Value = serde_json::from_slice(&xsts_body)
        .map_err(|error| format!("Xbox returned an invalid authorization response: {error}"))?;
    let xsts_token = xsts_token["Token"]
        .as_str()
        .ok_or_else(|| "Xbox did not return a Minecraft authorization token.".to_string())?;

    let minecraft_response = client
        .post("https://api.minecraftservices.com/authentication/login_with_xbox")
        .json(&serde_json::json!({
            "identityToken": format!("XBL3.0 x={user_hash};{xsts_token}"),
        }))
        .send()
        .await
        .map_err(|error| format!("Could not contact Minecraft services: {error}"))?;
    let minecraft_status = minecraft_response.status();
    let minecraft_body = minecraft_response
        .bytes()
        .await
        .map_err(|error| format!("Could not read the Minecraft sign-in response: {error}"))?;
    if !minecraft_status.is_success() {
        return Err(format_service_auth_error(
            "Minecraft",
            minecraft_status,
            &minecraft_body,
        ));
    }
    let minecraft_token: serde_json::Value = serde_json::from_slice(&minecraft_body)
        .map_err(|error| format!("Minecraft returned an invalid sign-in response: {error}"))?;
    let access_token = minecraft_token["access_token"]
        .as_str()
        .ok_or_else(|| "Minecraft did not return an access token.".to_string())?
        .to_string();
    let profile_response = client
        .get("https://api.minecraftservices.com/minecraft/profile")
        .bearer_auth(&access_token)
        .send()
        .await
        .map_err(|error| format!("Could not retrieve the Minecraft profile: {error}"))?;
    if !profile_response.status().is_success() {
        return Err(
            "This Microsoft account does not have an available Minecraft Java profile.".to_string(),
        );
    }
    let profile: serde_json::Value = profile_response
        .json()
        .await
        .map_err(|error| format!("Minecraft returned an invalid profile: {error}"))?;
    let account = MinecraftAccount {
        name: profile["name"]
            .as_str()
            .ok_or_else(|| "Minecraft profile is missing its player name.".to_string())?
            .to_string(),
        uuid: profile["id"]
            .as_str()
            .ok_or_else(|| "Minecraft profile is missing its player ID.".to_string())?
            .to_string(),
    };
    let session = MINECRAFT_SESSION.get_or_init(|| Mutex::new(None));
    *session
        .lock()
        .map_err(|_| "The sign-in session could not be saved.".to_string())? =
        Some(MinecraftSession {
            _access_token: access_token,
            account: account.clone(),
        });
    Ok(account)
}

#[tauri::command]
fn get_minecraft_account() -> Result<Option<MinecraftAccount>, String> {
    let Some(session) = MINECRAFT_SESSION.get() else {
        return Ok(None);
    };
    let session = session
        .lock()
        .map_err(|_| "The sign-in session could not be read.".to_string())?;
    Ok(session.as_ref().map(|session| session.account.clone()))
}

#[tauri::command]
fn sign_out_microsoft() -> Result<(), String> {
    if let Some(session) = MINECRAFT_SESSION.get() {
        *session
            .lock()
            .map_err(|_| "The sign-in session could not be cleared.".to_string())? = None;
    }
    Ok(())
}

#[tauri::command]
async fn get_minecraft_versions() -> Result<Vec<MojangVersion>, String> {
    const VERSION_MANIFEST_URL: &str =
        "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json";
    let body = fetch_catalog(
        VERSION_MANIFEST_URL,
        "Mojang's Minecraft version catalog",
        5 * 1024 * 1024,
    )
    .await?;
    let manifest: MojangVersionManifest = serde_json::from_slice(&body)
        .map_err(|error| format!("Mojang's Minecraft version catalog was invalid: {error}"))?;

    Ok(manifest.versions)
}

#[tauri::command]
async fn install_vanilla_instance(
    app: AppHandle,
    instance_id: String,
    version_id: String,
) -> Result<InstallResult, String> {
    if !is_valid_instance_id(&instance_id) {
        return Err("The instance identifier is invalid.".to_string());
    }
    let base_directory = instance_directory(&instance_id, true)?
        .ok_or_else(|| "Could not create the Sterling instance directory.".to_string())?;
    install_vanilla_instance_into(instance_id, version_id, base_directory, move |progress| {
        let _ = app.emit("vanilla-install-progress", progress);
    })
    .await
}

async fn install_vanilla_instance_into(
    instance_id: String,
    version_id: String,
    base_directory: PathBuf,
    emit_progress: impl Fn(InstallProgress) + Clone + Send + Sync + 'static,
) -> Result<InstallResult, String> {
    if !is_valid_instance_id(&instance_id) {
        return Err("The instance identifier is invalid.".to_string());
    }
    if version_id.is_empty()
        || version_id.len() > 64
        || !version_id.chars().all(|character| {
            character.is_ascii_alphanumeric() || matches!(character, '.' | '-' | '_')
        })
    {
        return Err("The Minecraft version identifier is invalid.".to_string());
    }
    emit_progress(InstallProgress {
        instance_id: instance_id.clone(),
        completed: 0,
        total: 1,
        current_file: version_id.clone(),
        phase: "Preparing installation".to_string(),
    });

    let manifest_body = fetch_catalog(
        "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json",
        "Mojang's Minecraft version catalog",
        5 * 1024 * 1024,
    )
    .await?;
    let manifest: MojangVersionManifest = serde_json::from_slice(&manifest_body)
        .map_err(|error| format!("Mojang's Minecraft version catalog was invalid: {error}"))?;
    let version_entry = manifest
        .versions
        .iter()
        .find(|version| version.id == version_id)
        .ok_or_else(|| "This Minecraft version is not in Mojang's official catalog.".to_string())?;
    let entry: serde_json::Value = serde_json::from_slice(&manifest_body)
        .map_err(|error| format!("Mojang's Minecraft version catalog was invalid: {error}"))?;
    let version_metadata = entry["versions"]
        .as_array()
        .and_then(|versions| versions.iter().find(|version| version["id"] == version_id))
        .ok_or_else(|| "Mojang's catalog entry is missing version metadata.".to_string())?;
    let metadata_url = version_metadata["url"]
        .as_str()
        .ok_or_else(|| "Mojang's catalog entry is missing its metadata URL.".to_string())?;
    require_https_url(metadata_url)?;
    let metadata_sha1 = version_metadata["sha1"]
        .as_str()
        .ok_or_else(|| "Mojang's catalog entry is missing its metadata checksum.".to_string())?;
    validate_sha1(metadata_sha1)?;
    let metadata_bytes =
        fetch_catalog(metadata_url, "Minecraft version metadata", 20 * 1024 * 1024).await?;
    verify_sha1(&metadata_bytes, metadata_sha1, "Minecraft version metadata")?;
    let version_json: serde_json::Value = serde_json::from_slice(&metadata_bytes)
        .map_err(|error| format!("Minecraft version metadata was invalid: {error}"))?;
    let version_directory = base_directory.join("versions").join(&version_id);
    std::fs::create_dir_all(&version_directory)
        .map_err(|error| format!("Could not create the instance directory: {error}"))?;
    let resolved_version_directory = std::fs::canonicalize(&version_directory)
        .map_err(|error| format!("Could not resolve the Minecraft version directory: {error}"))?;
    if !resolved_version_directory.starts_with(&base_directory) {
        return Err(
            "The Minecraft version directory is outside the Sterling instance.".to_string(),
        );
    }
    let version_json_path = version_directory.join(format!("{version_id}.json"));
    std::fs::write(&version_json_path, &metadata_bytes)
        .map_err(|error| format!("Could not save Minecraft version metadata: {error}"))?;

    let mut jobs = Vec::new();
    jobs.push(job_from_download(
        &version_json["downloads"]["client"],
        &format!("versions/{version_id}/{version_id}.jar"),
        "Minecraft client",
    )?);
    if let Some(log_config) = version_json["logging"]["client"]["file"].as_object() {
        let file = serde_json::Value::Object(log_config.clone());
        jobs.push(job_from_download(
            &file,
            &format!(
                "assets/log_configs/{}.json",
                file["id"].as_str().ok_or_else(|| {
                    "Minecraft logging metadata is missing its file identifier.".to_string()
                })?
            ),
            "Minecraft logging configuration",
        )?);
    }

    if let Some(libraries) = version_json["libraries"].as_array() {
        for library in libraries {
            if library.get("downloads").is_none() {
                return Err(
                    "This Minecraft version uses legacy library metadata that Sterling cannot safely verify yet."
                        .to_string(),
                );
            }
            if let Some(artifact) = library
                .get("downloads")
                .and_then(|value| value.get("artifact"))
            {
                if artifact.is_object() {
                    jobs.push(job_from_download(
                        artifact,
                        artifact["path"].as_str().ok_or_else(|| {
                            "A Minecraft library is missing its relative path.".to_string()
                        })?,
                        "Minecraft library",
                    )?);
                }
            }
            if library["natives"]["windows"].is_string() {
                let classifier = library["natives"]["windows"]
                    .as_str()
                    .unwrap_or_default()
                    .replace("${arch}", "64");
                if let Some(native) = library["downloads"]["classifiers"].get(&classifier) {
                    if native.is_object() {
                        jobs.push(job_from_download(
                            native,
                            native["path"].as_str().ok_or_else(|| {
                                "A Minecraft native library is missing its relative path."
                                    .to_string()
                            })?,
                            "Minecraft native library",
                        )?);
                    }
                }
            }
        }
    }

    let asset_index = &version_json["assetIndex"];
    if asset_index.is_object() {
        let asset_index_job = job_from_download(
            asset_index,
            &format!(
                "assets/indexes/{}.json",
                asset_index["id"]
                    .as_str()
                    .ok_or_else(|| "Minecraft asset index is missing its identifier.".to_string())?
            ),
            "Minecraft asset index",
        )?;
        let asset_index_path = base_directory.join(&asset_index_job.relative_path);
        let asset_index_bytes = fetch_catalog(
            &asset_index_job.url,
            "Minecraft asset index",
            50 * 1024 * 1024,
        )
        .await?;
        verify_sha1(
            &asset_index_bytes,
            &asset_index_job.sha1,
            "Minecraft asset index",
        )?;
        std::fs::create_dir_all(asset_index_path.parent().expect("asset index has parent"))
            .map_err(|error| format!("Could not create the asset index directory: {error}"))?;
        let asset_parent =
            std::fs::canonicalize(asset_index_path.parent().expect("asset index has parent"))
                .map_err(|error| {
                    format!("Could not resolve the Minecraft asset index directory: {error}")
                })?;
        if !asset_parent.starts_with(&base_directory) {
            return Err(
                "The Minecraft asset index directory is outside the Sterling instance.".to_string(),
            );
        }
        std::fs::write(&asset_index_path, &asset_index_bytes)
            .map_err(|error| format!("Could not save the asset index: {error}"))?;
        let assets: serde_json::Value = serde_json::from_slice(&asset_index_bytes)
            .map_err(|error| format!("Minecraft asset index was invalid: {error}"))?;
        let objects = assets["objects"]
            .as_object()
            .ok_or_else(|| "Minecraft asset index does not contain an objects map.".to_string())?;
        for object in objects.values() {
            let hash = object["hash"]
                .as_str()
                .ok_or_else(|| "A Minecraft asset is missing its hash.".to_string())?;
            validate_sha1(hash)?;
            let size = object["size"]
                .as_u64()
                .ok_or_else(|| "A Minecraft asset is missing its size.".to_string())?;
            jobs.push(DownloadJob {
                url: format!(
                    "https://resources.download.minecraft.net/{}/{}",
                    &hash[..2],
                    hash
                ),
                relative_path: PathBuf::from("assets")
                    .join("objects")
                    .join(&hash[..2])
                    .join(hash),
                sha1: hash.to_string(),
                size: Some(size),
            });
        }
    }

    let mut unique_jobs: std::collections::HashMap<PathBuf, DownloadJob> =
        std::collections::HashMap::new();
    for job in jobs {
        if let Some(existing) = unique_jobs.get(&job.relative_path) {
            if existing.sha1 != job.sha1 {
                return Err("Minecraft metadata maps different files to the same path.".to_string());
            }
            continue;
        }
        unique_jobs.insert(job.relative_path.clone(), job);
    }
    let jobs: Vec<DownloadJob> = unique_jobs.into_values().collect();
    let total = jobs.len();
    if total == 0 {
        return Err("Minecraft metadata did not include any files to install.".to_string());
    }
    emit_progress(InstallProgress {
        instance_id: instance_id.clone(),
        completed: 0,
        total,
        current_file: String::new(),
        phase: "Starting Minecraft downloads".to_string(),
    });
    let completed = Arc::new(AtomicUsize::new(0));
    let client = reqwest::Client::builder()
        .timeout(std::time::Duration::from_secs(60))
        .build()
        .map_err(|error| format!("Could not prepare Minecraft downloads: {error}"))?;
    let results: Vec<Result<(), String>> = stream::iter(jobs.into_iter().map(|job| {
        let client = client.clone();
        let emit_progress = emit_progress.clone();
        let instance_id = instance_id.clone();
        let root = base_directory.clone();
        let completed = Arc::clone(&completed);
        async move {
            let current_file = job.relative_path.to_string_lossy().to_string();
            let result = download_job(&client, &root, &job).await;
            let count = completed.fetch_add(1, Ordering::Relaxed) + 1;
            emit_progress(InstallProgress {
                instance_id,
                completed: count,
                total,
                current_file,
                phase: "Downloading Minecraft files".to_string(),
            });
            result
        }
    }))
    .buffer_unordered(8)
    .collect()
    .await;
    if let Some(error) = results.into_iter().find_map(Result::err) {
        return Err(error);
    }

    let marker = serde_json::json!({
        "version": version_id,
        "loader": "Vanilla",
        "installedAtUnixSeconds": std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .map_err(|error| format!("System clock is invalid: {error}"))?
            .as_secs(),
    });
    std::fs::write(
        base_directory.join("sterling-install.json"),
        serde_json::to_vec_pretty(&marker)
            .map_err(|error| format!("Could not serialize the installation record: {error}"))?,
    )
    .map_err(|error| format!("Could not save the installation record: {error}"))?;

    Ok(InstallResult {
        instance_id,
        version: version_entry.id.clone(),
        install_directory: base_directory.to_string_lossy().to_string(),
    })
}

fn instances_directory() -> Result<PathBuf, String> {
    let app_data = std::env::var_os("APPDATA")
        .map(PathBuf::from)
        .ok_or_else(|| "Could not locate the Windows AppData directory.".to_string())?;
    Ok(app_data.join(".sterling").join("instances"))
}

#[tauri::command]
fn delete_instance(instance_id: String) -> Result<(), String> {
    let Some(directory) = instance_directory(&instance_id, false)? else {
        return Ok(());
    };
    std::fs::remove_dir_all(directory)
        .map_err(|error| format!("Could not delete the Sterling instance files: {error}"))
}

fn instance_directory(instance_id: &str, create: bool) -> Result<Option<PathBuf>, String> {
    if !is_valid_instance_id(instance_id) {
        return Err("The instance identifier is invalid.".to_string());
    }
    let root = instances_directory()?;
    if create {
        std::fs::create_dir_all(&root).map_err(|error| {
            format!("Could not create the Sterling instances directory: {error}")
        })?;
    }
    if !root.exists() {
        return Ok(None);
    }
    let canonical_root = std::fs::canonicalize(&root)
        .map_err(|error| format!("Could not resolve the Sterling instances directory: {error}"))?;
    let directory = root.join(instance_id);
    if create {
        std::fs::create_dir_all(&directory).map_err(|error| {
            format!("Could not create the Sterling instance directory: {error}")
        })?;
    } else if !directory.exists() {
        return Ok(None);
    }
    let metadata = std::fs::symlink_metadata(&directory)
        .map_err(|error| format!("Could not inspect the Sterling instance directory: {error}"))?;
    if metadata.file_type().is_symlink() || !metadata.is_dir() {
        return Err("The Sterling instance path is not a regular directory.".to_string());
    }
    let canonical_directory = std::fs::canonicalize(&directory)
        .map_err(|error| format!("Could not resolve the Sterling instance directory: {error}"))?;
    if canonical_directory.parent() != Some(canonical_root.as_path()) {
        return Err("The Sterling instance directory is outside its managed folder.".to_string());
    }
    Ok(Some(canonical_directory))
}

fn is_valid_instance_id(value: &str) -> bool {
    value.len() == 36
        && value.chars().enumerate().all(|(index, character)| {
            if [8, 13, 18, 23].contains(&index) {
                character == '-'
            } else {
                character.is_ascii_hexdigit()
            }
        })
}

fn random_url_safe(byte_count: usize) -> String {
    let mut bytes = vec![0; byte_count];
    rand::rngs::OsRng.fill_bytes(&mut bytes);
    URL_SAFE_NO_PAD.encode(bytes)
}

fn parse_oauth_callback(request: &str, expected_state: &str) -> Result<String, String> {
    let request_target = request
        .lines()
        .next()
        .and_then(|line| line.split_whitespace().nth(1))
        .ok_or_else(|| "Microsoft returned an invalid sign-in response.".to_string())?;
    let callback_url = Url::parse(&format!("http://localhost{request_target}"))
        .map_err(|_| "Microsoft returned an invalid sign-in response.".to_string())?;
    let parameters: Vec<(String, String)> = callback_url
        .query_pairs()
        .map(|(key, value)| (key.into_owned(), value.into_owned()))
        .collect();
    let actual_state = parameters
        .iter()
        .find(|(key, _)| key == "state")
        .map(|(_, value)| value.as_str())
        .ok_or_else(|| "Microsoft sign-in did not include a state value.".to_string())?;
    if actual_state != expected_state {
        return Err("Microsoft sign-in state validation failed. Please try again.".to_string());
    }
    if parameters.iter().any(|(key, _)| key == "error") {
        return Err("Microsoft sign-in was cancelled or denied.".to_string());
    }
    parameters
        .iter()
        .find(|(key, _)| key == "code")
        .map(|(_, value)| value.clone())
        .filter(|code| !code.is_empty())
        .ok_or_else(|| "Microsoft did not return an authorization code.".to_string())
}

fn xbox_token_user_hash(token: &serde_json::Value) -> Result<String, String> {
    token["DisplayClaims"]["xui"]
        .as_array()
        .and_then(|claims| claims.first())
        .and_then(|claim| claim["uhs"].as_str())
        .filter(|hash| !hash.is_empty())
        .map(str::to_string)
        .ok_or_else(|| "Xbox did not return a valid user identifier.".to_string())
}

fn format_xbox_auth_error(status: reqwest::StatusCode, body: &[u8]) -> String {
    let code = serde_json::from_slice::<serde_json::Value>(body)
        .ok()
        .and_then(|response| response.get("XErr").and_then(serde_json::Value::as_u64));
    match code {
        Some(2148916233) => {
            "Xbox sign-in failed (XErr 2148916233): this Microsoft account may not have an Xbox profile yet. Create/sign in to an Xbox profile, then retry.".to_string()
        }
        Some(code) => format!(
            "Xbox rejected sign-in (HTTP {}, XErr {code}). Share this error code to diagnose the account or Xbox app configuration.",
            status.as_u16()
        ),
        None => format!(
            "Xbox rejected sign-in (HTTP {}). Retry later; if it persists, share the HTTP error code.",
            status.as_u16()
        ),
    }
}

fn format_service_auth_error(service: &str, status: reqwest::StatusCode, body: &[u8]) -> String {
    let details = serde_json::from_slice::<serde_json::Value>(body)
        .ok()
        .and_then(|response| {
            ["XErr", "error", "errorMessage"].iter().find_map(|key| {
                response.get(key).and_then(|value| {
                    if value.is_string() || value.is_number() {
                        Some(value.to_string().trim_matches('"').to_string())
                    } else {
                        None
                    }
                })
            })
        })
        .filter(|value| !value.is_empty())
        .map(|value| format!(", service code: {value}"))
        .unwrap_or_default();
    format!(
        "{service} sign-in was rejected (HTTP {}){details}. Check that the Microsoft account has an Xbox profile and owns Minecraft Java Edition.",
        status.as_u16()
    )
}

fn safe_relative_path(value: &str) -> Result<PathBuf, String> {
    if value.is_empty() || value.contains('\\') || value.contains(':') {
        return Err("Minecraft metadata contains an invalid file path.".to_string());
    }
    let path = Path::new(value);
    if path
        .components()
        .any(|component| !matches!(component, Component::Normal(_)))
    {
        return Err("Minecraft metadata contains an unsafe file path.".to_string());
    }
    Ok(path.to_path_buf())
}

fn job_from_download(
    value: &serde_json::Value,
    relative_path: &str,
    label: &str,
) -> Result<DownloadJob, String> {
    let url = value["url"]
        .as_str()
        .ok_or_else(|| format!("{label} is missing its download URL."))?;
    require_https_url(url)?;
    let checksum = value["sha1"]
        .as_str()
        .ok_or_else(|| format!("{label} is missing its SHA-1 checksum."))?;
    validate_sha1(checksum)?;
    Ok(DownloadJob {
        url: url.to_string(),
        relative_path: safe_relative_path(relative_path)?,
        sha1: checksum.to_ascii_lowercase(),
        size: value["size"].as_u64(),
    })
}

fn require_https_url(value: &str) -> Result<(), String> {
    let url =
        Url::parse(value).map_err(|_| "Minecraft metadata contains an invalid URL.".to_string())?;
    if url.scheme() != "https"
        || url.host_str().is_none()
        || !url.username().is_empty()
        || url.password().is_some()
    {
        return Err("Minecraft downloads must use HTTPS URLs without credentials.".to_string());
    }
    Ok(())
}

fn validate_sha1(value: &str) -> Result<(), String> {
    if value.len() != 40 || !value.bytes().all(|byte| byte.is_ascii_hexdigit()) {
        return Err("Minecraft metadata contains an invalid SHA-1 checksum.".to_string());
    }
    Ok(())
}

fn sha1_hex(bytes: &[u8]) -> String {
    let digest = Sha1::digest(bytes);
    digest.iter().map(|byte| format!("{byte:02x}")).collect()
}

fn verify_sha1(bytes: &[u8], expected: &str, label: &str) -> Result<(), String> {
    if !sha1_hex(bytes).eq_ignore_ascii_case(expected) {
        return Err(format!("{label} failed SHA-1 verification."));
    }
    Ok(())
}

async fn download_job(
    client: &reqwest::Client,
    root: &Path,
    job: &DownloadJob,
) -> Result<(), String> {
    let destination = root.join(&job.relative_path);
    if std::fs::symlink_metadata(&destination)
        .is_ok_and(|metadata| metadata.file_type().is_symlink())
    {
        return Err(format!(
            "Refusing to replace a symbolic link at {}.",
            job.relative_path.display()
        ));
    }
    if let Some(expected_size) = job.size {
        if std::fs::metadata(&destination).is_ok_and(|metadata| metadata.len() == expected_size) {
            let existing = std::fs::read(&destination)
                .map_err(|error| format!("Could not verify an existing Minecraft file: {error}"))?;
            if sha1_hex(&existing).eq_ignore_ascii_case(&job.sha1) {
                return Ok(());
            }
        }
    } else if std::fs::metadata(&destination).is_ok() {
        let existing = std::fs::read(&destination)
            .map_err(|error| format!("Could not verify an existing Minecraft file: {error}"))?;
        if sha1_hex(&existing).eq_ignore_ascii_case(&job.sha1) {
            return Ok(());
        }
    }

    let response = client
        .get(&job.url)
        .send()
        .await
        .map_err(|error| {
            format!(
                "Could not download {}: {error}",
                job.relative_path.display()
            )
        })?
        .error_for_status()
        .map_err(|error| {
            format!(
                "Download failed for {}: {error}",
                job.relative_path.display()
            )
        })?;
    require_https_url(response.url().as_str())?;
    const MAX_DOWNLOAD_SIZE: u64 = 512 * 1024 * 1024;
    if response
        .content_length()
        .is_some_and(|length| length > MAX_DOWNLOAD_SIZE)
    {
        return Err(format!(
            "{} exceeds the maximum allowed file size.",
            job.relative_path.display()
        ));
    }
    let parent = destination
        .parent()
        .ok_or_else(|| "A Minecraft download path has no parent directory.".to_string())?;
    std::fs::create_dir_all(parent)
        .map_err(|error| format!("Could not create a Minecraft download directory: {error}"))?;
    let resolved_parent = std::fs::canonicalize(parent)
        .map_err(|error| format!("Could not resolve a Minecraft download directory: {error}"))?;
    if !resolved_parent.starts_with(root) {
        return Err("A Minecraft download path is outside the Sterling instance.".to_string());
    }
    let temporary = destination.with_file_name(format!(
        "{}.sterling-part",
        destination
            .file_name()
            .and_then(|name| name.to_str())
            .unwrap_or("download")
    ));
    let result =
        async {
            use tokio::io::AsyncWriteExt;

            let mut file = tokio::fs::File::create(&temporary)
                .await
                .map_err(|error| format!("Could not create a temporary download file: {error}"))?;
            let mut response = response;
            let mut hasher = Sha1::new();
            let mut downloaded = 0u64;
            while let Some(chunk) = response.chunk().await.map_err(|error| {
                format!("Could not read {}: {error}", job.relative_path.display())
            })? {
                downloaded = downloaded.saturating_add(chunk.len() as u64);
                if downloaded > MAX_DOWNLOAD_SIZE {
                    return Err(format!(
                        "{} exceeds the maximum allowed file size.",
                        job.relative_path.display()
                    ));
                }
                file.write_all(&chunk).await.map_err(|error| {
                    format!("Could not save {}: {error}", job.relative_path.display())
                })?;
                hasher.update(&chunk);
            }
            if job.size.is_some_and(|size| size != downloaded) {
                return Err(format!(
                    "{} has an unexpected file size.",
                    job.relative_path.display()
                ));
            }
            let actual_hash = hasher.finalize();
            let actual_hash: String = actual_hash
                .iter()
                .map(|byte| format!("{byte:02x}"))
                .collect();
            if !actual_hash.eq_ignore_ascii_case(&job.sha1) {
                return Err(format!(
                    "{} failed SHA-1 verification.",
                    job.relative_path.display()
                ));
            }
            file.flush().await.map_err(|error| {
                format!(
                    "Could not finish saving {}: {error}",
                    job.relative_path.display()
                )
            })?;
            drop(file);
            match tokio::fs::remove_file(&destination).await {
                Ok(()) => {}
                Err(error) if error.kind() == std::io::ErrorKind::NotFound => {}
                Err(error) => {
                    return Err(format!(
                        "Could not replace {}: {error}",
                        job.relative_path.display()
                    ));
                }
            }
            tokio::fs::rename(&temporary, &destination)
                .await
                .map_err(|error| {
                    format!(
                        "Could not finish saving {}: {error}",
                        job.relative_path.display()
                    )
                })?;
            Ok(())
        }
        .await;
    if result.is_err() {
        let _ = tokio::fs::remove_file(&temporary).await;
    }
    result
}

#[tauri::command]
async fn get_loader_versions(
    minecraft_version: String,
    loader: String,
) -> Result<LoaderCatalog, String> {
    if minecraft_version.is_empty()
        || minecraft_version.len() > 64
        || !minecraft_version.chars().all(|character| {
            character.is_ascii_alphanumeric() || matches!(character, '.' | '-' | '_')
        })
    {
        return Err("The selected Minecraft version is invalid.".to_string());
    }
    match loader.as_str() {
        "Fabric" => get_fabric_loader_versions(&minecraft_version).await,
        "Forge" => get_forge_loader_versions(&minecraft_version).await,
        _ => Err("Choose Fabric or Forge to look up loader versions.".to_string()),
    }
}

async fn get_fabric_loader_versions(minecraft_version: &str) -> Result<LoaderCatalog, String> {
    let mut endpoint = Url::parse("https://meta.fabricmc.net/v2/versions/loader/")
        .expect("Fabric metadata endpoint is a valid URL");
    endpoint
        .path_segments_mut()
        .map_err(|_| "Could not prepare the Fabric metadata request.".to_string())?
        .pop_if_empty()
        .push(minecraft_version);
    let body = fetch_catalog(endpoint.as_str(), "Fabric loader catalog", 5 * 1024 * 1024).await?;
    let mut versions: Vec<FabricLoaderVersion> = serde_json::from_slice(&body)
        .map_err(|error| format!("Fabric's loader catalog was invalid: {error}"))?;
    versions.sort_by(|left, right| {
        compare_numeric_versions(&right.loader.version, &left.loader.version)
    });

    let default_version = versions
        .iter()
        .find(|version| version.stable)
        .or_else(|| versions.first())
        .map(|version| version.loader.version.clone());
    let versions: Vec<String> = versions
        .into_iter()
        .map(|version| version.loader.version)
        .collect();
    Ok(LoaderCatalog {
        versions,
        default_version,
    })
}

async fn get_forge_loader_versions(minecraft_version: &str) -> Result<LoaderCatalog, String> {
    const FORGE_METADATA_URL: &str =
        "https://maven.minecraftforge.net/net/minecraftforge/forge/maven-metadata.xml";
    const FORGE_PROMOTIONS_URL: &str =
        "https://files.minecraftforge.net/net/minecraftforge/forge/promotions_slim.json";
    let body = fetch_catalog(FORGE_METADATA_URL, "Forge loader catalog", 10 * 1024 * 1024).await?;
    let metadata: ForgeMetadata = quick_xml::de::from_reader(body.as_slice())
        .map_err(|error| format!("Forge's loader catalog was invalid: {error}"))?;
    let prefix = format!("{minecraft_version}-");
    let mut versions: Vec<String> = metadata
        .versioning
        .versions
        .version
        .into_iter()
        .filter_map(|version| version.strip_prefix(&prefix).map(str::to_string))
        .collect();
    versions.sort_by(|left, right| compare_numeric_versions(right, left));
    versions.dedup();
    let default_version = if versions.is_empty() {
        None
    } else {
        let body = fetch_catalog(
            FORGE_PROMOTIONS_URL,
            "Forge promotions catalog",
            2 * 1024 * 1024,
        )
        .await?;
        let promotions: ForgePromotions = serde_json::from_slice(&body)
            .map_err(|error| format!("Forge's promotions catalog was invalid: {error}"))?;
        let recommended = promotions
            .promos
            .get(&format!("{minecraft_version}-recommended"))
            .filter(|version| versions.contains(version))
            .cloned();
        let latest = promotions
            .promos
            .get(&format!("{minecraft_version}-latest"))
            .filter(|version| versions.contains(version))
            .cloned();
        recommended.or(latest).or_else(|| versions.first().cloned())
    };
    Ok(LoaderCatalog {
        versions,
        default_version,
    })
}

fn compare_numeric_versions(left: &str, right: &str) -> std::cmp::Ordering {
    let left_parts = left.split('.').map(|part| part.parse::<u64>().unwrap_or(0));
    let right_parts = right
        .split('.')
        .map(|part| part.parse::<u64>().unwrap_or(0));
    left_parts.cmp(right_parts)
}

async fn fetch_catalog(url: &str, name: &str, max_size: usize) -> Result<Vec<u8>, String> {
    let client = reqwest::Client::builder()
        .timeout(std::time::Duration::from_secs(20))
        .build()
        .map_err(|error| format!("Could not prepare the {name} request: {error}"))?;
    let response = client
        .get(url)
        .send()
        .await
        .map_err(|error| format!("Could not reach the {name}: {error}"))?
        .error_for_status()
        .map_err(|error| format!("The {name} returned an error: {error}"))?;
    require_https_url(response.url().as_str())?;
    if response
        .content_length()
        .is_some_and(|length| length > max_size as u64)
    {
        return Err(format!("The {name} exceeded the allowed size."));
    }
    let mut response = response;
    let mut body = Vec::new();
    while let Some(chunk) = response
        .chunk()
        .await
        .map_err(|error| format!("Could not read the {name}: {error}"))?
    {
        if body.len().saturating_add(chunk.len()) > max_size {
            return Err(format!("The {name} exceeded the allowed size."));
        }
        body.extend_from_slice(&chunk);
    }
    Ok(body)
}

fn parse_link(raw_link: &str) -> Result<ServerLink, String> {
    let link = Url::parse(raw_link.trim())
        .map_err(|_| "Enter a valid sterling://connect link.".to_string())?;
    if link.scheme() != "sterling" || link.host_str() != Some("connect") {
        return Err("This is not a sterling://connect link.".to_string());
    }

    let query: std::collections::HashMap<String, String> =
        link.query_pairs().into_owned().collect();
    let server_address = query
        .get("ip")
        .filter(|address| {
            !address.is_empty()
                && address.trim() == address.as_str()
                && !address.chars().any(char::is_control)
        })
        .ok_or_else(|| "The Sterling link is missing a valid server address.".to_string())?
        .clone();
    let server_port = query.get("port").map_or(Ok(25565), |port| {
        port.parse::<u16>()
            .map_err(|_| "The server port in this link is invalid.".to_string())
    })?;
    if server_port == 0 {
        return Err("The server port must be between 1 and 65535.".to_string());
    }

    let manifest_value = query
        .get("manifest")
        .ok_or_else(|| "The Sterling link is missing its manifest URL.".to_string())?;
    let manifest_url = Url::parse(manifest_value)
        .map_err(|_| "The manifest URL in this Sterling link is invalid.".to_string())?;
    if !matches!(manifest_url.scheme(), "http" | "https")
        || manifest_url.host_str().is_none()
        || !manifest_url.username().is_empty()
        || manifest_url.password().is_some()
    {
        return Err(
            "The manifest must use an HTTP or HTTPS URL without embedded credentials.".to_string(),
        );
    }

    Ok(ServerLink {
        server_address,
        server_port,
        manifest_url: manifest_url.to_string(),
    })
}

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    tauri::Builder::default()
        .plugin(tauri_plugin_opener::init())
        .invoke_handler(tauri::generate_handler![
            parse_sterling_link,
            sign_in_with_microsoft,
            get_minecraft_account,
            sign_out_microsoft,
            get_minecraft_versions,
            get_loader_versions,
            install_vanilla_instance,
            delete_instance
        ])
        .run(tauri::generate_context!())
        .expect("error while running tauri application");
}

#[cfg(test)]
mod tests {
    use super::{
        FabricLoaderVersion, ForgeMetadata, ForgePromotions, MojangVersion,
        compare_numeric_versions, format_service_auth_error, format_xbox_auth_error,
        install_vanilla_instance_into, is_valid_instance_id, parse_link, parse_oauth_callback,
        safe_relative_path, sha1_hex, verify_sha1, xbox_token_user_hash,
    };
    use std::path::PathBuf;

    struct TemporaryInstallDirectory(PathBuf);

    impl TemporaryInstallDirectory {
        fn new() -> Self {
            let nonce = std::time::SystemTime::now()
                .duration_since(std::time::UNIX_EPOCH)
                .expect("system clock should be valid")
                .as_nanos();
            let path = std::env::temp_dir().join(format!(
                "sterling-vanilla-acceptance-{}-{nonce}",
                std::process::id()
            ));
            std::fs::create_dir(&path).expect("temporary install directory should be created");
            Self(std::fs::canonicalize(path).expect("temporary install directory should resolve"))
        }
    }

    impl Drop for TemporaryInstallDirectory {
        fn drop(&mut self) {
            let _ = std::fs::remove_dir_all(&self.0);
        }
    }

    #[test]
    fn validates_instance_ids_and_managed_download_paths() {
        assert!(is_valid_instance_id("550e8400-e29b-41d4-a716-446655440000"));
        assert!(!is_valid_instance_id("../../outside"));
        assert!(safe_relative_path("assets/objects/ab/hash").is_ok());
        assert!(safe_relative_path("../outside").is_err());
        assert!(safe_relative_path("C:/outside").is_err());
        assert!(safe_relative_path("assets\\outside").is_err());
    }

    #[test]
    fn verifies_download_content_with_sha1() {
        let content = b"sterling test";
        let hash = sha1_hex(content);
        assert_eq!(hash, "bb2dcd5aa8119314a81d757dd9b5f91f6a7f43f8");
        assert!(verify_sha1(content, &hash, "test file").is_ok());
        assert!(verify_sha1(content, &"0".repeat(40), "test file").is_err());
    }

    #[test]
    fn validates_microsoft_oauth_callback_state_and_code() {
        let request =
            "GET /?code=authorization-code&state=expected-state HTTP/1.1\r\nHost: localhost\r\n";
        assert_eq!(
            parse_oauth_callback(request, "expected-state").as_deref(),
            Ok("authorization-code")
        );
        assert!(parse_oauth_callback(request, "different-state").is_err());
        assert!(
            parse_oauth_callback(
                "GET /?error=access_denied&state=expected-state HTTP/1.1\r\n",
                "expected-state"
            )
            .is_err()
        );
    }

    #[test]
    fn reads_xbox_user_hash_from_token_claims() {
        let token = serde_json::json!({
            "DisplayClaims": {
                "xui": [{"uhs": "user-hash"}]
            }
        });
        assert_eq!(xbox_token_user_hash(&token).as_deref(), Ok("user-hash"));
        assert!(xbox_token_user_hash(&serde_json::json!({})).is_err());
    }

    #[test]
    fn reports_xbox_error_codes_without_exposing_response_body() {
        let error = format_xbox_auth_error(
            reqwest::StatusCode::BAD_REQUEST,
            br#"{"XErr":2148916233,"Message":"private diagnostic"}"#,
        );
        assert!(error.contains("2148916233"));
        assert!(error.contains("Xbox profile"));
        assert!(!error.contains("private diagnostic"));
    }

    #[test]
    fn reports_minecraft_service_error_without_exposing_tokens() {
        let error = format_service_auth_error(
            "Minecraft",
            reqwest::StatusCode::FORBIDDEN,
            br#"{"error":"ForbiddenOperationException","errorMessage":"Invalid credentials. Token omitted"}"#,
        );
        assert!(error.contains("HTTP 403"));
        assert!(error.contains("ForbiddenOperationException"));
        assert!(!error.contains("Token omitted"));
    }

    #[test]
    #[ignore = "Downloads Mojang's official Minecraft 1.7.10 files (~112 MiB)."]
    fn installs_mojang_vanilla_files_in_a_temporary_directory() {
        let temporary_directory = TemporaryInstallDirectory::new();
        let instance_id = "550e8400-e29b-41d4-a716-446655440000".to_string();
        let runtime = tokio::runtime::Builder::new_multi_thread()
            .enable_all()
            .build()
            .expect("Tokio runtime should start");
        let result = runtime
            .block_on(install_vanilla_instance_into(
                instance_id.clone(),
                "1.7.10".to_string(),
                temporary_directory.0.clone(),
                |_| {},
            ))
            .expect("Mojang's official Vanilla files should install");

        assert_eq!(result.instance_id, instance_id);
        assert_eq!(result.version, "1.7.10");
        assert!(
            temporary_directory
                .0
                .join("versions/1.7.10/1.7.10.jar")
                .is_file()
        );
        assert!(
            temporary_directory
                .0
                .join("assets/indexes/1.7.10.json")
                .is_file()
        );
        assert!(temporary_directory.0.join("assets/objects").is_dir());
        assert!(
            temporary_directory
                .0
                .join("sterling-install.json")
                .is_file()
        );
    }

    #[test]
    fn selects_latest_stable_fabric_loader_as_default() {
        let mut versions: Vec<FabricLoaderVersion> = serde_json::from_str(
            r#"[{"loader":{"version":"0.20.2"},"stable":false},{"loader":{"version":"0.20.0"},"stable":true},{"loader":{"version":"0.20.1"},"stable":true}]"#,
        )
        .expect("Fabric loader versions should deserialize");
        versions.sort_by(|left, right| {
            compare_numeric_versions(&right.loader.version, &left.loader.version)
        });

        let default = versions
            .iter()
            .find(|version| version.stable)
            .or_else(|| versions.first())
            .map(|version| version.loader.version.as_str());
        assert_eq!(default, Some("0.20.1"));
        assert_eq!(versions[0].loader.version, "0.20.2");
    }

    #[test]
    fn selects_forge_recommended_build_and_filters_game_version_prefix() {
        let metadata: ForgeMetadata = quick_xml::de::from_str(
            "<metadata><versioning><versions><version>1.21.8-58.0.1</version><version>1.21.8-58.0.3</version><version>1.21.7-57.0.3</version></versions></versioning></metadata>",
        )
        .expect("Forge metadata should deserialize");
        let prefix = "1.21.8-";
        let mut versions: Vec<String> = metadata
            .versioning
            .versions
            .version
            .into_iter()
            .filter_map(|version| version.strip_prefix(prefix).map(str::to_string))
            .collect();
        versions.sort_by(|left, right| compare_numeric_versions(right, left));

        assert_eq!(versions, ["58.0.3", "58.0.1"]);
        let promotions: ForgePromotions = serde_json::from_str(
            r#"{"promos":{"1.21.8-recommended":"58.0.1","1.21.8-latest":"58.0.3"}}"#,
        )
        .expect("Forge promotions should deserialize");
        let default = promotions
            .promos
            .get("1.21.8-recommended")
            .filter(|version| versions.contains(version))
            .cloned()
            .or_else(|| {
                promotions
                    .promos
                    .get("1.21.8-latest")
                    .filter(|version| versions.contains(version))
                    .cloned()
            });
        assert_eq!(default.as_deref(), Some("58.0.1"));
    }

    #[test]
    fn maps_mojang_version_types_to_frontend_fields() {
        let version: MojangVersion = serde_json::from_str(r#"{"id":"1.21.8","type":"release"}"#)
            .expect("Mojang version should deserialize");
        let serialized = serde_json::to_value(version).expect("version should serialize");

        assert_eq!(serialized["id"], "1.21.8");
        assert_eq!(serialized["versionType"], "release");
    }

    #[test]
    fn parses_a_complete_sterling_link() {
        let parsed = parse_link(
            "sterling://connect?ip=play.example.com&port=25582&manifest=http%3A%2F%2Fplay.example.com%3A8098%2Fmanifest.json",
        )
        .expect("link should be valid");

        assert_eq!(parsed.server_address, "play.example.com");
        assert_eq!(parsed.server_port, 25582);
        assert_eq!(
            parsed.manifest_url,
            "http://play.example.com:8098/manifest.json"
        );
    }

    #[test]
    fn defaults_to_the_minecraft_port() {
        let parsed = parse_link(
            "sterling://connect?ip=play.example.com&manifest=https%3A%2F%2Fcdn.example.com%2Fmanifest.json",
        )
        .expect("link should be valid");

        assert_eq!(parsed.server_port, 25565);
    }

    #[test]
    fn rejects_missing_manifest_url() {
        let error = parse_link("sterling://connect?ip=play.example.com")
            .expect_err("manifest URL is required");

        assert!(error.contains("missing its manifest URL"));
    }

    #[test]
    fn rejects_unsupported_manifest_scheme() {
        let error = parse_link(
            "sterling://connect?ip=play.example.com&manifest=ftp%3A%2F%2Fcdn.example.com%2Fmanifest.json",
        )
        .expect_err("FTP manifests are not allowed");

        assert!(error.contains("HTTP or HTTPS"));
    }

    #[test]
    fn rejects_out_of_range_server_port() {
        let error = parse_link(
            "sterling://connect?ip=play.example.com&port=70000&manifest=https%3A%2F%2Fcdn.example.com%2Fmanifest.json",
        )
        .expect_err("port must fit in a u16");

        assert!(error.contains("port"));
    }
}
