// Song Studio: one project — overview, lyrics, beat and promotion — with save, share and export.

import { h, icon, iconButton, button, screenShell, card, cardTitle, field, input, textarea, select, segmented, stepper,
  sheet, menu, confirm, toast, errorToast, coverEl, statusBadge, relativeTime } from './ui.js';
import { Native } from './native.js';
import * as Nav from './navigation.js';
import { GENRES, MOODS, KEYS, LANGUAGES, STATUSES } from './data.js';
import { createBeatEditor, beatHasNotes } from './components/beat-editor.js';
import { promoItem } from './promotion.js';

const TABS = [
  { id: 'overview', label: 'Overview' },
  { id: 'lyrics', label: 'Lyrics' },
  { id: 'beat', label: 'Beat' },
  { id: 'promotion', label: 'Promo' },
];

export function songStudioScreen({ id }) {
  let song = null;
  let dirty = false;
  let tab = 'overview';
  let beatEditor = null;
  let returning = false;

  const favBtn = iconButton('star', 'Favorite', toggleFavorite);
  const saveBtn = iconButton('save', 'Save', () => save());
  const shell = screenShell({
    title: 'Song',
    back: () => Nav.back(),
    actions: [favBtn, iconButton('share', 'Share', share), iconButton('export', 'Export', exportMenu), saveBtn],
  });

  const head = h('div', { class: 'studio-head' });
  const tabs = segmented(TABS, tab, (v) => { tab = v; renderTab(); });
  const panel = h('div');
  shell.content.append(head, h('div', { class: 'tabs-sticky' }, tabs), panel);

  // ---------------------------------------------------------------- state helpers
  function markDirty() {
    if (!dirty) { dirty = true; renderSaveState(); }
  }

  function renderSaveState() {
    saveBtn.classList.toggle('on', dirty);
    shell.titleEl.replaceChildren(h('span', { text: song ? song.title : 'Song' }), dirty ? h('span', { class: 'dirty-dot', 'aria-label': 'Unsaved changes' }) : null);
  }

  function collect() {
    const out = { ...song };
    if (beatEditor) {
      const b = beatEditor.getBeat();
      out.beat = beatHasNotes(b) || song.beat ? b : song.beat;
      if (out.beat) out.bpm = out.beat.bpm; // the beat tempo is the song tempo
    }
    return out;
  }

  async function save({ quiet = false } = {}) {
    try {
      song = await Native.saveSong(collect());
      dirty = false;
      renderHead();
      renderSaveState();
      if (!quiet) toast('Song saved', { type: 'ok' });
      return true;
    } catch (e) { errorToast(e); return false; }
  }

  /** Saves pending edits before opening an AI tool, so the tool works on current data. */
  async function openTool(screen) {
    if (dirty && !(await save({ quiet: true }))) return;
    returning = true;
    Nav.push(screen, { songId: song.id });
  }

  async function load() {
    try {
      song = await Native.getSong(id);
      dirty = false;
      renderHead();
      renderTab();
      renderSaveState();
    } catch (e) {
      errorToast(e);
      Nav.back({ force: true });
    }
  }

  // ---------------------------------------------------------------- header
  function renderHead() {
    favBtn.classList.toggle('on', !!song.favorite);
    favBtn.querySelector('svg').classList.toggle('filled', !!song.favorite);
    const tags = [song.genre, song.mood, song.bpm ? `${song.bpm} BPM` : null, song.key ? song.key : null].filter(Boolean);
    head.replaceChildren(
      h('button', { class: 'cover-btn', 'aria-label': 'Change cover', onclick: coverMenu }, coverEl(song, 'lg')),
      h('div', { class: 'h-info' },
        h('h1', { text: song.title }),
        h('div', { class: 'artist', text: song.artist || 'No artist set' }),
        h('div', { class: 'tags' },
          h('button', { class: 'status-btn', 'aria-label': 'Change status', onclick: statusMenu }, statusBadge(song.status)),
          tags.map((t) => h('span', { class: 'tag', text: t }))),
        h('div', { style: { 'margin-top': '10px' } }, button('Edit details', { icon: 'edit', cls: 'sm', onClick: editDetails }))));
  }

  async function coverMenu() {
    const choice = await menu({ title: 'Cover', items: [
      { id: 'image', label: 'Choose image', icon: 'image', sub: 'From your gallery' },
      { id: 'gradient', label: 'New generated cover', icon: 'refresh', sub: 'A fresh gradient artwork' },
    ] });
    try {
      if (dirty && choice) await save({ quiet: true });
      if (choice === 'image') song = await Native.pickCoverImage(song.id);
      if (choice === 'gradient') song = await Native.resetCover(song.id);
      if (choice) renderHead();
    } catch (e) { errorToast(e); }
  }

  async function statusMenu() {
    const v = await menu({ title: 'Status', items: STATUSES.map((s) => ({ id: s.id, label: s.label, selected: s.id === song.status })) });
    if (v && v !== song.status) { song.status = v; markDirty(); renderHead(); if (tab === 'overview') renderTab(); }
  }

  async function toggleFavorite() {
    try {
      const updated = await Native.setFavorite(song.id, !song.favorite);
      song.favorite = updated.favorite;
      song.modifiedAt = updated.modifiedAt;
      renderHead();
    } catch (e) { errorToast(e); }
  }

  function editDetails() {
    const draft = { ...song };
    const s = sheet({
      title: 'Song details',
      content: h('div', { class: 'form-grid two' },
        field('Title', input({ value: draft.title, onInput: (v) => { draft.title = v; } })),
        field('Artist', input({ value: draft.artist, placeholder: 'Artist name', onInput: (v) => { draft.artist = v; } })),
        field('Genre', select(withCurrent(GENRES, draft.genre), draft.genre, (v) => { draft.genre = v; })),
        field('Mood', select(withCurrent(MOODS, draft.mood), draft.mood, (v) => { draft.mood = v; })),
        field('Key', select([{ id: '', label: 'Not set' }, ...KEYS.map((k) => ({ id: k, label: k }))], draft.key, (v) => { draft.key = v; })),
        field('BPM', stepper(draft.bpm || 120, { min: 60, max: 200, onChange: (v) => { draft.bpm = v; } })),
        field('Language', segmented(LANGUAGES, draft.language, (v) => { draft.language = v; })),
        field('Status', select(STATUSES, draft.status, (v) => { draft.status = v; }))),
      footer: [
        button('Cancel', { onClick: () => s.close() }),
        button('Apply', { cls: 'primary', onClick: () => {
          if (!draft.title.trim()) { toast('Title cannot be empty.'); return; }
          Object.assign(song, draft, { title: draft.title.trim() });
          if (song.beat) song.beat = { ...song.beat, bpm: song.bpm };
          markDirty(); renderHead(); renderTab(); s.close();
        } }),
      ],
    });
  }

  function withCurrent(list, current) {
    const opts = [{ id: '', label: 'Not set' }, ...list.map((x) => ({ id: x, label: x }))];
    if (current && !list.includes(current)) opts.push({ id: current, label: current });
    return opts;
  }

  // ---------------------------------------------------------------- tabs
  function renderTab() {
    if (beatEditor && tab !== 'beat') { song.beat = collect().beat; beatEditor.destroy(); beatEditor = null; }
    tabs.setValue(tab);
    const view = { overview: overviewTab, lyrics: lyricsTab, beat: beatTab, promotion: promotionTab }[tab]();
    panel.replaceChildren(view);
    view.classList.add('stagger');
  }

  function progressSteps() {
    const steps = [
      { label: 'Idea', done: !!song.idea },
      { label: 'Lyrics', done: !!(song.lyrics && song.lyrics.trim()) },
      { label: 'Beat', done: !!song.beat && beatHasNotes(song.beat) },
      { label: 'Promo', done: (song.promotion || []).length > 0 },
      { label: 'Done', done: song.status === 'finished' || song.status === 'released' },
    ];
    return steps;
  }

  function overviewTab() {
    const steps = progressSteps();
    const done = steps.filter((s) => s.done).length;
    const meta = (label, value) => h('div', { class: 'meta' }, h('small', { text: label }), h('b', { text: value || '—' }));
    const lang = (LANGUAGES.find((l) => l.id === song.language) || {}).label;
    const tool = (ic, label, sub, screen) => h('button', { class: 'menu-item', onclick: () => openTool(screen) },
      icon(ic), h('div', { class: 'grow' }, h('div', { text: label }), h('small', { text: sub })), icon('chevron', 'sm'));
    const idea = song.idea;
    return h('div', {},
      card([cardTitle('Progress', h('span', { class: 'tiny', text: `${done} / ${steps.length}` })),
        h('div', { class: 'progress-steps' }, steps.map((s) => h('div', { class: `ps${s.done ? ' done' : ''}` }, h('i'), h('span', { text: s.label }))))]),
      card([cardTitle('Details'), h('div', { class: 'meta-grid' },
        meta('Genre', song.genre), meta('Mood', song.mood), meta('BPM', song.bpm ? String(song.bpm) : ''), meta('Key', song.key),
        meta('Language', lang), meta('Status', (STATUSES.find((s) => s.id === song.status) || {}).label),
        meta('Created', relativeTime(song.createdAt)), meta('Modified', relativeTime(song.modifiedAt)))]),
      idea ? card([cardTitle('Concept'),
        idea.concept ? h('p', { style: { margin: '0 0 10px' }, text: idea.concept }) : null,
        idea.hook ? h('p', { class: 'muted small', style: { margin: '0 0 6px' }, text: `Hook: ${idea.hook}` }) : null,
        idea.beatDirection ? h('p', { class: 'muted small', style: { margin: 0 }, text: `Beat: ${idea.beatDirection}` }) : null]) : null,
      card([cardTitle('Studio tools'), h('div', { class: 'list' },
        tool('bulb', idea ? 'New song idea' : 'Generate song idea', 'Concept, hook, key and beat direction', 'idea'),
        tool('pen', 'Lyrics Studio', 'Write and refine lyrics with AI', 'lyrics'),
        tool('pads', 'Beat Lab', 'Full-screen sequencer', 'beat'),
        tool('megaphone', 'Promotion', 'Captions, hashtags and more', 'promotion'))]),
      h('div', { class: 'section' }, button('Delete song', { icon: 'trash', cls: 'danger block', onClick: deleteSong })));
  }

  function lyricsTab() {
    const area = textarea({ cls: 'editor', rows: 14, maxlength: 20000, value: song.lyrics || '', placeholder: 'No lyrics yet. Type here or open the Lyrics Studio to write with AI.',
      onInput: (v) => { song.lyrics = v; markDirty(); } });
    area.setAttribute('aria-label', 'Lyrics');
    return h('div', {},
      h('div', { class: 'action-strip', style: { 'margin-bottom': '10px' } },
        button('Write with AI', { icon: 'spark', cls: 'primary sm', onClick: () => openTool('lyrics') }),
        button('Copy', { icon: 'copy', cls: 'sm', onClick: () => (song.lyrics ? Native.copyText(song.lyrics).then(() => toast('Lyrics copied', { type: 'ok' })).catch(errorToast) : null) })),
      card([area]));
  }

  function beatTab() {
    beatEditor = createBeatEditor({ beat: song.beat || { bpm: song.bpm || 120 }, onChange: () => markDirty() });
    return h('div', {},
      h('div', { class: 'action-strip', style: { 'margin-bottom': '10px' } },
        button('Open Beat Lab', { icon: 'pads', cls: 'sm', onClick: () => openTool('beat') }),
        button('Share WAV', { icon: 'share', cls: 'sm', onClick: () => {
          const b = beatEditor.getBeat();
          if (!beatHasNotes(b)) { toast('Add some steps first.'); return; }
          Native.shareBeat(b, song.title, 4).catch(errorToast);
        } })),
      beatEditor.el);
  }

  function promotionTab() {
    const items = song.promotion || [];
    const remove = (pid) => async () => {
      const ok = await confirm({ title: 'Delete this post?', message: 'It will be removed from this song.', okLabel: 'Delete', danger: true });
      if (!ok) return;
      song.promotion = items.filter((p) => p.id !== pid);
      markDirty();
      renderTab();
    };
    return h('div', {},
      h('div', { class: 'action-strip', style: { 'margin-bottom': '10px' } },
        button('Generate promotion', { icon: 'megaphone', cls: 'primary sm', onClick: () => openTool('promotion') })),
      items.length ? h('div', {}, items.map((p) => promoItem(p, { onDelete: remove(p.id) })))
        : card([h('p', { class: 'muted', style: { margin: 0 }, text: 'No promotional content yet. Generate captions, hashtags, teasers and more.' })]));
  }

  // ---------------------------------------------------------------- actions
  async function share() {
    if (dirty && !(await save({ quiet: true }))) return;
    Native.shareSong(song.id).catch(errorToast);
  }

  async function exportMenu() {
    const hasBeat = beatHasNotes(collect().beat);
    const choice = await menu({ title: 'Export', items: [
      { id: 'txt', label: 'Lyrics sheet (.txt)', icon: 'file', sub: 'Title, details and lyrics' },
      { id: 'json', label: 'Project file (.json)', icon: 'database', sub: 'Everything in this project' },
      { id: 'wav', label: 'Beat audio (.wav)', icon: 'wave', sub: hasBeat ? '4 bars, rendered on device' : 'Add a beat first' },
    ] });
    if (!choice) return;
    if (choice === 'wav' && !hasBeat) { toast('This song has no beat yet.'); return; }
    if (dirty && !(await save({ quiet: true }))) return;
    try {
      const r = await Native.exportSong(song.id, choice, 4);
      toast(`Exported ${r.fileName}`, { type: 'ok' });
    } catch (e) { errorToast(e); }
  }

  async function deleteSong() {
    const ok = await confirm({ title: 'Delete song?', message: `"${song.title}" will be permanently deleted from this device.`, okLabel: 'Delete', danger: true });
    if (!ok) return;
    try {
      await Native.deleteSong(song.id);
      dirty = false;
      toast('Song deleted');
      Nav.back({ force: true });
    } catch (e) { errorToast(e); }
  }

  load();

  return {
    el: shell.el,
    onShow() {
      // Coming back from a tool: reload so its saved changes appear.
      if (returning) { returning = false; load(); }
    },
    onHide() { if (beatEditor) beatEditor.stop(); },
    destroy() { if (beatEditor) beatEditor.destroy(); },
    async canLeave() {
      if (!dirty) return true;
      const choice = await menu({ title: 'Unsaved changes', items: [
        { id: 'save', label: 'Save changes', icon: 'save' },
        { id: 'discard', label: 'Discard changes', icon: 'trash', danger: true },
      ] });
      if (choice === 'save') return save({ quiet: true });
      return choice === 'discard';
    },
  };
}
