// UI themes. The brand colors (dark blue + dark purple) are fixed and only used for the
// logo and app icon; themes change everything else.

export const BRAND = { blue: "#1B2A6B", purple: "#4A2380" };

export interface Theme {
  id: string;
  name: string;
  dark: boolean;
  bg: string;
  surface: string;
  surface2: string;
  text: string;
  muted: string;
  /** Buttons, active nav, links. Must read on `surface` and carry `onAccent` text. */
  accent: string;
  accent2: string;
  onAccent: string;
  border: string;
}

export const THEMES: Theme[] = [
  // Light
  { id: "daybreak", name: "Daybreak", dark: false, bg: "#F4F6FB", surface: "#FFFFFF", surface2: "#EBEEF7", text: "#141A33", muted: "#545C78", accent: "#3D3AB8", accent2: "#6A3FB5", onAccent: "#FFFFFF", border: "#DDE2EF" },
  { id: "lavender", name: "Lavender", dark: false, bg: "#F6F3FC", surface: "#FFFFFF", surface2: "#EEE8F9", text: "#1E1433", muted: "#5E5277", accent: "#5B2E9E", accent2: "#3D3AB8", onAccent: "#FFFFFF", border: "#E2DAF2" },
  { id: "paper", name: "Paper", dark: false, bg: "#FAFAF7", surface: "#FFFFFF", surface2: "#F0F0EB", text: "#1A1A1A", muted: "#5A5A55", accent: "#1F3A8A", accent2: "#4B3A8A", onAccent: "#FFFFFF", border: "#E3E3DC" },
  { id: "mint", name: "Mint", dark: false, bg: "#F1F8F4", surface: "#FFFFFF", surface2: "#E3F1E9", text: "#10241A", muted: "#4A6356", accent: "#1E7A4F", accent2: "#2E9C8A", onAccent: "#FFFFFF", border: "#D3E6DB" },
  { id: "sunrise", name: "Sunrise", dark: false, bg: "#FDF5EF", surface: "#FFFFFF", surface2: "#FAE8DC", text: "#2A160B", muted: "#6B5245", accent: "#B4531A", accent2: "#C9772A", onAccent: "#FFFFFF", border: "#F0DACB" },
  { id: "lemon", name: "Lemon", dark: false, bg: "#FFFCEB", surface: "#FFFFFF", surface2: "#FBF3CC", text: "#221D05", muted: "#625A35", accent: "#7A5F00", accent2: "#E0B21C", onAccent: "#FFFFFF", border: "#EFE5B8" },
  // Dark
  { id: "midnight", name: "Midnight", dark: true, bg: "#0B1020", surface: "#121A33", surface2: "#1A2445", text: "#E8ECF8", muted: "#9AA3C0", accent: "#8B7CF6", accent2: "#4F7FE8", onAccent: "#0B1020", border: "#26304F" },
  { id: "nebula", name: "Nebula", dark: true, bg: "#140E24", surface: "#1D1533", surface2: "#281D45", text: "#EEE8FA", muted: "#A89BC6", accent: "#B39BFA", accent2: "#6E8BF0", onAccent: "#140E24", border: "#33285A" },
  { id: "graphite", name: "Graphite", dark: true, bg: "#111214", surface: "#1A1C1F", surface2: "#24272B", text: "#ECEDEF", muted: "#A0A4AB", accent: "#6E9BF2", accent2: "#9C88F0", onAccent: "#0B0C0E", border: "#2E3136" },
  { id: "forest", name: "Forest", dark: true, bg: "#0B1712", surface: "#11211A", surface2: "#183026", text: "#E3F2EA", muted: "#93B3A3", accent: "#4CC38A", accent2: "#3FA7A0", onAccent: "#06140D", border: "#23402F" },
  { id: "ember", name: "Ember", dark: true, bg: "#170E09", surface: "#22150E", surface2: "#2F1D13", text: "#F6E9E1", muted: "#BFA293", accent: "#FF8A4C", accent2: "#E8B04A", onAccent: "#1A0D05", border: "#43291B" },
  { id: "gold", name: "Gold", dark: true, bg: "#13110A", surface: "#1C1A10", surface2: "#272416", text: "#F5F0DC", muted: "#B7AE8C", accent: "#F5C542", accent2: "#E58E3A", onAccent: "#1A1505", border: "#3A3520" },
  { id: "ocean", name: "Ocean", dark: true, bg: "#071A1F", surface: "#0C252C", surface2: "#12323B", text: "#E1F3F6", muted: "#8DB4BD", accent: "#3CC6D6", accent2: "#5B8FF0", onAccent: "#04161A", border: "#1D4651" },
  { id: "rose", name: "Rose", dark: true, bg: "#1A0D14", surface: "#25131D", surface2: "#331A28", text: "#F8E6EE", muted: "#C49BAE", accent: "#F27BA6", accent2: "#B98AF2", onAccent: "#1F0912", border: "#4A2639" },
];

export const DEFAULT_LIGHT = "daybreak";
export const DEFAULT_DARK = "midnight";

export function themeById(id: string): Theme {
  return THEMES.find((t) => t.id === id) ?? THEMES[0];
}

export function applyTheme(t: Theme) {
  const root = document.documentElement.style;
  for (const key of ["bg", "surface", "surface2", "text", "muted", "accent", "accent2", "onAccent", "border"] as const) {
    root.setProperty(`--${key}`, t[key]);
  }
  root.setProperty("color-scheme", t.dark ? "dark" : "light");
}
