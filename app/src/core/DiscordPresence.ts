import { Client as DiscordClient } from '@xhayper/discord-rpc';
import { getLogger } from '../utils/logger';
import { ActivityType } from 'discord-api-types/v10';
import type { SetActivity } from '@xhayper/discord-rpc/dist/structures/ClientUser';
import { DISCORD } from '../constants';

class DiscordPresence {
    private static logger = getLogger("DiscordPresence");
    private static rpc: DiscordClient | null = null;
    private static enabled = false;

    private static ready = false;

    // Only ever one connection to Discord and one pending retry: each live connection shows as its
    // own activity, so a stale one (left by a retry, or by switching off and on quickly) appeared as
    // a second, identical dx.bi in Discord
    private static retryTimer: ReturnType<typeof setTimeout> | null = null;
    private static generation = 0;

    public static start(): void {
        if (this.enabled) return;
        this.enabled = true;
        this.ready = false;
        this.connect();
    }

    private static connect(): void {
        if (!this.enabled) return;
        this.clearRetry();
        this.dropClient();
        const generation = ++this.generation;
        const current = () => this.enabled && generation === this.generation;

        try {
            const rpc = new DiscordClient({ clientId: DISCORD.CLIENT_ID });
            this.rpc = rpc;

            rpc.on('ready', () => {
                if (!current()) return;
                this.ready = true;
                this.logger.info('Connected to DiscordRPC.');

                // show what's on screen now (not a fake hashchange: that also re-ran the page's
                // own navigation handlers)
                window.dispatchEvent(new Event('dxbi-discord-ready'));
            });

            rpc.on('disconnected', () => {
                if (!current()) return;
                this.ready = false;
                this.logger.warn(`DiscordRPC Disconnected! Attempting to reconnect...`);
                this.handleReconnect();
            });

            rpc.login().catch(() => {
                if (!current()) return;
                this.logger.error("Failed to connect to DiscordRPC.");
                this.handleReconnect();
            });
        } catch (error) {
            this.logger.error(`An unexpected error occurred: ${(error as Error).message}`);
            this.handleReconnect();
        }
    }

    private static handleReconnect(): void {
        if (!this.enabled || this.retryTimer) return;
        this.retryTimer = setTimeout(() => {
            this.retryTimer = null;
            this.connect();
        }, DISCORD.RECONNECT_INTERVAL);
    }

    private static clearRetry(): void {
        if (this.retryTimer) clearTimeout(this.retryTimer);
        this.retryTimer = null;
    }

    private static dropClient(): void {
        const rpc = this.rpc;
        this.rpc = null;
        if (!rpc) return;
        try { rpc.user?.clearActivity()?.catch?.(() => {}); } catch { /* not connected */ }
        rpc.destroy().catch(() => {});
    }

    public static stop(): void {
        if (!this.enabled) return;
        this.enabled = false;
        this.ready = false;
        this.generation++;
        this.clearRetry();
        if (this.rpc) this.logger.info('Clearing DiscordRPC.');
        this.dropClient();
    }

    public static setPlaying(
        details: string,
        state: string,
        startTimestamp: number,
        endTimestamp: number | undefined,
        imageKey: string | undefined
    ): void {
        this._updateActivity({
            details,
            state,
            startTimestamp,
            endTimestamp,
            largeImageKey: imageKey || DISCORD.DEFAULT_IMAGE,
            largeImageText: "dx.bi",
            smallImageKey: DISCORD.PLAY_IMAGE,
            smallImageText: "Playing",
            instance: false,
            type: ActivityType.Watching
        });
    }

    public static setPaused(details: string, state: string, imageKey: string | undefined): void {
        this._updateActivity({
            details,
            state,
            largeImageKey: imageKey || DISCORD.DEFAULT_IMAGE,
            largeImageText: "dx.bi",
            smallImageKey: DISCORD.PAUSE_IMAGE,
            smallImageText: "Paused",
            instance: false,
            type: ActivityType.Watching
        });
    }

    public static setExploring(details: string, imageKey: string | undefined): void {
        this._updateActivity({
            details,
            state: 'Exploring',
            largeImageKey: imageKey || DISCORD.DEFAULT_IMAGE,
            largeImageText: "dx.bi",
            smallImageKey: DISCORD.BROWSE_IMAGE,
            smallImageText: "Browsing",
            instance: false,
            type: ActivityType.Playing
        });
    }

    public static setMainMenu(menuName: string): void {
        this._updateActivity({
            details: menuName,
            largeImageKey: DISCORD.DEFAULT_IMAGE,
            largeImageText: "dx.bi",
            smallImageKey: DISCORD.BROWSE_IMAGE,
            smallImageText: "Browsing",
            instance: false,
            type: ActivityType.Playing
        });
    }

    private static _updateActivity(newActivity: SetActivity): void {
        if (!this.rpc || !this.enabled) return;
        if (!this.ready) return;

        // every status carries a link to dx.bi for whoever sees it
        this.rpc.user?.setActivity({ ...newActivity, buttons: DISCORD.BUTTONS.map((b) => ({ ...b })) }).catch((error) => {
            this.logger.error(`Failed to set Discord activity: ${(error as Error).message}`);
        });
    }
}

export default DiscordPresence;
