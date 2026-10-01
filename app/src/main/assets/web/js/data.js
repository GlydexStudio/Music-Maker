// Static catalogues used across the studio.

export const GENRES = [
  'Hip-Hop', 'Trap', 'Drill', 'R&B', 'Pop', 'Dance Pop', 'Afrobeats', 'Reggaeton', 'Latin', 'Rock',
  'Indie', 'Alternative', 'EDM', 'House', 'Techno', 'Lo-fi', 'Soul', 'Jazz', 'Country', 'Folk',
  'Manele', 'Romanian Pop', 'Hungarian Pop', 'Acoustic', 'Cinematic',
];

export const MOODS = [
  'Confident', 'Heartbroken', 'Euphoric', 'Melancholic', 'Romantic', 'Dark', 'Nostalgic', 'Hopeful',
  'Angry', 'Chill', 'Party', 'Motivational', 'Mysterious', 'Dreamy', 'Playful',
];

export const KEYS = [
  'C', 'Cm', 'C#', 'C#m', 'D', 'Dm', 'Eb', 'Ebm', 'E', 'Em', 'F', 'Fm',
  'F#', 'F#m', 'G', 'Gm', 'Ab', 'Abm', 'A', 'Am', 'Bb', 'Bbm', 'B', 'Bm',
];

export const LANGUAGES = [
  { id: 'en', label: 'English', short: 'EN' },
  { id: 'ro', label: 'Română', short: 'RO' },
  { id: 'hu', label: 'Magyar', short: 'HU' },
];

export const STATUSES = [
  { id: 'draft', label: 'Draft' },
  { id: 'writing', label: 'Writing' },
  { id: 'recording', label: 'Recording' },
  { id: 'finished', label: 'Finished' },
  { id: 'released', label: 'Released' },
];

export const LYRIC_MODES = [
  { id: 'verse', label: 'Verse' },
  { id: 'chorus', label: 'Chorus' },
  { id: 'hook', label: 'Hook' },
  { id: 'bridge', label: 'Bridge' },
  { id: 'full', label: 'Full Lyrics' },
];

export const PLATFORMS = [
  { id: 'tiktok', label: 'TikTok' },
  { id: 'instagram', label: 'Instagram' },
  { id: 'youtube', label: 'YouTube' },
  { id: 'spotify', label: 'Spotify' },
  { id: 'general', label: 'General' },
];

export const PROMO_TYPES = [
  { id: 'caption', label: 'Caption' },
  { id: 'hashtags', label: 'Hashtags' },
  { id: 'video_idea', label: 'Short video idea' },
  { id: 'teaser', label: 'Teaser' },
  { id: 'announcement', label: 'Release announcement' },
  { id: 'youtube_description', label: 'YouTube description' },
  { id: 'artist_bio', label: 'Artist bio' },
  { id: 'song_description', label: 'Song description' },
];

export const TONES = ['Hype', 'Heartfelt', 'Mysterious', 'Funny', 'Professional', 'Minimal'];

export const ACCENTS = [
  { id: '#A78BFA', label: 'Violet' },
  { id: '#818CF8', label: 'Indigo' },
  { id: '#60A5FA', label: 'Blue' },
  { id: '#2DD4BF', label: 'Teal' },
  { id: '#FB7185', label: 'Rose' },
  { id: '#FBBF24', label: 'Amber' },
  { id: '#D4D4DC', label: 'Silver' },
];

export const TRACKS = [
  { id: 'kick', label: 'KICK' },
  { id: 'snare', label: 'SNARE' },
  { id: 'hihat', label: 'HI-HAT' },
  { id: 'clap', label: 'CLAP' },
  { id: '808', label: '808' },
];

const bits = (s) => s.split('').map((c) => (c === 'x' ? 1 : 0));

export const BEAT_PRESETS = [
  { id: 'trap', label: 'Trap', bpm: 140, swing: 0, steps: {
    kick: bits('x.........x.....'), snare: bits('........x.......'), hihat: bits('x.x.x.x.x.xxx.x.'),
    clap: bits('........x.......'), '808': bits('x.........x..x..') } },
  { id: 'boombap', label: 'Boom Bap', bpm: 90, swing: 0.3, steps: {
    kick: bits('x......x..x.....'), snare: bits('....x.......x...'), hihat: bits('x.x.x.x.x.x.x.x.'),
    clap: bits('............x...'), '808': bits('................') } },
  { id: 'house', label: 'House', bpm: 124, swing: 0.1, steps: {
    kick: bits('x...x...x...x...'), snare: bits('................'), hihat: bits('..x...x...x...x.'),
    clap: bits('....x.......x...'), '808': bits('x.....x.x.....x.') } },
  { id: 'drill', label: 'Drill', bpm: 142, swing: 0, steps: {
    kick: bits('x.....x...x.....'), snare: bits('....x.......x...'), hihat: bits('x..x..x.x..x.x..'),
    clap: bits('............x...'), '808': bits('x.....x...x...x.') } },
  { id: 'lofi', label: 'Lo-fi', bpm: 82, swing: 0.4, steps: {
    kick: bits('x.......x.x.....'), snare: bits('....x.......x...'), hihat: bits('x.x.x.x.x.x.x.x.'),
    clap: bits('................'), '808': bits('x...............') } },
];

export function emptyBeat(bpm = 140) {
  const steps = {};
  TRACKS.forEach((t) => { steps[t.id] = new Array(16).fill(0); });
  return { bpm, swing: 0, steps, volumes: { kick: 0.95, snare: 0.85, hihat: 0.6, clap: 0.8, '808': 0.9 }, muted: [] };
}
