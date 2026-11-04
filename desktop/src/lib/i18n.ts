import { derived } from "svelte/store";
import { settings } from "./settings";
import en from "../locales/en.json";
import fa from "../locales/fa.json";

const dictionaries: Record<string, Record<string, string>> = { en, fa };

/** `$t("key", { name: "x" })` — falls back to English, then to the key itself. */
export const t = derived(settings, ($s) => {
  const dict = dictionaries[$s.language] ?? en;
  return (key: string, vars: Record<string, string | number> = {}) => {
    let text = dict[key] ?? (en as Record<string, string>)[key] ?? key;
    for (const [k, v] of Object.entries(vars)) text = text.replaceAll(`{${k}}`, String(v));
    return text;
  };
});

export const dir = derived(settings, ($s) => ($s.language === "fa" ? "rtl" : "ltr"));
