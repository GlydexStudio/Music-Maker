// Create: the entrance to the studio tools.

import { h, icon, screenShell, pageHead, errorToast } from './ui.js';
import * as Nav from './navigation.js';
import { createSong } from './projects.js';

export function createScreen() {
  const shell = screenShell({ root: true });

  const tool = (ic, title, text, onClick) => h('button', { class: 'studio-card', onclick: onClick },
    h('div', { class: 's-icon' }, icon(ic, 'lg')),
    h('div', { class: 'grow' }, h('h3', { text: title }), h('p', { text })),
    icon('chevron', 'chev'));

  shell.content.append(
    pageHead('Create', 'Pick a room in your studio.'),
    h('div', { class: 'studio-list stagger' },
      tool('pen', 'Lyrics', 'Verses, hooks, choruses and full songs. Rewrite, expand, sharpen rhymes.', () => Nav.push('lyrics')),
      tool('bulb', 'Song Idea', 'Title, concept, story, hook, key, BPM and beat direction.', () => Nav.push('idea')),
      tool('pads', 'Beat', 'A 16-step drum machine with kick, snare, hats, clap and 808.', () => Nav.push('beat')),
      tool('megaphone', 'Promotion', 'Captions, hashtags, teasers, bios and video ideas.', () => Nav.push('promotion')),
      tool('note', 'New Song Project', 'An empty project to combine everything.', async () => {
        try { const s = await createSong(); Nav.push('studio', { id: s.id }); } catch (e) { errorToast(e); }
      })));

  return { el: shell.el };
}
