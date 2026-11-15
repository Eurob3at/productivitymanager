/*
 * load-events.js
 * Reads final_events.json (from merge_events.py) and puts the events into the Productivity App page.
 *
 * Hook it up with TWO small changes to the HTML:
 *   1. Before the page's main <script>, add:
 *        <script src="load-events.js"></script>
 *   2. After the page's last line (layout();sections.forEach(addBody);...), add:
 *        loadRealEvents(items);
 *
 * If the file can't be loaded, the page keeps its sample data, so nothing breaks.
 * Note: browsers block fetch() on file:// pages. Run a local server in this folder:
 *        python -m http.server 8000     then open  http://localhost:8000/Productivity_App.html
 */

const EVENTS_FILE = 'final_events.json';

// Which bubble (section) each event goes in. First rule that matches wins.
const SECTION_RULES = [
  { section: 'exams',       test: e => /\b(exam|midterm|final|quiz|test|practical)\b/i.test(e.title) && !/\breview\b/i.test(e.title) },
  { section: 'si-sessions', test: e => /\b(SI|supplemental instruction|review|tutoring|office hours)\b/i.test(e.title) },
  { section: 'homework',    test: e => e.source === 'canvas' },
  { section: 'events',      test: e => true },   // everything else from Gmail
];

// "2026-11-20 23:59" or "2026-11-20" -> local Date. Returns null if unreadable.
function parseEventDate(text) {
  const m = /^(\d{4})-(\d{2})-(\d{2})(?:[ T](\d{2}):(\d{2}))?/.exec(text || '');
  if (!m) return null;
  const allDay = m[4] === undefined;
  const d = new Date(+m[1], +m[2] - 1, +m[3], allDay ? 9 : +m[4], allDay ? 0 : +m[5]);
  return isNaN(d) ? null : { when: d, allDay };
}

// Canvas descriptions look like "Course: ENGIN 112. Do problems 1-4 Link: https://..."
function splitDescription(desc) {
  let text = desc || '', course = '', link = '';
  const c = /^Course: (.*?)\.\s*/.exec(text);
  if (c) { course = c[1]; text = text.slice(c[0].length); }
  const l = /\s*Link: (\S+)\s*$/.exec(text);
  if (l) { link = l[1]; text = text.slice(0, l.index); }
  return { course, text: text.trim(), link };
}

function convertEvent(e, byId) {
  const parsed = parseEventDate(e.date);
  if (!parsed || !e.title) {
    console.warn('Skipping event with unreadable date:', e);
    return null;
  }
  const { course, text, link } = splitDescription(e.description);
  const source = e.source === 'canvas' ? 'Canvas' : e.source === 'gmail' ? 'Gmail' : (e.source || 'Unknown');

  let detail = text || 'No details.';
  if (parsed.allDay) detail += ' (All day.)';
  if (e.possibleDuplicateOf && byId[e.possibleDuplicateOf]) {
    detail += ' Might be the same as "' + byId[e.possibleDuplicateOf].title + '".';
  }
  if (link) detail += ' ' + link;

  return {
    id: e.id,
    section: SECTION_RULES.find(r => r.test(e)).section,
    title: e.title,
    course: e.course || course || (source === 'Gmail' ? 'Email' : ''),
    when: parsed.when,
    place: e.location || '',
    source,
    detail,
    // no "kind": the page then shows Gmail events under both Fun and Important
  };
}

async function loadRealEvents(items) {
  let data;
  try {
    const res = await fetch(EVENTS_FILE, { cache: 'no-store' });
    if (!res.ok) throw new Error('HTTP ' + res.status);
    data = await res.json();
  } catch (err) {
    console.warn('Could not load ' + EVENTS_FILE + ', keeping sample data.', err);
    return false;
  }

  const raw = Array.isArray(data) ? data : (data.events || []);
  const byId = Object.fromEntries(raw.map(e => [e.id, e]));
  const converted = raw.map(e => convertEvent(e, byId)).filter(Boolean);

  // Replace the sample items in place (items is a const array in the page)
  items.length = 0;
  items.push(...converted);
  console.log('Loaded ' + converted.length + ' events from ' + EVENTS_FILE);

  // Ask the page to redraw with the new data (these functions live in the page's script)
  if (typeof DONE !== 'undefined') DONE.clear();
  if (typeof syncCounts === 'function') syncCounts();
  if (typeof buildDev === 'function') buildDev();
  if (typeof mode !== 'undefined' && mode === 'focus' && typeof renderPage === 'function') renderPage();
  return true;
}