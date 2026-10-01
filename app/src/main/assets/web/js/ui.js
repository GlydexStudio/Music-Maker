// Reusable UI building blocks. Everything is built with DOM APIs and textContent,
// so model output and user text can never inject markup.

export function h(tag, props = {}, ...children) {
  const el = document.createElement(tag);
  for (const [k, v] of Object.entries(props || {})) {
    if (v == null || v === false) continue;
    if (k === 'class') el.className = v;
    else if (k === 'text') el.textContent = v;
    else if (k === 'style') Object.entries(v).forEach(([p, val]) => el.style.setProperty(p, val));
    else if (k === 'dataset') Object.assign(el.dataset, v);
    else if (k.startsWith('on') && typeof v === 'function') el.addEventListener(k.slice(2).toLowerCase(), v);
    else if (k in el && typeof v !== 'string') el[k] = v;
    else el.setAttribute(k, v === true ? '' : v);
  }
  append(el, children);
  return el;
}

function append(el, children) {
  for (const c of children.flat(Infinity)) {
    if (c == null || c === false) continue;
    el.append(c instanceof Node ? c : document.createTextNode(String(c)));
  }
}

const SVG_NS = 'http://www.w3.org/2000/svg';
export function icon(name, cls = '') {
  const svg = document.createElementNS(SVG_NS, 'svg');
  svg.setAttribute('class', `icon ${cls}`.trim());
  svg.setAttribute('aria-hidden', 'true');
  const use = document.createElementNS(SVG_NS, 'use');
  use.setAttribute('href', `#i-${name}`);
  svg.append(use);
  return svg;
}

export function iconButton(name, label, onClick, cls = '') {
  return h('button', { class: `icon-btn ${cls}`, 'aria-label': label, title: label, onclick: onClick }, icon(name));
}

export function button(label, { icon: ic, cls = '', onClick, disabled } = {}) {
  return h('button', { class: `btn ${cls}`, onclick: onClick, disabled }, ic ? icon(ic, 'sm') : null, h('span', { text: label }));
}

export function clear(el) {
  while (el.firstChild) el.removeChild(el.firstChild);
  return el;
}

/** Screen chrome: optional top bar + scrollable body. */
export function screenShell({ title, back, actions = [], root = false } = {}) {
  const body = h('div', { class: 'screen-body' }, h('div', { class: 'content' }));
  const el = h('section', { class: `screen${root ? ' root' : ''}` });
  let titleEl = null;
  if (!root) {
    titleEl = h('div', { class: 'title', text: title || '' });
    el.append(h('header', { class: 'topbar' },
      back ? iconButton('back', 'Back', back) : null,
      titleEl,
      h('div', { class: 'actions' }, actions)));
  }
  el.append(body);
  return { el, body, content: body.firstChild, titleEl };
}

export function pageHead(title, subtitle) {
  return h('div', { class: 'page-head' }, h('h1', { text: title }), subtitle ? h('p', { text: subtitle }) : null);
}

export function card(children, cls = '') {
  return h('div', { class: `card ${cls}` }, children);
}

export function cardTitle(text, ...extra) {
  return h('div', { class: 'card-title' }, h('span', { class: 'grow', text }), extra);
}

// ------------------------------------------------------------------ form controls

export function field(label, control) {
  return h('label', { class: 'field' }, h('span', { text: label }), control);
}

export function input({ value = '', placeholder = '', type = 'text', maxlength = 120, onInput } = {}) {
  const el = h('input', { class: 'input', type, placeholder, maxlength: String(maxlength), autocomplete: 'off' });
  el.value = value ?? '';
  if (onInput) el.addEventListener('input', () => onInput(el.value));
  return el;
}

export function textarea({ value = '', placeholder = '', rows = 3, maxlength = 2000, onInput, cls = 'textarea' } = {}) {
  const el = h('textarea', { class: cls, placeholder, rows: String(rows), maxlength: String(maxlength) });
  el.value = value ?? '';
  el.addEventListener('input', () => { autoGrow(el); if (onInput) onInput(el.value); });
  requestAnimationFrame(() => autoGrow(el));
  return el;
}

