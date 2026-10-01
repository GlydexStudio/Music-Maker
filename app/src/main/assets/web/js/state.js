// Shared app state with tiny pub/sub. Native storage stays the source of truth for
// songs and settings; this only caches what the UI needs to render quickly.

const state = {
  settings: null,
  model: null,
  appInfo: null,
  systemDark: true,
  beat: { playing: false, step: -1 },
  keyboard: false,
};

const subs = new Map();

export function get(key) {
  return state[key];
}

export function set(key, value) {
  state[key] = value;
  (subs.get(key) || []).forEach((fn) => {
    try { fn(value); } catch (e) { console.error(e); }
  });
}

/** Subscribe to a key. Returns an unsubscribe function. */
export function watch(key, fn) {
  if (!subs.has(key)) subs.set(key, new Set());
  subs.get(key).add(fn);
  return () => subs.get(key).delete(fn);
}
