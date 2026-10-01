// Entry point: connects to native, loads state, registers screens and starts navigation.

import * as NativeBridge from './native.js';
import { Native, on } from './native.js';
import * as State from './state.js';
import * as Nav from './navigation.js';
import { toast } from './ui.js';
import { applyTheme } from './theme.js';
import { setModel, refreshModel } from './components/model-status.js';
import { homeScreen } from './home.js';
import { songsScreen } from './songs.js';
import { createScreen } from './create.js';
import { settingsScreen } from './settings.js';
import { lyricsScreen } from './lyrics.js';
import { songIdeaScreen } from './song-idea.js';
import { beatScreen } from './beat.js';
import { promotionScreen } from './promotion.js';
import { songStudioScreen } from './song-studio.js';

function registerScreens() {
  Nav.register('home', homeScreen);
  Nav.register('songs', songsScreen);
  Nav.register('create', createScreen);
  Nav.register('settings', settingsScreen);
  Nav.register('lyrics', lyricsScreen);
  Nav.register('idea', songIdeaScreen);
  Nav.register('beat', beatScreen);
  Nav.register('promotion', promotionScreen);
  Nav.register('studio', songStudioScreen);
}

function listenToNative() {
  on('back', () => Nav.handleBack());
  on('model', (m) => {
    const prev = State.get('model') || {};
    setModel({ ...m, install: prev.install });
  });
  on('install', (inst) => {
    const prev = State.get('model') || {};
    setModel({ ...prev, install: inst });
    if (inst.status === 'done') { toast('AI model installed. You are ready to create.', { type: 'ok' }); refreshModel(); }
    if (inst.status === 'failed' && inst.error) toast(inst.error.message, { type: 'err', duration: 5000 });
  });
  on('keyboard', ({ visible }) => {
    document.body.classList.toggle('kb-open', !!visible);
    Nav.refreshChrome();
  });
  on('systemTheme', ({ dark }) => { State.set('systemDark', !!dark); applyTheme(); });
  on('toast', ({ message }) => toast(message));
  on('resume', () => refreshModel());
}

function fail(message) {
  const boot = document.getElementById('boot');
  const err = boot.querySelector('.boot-error');
  err.textContent = message;
  err.hidden = false;
}

async function start() {
  if (!NativeBridge.init()) {
    fail('Music Maker could not connect to its engine. Please update Android System WebView and restart the app.');
    return;
  }
  listenToNative();
  try {
    const [info, settings, model] = await Promise.all([Native.getAppInfo(), Native.getSettings(), Native.getModelStatus()]);
    State.set('appInfo', info);
    State.set('systemDark', !!info.systemDark);
    State.set('settings', settings);
    State.set('model', model);
    applyTheme();
  } catch (e) {
    fail(e.message);
    return;
  }
  registerScreens();
  Nav.init();
  await Nav.tab('home');
  document.getElementById('boot').classList.add('done');
}

window.addEventListener('error', (e) => console.error('Uncaught', e.error || e.message));
window.addEventListener('unhandledrejection', (e) => console.error('Unhandled', e.reason));
start();
