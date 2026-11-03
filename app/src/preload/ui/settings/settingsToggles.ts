import { ipcRenderer } from "electron";
import Helpers from "../../../utils/Helpers";
import Updater from "../../../core/Updater";
import logger from "../../../utils/logger";
import DiscordPresence from "../../../core/DiscordPresence";
import { discordTracker } from "../discordTracker";
import { STORAGE_KEYS, IPC_CHANNELS, CLASSES, PLAYER_PATH_STORAGE_KEY } from "../../../constants";
import { gpuRendererAPI } from "../../api/gpuRenderer";
import { externalPlayerAPI } from "../../api/externalPlayer";
import { alertAPI } from "../../api/alert";

export function setupCheckUpdatesButton(): void {
    Helpers.waitForElm('#checkforupdatesBtn').then(() => {
        const btn = document.getElementById("checkforupdatesBtn");
        btn?.addEventListener("click", async () => {
            if (btn) btn.style.pointerEvents = "none";
            // Checked here, in the page: the main process has no page to show the update popup on,
            // so a check there failed with "check your internet connection" whenever an update existed
            if (ipcRenderer.sendSync(IPC_CHANNELS.AUTO_UPDATE_AVAILABLE)) ipcRenderer.send(IPC_CHANNELS.AUTO_UPDATE_CHECK, true);
            else await Updater.checkForUpdates(true).catch(() => false);
            if (btn) btn.style.pointerEvents = "all";
        });
    }).catch(() => {});
}

export function setupCheckUpdatesOnStartupToggle(): void {
    Helpers.waitForElm('#checkForUpdatesOnStartup').then(() => {
        const toggle = document.getElementById("checkForUpdatesOnStartup");
        toggle?.addEventListener("click", () => {
            toggle.classList.toggle(CLASSES.CHECKED);
            const isChecked = toggle.classList.contains(CLASSES.CHECKED);
            logger.info(`Check for updates on startup toggled ${isChecked ? "ON" : "OFF"}`);
            localStorage.setItem(STORAGE_KEYS.CHECK_UPDATES_ON_STARTUP, isChecked ? "true" : "false");
        });
    }).catch(() => {});
}

export function setupDiscordRpcToggle(): void {
    Helpers.waitForElm('#discordrichpresence').then(() => {
        const toggle = document.getElementById("discordrichpresence");
        toggle?.addEventListener("click", async () => {
            toggle.classList.toggle(CLASSES.CHECKED);
            const isChecked = toggle.classList.contains(CLASSES.CHECKED);
            logger.info(`Discord Rich Presence toggled ${isChecked ? "ON" : "OFF"}`);
            
            if (isChecked) {
                localStorage.setItem(STORAGE_KEYS.DISCORD_RPC, "true");
                DiscordPresence.start();
                discordTracker.init();
            } else {
                localStorage.setItem(STORAGE_KEYS.DISCORD_RPC, "false");
                DiscordPresence.stop();
            }
        });
    }).catch(() => {});
}

export function setupTransparencyToggle(): void {
    Helpers.waitForElm('#enableTransparentThemes').then(() => {
        const toggle = document.getElementById("enableTransparentThemes");
        toggle?.addEventListener("click", () => {
            toggle.classList.toggle(CLASSES.CHECKED);
            const isChecked = toggle.classList.contains(CLASSES.CHECKED);
            logger.info(`Enable transparency toggled ${isChecked ? "ON" : "OFF"}`);
            ipcRenderer.send(IPC_CHANNELS.SET_TRANSPARENCY, isChecked);
        });
    }).catch(() => {});
}

export function setupGpuDropdown() {
    Helpers.waitForElm('#gpu-renderer-dropdown').then(() => {
        const gpuDropdown = document.getElementById('gpu-renderer-dropdown') as HTMLSelectElement;
        if (!gpuDropdown) return;

        gpuDropdown.addEventListener('change', async (e) => {
            const selectedValue = (e.target as HTMLSelectElement).value;
            
            await gpuRendererAPI.setGpuRenderer(selectedValue);
            await alertAPI.showAlert(
                "info",
                "Restart Required",
                "Your hardware acceleration settings have been saved. Please restart dx.bi to apply the changes.",
                ["OK"]
            );
        });
    }).catch(() => {});
}

