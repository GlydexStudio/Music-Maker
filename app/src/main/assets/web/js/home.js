// Home: branding, greeting, create CTA, quick actions, AI status and recent songs.

import { h, icon, button, screenShell, coverEl, relativeTime, errorToast, emptyState } from './ui.js';
import { Native } from './native.js';
import * as Nav from './navigation.js';
import * as State from './state.js';
import { modelPill, modelCard } from './components/model-status.js';
import { createSong } from './projects.js';

function greeting() {
  const hr = new Date().getHours();
  if (hr < 5) return 'Late night session';
  if (hr < 12) return 'Good morning';
  if (hr < 18) return 'Good afternoon';
  return 'Good evening';
}

export function homeScreen() {
  const shell = screenShell({ root: true });
  const recentWrap = h('div');
  const greetTitle = h('h1');

  const newSong = async () => {
    try {
      const song = await createSong();
      Nav.push('studio', { id: song.id });
    } catch (e) { errorToast(e); }
  };

  const quick = (ic, title, sub, screen) => h('button', { class: 'quick', onclick: () => Nav.push(screen) },
    h('div', { class: 'q-icon' }, icon(ic)), h('div', {}, h('b', { text: title }), h('small', { text: sub })));

  const bars = h('div', { class: 'bars', 'aria-hidden': 'true' }, [18, 34, 52, 30, 44].map((ht) => {
    const i = h('i'); i.style.setProperty('height', `${ht}px`); return i;
  }));

  shell.content.append(
    h('div', { class: 'stagger' },
      h('div', { class: 'row between', style: { 'padding-top': '14px' } },
        h('div', { class: 'brand' }, h('div', { class: 'brand-mark' }, icon('wave')), h('div', { class: 'brand-name', text: 'Music Maker' })),
        modelPill(() => Nav.tab('settings'))),
      h('div', { class: 'greeting' }, greetTitle, h('p', { text: 'What are we making today?' })),
      h('div', { class: 'hero' }, bars,
        h('h2', { text: 'Create a new song' }),
        h('p', { text: 'Start a project and take it from idea to lyrics, beat and release.' }),
        button('New Song', { icon: 'plus', cls: 'primary', onClick: newSong }),
        h('div', { class: 'workflow' }, ['Idea', 'Lyrics', 'Beat', 'Song', 'Promotion'].flatMap((s, i) => (i ? [h('span', { class: 'sep', text: '→' }), h('span', { text: s })] : [h('span', { text: s })])))),
      h('div', { class: 'section' },
        h('div', { class: 'section-head' }, h('h2', { text: 'Quick actions' })),
        h('div', { class: 'quick-grid' },
          quick('pen', 'Lyrics', 'Write with AI', 'lyrics'),
          quick('bulb', 'Song Idea', 'Find a concept', 'idea'),
          quick('pads', 'Beat', '16-step lab', 'beat'),
          quick('megaphone', 'Promotion', 'Captions & more', 'promotion'))),
      h('div', { class: 'section' },
        h('div', { class: 'section-head' }, h('h2', { text: 'Recent songs' }), h('button', { class: 'link', text: 'See all', onclick: () => Nav.tab('songs') })),
        recentWrap),
      h('div', { class: 'section' },
        h('div', { class: 'section-head' }, h('h2', { text: 'Your AI' })),
        modelCard())));

  async function loadRecent() {
    try {
      const songs = await Native.getSongs();
      if (!songs.length) {
        recentWrap.replaceChildren(h('div', { class: 'card' }, emptyState({
          iconName: 'note', title: 'No songs yet', text: 'Your projects will appear here.',
          action: button('Start your first song', { icon: 'plus', cls: 'sm', onClick: newSong }),
        })));
        return;
      }
      recentWrap.replaceChildren(h('div', { class: 'recent-row' }, songs.slice(0, 8).map((s) => h('button', {
        class: 'recent', onclick: () => Nav.push('studio', { id: s.id }), 'aria-label': `Open ${s.title}`,
      }, coverEl(s, 'md'), h('div', { class: 't', text: s.title }), h('div', { class: 'm', text: `${s.genre || 'Song'} · ${relativeTime(s.modifiedAt)}` })))));
    } catch (e) { errorToast(e); }
  }

  function renderGreeting() {
    const name = (State.get('settings') || {}).artistName;
    greetTitle.textContent = name ? `${greeting()}, ${name}` : greeting();
  }
  State.watch('settings', renderGreeting);

  return {
    el: shell.el,
    onShow() { renderGreeting(); loadRecent(); },
  };
}
