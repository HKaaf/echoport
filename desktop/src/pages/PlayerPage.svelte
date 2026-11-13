<script lang="ts">
  import { onMount } from "svelte";
  import { openUrl, revealItemInDir } from "@tauri-apps/plugin-opener";
  import { api, type Destination, type Discovered } from "../lib/api";
  import { settings, update } from "../lib/settings";
  import { t } from "../lib/i18n";
  import DelayChart from "../components/DelayChart.svelte";
  import { status, devices, playerError, delayHistory, connectTo, disconnect } from "../lib/state";

  const VB_CABLE_URL = "https://vb-audio.com/Cable/";

  let servers = $state<Discovered[]>([]);
  let manualIp = $state("");
  let manualPin = $state("");
  let busy = $state("");
  let savedPath = $state("");

  onMount(() => {
    const load = async () => (servers = await api.discovered());
    load();
    const timer = setInterval(load, 2000);
    return () => clearInterval(timer);
  });

  const connectedId = $derived($status.player?.connected ? $status.player.serverId : "");

  async function connect(addrs: string[], port: number, name: string, key: string, pin: string | null) {
    busy = key;
    await connectTo(addrs, port, name, pin);
    busy = "";
  }

  async function connectManual() {
    const [ip, port] = manualIp.trim().split(":");
    if (!ip) return;
    await connect([ip], Number(port) || 47210, ip, "manual", manualPin.trim() || null);
  }

  async function setDestination(destination: Destination) {
    update({ destination });
    const last = $settings.lastServer;
    if ($status.player?.connected && last) await connectTo(last.addrs, last.port, last.name, manualPin.trim() || null);
  }

  async function toggleRecording() {
    if ($status.recording) {
      await api.stopRecording();
    } else {
      savedPath = await api.startRecording();
    }
  }

  const errorText = $derived(
    $playerError === "pin_required" ? $t("player.pinNeeded")
    : $playerError === "bad_pin" ? $t("player.badPin")
    : $playerError === "vbcable_missing" ? ""
    : $playerError,
  );
  const needsVbCable = $derived($settings.destination === "virtualMic" && !$devices.virtualCable);
</script>

<div class="page">
  <header>
    <h1>{$t("player.title")}</h1>
    <p class="muted">{$t("player.subtitle")}</p>
  </header>

  {#if needsVbCable}
    <div class="banner">
      <div>
        <div style="font-weight: 600">{$t("vbcable.title")}</div>
        <div class="muted">{$t("vbcable.body")}</div>
      </div>
      <button class="btn primary" onclick={() => openUrl(VB_CABLE_URL).catch((e) => playerError.set(String(e)))}>{$t("vbcable.download")}</button>
    </div>
  {/if}

  <section class="card">
    <h2>{$t("player.servers")}</h2>
    {#each servers as s (s.id)}
      <div class="item">
        <div>
          <div style="font-weight: 600">{s.name}</div>
          <div class="muted">{s.addrs.join(", ")} · {s.source === "system" ? $t("server.system") : $t("server.mic")}</div>
        </div>
        {#if connectedId === s.id}
          <button class="btn small" onclick={disconnect}>{$t("player.disconnect")}</button>
        {:else}
          <button class="btn small primary" disabled={busy !== ""}
            onclick={() => connect(s.addrs, s.port, s.name, s.id, s.pinRequired ? manualPin.trim() || null : null)}>
            {busy === s.id ? $t("player.connecting") : $t("player.connect")}
          </button>
        {/if}
      </div>
    {:else}
      <p class="muted">{$t("player.searching")}</p>
    {/each}

    <h2 style="margin-top: 8px">{$t("player.manual")}</h2>
    <form class="manual" onsubmit={(e) => { e.preventDefault(); connectManual(); }}>
      <label class="field">{$t("player.ip")}
        <input type="text" class="mono" placeholder="192.168.1.10" bind:value={manualIp} />
      </label>
      <label class="field">{$t("player.pinOptional")}
        <input type="text" inputmode="numeric" maxlength="8" bind:value={manualPin} />
      </label>
      <button class="btn primary" type="submit" disabled={busy !== "" || !manualIp.trim()}>
        {busy === "manual" ? $t("player.connecting") : $t("player.connect")}
      </button>
    </form>
    {#if errorText}<p class="error">{errorText}</p>{/if}
  </section>

  {#if $status.player?.connected}
    <section class="card">
      <div class="row between">
        <h2>{$t("delay.title")}</h2>
        <span class="muted">{$t("delay.lost", { lost: $status.player.lost })}</span>
      </div>
      <DelayChart samples={$delayHistory} />
    </section>
  {/if}

  <div class="grid">
    <section class="card">
      <h2>{$t("player.playInto")}</h2>
      <div class="seg">
        <button class:on={$settings.destination === "speaker"} onclick={() => setDestination("speaker")}>{$t("player.speaker")}</button>
        <button class:on={$settings.destination === "virtualMic"} onclick={() => setDestination("virtualMic")}>{$t("player.virtualMic")}</button>
      </div>
      <p class="muted">{$settings.destination === "speaker" ? $t("player.speakerHint") : $t("player.vmicHint")}</p>
      {#if $settings.destination === "speaker"}
        <label class="field">{$t("player.output")}
          <select value={$settings.speakerDevice ?? ""} onchange={(e) => update({ speakerDevice: e.currentTarget.value || null })}>
            <option value="">{$t("server.default")}</option>
            {#each $devices.outputs as d}<option value={d}>{d}</option>{/each}
          </select>
        </label>
      {/if}
    </section>

    <section class="card">
      <label class="field">
        <span class="row between"><span>{$t("player.volume")}</span><span class="value">{$settings.volume}%</span></span>
        <input type="range" min="0" max="200" step="5" value={$settings.volume}
          oninput={(e) => { const v = Number(e.currentTarget.value); update({ volume: v }); api.setVolume(v); }} />
      </label>
      <label class="field">
        <span class="row between"><span>{$t("player.buffer")}</span><span class="value">{$settings.bufferMs} ms</span></span>
        <input type="range" min="10" max="300" step="5" value={$settings.bufferMs}
          oninput={(e) => { const v = Number(e.currentTarget.value); update({ bufferMs: v }); api.setBuffer(v); }} />
        <span>{$t("player.bufferHint")}</span>
      </label>
      <button class="btn" class:primary={$status.recording} disabled={!$status.player?.connected} onclick={toggleRecording}>
        {$status.recording ? $t("player.stopRecord") : $t("player.record")}
      </button>
      {#if savedPath && !$status.recording}
        <button class="link muted" onclick={() => revealItemInDir(savedPath)}>{$t("player.savedTo", { path: savedPath })}</button>
      {/if}
    </section>
  </div>
</div>

<style>
  .page { display: flex; flex-direction: column; gap: 20px; max-width: 1100px; }
  .manual { display: grid; grid-template-columns: 2fr 1fr auto; gap: 12px; align-items: end; }
  @media (max-width: 760px) { .manual { grid-template-columns: 1fr; } }
  .link { background: none; border: none; padding: 0; text-align: start; text-decoration: underline; word-break: break-all; }
</style>
