<script lang="ts">
  let {
    checked,
    label,
    hint = "",
    onchange,
  }: { checked: boolean; label: string; hint?: string; onchange: (v: boolean) => void } = $props();
</script>

<div class="toggle-row">
  <div class="text">
    <div class="label">{label}</div>
    {#if hint}<div class="muted">{hint}</div>{/if}
  </div>
  <button
    class="switch"
    class:on={checked}
    role="switch"
    aria-checked={checked}
    aria-label={label}
    onclick={() => onchange(!checked)}
  >
    <span class="knob"></span>
  </button>
</div>

<style>
  .toggle-row { display: flex; align-items: center; justify-content: space-between; gap: 16px; }
  .label { font-size: 14px; font-weight: 500; }
  .text { display: flex; flex-direction: column; gap: 2px; }
  .switch {
    flex: none; width: 48px; height: 28px; border-radius: 14px; padding: 3px;
    display: flex; justify-content: flex-start;
    background: var(--surface2); border: 1px solid var(--border);
    transition: background 0.15s;
  }
  .switch.on { background: var(--accent); border-color: var(--accent); justify-content: flex-end; }
  .knob { width: 20px; height: 20px; border-radius: 10px; background: var(--muted); }
  .switch.on .knob { background: var(--onAccent); }
</style>
