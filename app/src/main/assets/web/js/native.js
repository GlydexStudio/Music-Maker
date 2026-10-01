// Bridge client. The only module that talks to native Android.
// Native injects `MusicMakerBridge` (origin-restricted WebMessageListener); calls are
// JSON messages with an id, answered asynchronously by a matching "result" message.

const ERROR_TEXT = {
  BAD_REQUEST: 'That request could not be processed.',
  NOT_FOUND: 'This item no longer exists.',
  STORAGE: 'Could not access device storage.',
  MODEL_MISSING: 'The AI model is not installed yet.',
  MODEL_LOAD_FAILED: 'The AI model could not be loaded.',
  UNSUPPORTED_DEVICE: 'On-device AI is not supported on this device.',
  OUT_OF_MEMORY: 'Not enough memory. Close other apps and try again.',
  BUSY: 'The AI is busy. Wait for the current task to finish.',
  GENERATION_FAILED: 'Generation failed. Please try again.',
  INVALID_RESPONSE: 'The AI returned an unreadable answer. Try again.',
  INSTALL_FAILED: 'Model installation failed.',
  NO_SPACE: 'Not enough free storage.',
  AUDIO: 'Audio playback failed.',
  CANCELLED: 'Cancelled.',
  BRIDGE: 'The app engine is not responding. Restart Music Maker.',
  INTERNAL: 'Something went wrong. Please try again.',
};

export class NativeError extends Error {
  constructor(code, message) {
    super(message || ERROR_TEXT[code] || ERROR_TEXT.INTERNAL);
    this.code = code || 'INTERNAL';
  }
}

// Long-running methods (AI, pickers, export) have no timeout; everything else must answer fast.
const NO_TIMEOUT = new Set([
  'generateLyrics', 'generateSongIdea', 'generatePromotion', 'loadModel', 'reloadModel', 'unloadModel',
  'importModel', 'pickCoverImage', 'exportSong', 'shareBeat', 'deleteModel',
]);
const TIMEOUT_MS = 15000;

const pending = new Map();
const listeners = new Map();
let seq = 0;

function port() {
  return typeof window.MusicMakerBridge !== 'undefined' ? window.MusicMakerBridge : null;
}

function onMessage(event) {
  let msg;
  try { msg = JSON.parse(event.data); } catch { return; }
  if (msg.type === 'result') {
    const p = pending.get(msg.id);
    if (!p) return;
    pending.delete(msg.id);
    clearTimeout(p.timer);
    if (msg.ok) p.resolve(msg.data);
    else p.reject(new NativeError(msg.error && msg.error.code, msg.error && msg.error.message));
  } else if (msg.type === 'event') {
    (listeners.get(msg.name) || []).forEach((fn) => {
      try { fn(msg.data || {}); } catch (e) { console.error(e); }
    });
  }
}

export function available() {
  return !!port();
}

export function init() {
  const p = port();
  if (!p) return false;
  p.onmessage = onMessage;
  return true;
}

export function call(method, payload = {}) {
  const p = port();
  if (!p) return Promise.reject(new NativeError('BRIDGE'));
  const id = String(++seq);
  return new Promise((resolve, reject) => {
    const timer = NO_TIMEOUT.has(method) ? null : setTimeout(() => {
      pending.delete(id);
      reject(new NativeError('BRIDGE'));
    }, TIMEOUT_MS);
    pending.set(id, { resolve, reject, timer });
    try {
      p.postMessage(JSON.stringify({ id, method, payload }));
    } catch (e) {
      pending.delete(id);
      clearTimeout(timer);
      reject(new NativeError('BRIDGE'));
    }
  });
}

export function on(name, fn) {
  if (!listeners.has(name)) listeners.set(name, []);
  listeners.get(name).push(fn);
  return () => listeners.set(name, (listeners.get(name) || []).filter((f) => f !== fn));
}

/** Typed facade over the bridge protocol, one function per native capability. */
export const Native = {
  getAppInfo: () => call('getAppInfo'),
  exitApp: () => call('exitApp'),
  setSystemTheme: (light) => call('setSystemTheme', { light }),

  getModelStatus: () => call('getModelStatus'),
  loadModel: () => call('loadModel'),
  reloadModel: () => call('reloadModel'),
  unloadModel: () => call('unloadModel'),
  deleteModel: () => call('deleteModel'),
  downloadModel: () => call('downloadModel'),
  importModel: () => call('importModel'),
  cancelModelInstall: () => call('cancelModelInstall'),

  generateLyrics: (request) => call('generateLyrics', request),
  generateSongIdea: (request) => call('generateSongIdea', request),
  generatePromotion: (request) => call('generatePromotion', request),

  getSongs: () => call('getSongs'),
  getSong: (id) => call('getSong', { id }),
  saveSong: (song) => call('saveSong', { song }),
  deleteSong: (id) => call('deleteSong', { id }),
  duplicateSong: (id) => call('duplicateSong', { id }),
  renameSong: (id, title) => call('renameSong', { id, title }),
  setFavorite: (id, favorite) => call('setFavorite', { id, favorite }),
  saveBeat: (id, beat) => call('saveBeat', { id, beat }),
  loadBeat: (id) => call('loadBeat', { id }),
  addPromotion: (id, item) => call('addPromotion', { id, item }),
  pickCoverImage: (id) => call('pickCoverImage', { id }),
  resetCover: (id) => call('resetCover', { id }),
  deleteAllProjects: () => call('deleteAllProjects'),
  resetAppData: () => call('resetAppData'),
  getStorageInfo: () => call('getStorageInfo'),

  playBeat: (beat) => call('playBeat', { beat }),
  updateBeat: (beat) => call('updateBeat', { beat }),
  pauseBeat: () => call('pauseBeat'),
  stopBeat: () => call('stopBeat'),
  previewSound: (track) => call('previewSound', { track }),
  getBeatState: () => call('getBeatState'),

  copyText: (text) => call('copyText', { text }),
  shareText: (text, subject) => call('shareText', { text, subject }),
  shareSong: (id) => call('shareSong', { id }),
  shareBeat: (beat, title, bars = 4) => call('shareBeat', { beat, title, bars }),
  exportSong: (id, format, bars = 4) => call('exportSong', { id, format, bars }),

  getSettings: () => call('getSettings'),
  saveSettings: (settings) => call('saveSettings', { settings }),
};
