<script lang="ts">
  import { onMount } from "svelte";
  import { getCurrentWindow } from "@tauri-apps/api/window";
  import Logo from "./components/Logo.svelte";
  import ServerPage from "./pages/ServerPage.svelte";
  import PlayerPage from "./pages/PlayerPage.svelte";
  import SettingsPage from "./pages/SettingsPage.svelte";
  import { api } from "./lib/api";
  import { settings, loadSettings, current } from "./lib/settings";
  import { applyTheme, themeById } from "./lib/themes";
  import { t, dir } from "./lib/i18n";
  import { info, status, refreshDevices, startServer, connectTo, deviceName } from "./lib/state";

  type Tab = "server" | "player" | "settings";
  let tab = $state<Tab>("server");
  let systemDark = $state(window.matchMedia("(prefers-color-scheme: dark)").matches);

  // Theme follows the chosen mode; "system" picks the default light or dark theme.
  $effect(() => {
    const s = $settings;
    const dark = s.themeMode === "dark" || (s.themeMode === "system" && systemDark);
    applyTheme(themeById(dark ? s.darkTheme : s.lightTheme));
  });

  $effect(() => {
    document.documentElement.dir = $dir;
    document.documentElement.lang = $settings.language;
  });

  onMount(() => {
    const media = window.matchMedia("(prefers-color-scheme: dark)");
    const onScheme = (e: MediaQueryListEvent) => (systemDark = e.matches);
    media.addEventListener("change", onScheme);
    let timer: ReturnType<typeof setInterval>;

    (async () => {
      info.set(await api.info());
      await loadSettings();
      const s = current();
      await api.setName(deviceName());
      await refreshDevices();
      if (!($info.launchedMinimized && s.startMinimized)) {
        const w = getCurrentWindow();
        await w.show();
        await w.setFocus();
      }
      if (s.autoStartServer) await startServer();
      if (s.autoReconnect && s.lastServer) {
        tab = "player";
        await connectTo(s.lastServer.addrs, s.lastServer.port, s.lastServer.name, null);
      }
      timer = setInterval(async () => status.set(await api.status()), 500);
    })();

    return () => {
      clearInterval(timer);
      media.removeEventListener("change", onScheme);
    };
  });

  const tabs: Tab[] = ["server", "player", "settings"];
</script>

<div class="shell">
  <nav>
    <div class="brand">
      <Logo size={34} />
      <span>Echoport</span>
    </div>
    {#each tabs as id}
      <button class="nav-item" class:active={tab === id} onclick={() => (tab = id)} aria-current={tab === id ? "page" : undefined}>
        {$t(`nav.${id}`)}
      </button>
    {/each}
    <div class="spacer"></div>
    <div class="me">
      <div class="me-name">{$settings.deviceName.trim() || $info.defaultName}</div>
      <div class="muted">
        {#if $status.player?.connected}
          {$t("status.connectedTo", { name: $status.player.serverName })}
        {:else if $status.serverRunning}
          {$t("status.serverOn")}
        {:else}
          {$t("status.idle")}
        {/if}
      </div>
    </div>
  </nav>

  <main>
    {#if tab === "server"}
      <ServerPage />
    {:else if tab === "player"}
      <PlayerPage />
    {:else}
      <SettingsPage />
    {/if}
  </main>
</div>

<style>
  .shell { display: flex; min-height: 100vh; }
  nav {
    width: 230px; flex: none; padding: 22px 14px; display: flex; flex-direction: column; gap: 4px;
    background: var(--surface); border-inline-end: 1px solid var(--border);
    position: sticky; top: 0; height: 100vh;
  }
  .brand { display: flex; align-items: center; gap: 10px; padding: 0 8px 22px; font-size: 21px; font-weight: 700; letter-spacing: -0.01em; }
  .nav-item {
    height: 42px; text-align: start; padding: 0 14px; border-radius: 10px; border: none;
    background: transparent; font-weight: 500;
  }
  .nav-item:hover { background: var(--surface2); }
  .nav-item.active { background: var(--surface2); color: var(--accent); font-weight: 700; }
  .spacer { flex: 1; }
  .me { padding: 12px; border-radius: 12px; background: var(--surface2); }
  .me-name { font-weight: 600; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
  main { flex: 1; min-width: 0; padding: 30px 36px 48px; }
  @media (max-width: 820px) {
    nav { width: 180px; }
    main { padding: 22px; }
  }
</style>