export function autoGrow(el) {
  el.style.setProperty('height', 'auto');
  el.style.setProperty('height', `${el.scrollHeight + 2}px`);
}

export function select(options, value, onChange) {
  const el = h('select', { class: 'select' });
  options.forEach((o) => {
    const opt = typeof o === 'string' ? { id: o, label: o } : o;
    const node = h('option', { value: opt.id, text: opt.label });
    el.append(node);
  });
  el.value = value ?? '';
  if (onChange) el.addEventListener('change', () => onChange(el.value));
  return el;
}

/** Single-choice segmented control. */
export function segmented(options, value, onChange) {
  const wrap = h('div', { class: 'segmented', role: 'radiogroup' });
  const render = (v) => {
    wrap.querySelectorAll('button').forEach((b) => {
      const on = b.dataset.value === v;
      b.classList.toggle('active', on);
      b.setAttribute('aria-checked', on ? 'true' : 'false');
    });
  };
  options.forEach((o) => {
    wrap.append(h('button', {
      type: 'button', role: 'radio', dataset: { value: o.id }, text: o.short || o.label,
      onclick: () => { render(o.id); onChange && onChange(o.id); },
    }));
  });
  render(value);
  wrap.setValue = render;
  return wrap;
}

/** Single-choice chip row. */
export function chipGroup(options, value, onChange, { scroll = false } = {}) {
  const wrap = h('div', { class: `chips${scroll ? ' scroll' : ''}`, role: 'radiogroup' });
  const render = (v) => wrap.querySelectorAll('.chip').forEach((c) => {
    c.classList.toggle('active', c.dataset.value === v);
    c.setAttribute('aria-checked', c.dataset.value === v ? 'true' : 'false');
  });
  options.forEach((o) => wrap.append(h('button', {
    type: 'button', class: 'chip', role: 'radio', dataset: { value: o.id }, text: o.label,
    onclick: () => { render(o.id); onChange && onChange(o.id); },
  })));
  render(value);
  wrap.setValue = render;
  return wrap;
}

export function stepper(value, { min, max, step = 1, onChange, format = (v) => v }) {
  let v = value;
  const out = h('div', { class: 'value', 'aria-live': 'polite' });
  const set = (nv, emit = true) => {
    v = Math.max(min, Math.min(max, nv));
    out.textContent = format(v);
    if (emit && onChange) onChange(v);
  };
  let holdTimer = null;
  const hold = (delta) => {
    set(v + delta);
    holdTimer = setTimeout(function rep() { set(v + delta); holdTimer = setTimeout(rep, 70); }, 400);
  };
  const release = () => clearTimeout(holdTimer);
  const mk = (ic, delta, label) => {
    const b = iconButton(ic, label, null);
    b.addEventListener('pointerdown', (e) => { e.preventDefault(); hold(delta); });
    ['pointerup', 'pointerleave', 'pointercancel'].forEach((ev) => b.addEventListener(ev, release));
    b.addEventListener('keydown', (e) => { if (e.key === 'Enter' || e.key === ' ') set(v + delta); });
    return b;
  };
  const el = h('div', { class: 'stepper' }, mk('minus', -step, 'Decrease'), out, mk('plus', step, 'Increase'));
  set(v, false);
  el.setValue = (nv) => set(nv, false);
  return el;
}

export function toggle(checked, onChange, label) {
  const inp = h('input', { type: 'checkbox', role: 'switch', 'aria-label': label || 'Toggle' });
  inp.checked = !!checked;
  inp.addEventListener('change', () => onChange && onChange(inp.checked));
  return h('label', { class: 'switch' }, inp, h('span'));
}

// ------------------------------------------------------------------ overlays

const overlayStack = [];

