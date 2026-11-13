<script lang="ts">
  // Live line chart of the player's estimated delay over the last minute.
  import type { DelaySample } from "../lib/state";
  import { t, dir } from "../lib/i18n";

  let { samples }: { samples: DelaySample[] } = $props();

  const W = 640;
  const H = 180;
  const PAD = { top: 12, right: 12, bottom: 24, left: 44 };
  const plotW = W - PAD.left - PAD.right;
  const plotH = H - PAD.top - PAD.bottom;
  const WINDOW_MS = 60_000;

  let hover = $state<number | null>(null);
  let svg: SVGSVGElement;

  const now = $derived(samples.length ? samples[samples.length - 1].t : Date.now());
  // Round the top of the scale up to a tidy step so gridlines land on whole numbers.
  const scale = $derived.by(() => {
    const peak = Math.max(50, ...samples.map((s) => s.delayMs));
    const step = peak <= 100 ? 25 : peak <= 400 ? 100 : 250;
    return { step, max: Math.ceil((peak * 1.1) / step) * step };
  });
  const yMax = $derived(scale.max);
  const ticks = $derived(Array.from({ length: scale.max / scale.step + 1 }, (_, i) => i * scale.step));

  // The plot itself is always left-to-right; the two time labels keep their own text direction
  // so Persian reads "۶۰ ثانیه پیش" correctly. In RTL, SVG's start/end anchors swap sides.
  const rtl = $derived($dir === "rtl");

  const x = (time: number) => PAD.left + plotW * (1 - (now - time) / WINDOW_MS);
  const y = (ms: number) => PAD.top + plotH * (1 - ms / yMax);

  const points = $derived(samples.map((s) => `${x(s.t).toFixed(1)},${y(s.delayMs).toFixed(1)}`).join(" "));
  const area = $derived(
    samples.length > 1
      ? `${x(samples[0].t).toFixed(1)},${y(0)} ${points} ${x(samples[samples.length - 1].t).toFixed(1)},${y(0)}`
      : "",
  );

  const stats = $derived.by(() => {
    if (!samples.length) return null;
    const d = samples.map((s) => s.delayMs);
    const last = samples[samples.length - 1];
    return {
      current: last.delayMs,
      avg: Math.round(d.reduce((a, b) => a + b, 0) / d.length),
      max: Math.max(...d),
      rtt: last.rttMs,
      buffer: last.bufferMs,
    };
  });

  function onMove(e: PointerEvent) {
    if (!samples.length) return;
    const r = svg.getBoundingClientRect();
    const px = ((e.clientX - r.left) / r.width) * W;
    let best = 0;
    for (let i = 1; i < samples.length; i++) {
      if (Math.abs(x(samples[i].t) - px) < Math.abs(x(samples[best].t) - px)) best = i;
    }
    hover = best;
  }

  const hovered = $derived(hover !== null ? samples[hover] : null);
  const ago = (s: DelaySample) => Math.round((now - s.t) / 1000);
</script>

