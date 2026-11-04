<script lang="ts">
  import { onMount } from "svelte";
  import { enable, disable, isEnabled } from "@tauri-apps/plugin-autostart";
  import { openUrl } from "@tauri-apps/plugin-opener";
  import Toggle from "../components/Toggle.svelte";
  import { api } from "../lib/api";
  import { settings, update, randomPin } from "../lib/settings";
  import { THEMES, type Theme } from "../lib/themes";
  import { t } from "../lib/i18n";
  import { info, restartServerIfRunning, deviceName } from "../lib/state";

  const REPO = "https://github.com/HKaaf/echoport";

  let launchAtLogin = $state(false);
  let nameDraft = $state("");

  onMount(async () => {
    launchAtLogin = await isEnabled().catch(() => false);
    nameDraft = $settings.deviceName;
  });

  async function setLaunchAtLogin(v: boolean) {
    await (v ? enable() : disable());
    launchAtLogin = await isEnabled();
  }

  async function saveName() {
    update({ deviceName: nameDraft.trim() });
    await api.setName(deviceName());
    await restartServerIfRunning();
  }

  async function resetName() {
    nameDraft = "";
    await saveName();
  }

  function pickTheme(th: Theme) {
    update(th.dark ? { darkTheme: th.id, themeMode: "dark" } : { lightTheme: th.id, themeMode: "light" });
  }

  const lightThemes = THEMES.filter((x) => !x.dark);
  const darkThemes = THEMES.filter((x) => x.dark);
  const modes = ["system", "light", "dark"] as const;
  const modeLabel = { system: "settings.modeSystem", light: "settings.modeLight", dark: "settings.modeDark" };
</script>

{#snippet themeGrid(list: Theme[], selectedId: string)}
  <div class="themes">
    {#each list as th (th.id)}
      <button class="theme" class:selected={th.id === selectedId} onclick={() => pickTheme(th)}
        style="background: {th.surface}; color: {th.text}; border-color: {th.id === selectedId ? 'var(--accent)' : th.border}">
        <span class="swatches">
          <span style="background: {th.bg}; border-color: {th.border}"></span>
          <span style="background: {th.accent}; border-color: {th.border}"></span>
          <span style="background: {th.accent2}; border-color: {th.border}"></span>
        </span>
        <span class="theme-name">{th.name}</span>
      </button>
    {/each}
  </div>
{/snippet}

<div class="page">
  <header>
    <h1>{$t("settings.title")}</h1>
    <p class="muted">{$t("settings.subtitle")}</p>
  </header>

  <section class="card">
    <h2>{$t("settings.device")}</h2>
    <label class="field">{$t("settings.deviceName")}
      <div class="row">
        <input type="text" class="text grow" maxlength="40" placeholder={$info.defaultName}
          bind:value={nameDraft} onblur={saveName} onkeydown={(e) => e.key === "Enter" && saveName()} />
        <button class="btn" onclick={resetName}>{$t("settings.reset")}</button>
      </div>
      <span>{$t("settings.deviceNameHint", { name: $info.defaultName })}</span>
    </label>
  </section>

  <section class="card">
    <h2>{$t("settings.appearance")}</h2>
    <div class="field">{$t("settings.mode")}
      <div class="seg narrow">
        {#each modes as m}
          <button class:on={$settings.themeMode === m} onclick={() => update({ themeMode: m })}>{$t(modeLabel[m])}</button>
        {/each}
      </div>
    </div>
    <div class="field">{$t("settings.lightThemes")}{@render themeGrid(lightThemes, $settings.lightTheme)}</div>
    <div class="field">{$t("settings.darkThemes")}{@render themeGrid(darkThemes, $settings.darkTheme)}</div>
    <label class="field">{$t("settings.language")}
      <select class="narrow" value={$settings.language} onchange={(e) => update({ language: e.currentTarget.value as "en" | "fa" })}>
        <option value="en">English</option>
        <option value="fa">فارسی</option>
      </select>
    </label>
  </section>

  <section class="card">
    <h2>{$t("settings.startup")}</h2>
    <Toggle checked={launchAtLogin} label={$t("settings.launchAtLogin")} hint={$t("settings.launchAtLoginHint")} onchange={setLaunchAtLogin} />
    <Toggle checked={$settings.startMinimized} label={$t("settings.startMinimized")} onchange={(v) => update({ startMinimized: v })} />
    <Toggle checked={$settings.closeToTray} label={$t("settings.closeToTray")} onchange={(v) => update({ closeToTray: v })} />
    <Toggle checked={$settings.autoStartServer} label={$t("settings.autoStartServer")} onchange={(v) => update({ autoStartServer: v })} />
    <Toggle checked={$settings.autoReconnect} label={$t("settings.autoReconnect")} onchange={(v) => update({ autoReconnect: v })} />
  </section>

  <section class="card">
    <h2>{$t("settings.connection")}</h2>
    <Toggle checked={$settings.requirePin} label={$t("settings.requirePin")} hint={$t("settings.requirePinHint")}
      onchange={async (v) => { update({ requirePin: v }); await restartServerIfRunning(); }} />
    {#if $settings.requirePin}
      <div class="row">
        <span class="pin mono">{$settings.pin}</span>
        <button class="btn small" onclick={async () => { update({ pin: randomPin() }); await restartServerIfRunning(); }}>{$t("settings.newPin")}</button>
      </div>
    {/if}
    <label class="field">{$t("settings.port")}
      <input type="number" class="narrow" min="1024" max="65535" value={$settings.port}
        onchange={(e) => { const p = Number(e.currentTarget.value); if (p >= 1024 && p <= 65535) update({ port: p }); }} />
      <span>{$t("settings.portHint")}</span>
    </label>
  </section>

  <section class="card">
    <h2>{$t("settings.audio")}</h2>
    <label class="field">
      <span class="row between"><span>{$t("settings.defaultBuffer")}</span><span class="value">{$settings.bufferMs} ms</span></span>
      <input type="range" min="10" max="300" step="5" value={$settings.bufferMs} oninput={(e) => update({ bufferMs: Number(e.currentTarget.value) })} />
      <span>{$t("player.bufferHint")}</span>
    </label>
    <Toggle checked={$settings.notifications} label={$t("settings.notifications")} onchange={(v) => update({ notifications: v })} />
  </section>

  <section class="card">
    <h2>{$t("settings.about")}</h2>
    <p>Echoport · {$t("settings.version", { v: $info.version })} · {$t("settings.protocol")}</p>
    <p class="muted">{$t("settings.license")}</p>
    <div class="row">
      <button class="btn" onclick={() => openUrl(REPO)}>{$t("settings.github")}</button>
    </div>
  </section>
</div>

<style>
  .page { display: flex; flex-direction: column; gap: 20px; max-width: 900px; }
  .grow { flex: 1; min-width: 200px; }
  .narrow { max-width: 360px; }
  .themes { display: grid; grid-template-columns: repeat(auto-fill, minmax(150px, 1fr)); gap: 10px; }
  .theme {
    display: flex; flex-direction: column; align-items: flex-start; gap: 10px;
    padding: 12px; border-radius: 12px; border: 1px solid; text-align: start;
  }
  .theme.selected { border-width: 2px; padding: 11px; }
  .swatches { display: flex; gap: 4px; }
  .swatches span { width: 20px; height: 20px; border-radius: 6px; border: 1px solid; }
  .theme-name { font-weight: 600; }
  .pin { font-size: 22px; letter-spacing: 0.2em; font-weight: 700; }
</style>
