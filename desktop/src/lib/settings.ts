// User settings, persisted as JSON by the Rust side (settings.json in the app config dir).
import { writable, get } from "svelte/store";
import { api, type Destination, type Effects, type Source } from "./api";

export interface Settings {
  deviceName: string; // empty = use the default name
  language: "en" | "fa";
  themeMode: "system" | "light" | "dark";
  lightTheme: string;
  darkTheme: string;
  // Startup
  startMinimized: boolean;
  closeToTray: boolean;
  autoStartServer: boolean;
  autoReconnect: boolean;
  // Server
  source: Source;
  micDevice: string | null;
  loopbackDevice: string | null;
  port: number;
  requirePin: boolean;
  pin: string;
  effects: Effects;
  // Player
  destination: Destination;
  speakerDevice: string | null;
  volume: number;
  bufferMs: number;
  lastServer: { addrs: string[]; port: number; name: string } | null;
  notifications: boolean;
}

export function randomPin(): string {
  return String(Math.floor(1000 + Math.random() * 9000));
}

export const DEFAULTS: Settings = {
  deviceName: "",
  language: "en",
  themeMode: "system",
  lightTheme: "daybreak",
  darkTheme: "midnight",
  startMinimized: true,
  closeToTray: true,
  autoStartServer: false,
  autoReconnect: true,
  source: "mic",
  micDevice: null,
  loopbackDevice: null,
  port: 47210,
  requirePin: false,
  pin: randomPin(),
  effects: { gainPercent: 100, noiseSuppression: false, eq: "flat" },
  destination: "speaker",
  speakerDevice: null,
  volume: 100,
  bufferMs: 40,
  lastServer: null,
  notifications: true,
};

export const settings = writable<Settings>({ ...DEFAULTS });

let saveTimer: ReturnType<typeof setTimeout> | undefined;

export async function loadSettings() {
  try {
    const raw = await api.loadSettings();
    if (raw) {
      const saved = JSON.parse(raw);
      settings.set({ ...DEFAULTS, ...saved, effects: { ...DEFAULTS.effects, ...saved.effects } });
    }
  } catch (e) {
    console.warn("settings unreadable, using defaults", e);
  }
  settings.subscribe((s) => {
    clearTimeout(saveTimer);
    saveTimer = setTimeout(() => api.saveSettings(JSON.stringify(s, null, 2), s.closeToTray), 300);
  });
}

export function update(patch: Partial<Settings>) {
  settings.update((s) => ({ ...s, ...patch }));
}

export function current(): Settings {
  return get(settings);
}
