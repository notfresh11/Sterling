import { invoke } from "@tauri-apps/api/core";
import { listen } from "@tauri-apps/api/event";

interface SterlingLink {
  serverAddress: string;
  serverPort: number;
  manifestUrl: string;
}

interface MinecraftAccount {
  name: string;
  uuid: string;
}

interface GameInstance {
  id: string;
  name: string;
  version: string;
  loader: string;
  loaderVersion?: string;
  channel: string;
  createdAt: string;
  installedDirectory?: string;
}

interface MinecraftVersion {
  id: string;
  versionType: string;
}

interface LoaderCatalog {
  versions: string[];
  defaultVersion: string | null;
}

interface InstallProgress {
  instanceId: string;
  completed: number;
  total: number;
  currentFile: string;
  phase: string;
}

interface InstallState {
  inProgress: boolean;
  error?: string;
}

const connectForm = document.querySelector<HTMLFormElement>("#connect-form");
const linkInput = document.querySelector<HTMLInputElement>("#server-link");
const feedback = document.querySelector<HTMLElement>("#link-feedback");
const serverDetails = document.querySelector<HTMLElement>("#server-details");
const serverAddress = document.querySelector<HTMLElement>("#server-address");
const manifestAddress = document.querySelector<HTMLElement>("#manifest-address");

connectForm?.addEventListener("submit", async (event) => {
  event.preventDefault();
  if (!linkInput || !feedback || !serverDetails || !serverAddress || !manifestAddress) {
    return;
  }

  serverDetails.hidden = true;
  feedback.className = "feedback";
  feedback.innerHTML = '<span class="feedback-symbol">…</span><span>Checking connection link…</span>';

  try {
    const link = await invoke<SterlingLink>("parse_sterling_link", {
      rawLink: linkInput.value,
    });
    serverAddress.textContent = `${link.serverAddress}:${link.serverPort}`;
    manifestAddress.textContent = link.manifestUrl;
    serverDetails.hidden = false;
    feedback.className = "feedback success";
    feedback.replaceChildren(
      Object.assign(document.createElement("span"), {
        className: "feedback-symbol",
        textContent: "✓",
      }),
      Object.assign(document.createElement("span"), {
        textContent: "Link is valid. Game launch is not connected yet.",
      }),
    );
  } catch (error) {
    feedback.className = "feedback error";
    const message = document.createElement("span");
    message.textContent = typeof error === "string" ? error : "Could not validate this Sterling link.";
    feedback.replaceChildren(
      Object.assign(document.createElement("span"), {
        className: "feedback-symbol",
        textContent: "!",
      }),
      message,
    );
  }
});

