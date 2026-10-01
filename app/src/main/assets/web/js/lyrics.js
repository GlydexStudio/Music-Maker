// Lyrics Studio: brief → generate → edit, with AI rewrite tools, undo and song integration.

import { h, icon, button, screenShell, card, cardTitle, field, input, textarea, select, segmented, chipGroup,
  stepper, sheet, toast, errorToast, menu, autoGrow } from './ui.js';
import { Native } from './native.js';
import * as Nav from './navigation.js';
import { GENRES, MOODS, LANGUAGES, LYRIC_MODES } from './data.js';
import { runAi, generatingOverlay } from './components/model-status.js';
import { pickSong } from './components/song-picker.js';
import { createSong, appendLyrics, defaults } from './projects.js';

export function lyricsScreen({ songId } = {}) {
  const d = defaults();
  const brief = { genre: d.genre, mood: 'Confident', language: d.language, topic: '', style: '', bpm: d.bpm, explicit: false, title: '' };
  let mode = 'full';
  let song = null;
  let busy = false;
  let savedValue = '';
  const history = [];
  let lastRequest = null;
  let lastApplied = null;

  const shell = screenShell({ title: 'Lyrics', back: () => Nav.back() });

  // ---------------------------------------------------------------- brief
  const topic = textarea({ placeholder: 'What is the song about? A moment, a person, a feeling…', rows: 2, maxlength: 600, onInput: (v) => { brief.topic = v; } });
  const genreSel = select(GENRES, brief.genre, (v) => { brief.genre = v; });
  const moodSel = select(MOODS, brief.mood, (v) => { brief.mood = v; });
  const style = input({ placeholder: 'e.g. storytelling, melodic, double-time flow', maxlength: 300, onInput: (v) => { brief.style = v; } });
  const lang = segmented(LANGUAGES, brief.language, (v) => { brief.language = v; });
  const content = segmented([{ id: 'clean', label: 'Clean' }, { id: 'explicit', label: 'Explicit' }], 'clean', (v) => { brief.explicit = v === 'explicit'; });
  const bpm = stepper(brief.bpm, { min: 60, max: 200, onChange: (v) => { brief.bpm = v; }, format: (v) => `${v}` });

  const briefBody = h('div', { class: 'form-grid' },
    field('Topic', topic),
    h('div', { class: 'grid-2' }, field('Genre', genreSel), field('Mood', moodSel)),
    field('Style', style),
    field('Language', lang),
    h('div', { class: 'grid-2' }, field('BPM', bpm), field('Content', content)));
  let briefOpen = true;
  const briefToggle = h('button', { class: 'icon-btn', 'aria-label': 'Toggle brief', onclick: () => { briefOpen = !briefOpen; briefBody.hidden = !briefOpen; briefToggle.style.setProperty('transform', briefOpen ? 'none' : 'rotate(-90deg)'); } }, icon('chevron-down'));

  const modes = chipGroup(LYRIC_MODES, mode, (v) => { mode = v; }, { scroll: true });
  const genBtn = button('Generate', { icon: 'spark', cls: 'primary block', onClick: () => generate('generate') });

  // ---------------------------------------------------------------- editor
  const titleInput = h('input', { class: 'title-input', placeholder: 'Song title', maxlength: '120', 'aria-label': 'Song title' });
  const editor = textarea({ cls: 'editor', rows: 12, maxlength: 20000, placeholder: 'Your lyrics appear here. You can type, edit and select any part to rewrite it.', onInput: () => updateMeta() });
  editor.setAttribute('aria-label', 'Lyrics editor');
  const meta = h('div', { class: 'editor-meta' });
  const undoBtn = button('Undo', { icon: 'undo', cls: 'sm', onClick: undo });
  const editorCard = card([titleInput, editor, meta], 'editor-card');

  const act = (label, ic, fn) => button(label, { icon: ic, cls: 'sm', onClick: fn });
  const tools = h('div', { class: 'action-strip' },
    undoBtn,
    act('Regenerate', 'refresh', regenerate),
    act('Rewrite', 'edit', () => transform('rewrite')),
    act('Continue', 'chevron', () => transform('continue')),
    act('Expand', 'plus', () => transform('expand')),
    act('Shorten', 'minus', () => transform('shorten')),
    act('Improve Rhymes', 'spark', () => transform('rhymes')),
    act('Punchlines', 'bulb', punchlines),
    act('Copy', 'copy', copy),
    act('Save', 'save', save),
    act('Add to Song', 'note', addToSong));

  shell.content.append(
    card([cardTitle('Brief', briefToggle), briefBody]),
    h('div', { class: 'section' }, h('div', { class: 'section-head' }, h('h2', { text: 'Write' })), modes, h('div', { style: { height: '12px' } }), genBtn),
    h('div', { class: 'section' }, tools, h('div', { style: { height: '8px' } }), editorCard));

  function updateMeta() {
    const text = editor.value;
    const lines = text.split('\n').filter((l) => l.trim() && !/^\[.*\]$/.test(l.trim())).length;
    const words = (text.match(/\S+/g) || []).length;
    const sel = editor.selectionEnd - editor.selectionStart;
    meta.replaceChildren(h('span', { text: sel > 0 ? `${sel} characters selected — tools apply to the selection` : `${lines} lines · ${words} words` }),
      h('span', { text: song ? `Linked to “${song.title}”` : '' }));
    undoBtn.disabled = !history.length;
  }
  ['select', 'keyup', 'pointerup'].forEach((ev) => editor.addEventListener(ev, updateMeta));

  function setBusy(b) {
    busy = b;
    [genBtn, ...tools.querySelectorAll('button')].forEach((x) => { x.disabled = b; });
    if (!b) updateMeta();
  }

  function snapshot() {
    history.push({ text: editor.value, title: titleInput.value });
    if (history.length > 30) history.shift();
  }

  function undo() {
    const prev = history.pop();
    if (!prev) return;
    editor.value = prev.text;
    titleInput.value = prev.title;
    autoGrow(editor);
    updateMeta();
  }

  function request(action, extra = {}) {
    return { ...brief, title: titleInput.value.trim(), mode, action, ...extra };
  }

  async function run(req, overlayText) {
    if (busy) return null;
    setBusy(true);
    const ov = generatingOverlay(overlayText);
    editorCard.append(ov.el);
    try {
      return await runAi(() => Native.generateLyrics(req));
    } catch (e) {
      errorToast(e);
      return null;
    } finally {
      ov.stop();
      setBusy(false);
    }
  }

  /** Inserts AI text: full songs replace, sections are inserted at the cursor (or appended). */
  function applyGenerated(text, isFull) {
    snapshot();
    if (isFull || !editor.value.trim()) {
      editor.value = text;
    } else {
      const pos = editor.selectionEnd || editor.value.length;
      const before = editor.value.slice(0, pos).replace(/\s*$/, '');
      const after = editor.value.slice(pos).replace(/^\s*/, '');
      editor.value = [before, text, after].filter(Boolean).join('\n\n');
    }
    autoGrow(editor);
  }

  async function generate(action) {
    const req = request(action);
    lastRequest = { kind: 'generate', req };
    const res = await run(req, mode === 'full' ? 'Writing your song…' : `Writing ${mode}…`);
    if (!res) return;
    applyGenerated(res.lyrics, mode === 'full');
    lastApplied = editor.value;
    if (res.title && !titleInput.value.trim()) titleInput.value = res.title;
    updateMeta();
  }

  async function regenerate() {
    // Replace the previous AI version only if the user has not edited it since.
    if (lastRequest && lastRequest.kind === 'generate' && history.length && editor.value === lastApplied) {
      undo();
    }
    const base = lastRequest && lastRequest.kind === 'generate' ? lastRequest.req : request('generate');
    const req = { ...base, action: 'regenerate' };
    lastRequest = { kind: 'generate', req: base };
    const res = await run(req, 'Trying a new version…');
    if (!res) return;
    applyGenerated(res.lyrics, req.mode === 'full');
    lastApplied = editor.value;
    if (res.title && !titleInput.value.trim()) titleInput.value = res.title;
    updateMeta();
  }

  async function transform(action) {
    const full = editor.value;
    if (!full.trim()) { toast('Write or generate some lyrics first.'); return; }
    const s = editor.selectionStart;
    const e = editor.selectionEnd;
    const hasSel = e - s > 3 && action !== 'continue';
    const target = hasSel ? full.slice(s, e) : full;
    lastRequest = { kind: 'transform' };
    const label = { rewrite: 'Rewriting…', continue: 'Continuing…', expand: 'Expanding…', shorten: 'Shortening…', rhymes: 'Sharpening rhymes…' }[action];
    const res = await run(request(action, { text: target }), label);
    if (!res) return;
    snapshot();
    if (action === 'continue') {
      editor.value = `${full.replace(/\s*$/, '')}\n\n${res.lyrics}`;
    } else if (hasSel) {
      editor.value = full.slice(0, s) + res.lyrics + full.slice(e);
    } else {
      editor.value = res.lyrics;
    }
    autoGrow(editor);
    updateMeta();
  }

  async function punchlines() {
    const res = await run(request('punchlines', { text: editor.value.slice(-1500) }), 'Finding punchlines…');
    if (!res) return;
    const insert = (line) => {
      snapshot();
      const pos = editor.selectionEnd || editor.value.length;
      editor.value = `${editor.value.slice(0, pos).replace(/\s*$/, '')}\n${line}\n${editor.value.slice(pos).replace(/^\s*/, '')}`.trim();
      autoGrow(editor);
      updateMeta();
      toast('Inserted');
    };
    const s = sheet({
      title: 'Punchlines',
      content: h('div', { class: 'list' }, res.punchlines.map((line) => h('button', { class: 'menu-item', onclick: () => insert(line) },
        icon('plus'), h('div', { class: 'grow', text: line })))),
      footer: [button('Insert all', { cls: 'primary', onClick: () => { insert(res.punchlines.join('\n')); s.close(); } })],
    });
  }

  async function copy() {
    if (!editor.value.trim()) return;
    try { await Native.copyText(editor.value); toast('Lyrics copied', { type: 'ok' }); } catch (e) { errorToast(e); }
  }

  async function save() {
    const text = editor.value.trim();
    if (!text) { toast('Nothing to save yet.'); return; }
    try {
      if (song) {
        song = await Native.saveSong({ ...song, lyrics: text, title: titleInput.value.trim() || song.title, genre: brief.genre, mood: brief.mood, language: brief.language, bpm: brief.bpm, status: song.status === 'draft' ? 'writing' : song.status });
      } else {
        song = await createSong({ title: titleInput.value.trim() || 'Untitled song', lyrics: text, genre: brief.genre, mood: brief.mood, language: brief.language, bpm: brief.bpm, status: 'writing' });
      }
      savedValue = editor.value;
      updateMeta();
      toast('Saved to Songs', { type: 'ok', action: 'Open', onAction: () => Nav.push('studio', { id: song.id }) });
    } catch (e) { errorToast(e); }
  }

  async function addToSong() {
    const text = editor.value.trim();
    if (!text) { toast('Nothing to add yet.'); return; }
    const target = await pickSong({ title: 'Add lyrics to…' });
    if (!target) return;
    try {
      const saved = target === 'new'
        ? await createSong({ title: titleInput.value.trim() || 'Untitled song', lyrics: text, genre: brief.genre, mood: brief.mood, language: brief.language, bpm: brief.bpm, status: 'writing' })
        : await appendLyrics(target, text, titleInput.value.trim());
      savedValue = editor.value;
      toast(`Added to “${saved.title}”`, { type: 'ok', action: 'Open', onAction: () => Nav.push('studio', { id: saved.id }) });
    } catch (e) { errorToast(e); }
  }

  async function loadSong() {
    if (!songId) { updateMeta(); return; }
    try {
      song = await Native.getSong(songId);
      shell.titleEl.textContent = `Lyrics · ${song.title}`;
      titleInput.value = song.title === 'Untitled song' ? '' : song.title;
      editor.value = song.lyrics || '';
      savedValue = editor.value;
      if (song.genre) { brief.genre = GENRES.includes(song.genre) ? song.genre : brief.genre; genreSel.value = brief.genre; }
      if (song.mood && MOODS.includes(song.mood)) { brief.mood = song.mood; moodSel.value = song.mood; }
      brief.language = song.language || brief.language; lang.setValue(brief.language);
      brief.bpm = song.bpm || brief.bpm; bpm.setValue(brief.bpm);
      if (song.idea && song.idea.concept) { brief.topic = song.idea.concept; topic.value = song.idea.concept; autoGrow(topic); }
      autoGrow(editor);
      updateMeta();
    } catch (e) { errorToast(e); }
  }
  loadSong();

  return {
    el: shell.el,
    async canLeave() {
      if (busy) { toast('The AI is still writing. It will finish in the background.'); }
      if (editor.value === savedValue || !editor.value.trim()) return true;
      const choice = await menu({ title: 'Unsaved lyrics', items: [
        { id: 'save', label: song ? `Save to “${song.title}”` : 'Save as new song', icon: 'save' },
        { id: 'discard', label: 'Discard changes', icon: 'trash', danger: true },
      ] });
      if (choice === 'save') { await save(); return editor.value === savedValue; }
      return choice === 'discard';
    },
  };
}
