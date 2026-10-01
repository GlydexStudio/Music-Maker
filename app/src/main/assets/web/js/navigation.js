// In-WebView navigation: four root tabs plus a push/pop stack for studio screens.
// Each screen module registers a factory: (params) => { el, onShow?, onHide?, canLeave?, destroy? }.

import { closeTopOverlay } from './ui.js';
import { Native } from './native.js';

const factories = new Map();
const roots = new Map();
const stack = [];
let activeTab = null;
let busy = false;

const stackEl = () => document.getElementById('stack');
const tabbar = () => document.getElementById('tabbar');

export function register(id, factory) {
  factories.set(id, factory);
}

function build(id, params) {
  const f = factories.get(id);
  if (!f) throw new Error(`Unknown screen: ${id}`);
  const screen = f(params || {});
  screen.id = id;
  return screen;
}

function current() {
  return stack.length ? stack[stack.length - 1] : roots.get(activeTab);
}

/** Runs a one-shot CSS animation. A new animation on the same element cancels the previous one. */
function animate(el, cls, done) {
  if (el._anim) el._anim.cancel();
  let timer = null;
  const cleanup = () => {
    el.removeEventListener('animationend', onEnd);
    clearTimeout(timer);
    el.classList.remove(cls);
    el._anim = null;
  };
  const finish = () => { cleanup(); if (done) done(); };
  // Ignore animationend bubbling up from child elements (e.g. staggered cards).
  const onEnd = (e) => { if (e.target === el) finish(); };
  el._anim = { cancel: cleanup };
  el.classList.add(cls);
  el.addEventListener('animationend', onEnd);
  timer = setTimeout(finish, 450);
}

function updateChrome() {
  const bar = tabbar();
  bar.classList.toggle('hidden', stack.length > 0 || document.body.classList.contains('kb-open'));
  const idx = ['home', 'songs', 'create', 'settings'].indexOf(activeTab);
  bar.querySelectorAll('.tab').forEach((t) => {
    const on = t.dataset.tab === activeTab;
    t.classList.toggle('active', on);
    t.setAttribute('aria-current', on ? 'page' : 'false');
  });
  bar.querySelector('.tab-indicator').style.setProperty('transform', `translateX(${idx * 100}%)`);
}

/** Switch root tab. Clears the pushed stack (asking pushed screens if they can leave). */
export async function tab(id) {
  if (busy) return;
  if (id === activeTab && !stack.length) {
    const r = roots.get(id);
    r && r.el.querySelector('.screen-body')?.scrollTo({ top: 0, behavior: 'smooth' });
    return;
  }
  for (let i = stack.length - 1; i >= 0; i--) {
    if (stack[i].canLeave && !(await stack[i].canLeave())) return;
  }
  while (stack.length) {
    const s = stack.pop();
    s.onHide && s.onHide();
    s.destroy && s.destroy();
    s.el.remove();
  }
  const prev = activeTab && roots.get(activeTab);
  if (prev) { prev.onHide && prev.onHide(); prev.el.hidden = true; }
  activeTab = id;
  let next = roots.get(id);
  if (!next) {
    next = build(id, {});
    roots.set(id, next);
    stackEl().append(next.el);
  }
  next.el.hidden = false;
  animate(next.el, 'enter-tab');
  next.onShow && next.onShow();
  updateChrome();
}

export function push(id, params) {
  if (busy) return;
  busy = true;
  const below = current();
  const screen = build(id, params);
  stack.push(screen);
  stackEl().append(screen.el);
  below && below.onHide && below.onHide();
  updateChrome();
  animate(screen.el, 'enter-push', () => { busy = false; });
  if (below && !below.el.hidden) animate(below.el, 'leave-push', () => { below.el.hidden = true; });
  screen.onShow && screen.onShow();
}

/** Pops one screen. `force` skips the canLeave guard. */
export async function back({ force = false } = {}) {
  if (busy || !stack.length) return false;
  const top = stack[stack.length - 1];
  if (!force && top.canLeave && !(await top.canLeave())) return true;
  busy = true;
  stack.pop();
  top.onHide && top.onHide();
  const below = current();
  below.el.hidden = false;
  animate(below.el, 'enter-pop');
  animate(top.el, 'leave-pop', () => { top.destroy && top.destroy(); top.el.remove(); busy = false; });
  updateChrome();
  below.onShow && below.onShow();
  return true;
}

/** Replace the top screen (e.g. Song Idea → Song Studio after "Use as Song"). */
export function replace(id, params) {
  const top = stack.pop();
  if (top) { top.onHide && top.onHide(); top.destroy && top.destroy(); top.el.remove(); }
  push(id, params);
}

/** Android back button. */
export async function handleBack() {
  if (closeTopOverlay()) return;
  if (await back()) return;
  if (activeTab !== 'home') { tab('home'); return; }
  Native.exitApp();
}

export function refreshChrome() {
  updateChrome();
}

export function init() {
  tabbar().querySelectorAll('.tab').forEach((t) => t.addEventListener('click', () => tab(t.dataset.tab)));
}