const instanceStorageKey = "sterling.instances";
const experimentalStorageKey = "sterling.showExperimentalVersions";
const instanceList = document.querySelector<HTMLElement>("#instance-list");
const instanceDialog = document.querySelector<HTMLDialogElement>("#instance-dialog");
const instanceForm = document.querySelector<HTMLFormElement>("#instance-form");
const instanceNameInput = document.querySelector<HTMLInputElement>("#instance-name");
const versionInput = document.querySelector<HTMLInputElement>("#minecraft-version");
const loaderSelect = document.querySelector<HTMLSelectElement>("#loader-type");
const loaderBuildContainer = document.querySelector<HTMLElement>("#loader-build-container");
const loaderBuildSelect = document.querySelector<HTMLSelectElement>("#loader-build");
const loaderBuildLabel = document.querySelector<HTMLElement>("#loader-build-label");
const loaderCatalogStatus = document.querySelector<HTMLElement>("#loader-catalog-status");
const retryLoaderCatalogButton = document.querySelector<HTMLButtonElement>("#retry-loader-catalog");
const versionKindSelect = document.querySelector<HTMLSelectElement>("#version-kind");
const versionCatalogStatus = document.querySelector<HTMLElement>("#version-catalog-status");
const retryVersionCatalogButton = document.querySelector<HTMLButtonElement>("#retry-version-catalog");
const experimentalCheckbox = document.querySelector<HTMLInputElement>("#experimental-versions");
const formError = document.querySelector<HTMLElement>("#instance-form-error");
const breadcrumb = document.querySelector<HTMLElement>("#breadcrumb");
const appNotice = document.querySelector<HTMLElement>("#app-notice");
const microsoftSignInButton = document.querySelector<HTMLButtonElement>("#microsoft-sign-in");
const microsoftAccountNote = document.querySelector<HTMLElement>("#microsoft-account-note");
const profileName = document.querySelector<HTMLElement>("#profile-name");
const profileSubtitle = document.querySelector<HTMLElement>("#profile-subtitle");
const profileAvatar = document.querySelector<HTMLElement>("#profile-avatar");
const accountStatusLabel = document.querySelector<HTMLElement>("#account-status-label");
const pages = [...document.querySelectorAll<HTMLElement>(".page")];
const navItems = [...document.querySelectorAll<HTMLButtonElement>("[data-page]")];
let instances: GameInstance[] = [];
let experimentalVersionsEnabled = false;
let minecraftVersions: MinecraftVersion[] | null = null;
let versionCatalogRequest: Promise<void> | null = null;
const loaderVersionsCache = new Map<string, LoaderCatalog>();
let activeLoaderVersions: string[] = [];
let loaderCompatibilityRequestId = 0;
let signedInAccount: MinecraftAccount | null = null;
let isSigningIn = false;
const installStates = new Map<string, InstallState>();
const installProgressElements = new Map<
  string,
  { progress: HTMLProgressElement; message: HTMLElement }
>();

const versionTypes: Record<string, string> = {
  release: "release",
  snapshot: "snapshot",
  alpha: "old_alpha",
  beta: "old_beta",
};

function showInstanceMessage(message: string, isError = false): void {
  if (!appNotice) return;
  appNotice.classList.toggle("storage-error", isError);
  appNotice.textContent = message;
  appNotice.setAttribute("role", isError ? "alert" : "status");
  appNotice.hidden = false;
}

function renderMicrosoftAccount(account: MinecraftAccount | null): void {
  signedInAccount = account;
  if (profileName) profileName.textContent = account?.name ?? "Not signed in";
  if (profileSubtitle) {
    profileSubtitle.textContent = account ? "Minecraft Java account" : "Microsoft account";
  }
  if (profileAvatar) {
    profileAvatar.textContent = account?.name.slice(0, 1).toUpperCase() ?? "?";
  }
  if (accountStatusLabel) {
    accountStatusLabel.textContent = account ? "MINECRAFT ACCOUNT CONNECTED" : "ACCOUNT NOT CONNECTED";
    accountStatusLabel
      .closest(".account-status")
      ?.classList.toggle("connected", account !== null);
  }
  if (microsoftSignInButton) {
    microsoftSignInButton.disabled = isSigningIn;
    microsoftSignInButton.innerHTML = account
      ? "Sign out <span aria-hidden=\"true\">↗</span>"
      : isSigningIn
        ? "Waiting for Microsoft…"
        : "Sign in with Microsoft <span aria-hidden=\"true\">↗</span>";
  }
  if (microsoftAccountNote) {
    microsoftAccountNote.textContent = account
      ? "Minecraft Java profile verified. Game launch is not connected yet."
      : "Sign in with the Microsoft account that owns Minecraft Java Edition.";
  }
}

async function loadMicrosoftAccount(): Promise<void> {
  try {
    const account = await invoke<MinecraftAccount | null>("get_minecraft_account");
    renderMicrosoftAccount(account);
  } catch (error) {
    showInstanceMessage(
      `Could not check Microsoft sign-in: ${error instanceof Error ? error.message : String(error)}`,
      true,
    );
  }
}

