// Settings: appearance, AI model, music defaults, data and about.

import { h, icon, button, screenShell, pageHead, card, cardTitle, field, input, select, segmented, toggle, confirm,
  toast, errorToast, formatBytes } from './ui.js';
import { Native } from './native.js';
import * as State from './state.js';
import { GENRES, LANGUAGES, ACCENTS } from './data.js';
import { modelCard, refreshModel } from './components/model-status.js';
import { applyTheme } from './theme.js';

export function settingsScreen() {
  const shell = screenShell({ root: true });
  const storageEl = h('div', { class: 'list' });
  const aboutEl = h('div', { class: 'list' });
  const formWrap = h('div');

  const save = async (patch) => {
    try {
      const s = await Native.saveSettings(patch);
      State.set('settings', s);
      applyTheme();
      return s;
    } catch (e) { errorToast(e); return null; }
  };

  const row = (label, sub, control) => h('div', { class: 'list-row' }, h('div', { class: 'label' }, h('b', { text: label }), sub ? h('small', { text: sub }) : null), control);

  function render() {
    const s = State.get('settings');
    if (!s) return;
    const ai = s.ai;

    const accents = h('div', { class: 'chips' }, ACCENTS.map((a) => {
      const sw = h('button', { class: `chip${s.accent === a.id ? ' active' : ''}`, 'aria-label': a.label, title: a.label, onclick: () => save({ accent: a.id }).then(render) },
        h('span', { class: 'dot', style: { background: a.id, width: '14px', height: '14px' } }));
      return sw;
    }));

    const threads = select([{ id: '0', label: 'Auto' }, ...[2, 3, 4, 6, 8].map((n) => ({ id: String(n), label: `${n} threads` }))], String(ai.threads), (v) => save({ ai: { threads: Number(v) } }));
    const ctx = select([2048, 4096, 8192].map((n) => ({ id: String(n), label: `${n} tokens` })), String(ai.contextSize), (v) => save({ ai: { contextSize: Number(v) } }));
    const creativityOut = h('span', { class: 'tiny mono muted', text: ai.creativity.toFixed(2) });
    const creativity = h('input', { class: 'range', type: 'range', min: '0.2', max: '1.3', step: '0.05', 'aria-label': 'Creativity' });
    creativity.value = String(ai.creativity);
    creativity.addEventListener('input', () => { creativityOut.textContent = Number(creativity.value).toFixed(2); });
    creativity.addEventListener('change', () => save({ ai: { creativity: Number(creativity.value) } }));
    const artist = input({ value: s.artistName, placeholder: 'Used in greetings and new songs', maxlength: 60 });
    artist.addEventListener('change', () => save({ artistName: artist.value.trim() }));

    formWrap.replaceChildren(
      card([cardTitle('Appearance'),
        field('Theme', segmented([{ id: 'dark', label: 'Dark' }, { id: 'light', label: 'Light' }, { id: 'system', label: 'System' }], s.theme, (v) => save({ theme: v }))),
        h('div', { style: { height: '14px' } }),
        field('Accent color', accents)]),

      h('div', { class: 'section' }, h('div', { class: 'section-head' }, h('h2', { text: 'AI' })), modelCard()),
      card([cardTitle('AI settings'),
        h('div', { class: 'field' }, h('span', { text: 'Creativity' }), h('div', { class: 'row' }, h('span', { class: 'tiny muted', text: 'Focused' }), creativity, h('span', { class: 'tiny muted', text: 'Wild' }), creativityOut)),
        h('div', { style: { height: '14px' } }),
        field('Response length', segmented([{ id: 'short', label: 'Short' }, { id: 'medium', label: 'Medium' }, { id: 'long', label: 'Long' }], ai.length, (v) => save({ ai: { length: v } }))),
        h('div', { style: { height: '14px' } }),
        h('div', { class: 'grid-2' }, field('CPU threads', threads), field('Context window', ctx)),
        h('div', { class: 'list', style: { 'margin-top': '8px' } },
          row('Free memory in background', 'Unload the model when you leave the app', toggle(ai.unloadInBackground, (v) => save({ ai: { unloadInBackground: v } }), 'Unload in background'))),
        h('p', { class: 'tiny muted', style: { margin: '10px 0 0' }, text: 'Creativity, threads and context apply the next time the model loads. Larger context uses more memory.' })]),

      h('div', { class: 'section' }, card([cardTitle('Music defaults'), h('div', { class: 'form-grid' },
        field('Artist name', artist),
        field('Default language', segmented(LANGUAGES.map((l) => ({ id: l.id, label: l.label })), s.language, (v) => save({ language: v }))),
        h('div', { class: 'grid-2' },
          field('Default genre', select(GENRES, s.genre, (v) => save({ genre: v }))),
          field('Default BPM', select([70, 80, 90, 100, 110, 120, 124, 128, 135, 140, 150, 160, 170].map((b) => ({ id: String(b), label: `${b}` })), String(s.bpm), (v) => save({ bpm: Number(v) })))))])),

      h('div', { class: 'section' }, card([cardTitle('Data'), storageEl, h('div', { class: 'stack-v', style: { 'margin-top': '12px' } },
        button('Delete all projects', { icon: 'trash', cls: 'danger block', onClick: deleteAll }),
        button('Reset application data', { icon: 'refresh', cls: 'block', onClick: resetAll }),
        button('Delete AI model file', { icon: 'chip', cls: 'ghost block', onClick: deleteModel }))])),

      h('div', { class: 'section' }, card([cardTitle('About'), aboutEl,
        h('div', { class: 'notice', style: { 'margin-top': '12px' } }, icon('shield', 'sm'),
          h('span', { text: 'Privacy: your songs, lyrics, beats and settings are stored only on this device. The AI runs fully on your phone — nothing you write is sent anywhere. The internet is used only if you choose to download the AI model.' }))])));
  }

  async function loadStorage() {
    try {
      const st = await Native.getStorageInfo();
      storageEl.replaceChildren(
        row('Song projects', `${st.projects} project${st.projects === 1 ? '' : 's'}`, h('span', { class: 'value', text: formatBytes(st.projectBytes + st.coverBytes) })),
        row('AI model', st.modelBytes ? 'Stored in app storage' : 'Not installed', h('span', { class: 'value', text: formatBytes(st.modelBytes) })),
        row('Free space', 'Available on this device', h('span', { class: 'value', text: formatBytes(st.freeBytes) })));
    } catch (e) { errorToast(e); }
  }

  function renderAbout() {
    const info = State.get('appInfo') || {};
    aboutEl.replaceChildren(
      row('Music Maker', 'A music studio with a local AI companion', h('span', { class: 'value', text: `v${info.version || ''}` })),
      row('Developer', 'Glydex Studio', h('span')),
      row('Device', `${info.device || ''} · Android ${info.android || ''}`, h('span', { class: 'value', text: info.totalRamBytes ? `${formatBytes(info.totalRamBytes)} RAM` : '' })));
  }

  async function deleteAll() {
    const ok = await confirm({ title: 'Delete all projects?', message: 'Every song, lyric, beat, cover and promotion on this device will be permanently deleted. This cannot be undone.', okLabel: 'Delete everything', danger: true });
    if (!ok) return;
    try { await Native.deleteAllProjects(); toast('All projects deleted'); loadStorage(); } catch (e) { errorToast(e); }
  }

  async function resetAll() {
    const ok = await confirm({ title: 'Reset application data?', message: 'All projects are deleted and settings return to defaults. The AI model file is kept so you do not need to download it again.', okLabel: 'Reset', danger: true });
    if (!ok) return;
    try {
      const s = await Native.resetAppData();
      State.set('settings', s);
      applyTheme();
      render();
      loadStorage();
      toast('Application data reset');
    } catch (e) { errorToast(e); }
  }

  async function deleteModel() {
    const m = State.get('model');
    if (!m || !m.installed) { toast('The AI model is not installed.'); return; }
    const ok = await confirm({ title: 'Delete AI model?', message: `This frees ${formatBytes(m.sizeBytes)}. AI features stop working until you install the model again.`, okLabel: 'Delete model', danger: true });
    if (!ok) return;
    try { await Native.deleteModel(); await refreshModel(); loadStorage(); toast('Model deleted'); } catch (e) { errorToast(e); }
  }

  shell.content.append(pageHead('Settings', 'Make the studio yours.'), formWrap);
  let rendered = false;

  return {
    el: shell.el,
    onShow() {
      if (!rendered) { render(); rendered = true; }
      renderAbout();
      loadStorage();
      refreshModel();
    },
  };
}
