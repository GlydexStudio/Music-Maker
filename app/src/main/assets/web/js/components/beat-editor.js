// 16-step sequencer bound to the native BeatEngine. Used by Beat Lab and the Song Studio.
// Playback, timing and sound are native; this component edits the pattern and shows the playhead.

import { h, icon, iconButton, stepper, errorToast } from '../ui.js';
import { Native, on } from '../native.js';
import { TRACKS, emptyBeat } from '../data.js';
import * as State from '../state.js';

let activeEditor = null;

// Native playhead + transport events go to whichever editor started playback.
on('beatStep', (d) => { if (activeEditor) activeEditor.showStep(d.step); });
on('beatState', (d) => {
  State.set('beat', d);
  if (activeEditor) activeEditor.showPlaying(!!d.playing);
});
on('beatError', (e) => { errorToast(e); if (activeEditor) activeEditor.showPlaying(false); });

export function normalizeBeat(b) {
  const base = emptyBeat((b && b.bpm) || 140);
  if (!b) return base;
  TRACKS.forEach(({ id }) => {
    const src = (b.steps && b.steps[id]) || [];
    base.steps[id] = Array.from({ length: 16 }, (_, i) => (src[i] ? 1 : 0));
    if (b.volumes && typeof b.volumes[id] === 'number') base.volumes[id] = b.volumes[id];
  });
  base.swing = typeof b.swing === 'number' ? b.swing : 0;
  base.muted = Array.isArray(b.muted) ? b.muted.filter((m) => TRACKS.some((t) => t.id === m)) : [];
  return base;
}

export function beatHasNotes(b) {
  return !!b && TRACKS.some(({ id }) => (b.steps[id] || []).some(Boolean));
}

/**
 * @param {object} opts
 * @param {object} opts.beat   initial pattern (any shape; normalized)
 * @param {(beat) => void} opts.onChange called after every edit
 */