/** Bottom sheet. Returns { close, body, el }. `onClose` fires once. */
export function sheet({ title, content, footer, onClose, dismissible = true } = {}) {
  const body = h('div', { class: 'sheet-body' }, content);
  const panel = h('div', { class: 'sheet', role: 'dialog', 'aria-modal': 'true', 'aria-label': title || 'Dialog' },
    h('div', { class: 'grabber' }),
    title ? h('div', { class: 'sheet-head' }, h('h3', { text: title }), dismissible ? iconButton('close', 'Close', () => close()) : null) : null,
    body,
    footer ? h('div', { class: 'sheet-foot' }, footer) : null);
  const backdrop = h('div', { class: 'backdrop' }, panel);
  let closed = false;
  const close = (result) => {
    if (closed) return;
    closed = true;
    const i = overlayStack.indexOf(api);
    if (i >= 0) overlayStack.splice(i, 1);
    backdrop.classList.add('closing');
    setTimeout(() => backdrop.remove(), 200);
    onClose && onClose(result);
  };
  backdrop.addEventListener('click', (e) => { if (e.target === backdrop && dismissible) close(); });
  const api = { close, body, el: panel, dismissible };
  overlayStack.push(api);
  document.getElementById('overlay-root').append(backdrop);
  const focusable = panel.querySelector('input, textarea, select');
  if (focusable && focusable.dataset.autofocus === 'true') setTimeout(() => focusable.focus(), 250);
  return api;
}

/** Closes the top-most overlay. Returns true if one was open (used by the back button). */
export function closeTopOverlay() {
  const top = overlayStack[overlayStack.length - 1];
  if (!top) return false;
  if (top.dismissible) top.close();
  return true;
}

export function confirm({ title, message, okLabel = 'Confirm', danger = false, cancelLabel = 'Cancel' }) {
  return new Promise((resolve) => {
    let result = false;
    const s = sheet({
      title,
      content: h('p', { class: 'muted', text: message }),
      footer: [
        button(cancelLabel, { onClick: () => s.close() }),
        button(okLabel, { cls: danger ? 'danger' : 'primary', onClick: () => { result = true; s.close(); } }),
      ],
      onClose: () => resolve(result),
    });
  });
}

/** Three-way choice used for unsaved changes. Resolves 'save' | 'discard' | null. */
export function choose({ title, message, options }) {
  return new Promise((resolve) => {
    let result = null;
    const s = sheet({
      title,
      content: h('p', { class: 'muted', text: message }),
      footer: options.map((o) => button(o.label, { cls: o.cls || '', onClick: () => { result = o.id; s.close(); } })),
      onClose: () => resolve(result),
    });
  });
}

export function promptText({ title, label, value = '', placeholder = '', okLabel = 'Save', maxlength = 120 }) {
  return new Promise((resolve) => {
    let result = null;
    const inp = input({ value, placeholder, maxlength });
    inp.dataset.autofocus = 'true';
    const submit = () => { const v = inp.value.trim(); if (v) { result = v; s.close(); } else inp.focus(); };
    inp.addEventListener('keydown', (e) => { if (e.key === 'Enter') submit(); });
    const s = sheet({
      title,
      content: field(label, inp),
      footer: [button('Cancel', { onClick: () => s.close() }), button(okLabel, { cls: 'primary', onClick: submit })],
      onClose: () => resolve(result),
    });
  });
}

/** Action menu. items: [{id, label, icon, danger, sub, selected}] → resolves id or null. */
export function menu({ title, items }) {
  return new Promise((resolve) => {
    let result = null;
    const list = h('div', { class: 'list' }, items.map((it) => h('button', {
      class: `menu-item${it.danger ? ' danger' : ''}${it.selected ? ' selected' : ''}`,
      onclick: () => { result = it.id; s.close(); },
    }, it.icon ? icon(it.icon) : null, h('div', { class: 'grow' }, h('div', { text: it.label }), it.sub ? h('small', { text: it.sub }) : null),
    it.selected ? icon('check', 'sm') : null)));
    const s = sheet({ title, content: list, onClose: () => resolve(result) });
  });
}

