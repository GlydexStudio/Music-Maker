// Model status UI (pill + card) and the shared "run an AI task" flow.

import { h, icon, button, sheet, toast, errorToast, formatBytes, confirm } from '../ui.js';
import { Native, NativeError } from '../native.js';
import * as State from '../state.js';

export function describe(m) {
  if (!m) return { label: 'Checking AI…', dot: 'idle', sub: '' };
  const inst = m.install || {};
  if (inst.active) {
    const pct = Math.round((inst.progress || 0) * 100);
    const verb = inst.kind === 'import' ? 'Importing' : 'Downloading';
    const sub = inst.status === 'paused' ? (inst.reason || 'Paused') : `${formatBytes(inst.bytes)} of ${formatBytes(inst.total)}`;
    return { label: `${verb} model ${pct}%`, dot: 'busy', sub };
  }
  if (!m.deviceSupported) return { label: 'AI unsupported', dot: 'err', sub: 'Needs a 64-bit ARM device' };
  switch (m.state) {
    case 'NOT_INSTALLED': return { label: 'AI not installed', dot: 'idle', sub: 'Install the model to write with AI' };
    case 'INSTALLED': return { label: 'AI on device', dot: 'idle', sub: 'Loads automatically when you use it' };
    case 'LOADING': return { label: 'Loading AI…', dot: 'busy', sub: 'Preparing the model in memory' };
    case 'READY': return { label: 'AI ready', dot: 'ok', sub: m.tokensPerSecond > 0 ? `${m.tokensPerSecond.toFixed(1)} tokens/s on this device` : 'Running fully offline' };
    case 'GENERATING': return { label: 'Generating…', dot: 'busy', sub: 'Working on-device' };
    case 'ERROR': return { label: 'AI error', dot: 'err', sub: (m.error && m.error.message) || 'Something went wrong' };
    default: return { label: m.state, dot: 'idle', sub: '' };
  }
}

/** Calls `render` now and on every model change until the element is removed. */
function live(el, render) {
  let seen = false;
  const update = (m) => {
    if (el.isConnected) seen = true;
    else if (seen) { stop(); return; }
    render(m);
  };
  const stop = State.watch('model', update);
  update(State.get('model'));
  return el;
}

export function modelPill(onClick) {
  const dot = h('span', { class: 'dot' });
  const label = h('span');
  const el = h('button', { class: 'model-pill', onclick: onClick, 'aria-label': 'AI model status' }, dot, label);
  return live(el, (m) => {
    const d = describe(m);
    dot.className = `dot ${d.dot}`;
    label.textContent = d.label;
  });
}

export function modelCard({ showManage = true } = {}) {
  const el = h('div', { class: 'card model-card' });
  return live(el, (m) => renderCard(el, m, showManage));
}

function renderCard(el, m, showManage) {
  const d = describe(m);
  const inst = (m && m.install) || {};
  el.replaceChildren(
    h('div', { class: 'm-head' },
      h('div', { class: 'm-icon' }, icon('chip')),
      h('div', { class: 'grow' },
        h('div', { class: 'm-name' }, (m && m.modelName) || 'Qwen3 1.7B · Q4_K_M'),
        h('div', { class: 'row', style: { gap: '8px', 'margin-top': '3px' } }, h('span', { class: `dot ${d.dot}` }), h('span', { class: 'm-sub', text: `${d.label} · ${d.sub}` })))),
  );
  if (inst.active) {
    const bar = h('i');
    bar.style.setProperty('width', `${Math.round((inst.progress || 0) * 100)}%`);
    el.append(h('div', { class: 'progress' }, bar));
  }
  if (inst.status === 'failed' && inst.error) {
    el.append(h('div', { class: 'notice err', style: { 'margin-top': '12px' } }, icon('alert', 'sm'), h('span', { text: inst.error.message })));
  }
  if (!m || !showManage) return;
  const actions = h('div', { class: 'm-actions' });
  if (inst.active) {
    actions.append(button('Cancel', { icon: 'close', cls: 'sm', onClick: () => Native.cancelModelInstall().then(setModel).catch(errorToast) }));
  } else if (m.state === 'NOT_INSTALLED' && m.deviceSupported) {
    actions.append(
      button(`Download (${formatBytes(m.expectedBytes)})`, { icon: 'download', cls: 'primary sm', onClick: () => startDownload(m) }),
      button('Import .gguf', { icon: 'file', cls: 'sm', onClick: importModel }));
  } else if (m.state === 'INSTALLED' || m.state === 'ERROR') {
    if (m.installed) actions.append(button(m.state === 'ERROR' ? 'Retry load' : 'Load model', { icon: 'chip', cls: 'primary sm', onClick: loadModel }));
  } else if (m.state === 'READY') {
    if (m.reloadRequired) actions.append(button('Reload to apply settings', { icon: 'refresh', cls: 'primary sm', onClick: () => withBusy(Native.reloadModel()) }));
    actions.append(button('Unload', { icon: 'close', cls: 'sm', onClick: () => withBusy(Native.unloadModel()) }));
  }
  if (actions.childElementCount) el.append(actions);
}