microsoftSignInButton?.addEventListener("click", async () => {
  if (isSigningIn) return;
  if (signedInAccount) {
    try {
      await invoke("sign_out_microsoft");
      renderMicrosoftAccount(null);
      showInstanceMessage("Signed out of the Minecraft account.");
    } catch (error) {
      showInstanceMessage(
        `Could not sign out: ${error instanceof Error ? error.message : String(error)}`,
        true,
      );
    }
    return;
  }

  isSigningIn = true;
  renderMicrosoftAccount(null);
  showInstanceMessage("Complete Microsoft sign-in in your browser, then return to Sterling.");
  try {
    const account = await invoke<MinecraftAccount>("sign_in_with_microsoft");
    renderMicrosoftAccount(account);
    showInstanceMessage(`Minecraft Java profile verified for ${account.name}.`);
  } catch (error) {
    showInstanceMessage(
      `Microsoft sign-in failed: ${error instanceof Error ? error.message : String(error)}`,
      true,
    );
  } finally {
    isSigningIn = false;
    renderMicrosoftAccount(signedInAccount);
  }
});

function readStoredInstances(): void {
  try {
    const stored = localStorage.getItem(instanceStorageKey);
    if (!stored) return;
    const parsed: unknown = JSON.parse(stored);
    if (!Array.isArray(parsed) || !parsed.every(isGameInstance)) {
      throw new Error("Saved instance data has an unexpected format.");
    }
    instances = parsed;
  } catch (error) {
    showInstanceMessage(
      `Could not load saved instances: ${error instanceof Error ? error.message : "local storage is unavailable."}`,
      true,
    );
  }
}

function isGameInstance(value: unknown): value is GameInstance {
  if (!value || typeof value !== "object") return false;
  const instance = value as Record<string, unknown>;
  return ["id", "name", "version", "loader", "channel", "createdAt"].every(
    (key) => typeof instance[key] === "string",
  ) &&
    (instance.loaderVersion === undefined || typeof instance.loaderVersion === "string") &&
    (instance.installedDirectory === undefined || typeof instance.installedDirectory === "string");
}

function saveInstances(nextInstances: GameInstance[]): boolean {
  try {
    localStorage.setItem(instanceStorageKey, JSON.stringify(nextInstances));
    instances = nextInstances;
    return true;
  } catch (error) {
    showInstanceMessage(
      `Could not save instances: ${error instanceof Error ? error.message : "local storage is unavailable."}`,
      true,
    );
    return false;
  }
}

