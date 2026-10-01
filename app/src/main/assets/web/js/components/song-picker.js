// Sheet for choosing a destination song (or creating a new one).

import { h, icon, sheet, coverEl, statusBadge, relativeTime, errorToast } from '../ui.js';
import { Native } from '../native.js';

/** Resolves to a song id, the string 'new', or null when dismissed. */
export async function pickSong({ title = 'Choose a song', allowNew = true } = {}) {
  let songs = [];
  try { songs = await Native.getSongs(); } catch (e) { errorToast(e); return null; }
  return new Promise((resolve) => {
    let result = null;
    const list = h('div', { class: 'list' });
    if (allowNew) {
      list.append(h('button', { class: 'menu-item', onclick: () => { result = 'new'; s.close(); } },
        h('div', { class: 'cover', style: { width: '44px', height: '44px' } }, icon('plus')),
        h('div', { class: 'grow' }, h('div', { text: 'New song project' }), h('small', { text: 'Start a fresh project' }))));
    }
    songs.forEach((song) => {
      const c = coverEl(song);
      c.style.setProperty('width', '44px');
      c.style.setProperty('height', '44px');
      list.append(h('button', { class: 'menu-item', onclick: () => { result = song.id; s.close(); } },
        c, h('div', { class: 'grow' }, h('div', { text: song.title }), h('small', { text: `${song.genre || 'No genre'} · ${relativeTime(song.modifiedAt)}` })),
        statusBadge(song.status)));
    });
    if (!songs.length && !allowNew) list.append(h('p', { class: 'muted', text: 'No songs yet.' }));
    const s = sheet({ title, content: list, onClose: () => resolve(result) });
  });
}
