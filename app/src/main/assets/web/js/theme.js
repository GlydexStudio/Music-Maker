// Applies theme + accent from settings to CSS variables and the native system bars.

import { Native } from './native.js';
import * as State from './state.js';

function hexToRgb(hex) {
  const n = parseInt(hex.slice(1), 16);
  return `${(n >> 16) & 255}, ${(n >> 8) & 255}, ${n & 255}`;
}

export function resolvedTheme() {
  const s = State.get('settings') || {};
  if (s.theme === 'light' || s.theme === 'dark') return s.theme;
  return State.get('systemDark') ? 'dark' : 'light';
}

export function applyTheme() {
  const s = State.get('settings') || {};
  const theme = resolvedTheme();
  const root = document.documentElement;
  root.dataset.theme = theme;
  const accent = s.accent || '#A78BFA';
  root.style.setProperty('--accent', accent);
  root.style.setProperty('--accent-rgb', hexToRgb(accent));
  Native.setSystemTheme(theme === 'light').catch(() => {});
}