function renderInstances(): void {
  if (!instanceList) return;
  instanceList.replaceChildren();

  if (instances.length === 0) {
    const empty = document.createElement("div");
    empty.className = "empty-instances";
    empty.innerHTML = '<div class="empty-instance-icon">▦</div><h2>No instances yet</h2><p>Create a separate profile for each Minecraft version or server.</p>';
    const createButton = document.createElement("button");
    createButton.className = "secondary-button";
    createButton.type = "button";
    createButton.textContent = "Create your first instance";
    createButton.addEventListener("click", openInstanceDialog);
    empty.append(createButton);
    instanceList.append(empty);
    return;
  }

  const grid = document.createElement("div");
  grid.className = "instance-grid";
  for (const instance of instances) {
    const card = document.createElement("article");
    card.className = "instance-card-item";

    const cardHeader = document.createElement("div");
    cardHeader.className = "instance-card-header";
    const icon = document.createElement("div");
    icon.className = "instance-icon";
    icon.textContent = instance.loader === "Vanilla" ? "◇" : "✳";
    const heading = document.createElement("div");
    heading.className = "instance-title";
    const name = document.createElement("h2");
    name.textContent = instance.name;
    const date = document.createElement("span");
    date.textContent = `Created ${new Date(instance.createdAt).toLocaleDateString()}`;
    heading.append(name, date);
    const deleteButton = document.createElement("button");
    deleteButton.className = "icon-button";
    deleteButton.type = "button";
    deleteButton.setAttribute("aria-label", `Delete ${instance.name}`);
    deleteButton.title = "Delete instance";
    deleteButton.textContent = "×";
    const installState = installStates.get(instance.id);
    deleteButton.disabled = installState?.inProgress ?? false;
    deleteButton.addEventListener("click", () => void deleteInstance(instance));
    cardHeader.append(icon, heading, deleteButton);

    const tags = document.createElement("div");
    tags.className = "instance-tags";
    const labels = [instance.version, instance.loader];
    if (instance.loaderVersion && instance.loader !== "Vanilla") labels.push(instance.loaderVersion);
    labels.push(instance.channel);
    for (const label of labels) {
      const tag = document.createElement("span");
      tag.className = "instance-tag";
      tag.textContent = label;
      tags.append(tag);
    }

    const status = document.createElement("div");
    status.className = "instance-status";
    const statusDot = document.createElement("span");
    statusDot.className = "status-dot";
    const statusLabel = document.createElement("span");
    statusLabel.textContent =
      instance.loader !== "Vanilla"
        ? "PROFILE SAVED · LOADER INSTALLATION NOT CONNECTED"
        : instance.installedDirectory
          ? "VANILLA FILES INSTALLED · LAUNCH NOT CONNECTED"
          : "VANILLA FILES NOT INSTALLED";
    status.append(statusDot, statusLabel);
    card.append(cardHeader, tags, status);
    installProgressElements.delete(instance.id);
    if (instance.loader === "Vanilla") {
      const installArea = document.createElement("div");
      installArea.className = "instance-install-area";
      const installButton = document.createElement("button");
      installButton.className = "secondary-button instance-install-button";
      installButton.type = "button";
      installButton.disabled = installState?.inProgress ?? false;
      installButton.textContent = installState?.inProgress
        ? "Installing…"
        : instance.installedDirectory
          ? "Reinstall Vanilla"
          : "Install Vanilla";
      installButton.addEventListener("click", () => void installVanillaInstance(instance));
      installArea.append(installButton);

      const progressMessage = document.createElement("p");
      progressMessage.className = "install-progress-message";
      const progress = document.createElement("progress");
      progress.className = "install-progress-bar";
      progress.max = 1;
      progress.value = 0;
      const isInstalling = installState?.inProgress ?? false;
      progress.hidden = !isInstalling;
      progressMessage.hidden = !isInstalling && !installState?.error;
      progressMessage.textContent = installState?.error
        ? `Installation failed: ${installState.error}`
        : instance.installedDirectory
          ? `Installed in ${instance.installedDirectory}`
          : "";
      if (installState?.error) progressMessage.classList.add("install-error");
      installArea.append(progressMessage, progress);
      card.append(installArea);
      if (isInstalling) {
        installProgressElements.set(instance.id, { progress, message: progressMessage });
      }
    }
    grid.append(card);
  }
  instanceList.append(grid);
}

async function deleteInstance(instance: GameInstance): Promise<void> {
  const hasInstalledFiles = Boolean(instance.installedDirectory);
  const confirmed = window.confirm(
    hasInstalledFiles
      ? `Delete "${instance.name}" and its downloaded Minecraft files?`
      : `Delete the "${instance.name}" instance profile?`,
  );
  if (!confirmed) return;
  try {
    await invoke("delete_instance", { instanceId: instance.id });
    const nextInstances = instances.filter((item) => item.id !== instance.id);
    if (saveInstances(nextInstances)) {
      installStates.delete(instance.id);
      renderInstances();
    }
  } catch (error) {
    showInstanceMessage(
      `Could not delete the instance: ${error instanceof Error ? error.message : String(error)}`,
      true,
    );
  }
}

async function installVanillaInstance(instance: GameInstance): Promise<void> {
  if (instance.loader !== "Vanilla" || installStates.get(instance.id)?.inProgress) return;
  installStates.set(instance.id, { inProgress: true });
  renderInstances();
  let unlisten: (() => void) | undefined;
  try {
    unlisten = await listen<InstallProgress>("vanilla-install-progress", ({ payload }) => {
      if (payload.instanceId !== instance.id) return;
      const elements = installProgressElements.get(instance.id);
      if (!elements) return;
      elements.progress.max = Math.max(payload.total, 1);
      elements.progress.value = payload.completed;
      const fileName = payload.currentFile.split(/[\\/]/).pop() ?? payload.currentFile;
      elements.message.textContent =
        `${payload.phase}: ${payload.completed}/${payload.total} · ${fileName}`;
    });
    const result = await invoke<{ instanceId: string; installDirectory: string }>(
      "install_vanilla_instance",
      {
        instanceId: instance.id,
        versionId: instance.version,
      },
    );
    const nextInstances = instances.map((item) =>
      item.id === instance.id ? { ...item, installedDirectory: result.installDirectory } : item,
    );
    installStates.set(instance.id, { inProgress: false });
    if (!saveInstances(nextInstances)) {
      showInstanceMessage(
        "Vanilla installed successfully, but the instance status could not be saved.",
        true,
      );
    }
    renderInstances();
  } catch (error) {
    installStates.set(instance.id, {
      inProgress: false,
      error: error instanceof Error ? error.message : String(error),
    });
    renderInstances();
  } finally {
    unlisten?.();
  }
}