export function createBeatEditor({ beat, onChange }) {
  let pattern = normalizeBeat(beat);
  let playing = false;
  let page = 0;
  let follow = true;
  let head = -1;
  const wide = window.matchMedia('(min-width: 640px)');

  const changed = () => {
    if (playing) Native.updateBeat(pattern).catch(() => {});
    onChange && onChange(copy());
  };
  const copy = () => JSON.parse(JSON.stringify(pattern));

  // ---------------------------------------------------------------- transport
  const playIcon = icon('play');
  const playBtn = h('button', { class: 'play-btn', 'aria-label': 'Play', onclick: () => (playing ? pause() : play()) }, playIcon);
  const stopBtn = iconButton('stop', 'Stop', () => stop());
  const bpmLabel = h('div', { class: 'bpm-display' });
  const renderBpm = () => { bpmLabel.replaceChildren(String(pattern.bpm), h('small', { text: 'BPM' })); };
  const bpm = stepper(pattern.bpm, { min: 60, max: 200, onChange: (v) => { pattern.bpm = v; renderBpm(); changed(); } });
  bpm.querySelector('.value').hidden = true;
  const swing = h('input', { class: 'range', type: 'range', min: '0', max: '50', step: '5', 'aria-label': 'Swing' });
  swing.value = String(Math.round(pattern.swing * 100));
  const swingVal = h('span', { class: 'tiny muted mono' });
  const renderSwing = () => { swingVal.textContent = `${swing.value}%`; };
  swing.addEventListener('input', () => { pattern.swing = Number(swing.value) / 100; renderSwing(); changed(); });
  renderBpm(); renderSwing();

  const transport = h('div', { class: 'stack-v' },
    h('div', { class: 'transport' }, playBtn, stopBtn, h('div', { class: 'grow' }), bpmLabel),
    h('div', { class: 'tempo-row' }, bpm, h('span', { class: 'tiny muted', text: 'SWING' }), swing, swingVal));

  // ---------------------------------------------------------------- grid
  const seq = h('div', { class: 'seq' });
  const pageDots = h('div', { class: 'page-dots' }, h('i'), h('i'));
  const pageBtns = h('div', { class: 'segmented', style: { width: '170px' } },
    h('button', { type: 'button', text: '1 – 8', onclick: () => setPage(0, true) }),
    h('button', { type: 'button', text: '9 – 16', onclick: () => setPage(1, true) }));
  const pager = h('div', { class: 'row between' }, h('span', { class: 'tiny muted', text: 'STEPS' }), pageDots, pageBtns);
  const cells = {}; // track -> [16 buttons]
  const rows = {};
  const ruler = h('div', { class: 'nums' });

  function buildGrid() {
    const full = wide.matches;
    seq.classList.toggle('full', full);
    pager.hidden = full;
    seq.replaceChildren();
    ruler.replaceChildren();
    const indices = full ? [...Array(16).keys()] : [...Array(8).keys()].map((i) => i + page * 8);
    indices.forEach((i) => ruler.append(h('span', { text: String(i + 1) })));
    seq.append(h('div', { class: 'seq-ruler' }, h('span'), ruler));
    TRACKS.forEach(({ id, label }) => {
      const muteBtn = h('button', { class: `mute${pattern.muted.includes(id) ? ' on' : ''}`, 'aria-label': `Mute ${label}`, onclick: () => toggleMute(id) },
        icon(pattern.muted.includes(id) ? 'mute' : 'volume', 'sm'));
      const labelEl = h('div', { class: 'seq-label' },
        h('button', { class: 'name', text: label, 'aria-label': `Preview ${label}`, onclick: () => Native.previewSound(id).catch(errorToast) }),
        muteBtn);
      const cellWrap = h('div', { class: 'seq-cells' });
      cells[id] = [];
      indices.forEach((i) => {
        const c = h('button', {
          class: `cell${i % 4 === 0 ? ' beat-start' : ''}`,
          'aria-label': `${label} step ${i + 1}`,
          onclick: () => toggleCell(id, i),
        });
        cells[id][i] = c;
        cellWrap.append(c);
      });
      rows[id] = h('div', { class: `seq-row${pattern.muted.includes(id) ? ' muted' : ''}` }, labelEl, cellWrap);
      seq.append(rows[id]);
    });
    paintAll();
    renderPager();
  }

  function paintAll() {
    TRACKS.forEach(({ id }) => (cells[id] || []).forEach((c, i) => {
      if (!c) return;
      const onState = !!pattern.steps[id][i];
      c.classList.toggle('on', onState);
      c.classList.toggle('head', i === head);
      c.setAttribute('aria-pressed', onState ? 'true' : 'false');
    }));
  }

  function renderPager() {
    pageBtns.querySelectorAll('button').forEach((b, i) => b.classList.toggle('active', i === page));
    pageDots.querySelectorAll('i').forEach((d, i) => {
      d.classList.toggle('active', i === page);
      d.classList.toggle('playing', playing && head >= 0 && Math.floor(head / 8) === i && i !== page);
    });
  }

  function setPage(p, manual) {
    if (manual) follow = p === Math.floor(Math.max(head, 0) / 8) || !playing;
    if (p === page) { renderPager(); return; }
    page = p;
    buildGrid();
  }

  function toggleCell(track, i) {
    const nowOn = pattern.steps[track][i] ? 0 : 1;
    pattern.steps[track][i] = nowOn;
    const c = cells[track][i];
    c.classList.toggle('on', !!nowOn);
    c.setAttribute('aria-pressed', nowOn ? 'true' : 'false');
    if (nowOn && !playing) Native.previewSound(track).catch(() => {});
    changed();
  }

  function toggleMute(track) {
    const i = pattern.muted.indexOf(track);
    if (i >= 0) pattern.muted.splice(i, 1); else pattern.muted.push(track);
    buildGrid();
    changed();
  }

  // ---------------------------------------------------------------- playback
  async function play() {
    if (activeEditor && activeEditor !== api) activeEditor.showPlaying(false);
    activeEditor = api;
    try {
      const s = await Native.playBeat(pattern);
      showPlaying(!!s.playing);
    } catch (e) { errorToast(e); }
  }

  async function pause() {
    try { await Native.pauseBeat(); } catch (e) { errorToast(e); }
    showPlaying(false);
  }

  async function stop() {
    if (activeEditor === api || !activeEditor) {
      try { await Native.stopBeat(); } catch (e) { /* engine already idle */ }
    }
    follow = true;
    showPlaying(false);
    showStep(-1);
  }

  function showPlaying(p) {
    playing = p;
    playBtn.classList.toggle('playing', p);
    playBtn.setAttribute('aria-label', p ? 'Pause' : 'Play');
    playIcon.firstChild.setAttribute('href', p ? '#i-pause' : '#i-play');
    renderPager();
  }

  function showStep(step) {
    const prev = head;
    head = step;
    if (!wide.matches && step >= 0 && follow && Math.floor(step / 8) !== page) {
      page = Math.floor(step / 8);
      buildGrid();
      return;
    }
    TRACKS.forEach(({ id }) => {
      const row = cells[id] || [];
      if (row[prev]) row[prev].classList.remove('head');
      if (row[step]) row[step].classList.add('head');
    });
    renderPager();
  }

  wide.addEventListener('change', buildGrid);
  buildGrid();

  const el = h('div', { class: 'stack-v' }, h('div', { class: 'card' }, transport), h('div', { class: 'card seq-card' }, pager, h('div', { style: { height: '12px' } }), seq));

  const api = {
    el,
    getBeat: copy,
    setBeat(b) {
      pattern = normalizeBeat(b);
      bpm.setValue(pattern.bpm); renderBpm();
      swing.value = String(Math.round(pattern.swing * 100)); renderSwing();
      buildGrid();
      if (playing) Native.updateBeat(pattern).catch(() => {});
    },
    stop,
    showStep,
    showPlaying,
    destroy() {
      wide.removeEventListener('change', buildGrid);
      if (activeEditor === api) { Native.stopBeat().catch(() => {}); activeEditor = null; }
    },
  };
  return api;
}
