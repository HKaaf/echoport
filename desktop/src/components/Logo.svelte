<script lang="ts">
  // The app mark always uses the fixed brand colors, whatever theme is active.
  import { BRAND } from "../lib/themes";
  let { size = 36 }: { size?: number } = $props();
</script>

<svg width={size} height={size} viewBox="0 0 64 64" aria-hidden="true">
  <defs>
    <linearGradient id="ep-brand" x1="0" y1="0" x2="1" y2="1">
      <stop offset="0" stop-color={BRAND.blue} />
      <stop offset="1" stop-color={BRAND.purple} />
    </linearGradient>
  </defs>
  <rect width="64" height="64" rx="16" fill="url(#ep-brand)" />
  <circle cx="22" cy="32" r="6" fill="#FFFFFF" />
  <path d="M33 20a17 17 0 0 1 0 24" fill="none" stroke="#FFFFFF" stroke-width="4.5" stroke-linecap="round" />
  <path d="M41 13a27 27 0 0 1 0 38" fill="none" stroke="#FFFFFF" stroke-opacity="0.6" stroke-width="4.5" stroke-linecap="round" />
</svg>