function setExperimentalOptions(enabled: boolean): void {
  if (!versionKindSelect) return;
  for (const option of versionKindSelect.querySelectorAll<HTMLOptionElement>("[data-experimental]")) {
    option.hidden = !enabled;
    option.disabled = !enabled;
  }
  if (!enabled && versionKindSelect.value !== "release") {
    versionKindSelect.value = "release";
  }
  renderVersionChoices();
}

function renderVersionChoices(): void {
  if (!versionInput || !versionKindSelect || !minecraftVersions) return;

  const selectedType = versionTypes[versionKindSelect.value];
  const matchingVersions = minecraftVersions.filter((version) => version.versionType === selectedType);
  versionInput.replaceChildren();
  const placeholder = document.createElement("option");
  placeholder.value = "";
  placeholder.textContent = matchingVersions.length
    ? "Select a Minecraft version"
    : "No versions available in this channel";
  versionInput.append(placeholder);
  for (const version of matchingVersions) {
    const option = document.createElement("option");
    option.value = version.id;
    option.textContent = version.id;
    versionInput.append(option);
  }
  versionInput.disabled = matchingVersions.length === 0;
  if (versionCatalogStatus) {
    versionCatalogStatus.textContent = `${matchingVersions.length.toLocaleString()} ${
      versionKindSelect.value
    } versions from Mojang’s official catalog.`;
  }
  void updateLoaderCompatibility();
}

async function loadMinecraftVersions(retry = false): Promise<void> {
  if (minecraftVersions && !retry) {
    renderVersionChoices();
    return;
  }
  if (versionCatalogRequest) return versionCatalogRequest;
  if (!versionInput || !versionCatalogStatus) return;

  versionInput.disabled = true;
  versionInput.replaceChildren(new Option("Loading Minecraft versions…", ""));
  versionCatalogStatus.textContent = "Loading Mojang’s official version catalog…";
  if (retryVersionCatalogButton) retryVersionCatalogButton.hidden = true;

  versionCatalogRequest = (async () => {
    try {
      const versions = await invoke<MinecraftVersion[]>("get_minecraft_versions");
      if (
        !Array.isArray(versions) ||
        versions.some(
          (version) =>
            !version ||
            typeof version.id !== "string" ||
            typeof version.versionType !== "string",
        )
      ) {
        throw new Error("The version catalog returned data in an unexpected format.");
      }
      minecraftVersions = versions.filter((version) =>
        Object.values(versionTypes).includes(version.versionType),
      );
      if (minecraftVersions.length === 0) {
        throw new Error("The version catalog did not contain any supported Minecraft versions.");
      }
      renderVersionChoices();
    } catch (error) {
      versionInput.disabled = true;
      versionInput.replaceChildren(new Option("Could not load Minecraft versions", ""));
      versionCatalogStatus.textContent =
        error instanceof Error ? error.message : "Could not load Mojang’s version catalog.";
      if (retryVersionCatalogButton) retryVersionCatalogButton.hidden = false;
    } finally {
      versionCatalogRequest = null;
    }
  })();
  return versionCatalogRequest;
}

function openInstanceDialog(): void {
  if (!instanceDialog || !instanceForm) return;
  formError?.setAttribute("hidden", "");
  instanceForm.reset();
  setExperimentalOptions(experimentalVersionsEnabled);
  instanceDialog.showModal();
  instanceNameInput?.focus();
  void loadMinecraftVersions();
  void updateLoaderCompatibility();
}