<div class="delay">
  <div class="tiles">
    <div class="tile hero">
      <span class="label">{$t("delay.current")}</span>
      <span class="num">{stats ? stats.current : "—"}<small> ms</small></span>
    </div>
    <div class="tile"><span class="label">{$t("delay.average")}</span><span class="num sm">{stats ? `${stats.avg} ms` : "—"}</span></div>
    <div class="tile"><span class="label">{$t("delay.max")}</span><span class="num sm">{stats ? `${stats.max} ms` : "—"}</span></div>
    <div class="tile"><span class="label">{$t("delay.ping")}</span><span class="num sm">{stats ? `${stats.rtt} ms` : "—"}</span></div>
    <div class="tile"><span class="label">{$t("delay.buffer")}</span><span class="num sm">{stats ? `${stats.buffer} ms` : "—"}</span></div>
  </div>

  <div class="plot">
    <svg
      bind:this={svg}
      viewBox="0 0 {W} {H}"
      role="img"
      aria-label={stats ? $t("delay.aria", { current: stats.current, avg: stats.avg, max: stats.max }) : $t("delay.empty")}
      onpointermove={onMove}
      onpointerleave={() => (hover = null)}
    >
      {#each ticks as v}
        <line x1={PAD.left} x2={W - PAD.right} y1={y(v)} y2={y(v)} class="grid" />
        <text x={PAD.left - 8} y={y(v) + 4} class="axis" text-anchor="end">{v}</text>
      {/each}
      <text x={PAD.left} y={H - 6} class="axis" style="direction: {$dir}" text-anchor={rtl ? "end" : "start"}>{$t("delay.ago", { s: 60 })}</text>
      <text x={W - PAD.right} y={H - 6} class="axis" style="direction: {$dir}" text-anchor={rtl ? "start" : "end"}>{$t("delay.now")}</text>

      {#if area}
        <polygon points={area} class="area" />
        <polyline points={points} class="line" />
      {/if}

      {#if hovered}
        <line x1={x(hovered.t)} x2={x(hovered.t)} y1={PAD.top} y2={PAD.top + plotH} class="cross" />
        <circle cx={x(hovered.t)} cy={y(hovered.delayMs)} r="5" class="dot" />
      {/if}
      <!-- Transparent hit area so the whole plot answers hover, not just the line. -->
      <rect x={PAD.left} y={PAD.top} width={plotW} height={plotH} fill="transparent" />
    </svg>

    {#if hovered}
      <div class="tip" style="left: {Math.min(88, Math.max(12, (x(hovered.t) / W) * 100))}%; top: {(y(hovered.delayMs) / H) * 100}%">
        <strong>{hovered.delayMs} ms</strong>
        <span>{$t("delay.tipDetail", { rtt: hovered.rttMs, buf: hovered.bufferMs })}</span>
        <span class="muted-ink">{ago(hovered) === 0 ? $t("delay.now") : $t("delay.ago", { s: ago(hovered) })}</span>
      </div>
    {/if}
    {#if !samples.length}
      <p class="empty muted">{$t("delay.empty")}</p>
    {/if}
  </div>
  <p class="muted">{$t("delay.hint")}</p>
</div>

<style>
  .delay { display: flex; flex-direction: column; gap: 12px; }
  .tiles { display: flex; flex-wrap: wrap; gap: 8px 20px; align-items: flex-end; }
  .tile { display: flex; flex-direction: column; gap: 2px; }
  .label { font-size: 12px; color: var(--muted); }
  .num { font-size: 15px; font-weight: 600; color: var(--text); font-variant-numeric: tabular-nums; }
  .hero .num { font-size: 30px; font-weight: 700; line-height: 1.1; }
  .hero small { font-size: 14px; font-weight: 600; color: var(--muted); }
  .plot { position: relative; }
  /* The time axis runs left to right in every language; in RTL the text anchors would flip and clip. */
  svg { width: 100%; height: auto; display: block; touch-action: none; direction: ltr; }
  .grid { stroke: var(--border); stroke-width: 1; }
  .axis { fill: var(--muted); font-size: 11px; font-variant-numeric: tabular-nums; }
  .line { fill: none; stroke: var(--accent); stroke-width: 2; stroke-linejoin: round; stroke-linecap: round; }
  .area { fill: var(--accent); opacity: 0.12; }
  .cross { stroke: var(--muted); stroke-width: 1; stroke-dasharray: 3 3; }
  .dot { fill: var(--accent); stroke: var(--surface); stroke-width: 2; }
  .tip {
    position: absolute; transform: translate(-50%, calc(-100% - 12px)); pointer-events: none;
    display: flex; flex-direction: column; gap: 2px; white-space: nowrap;
    padding: 8px 10px; border-radius: 8px; font-size: 12px;
    background: var(--surface); color: var(--text); border: 1px solid var(--border);
    box-shadow: 0 4px 14px rgb(0 0 0 / 0.18);
  }
  .tip strong { font-size: 14px; }
  .muted-ink { color: var(--muted); }
  .empty { position: absolute; inset: 0; display: grid; place-items: center; text-align: center; }
</style>
