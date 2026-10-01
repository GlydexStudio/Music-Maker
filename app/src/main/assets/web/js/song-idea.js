// Song Idea: generate a complete concept and turn it into a song project.

import { h, button, screenShell, card, cardTitle, field, textarea, select, segmented, toast, errorToast } from './ui.js';
import { Native } from './native.js';
import * as Nav from './navigation.js';
import { GENRES, MOODS, LANGUAGES } from './data.js';
import { runAi, generatingOverlay } from './components/model-status.js';
import { createSong, defaults } from './projects.js';

const ANY = { id: '', label: 'Let AI choose' };

export function songIdeaScreen({ songId } = {}) {
  const d = defaults();
  const req = { spark: '', genre: '', mood: '', language: d.language, bpm: 0 };
  let idea = null;
  let busy = false;
  let song = null;
  let savedSongId = null;

  const shell = screenShell({ title: 'Song Idea', back: () => Nav.back() });

  const spark = textarea({ placeholder: 'Optional: a word, a memory, a line, a vibe…', rows: 2, maxlength: 1000, onInput: (v) => { req.spark = v; savedSongId = null; } });
  const genre = select([ANY, ...GENRES.map((g) => ({ id: g, label: g }))], '', (v) => { req.genre = v; });
  const mood = select([ANY, ...MOODS.map((m) => ({ id: m, label: m }))], '', (v) => { req.mood = v; });
  const lang = segmented(LANGUAGES, req.language, (v) => { req.language = v; });
  const tempo = select([{ id: '0', label: 'Let AI choose' }, ...[70, 80, 90, 100, 110, 120, 128, 140, 150, 160].map((b) => ({ id: String(b), label: `${b} BPM` }))], '0', (v) => { req.bpm = Number(v); });

  const genBtn = button('Generate idea', { icon: 'spark', cls: 'primary block', onClick: generate });
  const resultCard = card([], 'editor-card');
  resultCard.hidden = true;
  const actions = h('div', { class: 'action-strip' });

  shell.content.append(
    card([cardTitle('Starting point'), h('div', { class: 'form-grid' },
      field('Spark', spark),
      h('div', { class: 'grid-2' }, field('Genre', genre), field('Mood', mood)),
      h('div', { class: 'grid-2' }, field('Tempo', tempo), field('Language', lang)))]),
    h('div', { class: 'section' }, genBtn),
    h('div', { class: 'section' }, actions, h('div', { style: { height: '8px' } }), resultCard));

  function block(title, content) {
    if (!content || (Array.isArray(content) && !content.length)) return null;
    return h('div', { class: 'result-block' }, h('h4', { text: title }),
      Array.isArray(content) ? h('ul', {}, content.map((c) => h('li', { text: c }))) : h('p', { text: content }));
  }

  function renderIdea() {
    if (!idea) return;
    resultCard.hidden = false;
    const tags = [idea.genre, idea.mood, idea.bpm ? `${idea.bpm} BPM` : null, idea.key ? `Key ${idea.key}` : null].filter(Boolean);
    resultCard.replaceChildren(
      h('div', { class: 'result-title', text: idea.title }),
      h('div', { class: 'chips', style: { 'margin-bottom': '16px' } }, tags.map((t) => h('span', { class: 'tag accent', text: t }))),
      block('Concept', idea.concept),
      block('Story', idea.story),
      block('Hook idea', idea.hook),
      block('Verse ideas', idea.verseIdeas),
      block('Beat direction', idea.beatDirection));
    actions.replaceChildren(
      button('Regenerate', { icon: 'refresh', cls: 'sm', onClick: generate }),
      song ? button(`Apply to “${song.title}”`, { icon: 'check', cls: 'primary sm', onClick: applyToSong })
        : button('Use as Song', { icon: 'note', cls: 'primary sm', onClick: useAsSong }),
      song ? null : button('Save', { icon: 'save', cls: 'sm', onClick: save }),
      button('Copy', { icon: 'copy', cls: 'sm', onClick: copy }));
  }

  async function generate() {
    if (busy) return;
    busy = true;
    genBtn.disabled = true;
    resultCard.hidden = false;
    if (!idea) resultCard.replaceChildren(h('div', { style: { height: '220px' } }));
    const ov = generatingOverlay('Dreaming up a song…');
    resultCard.append(ov.el);
    actions.querySelectorAll('button').forEach((b) => { b.disabled = true; });
    try {
      idea = await runAi(() => Native.generateSongIdea(req));
      savedSongId = null;
    } catch (e) {
      errorToast(e);
      if (!idea) resultCard.hidden = true;
    } finally {
      ov.stop();
      busy = false;
      genBtn.disabled = false;
      renderIdea();
    }
  }

  function projectFields() {
    return {
      title: idea.title,
      genre: idea.genre || req.genre || d.genre,
      mood: idea.mood || req.mood,
      language: req.language,
      bpm: idea.bpm || req.bpm || d.bpm,
      key: idea.key,
      idea: { title: idea.title, concept: idea.concept, story: idea.story, hook: idea.hook, verseIdeas: idea.verseIdeas, beatDirection: idea.beatDirection },
    };
  }

  async function save() {
    if (savedSongId) { toast('Already saved to Songs'); return; }
    try {
      const s = await createSong(projectFields());
      savedSongId = s.id;
      toast('Idea saved as a draft song', { type: 'ok', action: 'Open', onAction: () => Nav.push('studio', { id: s.id }) });
    } catch (e) { errorToast(e); }
  }

  async function useAsSong() {
    try {
      const s = savedSongId ? { id: savedSongId } : await createSong(projectFields());
      Nav.replace('studio', { id: s.id });
    } catch (e) { errorToast(e); }
  }

  async function applyToSong() {
    try {
      const f = projectFields();
      const keepTitle = song.title && song.title !== 'Untitled song';
      song = await Native.saveSong({ ...song, ...f, title: keepTitle ? song.title : f.title });
      toast('Idea applied to the song', { type: 'ok' });
      Nav.back();
    } catch (e) { errorToast(e); }
  }

  async function copy() {
    const text = [idea.title, '', idea.concept, '', idea.story, '', `Hook: ${idea.hook}`, '', ...(idea.verseIdeas || []).map((v) => `• ${v}`), '', `Beat: ${idea.beatDirection}`]
      .filter((x) => x !== undefined).join('\n');
    try { await Native.copyText(text); toast('Idea copied', { type: 'ok' }); } catch (e) { errorToast(e); }
  }

  if (songId) {
    Native.getSong(songId).then((s) => {
      song = s;
      shell.titleEl.textContent = `Idea · ${s.title}`;
      req.language = s.language || req.language; lang.setValue(req.language);
      if (GENRES.includes(s.genre)) { req.genre = s.genre; genre.value = s.genre; }
      if (MOODS.includes(s.mood)) { req.mood = s.mood; mood.value = s.mood; }
      if (s.title && s.title !== 'Untitled song') { req.spark = `Working title: ${s.title}`; spark.value = req.spark; }
    }).catch(errorToast);
  }

  return { el: shell.el };
}