export function toast(message, { type = 'info', action, onAction, duration = 3200 } = {}) {
  const root = document.getElementById('toast-root');
  const ic = type === 'err' ? 'alert' : type === 'ok' ? 'check' : 'info';
  const el = h('div', { class: `toast ${type}` }, icon(ic, 'sm'), h('span', { class: 'grow', text: message }),
    action ? h('button', { text: action, onclick: () => { onAction && onAction(); dismiss(); } }) : null);
  const dismiss = () => { el.classList.add('leaving'); setTimeout(() => el.remove(), 200); };
  root.append(el);
  while (root.children.length > 3) root.firstChild.remove();
  setTimeout(dismiss, duration);
}

export function errorToast(err) {
  if (err && err.code === 'CANCELLED') return;
  console.error(err);
  toast((err && err.message) || 'Something went wrong.', { type: 'err', duration: 4500 });
}

// ------------------------------------------------------------------ formatting & covers

export function formatBytes(b) {
  if (!b || b < 0) return '0 MB';
  if (b >= 1024 ** 3) return `${(b / 1024 ** 3).toFixed(2)} GB`;
  if (b >= 1024 ** 2) return `${(b / 1024 ** 2).toFixed(b >= 100 * 1024 ** 2 ? 0 : 1)} MB`;
  return `${Math.max(1, Math.round(b / 1024))} KB`;
}

export function relativeTime(ms) {
  if (!ms) return '';
  const diff = Date.now() - ms;
  const min = Math.round(diff / 60000);
  if (min < 1) return 'Just now';
  if (min < 60) return `${min} min ago`;
  const hrs = Math.round(min / 60);
  if (hrs < 24) return `${hrs} h ago`;
  const days = Math.round(hrs / 24);
  if (days < 7) return `${days} d ago`;
  return new Date(ms).toLocaleDateString(undefined, { day: 'numeric', month: 'short', year: days > 300 ? 'numeric' : undefined });
}

export function statusBadge(status) {
  const label = { draft: 'Draft', writing: 'Writing', recording: 'Recording', finished: 'Finished', released: 'Released' }[status] || 'Draft';
  return h('span', { class: `status ${status || 'draft'}`, text: label });
}

/** Deterministic, tasteful two-tone gradient from a numeric seed. */
function gradientFor(seed) {
  const s = Math.abs(Number(seed) || 0);
  const hue = s % 360;
  const hue2 = (hue + 35 + (s % 50)) % 360;
  const angle = 120 + (s % 120);
  return `linear-gradient(${angle}deg, hsl(${hue} 45% 32%), hsl(${hue2} 55% 16%))`;
}

export function coverEl(song, size = '') {
  const el = h('div', { class: `cover ${size}`.trim(), 'aria-hidden': 'true' });
  const c = (song && song.cover) || {};
  if (c.type === 'image' && song.id) {
    const img = h('img', { alt: '', loading: 'lazy', src: `/covers/${song.id}.jpg?v=${c.version || 0}` });
    img.addEventListener('error', () => { img.remove(); el.style.setProperty('background', gradientFor(song.id.length * 97)); el.append(icon('note')); });
    el.append(img);
  } else {
    el.style.setProperty('background', gradientFor(c.seed ?? 0));
    el.append(icon('note', size === 'lg' || size === 'md' ? 'lg' : ''));
  }
  return el;
}

export function debounce(fn, ms) {
  let t;
  return (...args) => { clearTimeout(t); t = setTimeout(() => fn(...args), ms); };
}

export function emptyState({ iconName, title, text, action }) {
  return h('div', { class: 'empty' }, h('div', { class: 'e-icon' }, icon(iconName, 'lg')), h('h3', { text: title }), h('p', { text }), action || null);
}