async function updateLoaderCompatibility(retry = false): Promise<void> {
  if (
    !versionInput ||
    !loaderSelect ||
    !loaderBuildContainer ||
    !loaderBuildSelect ||
    !loaderBuildLabel ||
    !loaderCatalogStatus
  ) return;

  const loader = loaderSelect.value;
  const minecraftVersion = versionInput.value;
  const requestId = ++loaderCompatibilityRequestId;
  activeLoaderVersions = [];
  loaderBuildSelect.replaceChildren();
  loaderBuildSelect.disabled = true;
  if (loader !== "Fabric" && loader !== "Forge") {
    loaderBuildContainer.hidden = true;
    return;
  }

  loaderBuildContainer.hidden = false;
  loaderBuildLabel.textContent = `${loader.toUpperCase()} VERSION`;
  const placeholder = document.createElement("option");
  placeholder.value = "";
  placeholder.textContent = minecraftVersion
    ? "Checking available versions…"
    : "Choose a Minecraft version first";
  loaderBuildSelect.append(placeholder);
  if (retryLoaderCatalogButton) retryLoaderCatalogButton.hidden = true;
  if (!minecraftVersion) {
    loaderCatalogStatus.textContent = "Choose a Minecraft version to check loader compatibility.";
    return;
  }

  const cacheKey = `${loader}:${minecraftVersion}`;
  try {
    let catalog = retry ? undefined : loaderVersionsCache.get(cacheKey);
    if (!catalog) {
      loaderCatalogStatus.textContent = `Checking ${loader} compatibility for Minecraft ${minecraftVersion}…`;
      catalog = await invoke<LoaderCatalog>("get_loader_versions", {
        minecraftVersion,
        loader,
      });
      if (
        !catalog ||
        !Array.isArray(catalog.versions) ||
        catalog.versions.some((version) => typeof version !== "string") ||
        (catalog.defaultVersion !== null &&
          (typeof catalog.defaultVersion !== "string" ||
            !catalog.versions.includes(catalog.defaultVersion)))
      ) {
        throw new Error("The loader catalog returned data in an unexpected format.");
      }
      loaderVersionsCache.set(cacheKey, catalog);
    }
    if (
      requestId !== loaderCompatibilityRequestId ||
      loaderSelect.value !== loader ||
      versionInput.value !== minecraftVersion
    ) return;

    const { versions } = catalog;
    activeLoaderVersions = versions;
    loaderBuildSelect.replaceChildren();
    if (versions.length === 0) {
      loaderBuildSelect.append(new Option(`No ${loader} versions available`, ""));
      loaderCatalogStatus.textContent =
        `No ${loader} builds are published for Minecraft ${minecraftVersion}. Try Vanilla or another game version.`;
      return;
    }

    const defaultVersion = catalog.defaultVersion ?? versions[0];
    loaderBuildSelect.append(new Option(`Select a ${loader} version`, ""));
    for (const version of versions) {
      const option = new Option(
        version === defaultVersion ? `${version} (default)` : version,
        version,
      );
      loaderBuildSelect.append(option);
    }
    loaderBuildSelect.value = defaultVersion;
    loaderBuildSelect.disabled = false;
    loaderCatalogStatus.textContent = `${versions.length.toLocaleString()} ${loader} ${
      versions.length === 1 ? "build" : "builds"
    } are available for Minecraft ${minecraftVersion}. Default: ${defaultVersion}. Change it if you prefer another build.`;
  } catch (error) {
    if (requestId !== loaderCompatibilityRequestId) return;
    loaderBuildSelect.replaceChildren(new Option(`${loader} versions unavailable`, ""));
    loaderCatalogStatus.textContent =
      error instanceof Error ? error.message : `Could not load the ${loader} version catalog.`;
    if (retryLoaderCatalogButton) retryLoaderCatalogButton.hidden = false;
  }
}

