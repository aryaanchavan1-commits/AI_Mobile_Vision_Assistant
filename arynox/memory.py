import json
import math
import sqlite3

from .config import DATA_DIR


def _cos(a, b):
    dot = sum(x * y for x, y in zip(a, b))
    na = math.sqrt(sum(x * x for x in a))
    nb = math.sqrt(sum(x * x for x in b))
    if na == 0 or nb == 0:
        return 0.0
    return dot / (na * nb)


class Memory:
    def __init__(self, cfg, llm=None):
        self.cfg = cfg
        self.llm = llm
        DATA_DIR.mkdir(parents=True, exist_ok=True)
        self.db = sqlite3.connect(str(DATA_DIR / "arynox.db"))
        self.db.row_factory = sqlite3.Row
        self._init()

    def _init(self):
        self.db.executescript(
            """
            CREATE TABLE IF NOT EXISTS memory(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                name TEXT NOT NULL,
                type TEXT DEFAULT 'thing',
                description TEXT DEFAULT '',
                photo TEXT DEFAULT '',
                tags TEXT DEFAULT '',
                created_at TEXT DEFAULT (datetime('now'))
            );
            CREATE VIRTUAL TABLE IF NOT EXISTS memory_fts USING fts5(
                name, description, tags, content='memory', content_rowid='id'
            );
            CREATE TRIGGER IF NOT EXISTS memory_ai AFTER INSERT ON memory BEGIN
                INSERT INTO memory_fts(rowid, name, description, tags)
                VALUES (new.id, new.name, new.description, new.tags);
            END;
            CREATE TRIGGER IF NOT EXISTS memory_ad AFTER DELETE ON memory BEGIN
                INSERT INTO memory_fts(memory_fts, rowid, name, description, tags)
                VALUES ('delete', old.id, old.name, old.description, old.tags);
            END;
            CREATE TABLE IF NOT EXISTS memory_vectors(
                memory_id INTEGER PRIMARY KEY,
                embedding TEXT NOT NULL
            );
            CREATE TABLE IF NOT EXISTS events(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                event_type TEXT DEFAULT 'scene',
                summary TEXT DEFAULT '',
                photo TEXT DEFAULT '',
                created_at TEXT DEFAULT (datetime('now'))
            );
            """
        )
        self.db.commit()

    def remember(self, name, kind, description, photo="", tags=""):
        cur = self.db.cursor()
        cur.execute(
            "INSERT INTO memory(name, type, description, photo, tags) VALUES(?,?,?,?,?)",
            (name, kind, description, photo, tags),
        )
        mid = cur.lastrowid
        self.db.commit()
        vec = self.llm.embed(name + " " + description) if self.llm else None
        if vec:
            cur.execute(
                "INSERT INTO memory_vectors(memory_id, embedding) VALUES(?,?)",
                (mid, json.dumps(vec)),
            )
            self.db.commit()
        return mid

    def recall(self, query, top=3):
        vec = self.llm.embed(query) if self.llm else None
        if vec:
            scored = []
            for row in self.db.execute("SELECT * FROM memory_vectors"):
                stored = json.loads(row["embedding"])
                score = _cos(vec, stored)
                if score >= 0.5:
                    scored.append((score, row["memory_id"]))
            scored.sort(key=lambda r: -r[0])
            ids = [rid for _, rid in scored[:top]]
            if ids:
                q = ",".join("?" * len(ids))
                return self.db.execute(
                    f"SELECT * FROM memory WHERE id IN ({q})", ids
                ).fetchall()
        words = [w for w in query.lower().split() if len(w) > 2][:6]
        if not words:
            return []
        try:
            fts_query = " AND ".join(f'"{w}"' for w in words)
            return self.db.execute(
                "SELECT m.* FROM memory m JOIN memory_fts f ON m.id = f.rowid "
                "WHERE memory_fts MATCH ? ORDER BY bm25(memory_fts) LIMIT ?",
                (fts_query, top),
            ).fetchall()
        except sqlite3.OperationalError:
            pattern = "%" + "%".join(words) + "%"
            return self.db.execute(
                "SELECT * FROM memory WHERE lower(description) LIKE ? LIMIT ?",
                (pattern, top),
            ).fetchall()

    def forget(self, fragment):
        cur = self.db.cursor()
        cur.execute("SELECT id FROM memory WHERE lower(name) LIKE ?", (f"%{fragment.lower()}%",))
        ids = [row[0] for row in cur.fetchall()]
        for mid in ids:
            cur.execute("DELETE FROM memory WHERE id = ?", (mid,))
            cur.execute("DELETE FROM memory_vectors WHERE memory_id = ?", (mid,))
        self.db.commit()
        return len(ids)

    def list_memories(self, limit=10):
        return self.db.execute(
            "SELECT * FROM memory ORDER BY id DESC LIMIT ?", (limit,)
        ).fetchall()

    def add_event(self, kind, summary, photo=""):
        self.db.execute(
            "INSERT INTO events(event_type, summary, photo) VALUES(?,?,?)",
            (kind, summary, photo),
        )
        self.db.commit()

    def recent_events(self, limit=5):
        return self.db.execute(
            "SELECT * FROM events ORDER BY id DESC LIMIT ?", (limit,)
        ).fetchall()
