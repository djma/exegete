#!/usr/bin/env python3
"""Build the compact offline dictionary used by the Android app from WordNet 3.0."""

from __future__ import annotations

import argparse
import shutil
import sqlite3
from pathlib import Path


PARTS_OF_SPEECH = {
    "noun": (1, "noun"),
    "verb": (2, "verb"),
    "adj": (3, "adjective"),
    "adv": (4, "adverb"),
}


def normalized_word(value: str) -> str | None:
    word = value.replace("_", " ").lower()
    return word if " " not in word else None


def build_database(source: Path, destination: Path) -> None:
    destination.parent.mkdir(parents=True, exist_ok=True)
    destination.unlink(missing_ok=True)
    database = sqlite3.connect(destination)
    database.executescript(
        """
        PRAGMA journal_mode = OFF;
        PRAGMA synchronous = OFF;
        CREATE TABLE synsets (
            id INTEGER PRIMARY KEY,
            part_of_speech TEXT NOT NULL,
            definition TEXT NOT NULL
        );
        CREATE TABLE words (
            word TEXT NOT NULL COLLATE NOCASE,
            synset_id INTEGER NOT NULL,
            PRIMARY KEY (word, synset_id)
        ) WITHOUT ROWID;
        CREATE TABLE forms (
            form TEXT NOT NULL COLLATE NOCASE,
            lemma TEXT NOT NULL COLLATE NOCASE,
            PRIMARY KEY (form, lemma)
        ) WITHOUT ROWID;
        """
    )

    for filename, (part_number, label) in PARTS_OF_SPEECH.items():
        synsets: list[tuple[int, str, str]] = []
        words: list[tuple[str, int]] = []
        with (source / "dict" / f"data.{filename}").open(encoding="utf-8") as lines:
            for line in lines:
                if not line or not line[0].isdigit() or " | " not in line:
                    continue
                fields, gloss = line.rstrip().split(" | ", 1)
                tokens = fields.split()
                offset = int(tokens[0])
                synset_id = part_number * 100_000_000 + offset
                word_count = int(tokens[3], 16)
                lemmas = {
                    word
                    for index in range(word_count)
                    if (word := normalized_word(tokens[4 + index * 2])) is not None
                }
                if not lemmas:
                    continue
                synsets.append((synset_id, label, gloss.strip()))
                words.extend((word, synset_id) for word in lemmas)
        database.executemany("INSERT INTO synsets VALUES (?, ?, ?)", synsets)
        database.executemany("INSERT OR IGNORE INTO words VALUES (?, ?)", words)

        forms: list[tuple[str, str]] = []
        with (source / "dict" / f"{filename}.exc").open(encoding="utf-8") as lines:
            for line in lines:
                values = line.split()
                if not values:
                    continue
                form = normalized_word(values[0])
                if form is None:
                    continue
                forms.extend(
                    (form, lemma)
                    for value in values[1:]
                    if (lemma := normalized_word(value)) is not None
                )
        database.executemany("INSERT OR IGNORE INTO forms VALUES (?, ?)", forms)

    database.executescript(
        """
        CREATE INDEX words_by_word ON words(word COLLATE NOCASE);
        CREATE INDEX forms_by_form ON forms(form COLLATE NOCASE);
        ANALYZE;
        VACUUM;
        """
    )
    database.close()
    shutil.copyfile(source / "LICENSE", destination.parent / "wordnet-license.txt")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("source", type=Path, help="Extracted WordNet-3.0 directory")
    parser.add_argument("destination", type=Path, help="Output SQLite database")
    args = parser.parse_args()
    build_database(args.source, args.destination)


if __name__ == "__main__":
    main()
