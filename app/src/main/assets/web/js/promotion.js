// Promotion: platform-aware captions, hashtags, teasers, bios and video ideas.

import { h, iconButton, button, screenShell, card, cardTitle, field, input, textarea, select, segmented, chipGroup,
  toast, errorToast, autoGrow } from './ui.js';
import { Native } from './native.js';
import * as Nav from './navigation.js';
import { PLATFORMS, PROMO_TYPES, LANGUAGES, TONES, GENRES } from './data.js';
import { runAi, generatingOverlay } from './components/model-status.js';
import { pickSong } from './components/song-picker.js';
import { defaults } from './projects.js';

export function promotionScreen({ songId } = {}) {
  const d = defaults();
  const req = { platform: 'tiktok', type: 'caption', language: d.language, tone: '' };
  const manual = { title: '', artist: '', genre: d.genre, description: '' };
  let songs = [];
  let song = null;
  let result = null;
  let busy = false;

  const shell = screenShell({ title: 'Promotion', back: () => Nav.back() });

  const songSel = select([{ id: '', label: 'No song — describe it below' }], '', (v) => selectSong(v));
  const manualBox = h('div', { class: 'form-grid' },
    field('Title', input({ placeholder: 'Song or release title', onInput: (v) => { manual.title = v; } })),
    h('div', { class: 'grid-2' },
      field('Artist', input({ placeholder: 'Artist name', value: '', onInput: (v) => { manual.artist = v; } })),
      field('Genre', select(GENRES, manual.genre, (v) => { manual.genre = v; }))),
    field('About', textarea({ placeholder: 'What is it about? Why should people listen?', rows: 2, maxlength: 600, onInput: (v) => { manual.description = v; } })));
  const songInfo = h('div', { class: 'small muted' });

  const platforms = chipGroup(PLATFORMS, req.platform, (v) => { req.platform = v; }, { scroll: true });
  const types = chipGroup(PROMO_TYPES, req.type, (v) => { req.type = v; });
  const lang = segmented(LANGUAGES, req.language, (v) => { req.language = v; });
  const tone = select([{ id: '', label: 'Any tone' }, ...TONES.map((t) => ({ id: t, label: t }))], '', (v) => { req.tone = v; });
  const genBtn = button('Generate', { icon: 'spark', cls: 'primary block', onClick: generate });

  const resultText = textarea({ rows: 4, maxlength: 5000, onInput: (v) => { if (result) result.text = v; } });
  const tagsEl = h('div', { class: 'hashtags' });
  const resultCard = card([cardTitle('Result'), resultText, tagsEl], 'editor-card');
  resultCard.hidden = true;
  const actions = h('div', { class: 'action-strip' });
  const savedList = h('div');

  shell.content.append(
    card([cardTitle('What are you promoting?'), field('Song', songSel), h('div', { style: { height: '12px' } }), songInfo, manualBox]),
    card([cardTitle('Platform'), platforms, h('div', { style: { height: '16px' } }), cardTitle('Content'), types]),
    card([h('div', { class: 'grid-2' }, field('Language', lang), field('Tone', tone))]),
    h('div', { class: 'section' }, genBtn),
    h('div', { class: 'section' }, actions, h('div', { style: { height: '8px' } }), resultCard),
    savedList);

  async function loadSongs() {
    try {
      songs = await Native.getSongs();
      songSel.replaceChildren(h('option', { value: '', text: 'No song — describe it below' }), ...songs.map((s) => h('option', { value: s.id, text: s.title })));
      if (songId) { songSel.value = songId; await selectSong(songId); }
    } catch (e) { errorToast(e); }
  }

  async function selectSong(id) {
    if (!id) { song = null; manualBox.hidden = false; songInfo.textContent = ''; renderSaved(); return; }
    try {
      song = await Native.getSong(id);
      manualBox.hidden = true;
      songInfo.textContent = [song.artist, song.genre, song.mood, song.bpm ? `${song.bpm} BPM` : ''].filter(Boolean).join(' · ') || 'Song selected';
      if (song.language) { req.language = song.language; lang.setValue(song.language); }
      renderSaved();
    } catch (e) { errorToast(e); }
  }

  function songPayload() {
    if (song) {
      return {
        title: song.title, artist: song.artist, genre: song.genre, mood: song.mood,
        lyrics: (song.lyrics || '').slice(0, 900), concept: song.idea ? song.idea.concept : '',
      };
    }
    return { ...manual };
  }

  function renderResult() {
    if (!result) return;
    resultCard.hidden = false;
    resultText.value = result.text;
    autoGrow(resultText);
    tagsEl.replaceChildren(...(result.hashtags || []).map((t) => h('span', { text: t })));
    actions.replaceChildren(
      button('Regenerate', { icon: 'refresh', cls: 'sm', onClick: generate }),
      button('Copy', { icon: 'copy', cls: 'sm', onClick: copy }),
      button('Share', { icon: 'share', cls: 'sm', onClick: share }),
      button(song ? 'Save to song' : 'Save to…', { icon: 'save', cls: 'primary sm', onClick: save }));
  }

  function fullText() {
    if (!result) return '';
    const tags = (result.hashtags || []).join(' ');
    return [resultText.value.trim(), tags].filter(Boolean).join('\n\n');
  }

  async function generate() {
    if (busy) return;
    busy = true;
    genBtn.disabled = true;
    resultCard.hidden = false;
    const ov = generatingOverlay('Writing promo copy…');
    resultCard.append(ov.el);
    try {
      const r = await runAi(() => Native.generatePromotion({ ...req, song: songPayload() }));
      result = { ...r, platform: req.platform, type: req.type };
    } catch (e) {
      errorToast(e);
      if (!result) resultCard.hidden = true;
    } finally {
      ov.stop();
      busy = false;
      genBtn.disabled = false;
      renderResult();
    }
  }

  async function copy() {
    try { await Native.copyText(fullText()); toast('Copied', { type: 'ok' }); } catch (e) { errorToast(e); }
  }

  async function share() {
    try { await Native.shareText(fullText(), song ? song.title : manual.title); } catch (e) { errorToast(e); }
  }

  async function save() {
    let id = song ? song.id : await pickSong({ title: 'Save promotion to…', allowNew: false });
    if (!id) return;
    try {
      const updated = await Native.addPromotion(id, { platform: result.platform, type: result.type, text: resultText.value.trim(), hashtags: result.hashtags || [] });
      if (song && song.id === updated.id) song = updated;
      toast(`Saved to “${updated.title}”`, { type: 'ok' });
      renderSaved();
    } catch (e) { errorToast(e); }
  }

  function renderSaved() {
    const items = (song && song.promotion) || [];
    if (!items.length) { savedList.replaceChildren(); return; }
    savedList.replaceChildren(h('div', { class: 'section' },
      h('div', { class: 'section-head' }, h('h2', { text: `Saved for “${song.title}”` })),
      ...items.slice(0, 5).map((p) => promoItem(p))));
  }

  loadSongs();
  return { el: shell.el };
}

/** Saved promotion card, reused by the Song Studio. */
export function promoItem(p, { onDelete } = {}) {
  const platform = (PLATFORMS.find((x) => x.id === p.platform) || {}).label || p.platform || 'General';
  const type = (PROMO_TYPES.find((x) => x.id === p.type) || {}).label || p.type || 'Post';
  const text = [p.text, (p.hashtags || []).join(' ')].filter(Boolean).join('\n\n');
  return h('div', { class: 'card promo-item' },
    h('div', { class: 'p-head' }, h('span', { class: 'tag accent', text: platform }), h('span', { class: 'tag', text: type }), h('span', { class: 'grow' }),
      iconButton('copy', 'Copy', () => Native.copyText(text).then(() => toast('Copied', { type: 'ok' })).catch(errorToast)),
      iconButton('share', 'Share', () => Native.shareText(text).catch(errorToast)),
      onDelete ? iconButton('trash', 'Delete', onDelete) : null),
    h('div', { class: 'p-text', text: p.text }),
    (p.hashtags || []).length ? h('div', { class: 'hashtags' }, p.hashtags.map((t) => h('span', { text: t }))) : null);
}
