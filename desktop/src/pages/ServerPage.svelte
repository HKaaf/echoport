<script lang="ts">
  import { onMount } from "svelte";
  import QRCode from "qrcode";
  import Toggle from "../components/Toggle.svelte";
  import { api, type EqPreset, type NetInterface, type Source } from "../lib/api";
  import { settings, update } from "../lib/settings";
  import { t } from "../lib/i18n";
  import { status, devices, serverError, startServer, stopServer, restartServerIfRunning, deviceName } from "../lib/state";

  let interfaces = $state<NetInterface[]>([]);
  let qr = $state("");
  let copied = $state("");

  onMount(() => {
    const load = async () => (interfaces = await api.interfaces());
    load();
    const timer = setInterval(load, 5000);
    return () => clearInterval(timer);
  });

  // The QR code carries every address, so the phone can pick whichever network it shares.
  $effect(() => {
    const ips = interfaces.map((i) => i.ip).join(",");
    const pin = $settings.requirePin ? `&pin=${$settings.pin}` : "";
    const url = `echoport://${ips}:${$settings.port}?name=${encodeURIComponent(deviceName())}${pin}`;
    if (!ips) { qr = ""; return; }
    QRCode.toDataURL(url, { margin: 1, width: 280, color: { dark: "#141A33", light: "#FFFFFF" } }).then((d) => (qr = d));
  });

  async function setSource(source: Source) {
    update({ source });
    await restartServerIfRunning();
  }

  async function setDevice(value: string) {
    const device = value || null;
    update($settings.source === "mic" ? { micDevice: device } : { loopbackDevice: device });
    await restartServerIfRunning();
  }

  function setEffects(patch: Partial<typeof $settings.effects>) {
    const effects = { ...$settings.effects, ...patch };
    update({ effects });
    api.setEffects(effects);
  }

  async function copy(ip: string) {
    await navigator.clipboard.writeText(`${ip}:${$settings.port}`);
    copied = ip;
    setTimeout(() => (copied = ""), 1500);
  }

  const levelPct = $derived(Math.max(0, Math.min(100, (($status.serverLevelDb + 60) / 60) * 100)));
  const deviceList = $derived($settings.source === "mic" ? $devices.inputs : $devices.outputs);
  const selected = $derived(($settings.source === "mic" ? $settings.micDevice : $settings.loopbackDevice) ?? "");
  const eqs: EqPreset[] = ["flat", "voiceClarity", "bassBoost", "treble"];
</script>

<div class="page">
  <header class="row between">
    <div>
      <h1>{$t("server.title")}</h1>
      <p class="muted">{$t("server.subtitle")}</p>
    </div>
    {#if $status.serverRunning}
      <button class="btn big" onclick={stopServer}>{$t("server.stop")}</button>
    {:else}
      <button class="btn big primary" onclick={startServer}>{$t("server.start")}</button>
    {/if}
  </header>
  {#if $serverError}<p class="error">{$serverError}</p>{/if}

  <div class="grid">
    <section class="card">
      <h2>{$t("server.source")}</h2>
      <div class="seg">
        <button class:on={$settings.source === "mic"} onclick={() => setSource("mic")}>{$t("server.mic")}</button>
        <button class:on={$settings.source === "system"} onclick={() => setSource("system")}>{$t("server.system")}</button>
      </div>
      <label class="field">
        {$t("server.device")}
        <select value={selected} onchange={(e) => setDevice(e.currentTarget.value)}>
          <option value="">{$t("server.default")}</option>
          {#each deviceList as d}<option value={d}>{d}</option>{/each}
        </select>
      </label>
      <div class="field">
        <div class="row between"><span>{$t("server.level")}</span><span class="value">{$status.serverRunning ? `${$status.serverLevelDb.toFixed(0)} dB` : "—"}</span></div>
        <div class="meter"><div style="width: {$status.serverRunning ? levelPct : 0}%"></div></div>
      </div>
    </section>

    <section class="card">
      <h2>{$t("server.connect")}</h2>
      <div class="connect">
        {#if qr}<img class="qr" src={qr} alt="QR code" />{/if}
        <div class="addr-list">
          <p class="muted">{$t("server.connectHint")}</p>
          {#each interfaces as i}
            <div class="item addr">
              <div>
                <div class="mono">{i.ip}<span class="muted">:{$settings.port}</span></div>
                <div class="muted small">{i.name}</div>
              </div>
              <div class="row">
                <span class="badge">{$t(`iface.${i.kind}`)}</span>
                <button class="btn small" onclick={() => copy(i.ip)}>{copied === i.ip ? $t("server.copied") : $t("server.copy")}</button>
              </div>
            </div>
          {:else}
            <p class="muted">{$t("server.noAddresses")}</p>
          {/each}
          {#if $settings.requirePin}
            <div class="muted">{$t("server.pin")} <strong class="value mono">{$settings.pin}</strong></div>
          {/if}
        </div>
      </div>
    </section>
  </div>

  <section class="card">
    <Toggle
      checked={$settings.effects.noiseSuppression}
      label={$t("noise.title")}
      hint={$t("noise.hint")}
      onchange={(v) => setEffects({ noiseSuppression: v })}
    />
  </section>

  <section class="card">
    <h2>{$t("server.effects")}</h2>
    <div class="grid">
      <label class="field">
        <span class="row between"><span>{$t("server.gain")}</span><span class="value">{$settings.effects.gainPercent}%</span></span>
        <input type="range" min="0" max="400" step="5" value={$settings.effects.gainPercent}
          oninput={(e) => setEffects({ gainPercent: Number(e.currentTarget.value) })} />
        <span>{$t("server.gainHint")}</span>
      </label>
      <label class="field">
        {$t("server.eq")}
        <select value={$settings.effects.eq} onchange={(e) => setEffects({ eq: e.currentTarget.value as EqPreset })}>
          {#each eqs as eq}<option value={eq}>{$t(`eq.${eq}`)}</option>{/each}
        </select>
      </label>
    </div>
  </section>

  <section class="card">
    <h2>{$t("server.players")}</h2>
    {#if !$status.serverRunning}
      <p class="muted">{$t("server.notRunning")}</p>
    {:else}
      {#each $status.peers as p (p.id)}
        <div class="item">
          <div>
            <div style="font-weight: 600">{p.name}</div>
            <div class="muted">{p.addr} · {p.platform} · {p.rttMs} ms</div>
          </div>
          <button class="btn small" onclick={() => api.kick(p.id)}>{$t("server.disconnect")}</button>
        </div>
      {:else}
        <p class="muted">{$t("server.noPlayers")}</p>
      {/each}
    {/if}
  </section>
</div>

<style>
  .page { display: flex; flex-direction: column; gap: 20px; max-width: 1100px; }
  .meter { height: 8px; border-radius: 4px; background: var(--surface2); overflow: hidden; }
  .meter div { height: 100%; background: var(--accent); transition: width 0.1s linear; }
  .connect { display: flex; gap: 18px; flex-wrap: wrap; align-items: flex-start; }
  .qr { width: 140px; height: 140px; border-radius: 10px; background: #fff; }
  .addr-list { flex: 1; min-width: 220px; display: flex; flex-direction: column; gap: 8px; }
  .addr { padding: 10px 12px; }
  .small { font-size: 12px; }
</style>
