"""SQLite-backed storage for Maya's conversations and messages.

Conversation memory: every user and assistant message is persisted here,
keyed by conversation_id, so Maya can recall prior turns in a conversation
even across server restarts.
"""

import sqlite3
import threading
import uuid
from contextlib import contextmanager
from datetime import datetime, timezone
from pathlib import Path
from typing import Optional

DB_PATH = Path(__file__).parent / "data" / "maya.db"
DB_PATH.parent.mkdir(parents=True, exist_ok=True)

_lock = threading.Lock()


def _now() -> str:
    return datetime.now(timezone.utc).isoformat()


@contextmanager
def _connect():
    conn = sqlite3.connect(DB_PATH)
    conn.row_factory = sqlite3.Row
    conn.execute("PRAGMA foreign_keys = ON")
    try:
        yield conn
        conn.commit()
    finally:
        conn.close()


def init_db() -> None:
    with _lock, _connect() as conn:
        conn.execute(
            """
            CREATE TABLE IF NOT EXISTS conversations (
                id TEXT PRIMARY KEY,
                owner_token TEXT NOT NULL,
                title TEXT NOT NULL,
                created_at TEXT NOT NULL,
                updated_at TEXT NOT NULL
            )
            """
        )
        conn.execute(
            "CREATE INDEX IF NOT EXISTS idx_conversations_owner ON conversations(owner_token)"
        )
        conn.execute(
            """
            CREATE TABLE IF NOT EXISTS messages (
                id TEXT PRIMARY KEY,
                conversation_id TEXT NOT NULL,
                role TEXT NOT NULL,
                content TEXT NOT NULL,
                created_at TEXT NOT NULL,
                pinned INTEGER NOT NULL DEFAULT 0,
                reply_to_id TEXT,
                image_url TEXT,
                FOREIGN KEY (conversation_id) REFERENCES conversations(id) ON DELETE CASCADE,
                FOREIGN KEY (reply_to_id) REFERENCES messages(id) ON DELETE SET NULL
            )
            """
        )
        # Backfill columns for databases created before pin/reply/image support existed.
        existing_columns = {
            row["name"] for row in conn.execute("PRAGMA table_info(messages)").fetchall()
        }
        if "pinned" not in existing_columns:
            conn.execute("ALTER TABLE messages ADD COLUMN pinned INTEGER NOT NULL DEFAULT 0")
        if "reply_to_id" not in existing_columns:
            conn.execute("ALTER TABLE messages ADD COLUMN reply_to_id TEXT")
        if "image_url" not in existing_columns:
            conn.execute("ALTER TABLE messages ADD COLUMN image_url TEXT")


def create_conversation(owner_token: str, title: str = "New conversation") -> dict:
    conv_id = str(uuid.uuid4())
    now = _now()
    with _lock, _connect() as conn:
        conn.execute(
            "INSERT INTO conversations (id, owner_token, title, created_at, updated_at) VALUES (?, ?, ?, ?, ?)",
            (conv_id, owner_token, title, now, now),
        )
    return {"id": conv_id, "title": title, "created_at": now, "updated_at": now}


def list_conversations(owner_token: str) -> list[dict]:
    with _lock, _connect() as conn:
        rows = conn.execute(
            """
            SELECT c.id, c.title, c.created_at, c.updated_at,
                   (SELECT content FROM messages m WHERE m.conversation_id = c.id
                    ORDER BY m.created_at DESC LIMIT 1) AS preview
            FROM conversations c
            WHERE c.owner_token = ?
            ORDER BY c.updated_at DESC
            """,
            (owner_token,),
        ).fetchall()
        return [dict(row) for row in rows]


def get_conversation(conversation_id: str, owner_token: str) -> Optional[dict]:
    with _lock, _connect() as conn:
        row = conn.execute(
            "SELECT id, title, created_at, updated_at FROM conversations WHERE id = ? AND owner_token = ?",
            (conversation_id, owner_token),
        ).fetchone()
        return dict(row) if row else None


def rename_conversation(conversation_id: str, owner_token: str, title: str) -> Optional[dict]:
    with _lock, _connect() as conn:
        conn.execute(
            "UPDATE conversations SET title = ?, updated_at = ? WHERE id = ? AND owner_token = ?",
            (title, _now(), conversation_id, owner_token),
        )
    return get_conversation(conversation_id, owner_token)


def delete_conversation(conversation_id: str, owner_token: str) -> bool:
    with _lock, _connect() as conn:
        cur = conn.execute(
            "DELETE FROM conversations WHERE id = ? AND owner_token = ?",
            (conversation_id, owner_token),
        )
        return cur.rowcount > 0


def _row_to_message(row: sqlite3.Row) -> dict:
    return {
        "id": row["id"],
        "conversation_id": row["conversation_id"],
        "role": row["role"],
        "content": row["content"],
        "created_at": row["created_at"],
        "pinned": bool(row["pinned"]),
        "reply_to_id": row["reply_to_id"],
        "image_url": row["image_url"],
    }


def get_messages(conversation_id: str) -> list[dict]:
    with _lock, _connect() as conn:
        rows = conn.execute(
            "SELECT id, conversation_id, role, content, created_at, pinned, reply_to_id, image_url "
            "FROM messages WHERE conversation_id = ? ORDER BY created_at ASC",
            (conversation_id,),
        ).fetchall()
        return [_row_to_message(row) for row in rows]


def add_message(
    conversation_id: str,
    role: str,
    content: str,
    reply_to_id: Optional[str] = None,
    image_url: Optional[str] = None,
) -> dict:
    msg_id = str(uuid.uuid4())
    now = _now()
    with _lock, _connect() as conn:
        # A reply target must belong to the same conversation, otherwise
        # silently drop the reference instead of trusting client input.
        if reply_to_id is not None:
            target = conn.execute(
                "SELECT id FROM messages WHERE id = ? AND conversation_id = ?",
                (reply_to_id, conversation_id),
            ).fetchone()
            if target is None:
                reply_to_id = None

        conn.execute(
            "INSERT INTO messages (id, conversation_id, role, content, created_at, pinned, reply_to_id, image_url) "
            "VALUES (?, ?, ?, ?, ?, 0, ?, ?)",
            (msg_id, conversation_id, role, content, now, reply_to_id, image_url),
        )
        conn.execute(
            "UPDATE conversations SET updated_at = ? WHERE id = ?",
            (now, conversation_id),
        )
    return {
        "id": msg_id,
        "conversation_id": conversation_id,
        "role": role,
        "content": content,
        "created_at": now,
        "pinned": False,
        "reply_to_id": reply_to_id,
        "image_url": image_url,
    }


def set_message_pinned(conversation_id: str, message_id: str, pinned: bool) -> Optional[dict]:
    with _lock, _connect() as conn:
        cur = conn.execute(
            "UPDATE messages SET pinned = ? WHERE id = ? AND conversation_id = ?",
            (1 if pinned else 0, message_id, conversation_id),
        )
        if cur.rowcount == 0:
            return None
        row = conn.execute(
            "SELECT id, conversation_id, role, content, created_at, pinned, reply_to_id, image_url "
            "FROM messages WHERE id = ?",
            (message_id,),
        ).fetchone()
        return _row_to_message(row) if row else None
