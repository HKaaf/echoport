// Live app state plus the actions shared by several pages.
import { writable, get } from "svelte/store";
import { api, type Devices, type Info, type Status } from "./api";
import { current, update } from "./settings";

export const info = writable<Info>({ id: "", defaultName: "", version: "", launchedMinimized: false });
export const devices = writable<Devices>({ inputs: [], outputs: [], virtualCable: null });
export const status = writable<Status>({ serverRunning: false, serverLevelDb: -96, peers: [], player: null, recording: false });
export const playerError = writable<string>("");
export const serverError = writable<string>("");

export function deviceName(): string {
  return current().deviceName.trim() || get(info).defaultName;
}

export async function refreshDevices() {
  devices.set(await api.devices());
}

export async function startServer() {
  const s = current();
  serverError.set("");
  try {
    await api.startServer({
      source: s.source,
      device: s.source === "mic" ? s.micDevice : s.loopbackDevice,
      port: s.port,
      pin: s.requirePin ? s.pin : null,
      effects: s.effects,
    });
  } catch (e) {
    serverError.set(String(e));
  }
  status.set(await api.status());
}

export async function stopServer() {
  await api.stopServer();
  status.set(await api.status());
}

/** Restarts a running server so new source/device/port/PIN settings apply. */
export async function restartServerIfRunning() {
  if (get(status).serverRunning) await startServer();
}

export async function connectTo(addrs: string[], port: number, name: string, pin: string | null) {
  const s = current();
  playerError.set("");
  try {
    await api.connect({
      addrs, port, pin,
      destination: s.destination,
      device: s.speakerDevice,
      volumePercent: s.volume,
      bufferMs: s.bufferMs,
    });
    update({ lastServer: { addrs, port, name } });
  } catch (e) {
    playerError.set(String(e));
  }
  status.set(await api.status());
}

export async function disconnect() {
  await api.disconnect();
  update({ lastServer: null });
  status.set(await api.status());
}