export function setModel(m) {
  if (m) State.set('model', m);
}

async function withBusy(promise) {
  try { setModel(await promise); } catch (e) { errorToast(e); refreshModel(); }
}

export async function refreshModel() {
  try { setModel(await Native.getModelStatus()); } catch (e) { /* status stays as-is */ }
}

export function loadModel() {
  return withBusy(Native.loadModel());
}

async function startDownload(m) {
  const ok = await confirm({
    title: 'Download AI model',
    message: `Music Maker will download ${m.modelName} (${formatBytes(m.expectedBytes)}) from Hugging Face. Use Wi-Fi if you can. After this one-time download, all AI features work offline. Free space: ${formatBytes(m.freeBytes)}.`,
    okLabel: 'Download',
  });
  if (!ok) return;
  try {
    setModel(await Native.downloadModel());
    toast('Download started. You can keep using the app.', { type: 'ok' });
  } catch (e) { errorToast(e); }
}

async function importModel() {
  try {
    setModel(await Native.importModel());
  } catch (e) { errorToast(e); }
}

/** Shown when an AI feature is used before the model is installed. */
export function showInstallSheet() {
  const m = State.get('model') || {};
  const s = sheet({
    title: 'Install the AI model',
    content: h('div', { class: 'stack-v' },
      h('p', { class: 'muted', text: 'Music Maker writes lyrics, ideas and promo copy with an AI that runs entirely on your phone. It needs a one-time model install.' }),
      modelCard({ showManage: true }),
      h('p', { class: 'tiny muted', text: 'Tip: if you already have Qwen3-1.7B-Q4_K_M.gguf on your phone, choose Import to skip the download.' })),
  });
  return s;
}

/**
 * Runs an AI request with the shared pre-flight checks. The native layer loads the model on
 * first use; this only intercepts the "not installed" case with a helpful sheet.
 */
export async function runAi(task) {
  const m = State.get('model');
  if (m && m.install && m.install.active) throw new NativeError('BUSY', 'The model is still being installed.');
  if (m && !m.deviceSupported) throw new NativeError('UNSUPPORTED_DEVICE');
  if (m && m.state === 'NOT_INSTALLED') {
    showInstallSheet();
    throw new NativeError('CANCELLED');
  }
  try {
    return await task();
  } catch (e) {
    if (e.code === 'MODEL_MISSING') { showInstallSheet(); throw new NativeError('CANCELLED'); }
    throw e;
  } finally {
    refreshModel();
  }
}

/** Overlay shown on a card while the AI works. Returns { el, stop }. */
export function generatingOverlay(text = 'Writing…') {
  const timer = h('small', { text: 'On-device · 0 s' });
  const el = h('div', { class: 'generating', role: 'status' }, h('div', { class: 'orb' }), h('b', { text }), timer);
  const started = Date.now();
  const label = () => {
    const m = State.get('model');
    const prefix = m && m.state === 'LOADING' ? 'Loading model' : 'On-device';
    timer.textContent = `${prefix} · ${Math.round((Date.now() - started) / 1000)} s`;
  };
  const iv = setInterval(label, 500);
  return { el, stop: () => { clearInterval(iv); el.remove(); } };
}
