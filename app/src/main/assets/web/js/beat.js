// Beat Lab: full-screen sequencer with presets, save-to-song and WAV sharing.

import { h, iconButton, button, screenShell, toast, errorToast, menu, confirm } from './ui.js';
import { Native } from './native.js';
import * as Nav from './navigation.js';
import { BEAT_PRESETS, emptyBeat } from './data.js';
import { createBeatEditor, normalizeBeat, beatHasNotes } from './components/beat-editor.js';
import { pickSong } from './components/song-picker.js';
import { createSong, defaults } from './projects.js';

export function beatScreen({ songId } = {}) {
  let song = null;
  let dirty = false;
  const start = emptyBeat(defaults().bpm);
  BEAT_PRESETS[0] && Object.assign(start, normalizeBeat({ ...BEAT_PRESETS[0], bpm: defaults().bpm }));

  const shell = screenShell({
    title: 'Beat Lab',
    back: () => Nav.back(),
    actions: [iconButton('share', 'Share as WAV', share), iconButton('save', 'Save beat', save)],
  });

  const editor = createBeatEditor({ beat: start, onChange: () => { dirty = true; } });

  const presetBtn = button('Presets', { icon: 'pads', cls: 'sm', onClick: presets });
  const clearBtn = button('Clear', { icon: 'trash', cls: 'sm', onClick: clearAll });
  const saveBtn = button('Save', { icon: 'save', cls: 'primary sm', onClick: save });

  shell.content.append(
    h('div', { class: 'action-strip', style: { 'margin-bottom': '12px' } }, presetBtn, clearBtn, saveBtn),
    editor.el,
    h('p', { class: 'tiny muted', style: { margin: '14px 4px 0' }, text: 'Tap a step to toggle it, tap a track name to hear it. All sounds are synthesized on your phone.' }));

  async function presets() {
    const id = await menu({ title: 'Start from a preset', items: BEAT_PRESETS.map((p) => ({ id: p.id, label: p.label, sub: `${p.bpm} BPM`, icon: 'pads' })) });
    if (!id) return;
    if (beatHasNotes(editor.getBeat()) && dirty) {
      const ok = await confirm({ title: 'Replace pattern?', message: 'The current pattern will be replaced by the preset.', okLabel: 'Replace' });
      if (!ok) return;
    }
    const p = BEAT_PRESETS.find((x) => x.id === id);
    editor.setBeat({ ...editor.getBeat(), ...p, muted: [] });
    dirty = true;
  }

  async function clearAll() {
    if (!beatHasNotes(editor.getBeat())) return;
    const ok = await confirm({ title: 'Clear all steps?', message: 'Every step in every track will be turned off.', okLabel: 'Clear', danger: true });
    if (!ok) return;
    editor.setBeat({ ...emptyBeat(editor.getBeat().bpm), swing: editor.getBeat().swing });
    dirty = true;
  }

  async function save() {
    const beat = editor.getBeat();
    try {
      if (song) {
        song = await Native.saveBeat(song.id, beat);
        dirty = false;
        toast(`Beat saved to “${song.title}”`, { type: 'ok' });
        return;
      }
      const target = await pickSong({ title: 'Save beat to…' });
      if (!target) return;
      const saved = target === 'new'
        ? await createSong({ title: 'Untitled song', beat, bpm: beat.bpm })
        : await Native.saveBeat(target, beat);
      song = saved;
      shell.titleEl.textContent = `Beat · ${saved.title}`;
      dirty = false;
      toast(`Beat saved to “${saved.title}”`, { type: 'ok', action: 'Open', onAction: () => Nav.push('studio', { id: saved.id }) });
    } catch (e) { errorToast(e); }
  }

  async function share() {
    const beat = editor.getBeat();
    if (!beatHasNotes(beat)) { toast('Add some steps first.'); return; }
    try { await Native.shareBeat(beat, song ? song.title : 'Music Maker beat', 4); } catch (e) { errorToast(e); }
  }

  if (songId) {
    Native.getSong(songId).then((s) => {
      song = s;
      shell.titleEl.textContent = `Beat · ${s.title}`;
      editor.setBeat(s.beat || { ...emptyBeat(s.bpm || defaults().bpm) });
      dirty = false;
    }).catch(errorToast);
  }

  return {
    el: shell.el,
    onHide: () => editor.stop(),
    destroy: () => editor.destroy(),
    async canLeave() {
      if (!dirty || !beatHasNotes(editor.getBeat())) return true;
      const choice = await menu({ title: 'Unsaved beat', items: [
        { id: 'save', label: song ? `Save to “${song.title}”` : 'Save beat…', icon: 'save' },
        { id: 'discard', label: 'Discard beat', icon: 'trash', danger: true },
      ] });
      if (choice === 'save') { await save(); return !dirty; }
      return choice === 'discard';
    },
  };
}
