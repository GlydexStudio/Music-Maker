// Song project helpers shared by several screens.

import { Native } from './native.js';
import * as State from './state.js';

/** Creates and persists a new project using the user's defaults. */
export function createSong(fields = {}) {
  const s = State.get('settings') || {};
  return Native.saveSong({
    title: 'Untitled song',
    artist: s.artistName || '',
    genre: s.genre || '',
    mood: '',
    language: s.language || 'en',
    bpm: s.bpm || 120,
    key: '',
    lyrics: '',
    status: 'draft',
    ...fields,
  });
}

/** Appends a lyrics block to a song, separated by a blank line. */
export async function appendLyrics(songId, text, title) {
  const song = await Native.getSong(songId);
  const lyrics = song.lyrics ? `${song.lyrics.trimEnd()}\n\n${text.trim()}` : text.trim();
  const patch = { ...song, lyrics, status: song.status === 'draft' ? 'writing' : song.status };
  if (title && (!song.title || song.title === 'Untitled song')) patch.title = title;
  return Native.saveSong(patch);
}

export function defaults() {
  const s = State.get('settings') || {};
  return { language: s.language || 'en', genre: s.genre || 'Hip-Hop', bpm: s.bpm || 140 };
}