export function setupExternalPlayerDropdown() {
    Helpers.waitForElm('#external-player-dropdown').then(() => {
        const dropdown = document.getElementById('external-player-dropdown') as HTMLSelectElement;
        if (!dropdown) return;

        dropdown.addEventListener('change', async (e) => {
            const selectedValue = (e.target as HTMLSelectElement).value;
            localStorage.setItem(STORAGE_KEYS.EXTERNAL_PLAYER, selectedValue);
            logger.info(`External player set to: ${selectedValue}`);

            const vlcPathOption = document.getElementById('vlc-path-option');
            const mpvPathOption = document.getElementById('mpv-path-option');
            if (vlcPathOption) vlcPathOption.style.display = selectedValue === 'vlc' ? '' : 'none';
            if (mpvPathOption) mpvPathOption.style.display = selectedValue === 'mpv' ? '' : 'none';

            if (selectedValue !== 'disabled') {
                const customPath = localStorage.getItem(PLAYER_PATH_STORAGE_KEY[selectedValue]);

                if (!customPath) {
                    const paths = await externalPlayerAPI.getExternalPlayerPaths();
                    const playerPath = paths[selectedValue as keyof typeof paths];
                    if (!playerPath) {
                        await alertAPI.showAlert(
                            "warning",
                            "Player Not Found",
                            `${selectedValue.toUpperCase()} was not found on your system. You can set a custom path to the player executable below.`,
                            ["OK"]
                        );
                    }
                }
            }
        });
    }).catch(() => {});
}

export function setupExternalPlayerPathInputs() {
    setupPathInput('external-player-vlc-path', STORAGE_KEYS.EXTERNAL_PLAYER_VLC_PATH, 'VLC');
    setupPathInput('external-player-mpv-path', STORAGE_KEYS.EXTERNAL_PLAYER_MPV_PATH, 'MPV');
}

function setupPathInput(elementId: string, storageKey: string, playerName: string) {
    Helpers.waitForElm(`#${elementId}`).then((el) => {
        const input = el as HTMLInputElement;

        input.addEventListener('change', () => {
            const value = input.value.trim();
            if (value) {
                localStorage.setItem(storageKey, value);
                logger.info(`Custom ${playerName} path set to: ${value}`);
            } else {
                localStorage.removeItem(storageKey);
                logger.info(`Custom ${playerName} path cleared, using auto-detect`);
            }
        });
    }).catch(() => {});
}
// "Play videos with mpv": dx.bi's own player (mpv behind the page), or the browser's. Only shown
// where dx.bi has mpv; the change applies after a restart.
export function setupMpvPlayerToggle(): void {
    Helpers.waitForElm('#enableTransparentThemes').then(() => {
        let status: { available: boolean; enabled: boolean };
        try { status = ipcRenderer.sendSync(IPC_CHANNELS.MPV_STATUS); } catch { return; }
        if (!status?.available || document.getElementById("dxbiMpvPlayer")) return;
        const option = document.getElementById("enableTransparentThemes")?.closest('[class*="option-"]');
        if (!option) return;
        const html = `
<div class="option-vFOAS">
    <div class="heading-dYMDt">
        <div class="label-qI6Vh">Play videos with mpv (recommended)</div>
    </div>
    <div class="content-P2T0i">
        <div tabindex="-1" class="toggle-container-lZfHP button-container-zVLH6 ${status.enabled ? CLASSES.CHECKED : ""}" id="dxbiMpvPlayer" style="outline: none;">
            <div class="toggle-toOWM"></div>
        </div>
    </div>
</div>
<p style="color:rgb(204, 204, 204);">mpv plays every format as it is (HEVC, Dolby Vision, AV1, DTS, TrueHD, embedded subtitles) with hardware decoding, like Stremio's own app. Switch it off to use the browser's player instead. Opening streams in VLC or mpv as separate apps is the External player setting below. Applies after restarting dx.bi.</p>`;
        option.insertAdjacentHTML("beforebegin", html);
        const toggle = document.getElementById("dxbiMpvPlayer");
        toggle?.addEventListener("click", () => {
            toggle.classList.toggle(CLASSES.CHECKED);
            const on = toggle.classList.contains(CLASSES.CHECKED);
            ipcRenderer.send(IPC_CHANNELS.MPV_SET_ENABLED, on);
            alertAPI.showAlert("info", "Video player changed", "Restart dx.bi to use the new player.", ["OK"]);
        });
    }).catch(() => {});
}