function showPage(pageName: string): void {
  for (const page of pages) {
    page.hidden = page.id !== `page-${pageName}`;
  }
  for (const item of navItems) {
    const active = item.dataset.page === pageName;
    item.classList.toggle("active", active);
    if (active) item.setAttribute("aria-current", "page");
    else item.removeAttribute("aria-current");
  }
  if (breadcrumb) {
    const section = pageName === "settings" ? "PREFERENCES" : "LIBRARY";
    breadcrumb.replaceChildren(
      document.createTextNode(section),
      document.createTextNode(" / "),
      Object.assign(document.createElement("span"), { textContent: pageName.toUpperCase() }),
    );
  }
}

for (const item of navItems) {
  item.addEventListener("click", () => showPage(item.dataset.page ?? "play"));
}

document.querySelectorAll<HTMLButtonElement>("[data-open-instances]").forEach((button) => {
  button.addEventListener("click", () => showPage("instances"));
});

document.querySelector("#create-instance")?.addEventListener("click", openInstanceDialog);
document.querySelector("#close-instance-dialog")?.addEventListener("click", () => instanceDialog?.close());
document.querySelector("#cancel-instance-dialog")?.addEventListener("click", () => instanceDialog?.close());
retryVersionCatalogButton?.addEventListener("click", () => void loadMinecraftVersions(true));
versionKindSelect?.addEventListener("change", renderVersionChoices);
versionInput?.addEventListener("change", () => void updateLoaderCompatibility());
loaderSelect?.addEventListener("change", () => void updateLoaderCompatibility());
retryLoaderCatalogButton?.addEventListener("click", () => void updateLoaderCompatibility(true));

instanceForm?.addEventListener("submit", (event) => {
  event.preventDefault();
  if (
    !instanceNameInput ||
    !versionInput ||
    !loaderSelect ||
    !loaderBuildSelect ||
    !versionKindSelect ||
    !formError
  ) return;

  const name = instanceNameInput.value.trim();
  const version = versionInput.value.trim();
  const channel = versionKindSelect.value;
  if (!name || !version) {
    formError.textContent = "Enter an instance name and Minecraft version.";
    formError.hidden = false;
    return;
  }
  if (channel !== "release" && !experimentalVersionsEnabled) {
    formError.textContent = "Enable experimental versions in Settings before choosing this channel.";
    formError.hidden = false;
    return;
  }
  if (!minecraftVersions?.some((item) => item.id === version)) {
    formError.textContent = "Select a Minecraft version from the official catalog.";
    formError.hidden = false;
    return;
  }
  const loaderVersion = loaderBuildSelect.value;
  if (loaderSelect.value !== "Vanilla" && !activeLoaderVersions.includes(loaderVersion)) {
    formError.textContent = "Choose an available loader version for this Minecraft version.";
    formError.hidden = false;
    return;
  }

  const newInstance: GameInstance = {
    id: crypto.randomUUID(),
    name,
    version,
    loader: loaderSelect.value,
    loaderVersion: loaderSelect.value === "Vanilla" ? "Vanilla" : loaderVersion,
    channel: channel.charAt(0).toUpperCase() + channel.slice(1),
    createdAt: new Date().toISOString(),
  };
  if (saveInstances([newInstance, ...instances])) {
    renderInstances();
    instanceDialog?.close();
    showPage("instances");
  }
});

experimentalCheckbox?.addEventListener("change", () => {
  experimentalVersionsEnabled = experimentalCheckbox.checked;
  setExperimentalOptions(experimentalVersionsEnabled);
  try {
    localStorage.setItem(experimentalStorageKey, String(experimentalVersionsEnabled));
  } catch (error) {
    showInstanceMessage(
      `Could not save settings: ${error instanceof Error ? error.message : "local storage is unavailable."}`,
      true,
    );
  }
});

readStoredInstances();
renderInstances();
void loadMicrosoftAccount();
if (experimentalCheckbox) {
  try {
    experimentalVersionsEnabled = localStorage.getItem(experimentalStorageKey) === "true";
    experimentalCheckbox.checked = experimentalVersionsEnabled;
  } catch (error) {
    showInstanceMessage(
      `Could not load settings: ${error instanceof Error ? error.message : "local storage is unavailable."}`,
      true,
    );
  }
}
setExperimentalOptions(experimentalVersionsEnabled);
