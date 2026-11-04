// Typed wrappers around the Rust commands in src-tauri/src/lib.rs.
import { invoke } from "@tauri-apps/api/core";

export type Source = "mic" | "system";
export type EqPreset = "flat" | "voiceClarity" | "bassBoost" | "treble";
export type Destination = "speaker" | "virtualMic";

export interface Effects { gainPercent: number; noiseSuppression: boolean; eq: EqPreset }
export interface Info { id: string; defaultName: string; version: string; launchedMinimized: boolean }
export interface Devices { inputs: string[]; outputs: string[]; virtualCable: string | null }
export interface NetInterface { name: string; ip: string; kind: "wifi" | "ethernet" | "usb" | "virtual" | "other" }
export interface Discovered { id: string; name: string; addrs: string[]; port: number; source: string; pinRequired: boolean }
export interface Peer { id: string; name: string; platform: string; addr: string; rttMs: number }
export interface PlayerStatus {
  connected: boolean; serverId: string; serverName: string; source: Source | null;
  rttMs: number; lost: number; underruns: number; bufferedMs: number;
}
export interface Status {
  serverRunning: boolean; serverLevelDb: number; peers: Peer[];
  player: PlayerStatus | null; recording: boolean;
}

export const api = {
  info: () => invoke<Info>("get_info"),
  loadSettings: () => invoke<string | null>("load_settings"),
  saveSettings: (json: string, closeToTray: boolean) => invoke<void>("save_settings", { json, closeToTray }),
  devices: () => invoke<Devices>("list_devices"),
  interfaces: () => invoke<NetInterface[]>("interfaces"),
  discovered: () => invoke<Discovered[]>("discovered"),
  setName: (name: string) => invoke<void>("set_name", { name }),
  startServer: (opts: { source: Source; device: string | null; port: number; pin: string | null; effects: Effects }) =>
    invoke<void>("start_server", { opts }),
  stopServer: () => invoke<void>("stop_server"),
  setEffects: (effects: Effects) => invoke<void>("set_effects", { effects }),
  kick: (peerId: string) => invoke<void>("kick", { peerId }),
  connect: (opts: {
    addrs: string[]; port: number; pin: string | null; destination: Destination;
    device: string | null; volumePercent: number; bufferMs: number;
  }) => invoke<void>("connect", { opts }),
  disconnect: () => invoke<void>("disconnect"),
  setVolume: (percent: number) => invoke<void>("set_volume", { percent }),
  setBuffer: (ms: number) => invoke<void>("set_buffer", { ms }),
  startRecording: () => invoke<string>("start_recording"),
  stopRecording: () => invoke<void>("stop_recording"),
  status: () => invoke<Status>("status"),
};
