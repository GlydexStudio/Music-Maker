// Songs library: search, filter, open, rename, duplicate, favorite, delete.

import { h, icon, iconButton, button, screenShell, pageHead, coverEl, statusBadge, relativeTime, menu, confirm,
  promptText, toast, errorToast, emptyState, debounce } from './ui.js';
import { Native } from './native.js';
import * as Nav from './navigation.js';
import { STATUSES } from './data.js';
import { createSong } from './projects.js';

export function songsScreen() {
  const shell = screenShell({ root: true });
  let songs = [];
  let query = '';
  let filter = 'all';
  let sort = 'recent';

  const listEl = h('div', { class: 'song-grid' });
  const countEl = h('span', { class: 'tiny muted' });
  const search = h('input', { class: 'input', type: 'search', placeholder: 'Search title, genre, mood…', 'aria-label': 'Search songs' });
  search.addEventListener('input', debounce(() => { query = search.value.trim().toLowerCase(); render(); }, 120));

  const filters = [{ id: 'all', label: 'All' }, { id: 'fav', label: 'Favorites' }, ...STATUSES];
  const filterRow = h('div', { class: 'chips scroll' });
  filters.forEach((f) => filterRow.append(h('button', {
    class: 'chip', dataset: { value: f.id }, text: f.label,
    onclick: () => { filter = f.id; paintFilters(); render(); },
  })));
  const paintFilters = () => filterRow.querySelectorAll('.chip').forEach((c) => c.classList.toggle('active', c.dataset.value === filter));
  paintFilters();

  const sortBtn = h('button', { class: 'btn ghost sm', onclick: async () => {
    const v = await menu({ title: 'Sort by', items: [
      { id: 'recent', label: 'Last modified', selected: sort === 'recent' },
      { id: 'created', label: 'Date created', selected: sort === 'created' },
      { id: 'title', label: 'Title', selected: sort === 'title' },
    ] });
    if (v) { sort = v; render(); }
  } }, icon('chevron-down', 'sm'), h('span', { text: 'Sort' }));

  const newSong = async () => {
    const title = await promptText({ title: 'New song', label: 'Title', placeholder: 'Untitled song', okLabel: 'Create' });
    if (!title) return;
    try {
      const s = await createSong({ title });
      Nav.push('studio', { id: s.id });
    } catch (e) { errorToast(e); }
  };

  shell.content.append(
    h('div', { class: 'row between' }, pageHead('Songs', 'Your project library.'), button('New', { icon: 'plus', cls: 'primary sm', onClick: newSong })),
    h('div', { class: 'search' }, icon('search'), search),
    h('div', { style: { height: '12px' } }),
    filterRow,
    h('div', { class: 'row between', style: { margin: '10px 2px 8px' } }, countEl, sortBtn),
    listEl);

  function visible() {
    let out = songs.filter((s) => {
      if (filter === 'fav' && !s.favorite) return false;
      if (filter !== 'all' && filter !== 'fav' && s.status !== filter) return false;
      if (!query) return true;
      return [s.title, s.genre, s.mood, s.artist, s.lyricsPreview].some((v) => (v || '').toLowerCase().includes(query));
    });
    if (sort === 'title') out = out.slice().sort((a, b) => a.title.localeCompare(b.title));
    if (sort === 'created') out = out.slice().sort((a, b) => b.createdAt - a.createdAt);
    return out;
  }

  function songCard(s) {
    const star = iconButton('star', s.favorite ? 'Remove from favorites' : 'Add to favorites', (e) => { e.stopPropagation(); toggleFav(s); }, s.favorite ? 'on' : '');
    if (s.favorite) star.querySelector('svg').classList.add('filled');
    const more = iconButton('more', 'More actions', (e) => { e.stopPropagation(); actions(s); });
    const card = h('div', { class: 'song-card', role: 'button', tabindex: '0', 'aria-label': `Open ${s.title}`,
      onclick: () => Nav.push('studio', { id: s.id }),
      onkeydown: (e) => { if (e.key === 'Enter') Nav.push('studio', { id: s.id }); } },
    coverEl(s),
    h('div', { class: 'info' },
      h('div', { class: 't', text: s.title }),
      h('div', { class: 'm', text: [s.genre || 'No genre', s.bpm ? `${s.bpm} BPM` : null, s.key || null].filter(Boolean).join(' · ') }),
      h('div', { class: 's' }, statusBadge(s.status), h('span', { class: 'tiny muted', text: relativeTime(s.modifiedAt) }))),
    star, more);
    return card;
  }

  function render() {
    const items = visible();
    countEl.textContent = songs.length ? `${items.length} of ${songs.length} song${songs.length === 1 ? '' : 's'}` : '';
    if (!songs.length) {
      listEl.replaceChildren(emptyState({ iconName: 'library', title: 'Your library is empty', text: 'Create a song project to get started.', action: button('New song', { icon: 'plus', cls: 'primary', onClick: newSong }) }));
      return;
    }
    if (!items.length) {
      listEl.replaceChildren(emptyState({ iconName: 'search', title: 'No matches', text: 'Try a different search or filter.' }));
      return;
    }
    listEl.replaceChildren(...items.map(songCard));
  }

  async function load() {
    try { songs = await Native.getSongs(); render(); } catch (e) { errorToast(e); }
  }

  async function toggleFav(s) {
    try {
      await Native.setFavorite(s.id, !s.favorite);
      s.favorite = !s.favorite;
      render();
    } catch (e) { errorToast(e); }
  }

  async function actions(s) {
    const id = await menu({ title: s.title, items: [
      { id: 'open', label: 'Open', icon: 'chevron' },
      { id: 'rename', label: 'Rename', icon: 'edit' },
      { id: 'duplicate', label: 'Duplicate', icon: 'copy' },
      { id: 'fav', label: s.favorite ? 'Remove from favorites' : 'Add to favorites', icon: 'star' },
      { id: 'share', label: 'Share', icon: 'share' },
      { id: 'delete', label: 'Delete', icon: 'trash', danger: true },
    ] });
    try {
      if (id === 'open') Nav.push('studio', { id: s.id });
      if (id === 'rename') {
        const t = await promptText({ title: 'Rename song', label: 'Title', value: s.title });
        if (t) { await Native.renameSong(s.id, t); await load(); }
      }
      if (id === 'duplicate') { await Native.duplicateSong(s.id); toast('Song duplicated', { type: 'ok' }); await load(); }
      if (id === 'fav') await toggleFav(s);
      if (id === 'share') await Native.shareSong(s.id);
      if (id === 'delete') {
        const ok = await confirm({ title: 'Delete song?', message: `"${s.title}" and its lyrics, beat and promotion will be permanently deleted from this device.`, okLabel: 'Delete', danger: true });
        if (ok) { await Native.deleteSong(s.id); toast('Song deleted'); await load(); }
      }
    } catch (e) { errorToast(e); }
  }

  return { el: shell.el, onShow: load };
}
