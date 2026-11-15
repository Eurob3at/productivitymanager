"""
Merge Canvas + Gmail events into one file for the GUI.

Reads:   canvas_events.json  (from CanvasFetcher.java)
         events.json         (from extraction.py / Gemini)
Writes:  final_events.json

Rules:
  - Canvas wins when the same event appears in both (its dates are exact, Gemini's are guesses).
  - Gmail events that look *possibly* the same as a Canvas event are kept, but marked with
    "possibleDuplicateOf" so the GUI can show them greyed out and let the user decide.
  - Everything is sorted by date. Events with a date we can't read go at the end.

Usage:  python merge_events.py
        python merge_events.py canvas.json gmail.json out.json
"""

import json
import os
import re
import sys
from datetime import datetime

# ---------- Tuning knobs ----------
SAME_EVENT_SCORE = 0.75     # title word overlap at or above this -> duplicate, Gmail copy dropped
POSSIBLE_DUP_SCORE = 0.3    # between this and SAME_EVENT_SCORE -> kept but flagged
MAX_MINUTES_APART = 60      # times further apart than this are never the same event

# Words that mean the same thing, so "Homework 5" matches "HW 5"
SYNONYMS = {
    "homework": "hw", "assignment": "hw", "problem": "hw", "set": "",
    "exam": "exam", "midterm": "exam", "test": "exam", "quiz": "quiz",
    "laboratory": "lab", "project": "proj",
}
# Filler words that shouldn't count toward a match
IGNORE = {"the", "a", "an", "for", "of", "due", "reminder", "re", "fwd", "fw", "and", "on", "at", "is", ""}


# ---------- Loading ----------
def load_events(path, source):
    if not os.path.exists(path):
        print(f">>> Warning: '{path}' not found, skipping {source} events.")
        return []
    with open(path, "r", encoding="utf-8") as f:
        data = json.load(f)
    events = data.get("events", []) if isinstance(data, dict) else data
    cleaned = []
    for e in events:
        if not e.get("title") or not e.get("date"):
            continue  # unusable without a title and date
        cleaned.append({
            "title": e["title"].strip(),
            "date": e["date"].strip(),
            "location": e.get("location"),
            "description": e.get("description"),
            "source": source,
        })
    return cleaned


def parse_date(text):
    """Returns (datetime, has_time) or (None, False) if unreadable."""
    for fmt, has_time in (("%Y-%m-%d %H:%M", True), ("%Y-%m-%dT%H:%M", True), ("%Y-%m-%d", False)):
        try:
            return datetime.strptime(text[:16] if has_time else text[:10], fmt), has_time
        except ValueError:
            pass
    return None, False


# ---------- Matching ----------
def title_words(title):
    words = re.sub(r"[^a-z0-9 ]", " ", title.lower()).split()
    words = [SYNONYMS.get(w, w) for w in words]
    return {w for w in words if w not in IGNORE}


def title_score(a, b):
    wa, wb = title_words(a), title_words(b)
    if not wa or not wb:
        return 0.0
    return len(wa & wb) / len(wa | wb)


def close_in_time(a, b):
    da, ta = a["_dt"], a["_has_time"]
    db, tb = b["_dt"], b["_has_time"]
    if da is None or db is None or da.date() != db.date():
        return False
    if ta and tb:
        return abs((da - db).total_seconds()) <= MAX_MINUTES_APART * 60
    return True  # same day and at least one has no time -> could be the same


# ---------- Merge ----------
def merge(canvas, gmail):
    for i, e in enumerate(canvas):
        e["id"] = f"canvas-{i + 1}"
    for i, e in enumerate(gmail):
        e["id"] = f"gmail-{i + 1}"
    for e in canvas + gmail:
        e["_dt"], e["_has_time"] = parse_date(e["date"])

    result = list(canvas)
    dropped = flagged = 0

    for g in gmail:
        best, best_score = None, 0.0
        for c in canvas:
            if close_in_time(c, g):
                s = title_score(c["title"], g["title"])
                if s > best_score:
                    best, best_score = c, s

        if best_score >= SAME_EVENT_SCORE:
            dropped += 1
            continue  # Canvas already has it
        if best_score >= POSSIBLE_DUP_SCORE:
            g["possibleDuplicateOf"] = best["id"]
            flagged += 1

        # Gemini sometimes pulls the same event from two emails
        if any(r["source"] == "gmail" and r["_dt"] == g["_dt"]
               and title_score(r["title"], g["title"]) >= SAME_EVENT_SCORE for r in result):
            dropped += 1
            continue
        result.append(g)

    # Sort by date; unreadable dates last
    result.sort(key=lambda e: (e["_dt"] is None, e["_dt"] or datetime.max))

    for e in result:
        if e["_dt"] is None:
            print(f">>> Warning: couldn't read date '{e['date']}' for '{e['title']}' (kept at the end).")
        del e["_dt"], e["_has_time"]

    print(f">>> {len(canvas)} Canvas + {len(gmail)} Gmail events -> {len(result)} total "
          f"({dropped} duplicate(s) removed, {flagged} flagged as possible duplicates).")
    return result


# Key order in the output file
FIELD_ORDER = ["id", "title", "date", "location", "description", "source", "possibleDuplicateOf"]


def main():
    canvas_path = sys.argv[1] if len(sys.argv) > 1 else "canvas_events.json"
    gmail_path = sys.argv[2] if len(sys.argv) > 2 else "events.json"
    out_path = sys.argv[3] if len(sys.argv) > 3 else "final_events.json"

    canvas = load_events(canvas_path, "canvas")
    gmail = load_events(gmail_path, "gmail")
    merged = merge(canvas, gmail)

    ordered = [{k: e[k] for k in FIELD_ORDER if k in e} for e in merged]
    with open(out_path, "w", encoding="utf-8") as f:
        json.dump({"events": ordered}, f, indent=2, ensure_ascii=False)
    print(f">>> Saved to '{out_path}'.")


if __name__ == "__main__":
    main()
