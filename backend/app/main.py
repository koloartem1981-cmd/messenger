from __future__ import annotations

import asyncio
import json
import os
import secrets
import uuid
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Annotated, Optional

from fastapi import (
    Depends,
    FastAPI,
    File,
    Form,
    HTTPException,
    Query,
    UploadFile,
    WebSocket,
    WebSocketDisconnect,
    status,
)
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import FileResponse
from fastapi.security import OAuth2PasswordBearer
import bcrypt
from jose import JWTError, jwt
from PIL import Image
from pydantic import BaseModel, Field
from sqlalchemy import (
    DateTime,
    ForeignKey,
    Integer,
    String,
    Text,
    UniqueConstraint,
    and_,
    create_engine,
    or_,
    select,
    text as sql_text,
)
from sqlalchemy.orm import (
    DeclarativeBase,
    Mapped,
    Session,
    mapped_column,
    sessionmaker,
)

# ---------- Paths ----------

DATA_DIR_ENV = os.environ.get("MESSENGER_DATA_DIR")
if DATA_DIR_ENV:
    DATA_DIR = Path(DATA_DIR_ENV)
elif Path("/data").exists() and os.access("/data", os.W_OK):
    DATA_DIR = Path("/data")
else:
    DATA_DIR = Path(os.environ.get("MESSENGER_LOCAL_DATA_DIR", "./_data"))
DATA_DIR.mkdir(parents=True, exist_ok=True)

AVATAR_DIR = DATA_DIR / "avatars"
AVATAR_DIR.mkdir(parents=True, exist_ok=True)

CHAT_AVATAR_DIR = DATA_DIR / "chat_avatars"
CHAT_AVATAR_DIR.mkdir(parents=True, exist_ok=True)

MEDIA_DIR = DATA_DIR / "media"
MEDIA_DIR.mkdir(parents=True, exist_ok=True)

DB_PATH = DATA_DIR / "app.db"
DATABASE_URL = f"sqlite:///{DB_PATH}"

# ---------- Auth secret ----------

# Read from env, or fall back to a file inside DATA_DIR so tokens survive
# container restarts even if the operator forgets to pin the env var.
SECRET_KEY_FILE = DATA_DIR / "secret_key.txt"


def _load_or_create_secret_key() -> str:
    env = os.environ.get("MESSENGER_SECRET_KEY")
    if env:
        return env
    if SECRET_KEY_FILE.exists():
        textval = SECRET_KEY_FILE.read_text(encoding="utf-8").strip()
        if textval:
            return textval
    generated = secrets.token_urlsafe(64)
    SECRET_KEY_FILE.write_text(generated, encoding="utf-8")
    return generated


SECRET_KEY = _load_or_create_secret_key()
ALGORITHM = "HS256"
ACCESS_TOKEN_EXPIRE_DAYS = 30

# ---------- Limits ----------

MAX_AVATAR_BYTES = 5 * 1024 * 1024
MAX_MEDIA_BYTES = 50 * 1024 * 1024

MEDIA_KINDS = {"voice", "photo", "video", "file", "video_circle"}
CHAT_TYPES = {"group", "channel"}
MEMBER_ROLES = {"owner", "admin", "member"}

# ---------- DB ----------

engine = create_engine(
    DATABASE_URL,
    connect_args={"check_same_thread": False},
    future=True,
)
SessionLocal = sessionmaker(bind=engine, autoflush=False, autocommit=False, future=True)
oauth2_scheme = OAuth2PasswordBearer(tokenUrl="auth/login", auto_error=False)


class Base(DeclarativeBase):
    pass


class User(Base):
    __tablename__ = "users"

    id: Mapped[int] = mapped_column(Integer, primary_key=True)
    username: Mapped[str] = mapped_column(String(32), unique=True, index=True, nullable=False)
    display_name: Mapped[str] = mapped_column(String(64), nullable=False)
    password_hash: Mapped[str] = mapped_column(String(255), nullable=False)
    avatar_path: Mapped[Optional[str]] = mapped_column(String(255), nullable=True)
    bio: Mapped[Optional[str]] = mapped_column(String(255), nullable=True)
    created_at: Mapped[datetime] = mapped_column(
        DateTime, default=lambda: datetime.now(timezone.utc)
    )


class Contact(Base):
    __tablename__ = "contacts"
    __table_args__ = (UniqueConstraint("owner_id", "contact_id", name="uq_contact"),)

    id: Mapped[int] = mapped_column(Integer, primary_key=True)
    owner_id: Mapped[int] = mapped_column(ForeignKey("users.id"), index=True)
    contact_id: Mapped[int] = mapped_column(ForeignKey("users.id"), index=True)
    created_at: Mapped[datetime] = mapped_column(
        DateTime, default=lambda: datetime.now(timezone.utc)
    )


class Chat(Base):
    __tablename__ = "chats"

    id: Mapped[int] = mapped_column(Integer, primary_key=True)
    type: Mapped[str] = mapped_column(String(16), nullable=False)  # group | channel
    title: Mapped[str] = mapped_column(String(80), nullable=False)
    owner_id: Mapped[int] = mapped_column(ForeignKey("users.id"), index=True)
    avatar_path: Mapped[Optional[str]] = mapped_column(String(255), nullable=True)
    description: Mapped[Optional[str]] = mapped_column(String(255), nullable=True)
    created_at: Mapped[datetime] = mapped_column(
        DateTime, default=lambda: datetime.now(timezone.utc)
    )


class ChatMember(Base):
    __tablename__ = "chat_members"
    __table_args__ = (UniqueConstraint("chat_id", "user_id", name="uq_chat_member"),)

    id: Mapped[int] = mapped_column(Integer, primary_key=True)
    chat_id: Mapped[int] = mapped_column(ForeignKey("chats.id", ondelete="CASCADE"), index=True)
    user_id: Mapped[int] = mapped_column(ForeignKey("users.id"), index=True)
    role: Mapped[str] = mapped_column(String(16), default="member", nullable=False)
    joined_at: Mapped[datetime] = mapped_column(
        DateTime, default=lambda: datetime.now(timezone.utc)
    )


class Message(Base):
    __tablename__ = "messages"

    id: Mapped[int] = mapped_column(Integer, primary_key=True)
    sender_id: Mapped[int] = mapped_column(ForeignKey("users.id"), index=True)
    # For DM messages recipient_id > 0; for group/channel messages recipient_id == 0.
    recipient_id: Mapped[int] = mapped_column(Integer, default=0, nullable=False, index=True)
    chat_id: Mapped[Optional[int]] = mapped_column(
        ForeignKey("chats.id"), nullable=True, index=True
    )
    kind: Mapped[str] = mapped_column(String(16), nullable=False, default="text")
    content: Mapped[str] = mapped_column(Text, nullable=False, default="")
    media_uuid: Mapped[Optional[str]] = mapped_column(String(36), nullable=True)
    media_mime: Mapped[Optional[str]] = mapped_column(String(80), nullable=True)
    media_size: Mapped[Optional[int]] = mapped_column(Integer, nullable=True)
    media_duration_ms: Mapped[Optional[int]] = mapped_column(Integer, nullable=True)
    media_filename: Mapped[Optional[str]] = mapped_column(String(255), nullable=True)
    created_at: Mapped[datetime] = mapped_column(
        DateTime, default=lambda: datetime.now(timezone.utc), index=True
    )


Base.metadata.create_all(engine)


def _migrate_schema() -> None:
    """Apply additive SQLite migrations for older databases that pre-date the
    chats/chat_members tables and the messages.chat_id column."""
    with engine.begin() as conn:
        cols = {row[1] for row in conn.execute(sql_text("PRAGMA table_info(messages)"))}
        if "chat_id" not in cols:
            conn.execute(sql_text("ALTER TABLE messages ADD COLUMN chat_id INTEGER"))


_migrate_schema()

# ---------- Session dep ----------


def get_db():
    db = SessionLocal()
    try:
        yield db
    finally:
        db.close()


# ---------- Crypto / auth helpers ----------


def hash_password(password: str) -> str:
    pwd_bytes = password.encode("utf-8")[:72]
    return bcrypt.hashpw(pwd_bytes, bcrypt.gensalt()).decode("utf-8")


def verify_password(password: str, hashed: str) -> bool:
    try:
        pwd_bytes = password.encode("utf-8")[:72]
        return bcrypt.checkpw(pwd_bytes, hashed.encode("utf-8"))
    except Exception:
        return False


def create_access_token(user_id: int) -> str:
    expire = datetime.now(timezone.utc) + timedelta(days=ACCESS_TOKEN_EXPIRE_DAYS)
    payload = {"sub": str(user_id), "exp": expire}
    return jwt.encode(payload, SECRET_KEY, algorithm=ALGORITHM)


def decode_token(token: str) -> Optional[int]:
    try:
        payload = jwt.decode(token, SECRET_KEY, algorithms=[ALGORITHM])
        sub = payload.get("sub")
        if sub is None:
            return None
        return int(sub)
    except (JWTError, ValueError):
        return None


def get_current_user(
    token: Annotated[Optional[str], Depends(oauth2_scheme)],
    db: Annotated[Session, Depends(get_db)],
) -> User:
    if not token:
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="Not authenticated")
    user_id = decode_token(token)
    if user_id is None:
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="Invalid token")
    user = db.get(User, user_id)
    if user is None:
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="User not found")
    return user


# ---------- Serializers ----------


def user_to_public(u: User) -> dict:
    return {
        "id": u.id,
        "username": u.username,
        "display_name": u.display_name,
        "bio": u.bio,
        "avatar_url": f"/avatars/{u.id}" if u.avatar_path else None,
    }


def message_to_dict(m: Message) -> dict:
    created = m.created_at
    if created.tzinfo is None:
        created = created.replace(tzinfo=timezone.utc)
    out: dict = {
        "id": m.id,
        "sender_id": m.sender_id,
        "recipient_id": m.recipient_id or 0,
        "chat_id": m.chat_id,
        "kind": m.kind or "text",
        "content": m.content or "",
        "created_at": created.isoformat(),
    }
    if m.media_uuid:
        out["media_url"] = f"/media/{m.media_uuid}"
        out["media_mime"] = m.media_mime
        out["media_size"] = m.media_size
        out["media_duration_ms"] = m.media_duration_ms
        out["media_filename"] = m.media_filename
    return out


def chat_to_dict(c: Chat, current_user_id: int, members_count: Optional[int] = None) -> dict:
    return {
        "id": c.id,
        "type": c.type,
        "title": c.title,
        "owner_id": c.owner_id,
        "description": c.description,
        "avatar_url": f"/chat_avatars/{c.id}" if c.avatar_path else None,
        "is_owner": c.owner_id == current_user_id,
        "members_count": members_count,
        "created_at": (
            c.created_at.replace(tzinfo=timezone.utc) if c.created_at.tzinfo is None else c.created_at
        ).isoformat(),
    }


# ---------- App ----------


app = FastAPI(title="Messenger API")

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)


@app.get("/healthz")
async def healthz():
    return {"status": "ok"}


# ---------- Schemas ----------


class RegisterRequest(BaseModel):
    username: str = Field(min_length=3, max_length=32)
    display_name: str = Field(min_length=1, max_length=64)
    password: str = Field(min_length=6, max_length=128)


class LoginRequest(BaseModel):
    username: str
    password: str


class TokenResponse(BaseModel):
    access_token: str
    token_type: str = "bearer"
    user: dict


class UpdateProfileRequest(BaseModel):
    display_name: Optional[str] = Field(default=None, max_length=64)
    bio: Optional[str] = Field(default=None, max_length=255)


class SendMessageRequest(BaseModel):
    recipient_id: int
    content: str = Field(min_length=1, max_length=4000)


class SendChatMessageRequest(BaseModel):
    content: str = Field(min_length=1, max_length=4000)


class AddContactRequest(BaseModel):
    username: Optional[str] = None
    user_id: Optional[int] = None


class CreateChatRequest(BaseModel):
    type: str = Field(pattern="^(group|channel)$")
    title: str = Field(min_length=1, max_length=80)
    description: Optional[str] = Field(default=None, max_length=255)
    member_ids: list[int] = Field(default_factory=list)


class UpdateChatRequest(BaseModel):
    title: Optional[str] = Field(default=None, min_length=1, max_length=80)
    description: Optional[str] = Field(default=None, max_length=255)


class AddMemberRequest(BaseModel):
    user_id: Optional[int] = None
    username: Optional[str] = None
    role: str = Field(default="member", pattern="^(admin|member)$")


# ---------- Helpers ----------


def _normalize_username(username: str) -> str:
    return username.strip().lower()


def _ensure_mutual_contacts(db: Session, a: int, b: int) -> None:
    for owner_id, other_id in ((a, b), (b, a)):
        exists = db.scalar(
            select(Contact).where(
                and_(Contact.owner_id == owner_id, Contact.contact_id == other_id)
            )
        )
        if not exists:
            db.add(Contact(owner_id=owner_id, contact_id=other_id))


def _connect_everyone(db: Session, new_user: User) -> None:
    """Make a brand-new user mutually visible to all existing users so every
    registered account shows up in everyone's chat list without an explicit
    search step."""
    others = db.scalars(select(User).where(User.id != new_user.id)).all()
    for other in others:
        _ensure_mutual_contacts(db, new_user.id, other.id)


def _is_member(db: Session, chat_id: int, user_id: int) -> Optional[ChatMember]:
    return db.scalar(
        select(ChatMember).where(
            and_(ChatMember.chat_id == chat_id, ChatMember.user_id == user_id)
        )
    )


def _can_post_to_chat(chat: Chat, membership: Optional[ChatMember], user_id: int) -> bool:
    if membership is None:
        return False
    if chat.type == "channel":
        return chat.owner_id == user_id or membership.role in {"owner", "admin"}
    return True


def _chat_members(db: Session, chat_id: int) -> list[tuple[ChatMember, User]]:
    rows = db.execute(
        select(ChatMember, User)
        .join(User, User.id == ChatMember.user_id)
        .where(ChatMember.chat_id == chat_id)
        .order_by(User.display_name)
    ).all()
    return [(m, u) for m, u in rows]


def _ext_for_mime(mime: Optional[str], fallback: str = "bin") -> str:
    if not mime:
        return fallback
    mime_main = mime.split(";")[0].strip().lower()
    return {
        "image/jpeg": "jpg",
        "image/jpg": "jpg",
        "image/png": "png",
        "image/webp": "webp",
        "image/gif": "gif",
        "video/mp4": "mp4",
        "video/quicktime": "mov",
        "video/webm": "webm",
        "audio/mp4": "m4a",
        "audio/aac": "aac",
        "audio/mpeg": "mp3",
        "audio/ogg": "ogg",
        "audio/wav": "wav",
        "audio/webm": "webm",
    }.get(mime_main, fallback)


# ---------- Auth ----------


@app.post("/auth/register", response_model=TokenResponse)
async def register(req: RegisterRequest, db: Annotated[Session, Depends(get_db)]):
    username = _normalize_username(req.username)
    if not username.replace("_", "").isalnum():
        raise HTTPException(status_code=400, detail="Username must be alphanumeric or underscore")
    existing = db.scalar(select(User).where(User.username == username))
    if existing:
        raise HTTPException(status_code=409, detail="Username already taken")
    user = User(
        username=username,
        display_name=req.display_name.strip(),
        password_hash=hash_password(req.password),
    )
    db.add(user)
    db.commit()
    db.refresh(user)
    _connect_everyone(db, user)
    db.commit()
    token = create_access_token(user.id)
    return TokenResponse(access_token=token, user=user_to_public(user))


@app.post("/auth/login", response_model=TokenResponse)
async def login(req: LoginRequest, db: Annotated[Session, Depends(get_db)]):
    username = _normalize_username(req.username)
    user = db.scalar(select(User).where(User.username == username))
    if not user or not verify_password(req.password, user.password_hash):
        raise HTTPException(status_code=401, detail="Invalid credentials")
    # Make sure the everyone-sees-everyone invariant holds even for accounts that
    # registered before this feature shipped.
    _connect_everyone(db, user)
    db.commit()
    token = create_access_token(user.id)
    return TokenResponse(access_token=token, user=user_to_public(user))


@app.get("/me")
async def me(current: Annotated[User, Depends(get_current_user)]):
    return user_to_public(current)


@app.patch("/me")
async def update_me(
    req: UpdateProfileRequest,
    current: Annotated[User, Depends(get_current_user)],
    db: Annotated[Session, Depends(get_db)],
):
    if req.display_name is not None:
        dn = req.display_name.strip()
        if not dn:
            raise HTTPException(status_code=400, detail="display_name cannot be empty")
        current.display_name = dn
    if req.bio is not None:
        current.bio = req.bio.strip() or None
    db.add(current)
    db.commit()
    db.refresh(current)
    return user_to_public(current)


@app.post("/me/avatar")
async def upload_avatar(
    current: Annotated[User, Depends(get_current_user)],
    db: Annotated[Session, Depends(get_db)],
    file: UploadFile = File(...),
):
    if file.content_type not in {"image/jpeg", "image/png", "image/webp"}:
        raise HTTPException(status_code=400, detail="Unsupported image format")
    raw = await file.read()
    if len(raw) > MAX_AVATAR_BYTES:
        raise HTTPException(status_code=413, detail="Image too large (max 5MB)")
    out_path = AVATAR_DIR / f"{current.id}.jpg"
    try:
        from io import BytesIO

        img = Image.open(BytesIO(raw)).convert("RGB")
        img.thumbnail((512, 512))
        img.save(out_path, format="JPEG", quality=85)
    except Exception:
        raise HTTPException(status_code=400, detail="Could not decode image")
    current.avatar_path = str(out_path)
    db.add(current)
    db.commit()
    db.refresh(current)
    return user_to_public(current)


@app.get("/avatars/{user_id}")
async def get_avatar(user_id: int, db: Annotated[Session, Depends(get_db)]):
    user = db.get(User, user_id)
    if not user or not user.avatar_path:
        raise HTTPException(status_code=404, detail="No avatar")
    p = Path(user.avatar_path)
    if not p.exists():
        raise HTTPException(status_code=404, detail="No avatar")
    return FileResponse(p, media_type="image/jpeg")


# ---------- Users / Contacts ----------


@app.get("/users")
async def list_all_users(
    current: Annotated[User, Depends(get_current_user)],
    db: Annotated[Session, Depends(get_db)],
):
    rows = db.scalars(
        select(User).where(User.id != current.id).order_by(User.display_name)
    ).all()
    return [user_to_public(u) for u in rows]


@app.get("/users/search")
async def search_users(
    q: Annotated[str, Query(min_length=1, max_length=64)],
    current: Annotated[User, Depends(get_current_user)],
    db: Annotated[Session, Depends(get_db)],
):
    pattern = f"%{q.strip().lower()}%"
    rows = db.scalars(
        select(User)
        .where(
            and_(
                User.id != current.id,
                or_(User.username.like(pattern), User.display_name.like(pattern)),
            )
        )
        .limit(20)
    ).all()
    return [user_to_public(u) for u in rows]


@app.get("/contacts")
async def list_contacts(
    current: Annotated[User, Depends(get_current_user)],
    db: Annotated[Session, Depends(get_db)],
):
    rows = (
        db.execute(
            select(User)
            .join(Contact, Contact.contact_id == User.id)
            .where(Contact.owner_id == current.id)
            .order_by(User.display_name)
        )
        .scalars()
        .all()
    )
    return [user_to_public(u) for u in rows]


@app.post("/contacts")
async def add_contact(
    req: AddContactRequest,
    current: Annotated[User, Depends(get_current_user)],
    db: Annotated[Session, Depends(get_db)],
):
    target: Optional[User] = None
    if req.user_id is not None:
        target = db.get(User, req.user_id)
    elif req.username:
        target = db.scalar(
            select(User).where(User.username == _normalize_username(req.username))
        )
    if not target:
        raise HTTPException(status_code=404, detail="User not found")
    if target.id == current.id:
        raise HTTPException(status_code=400, detail="Cannot add yourself")
    existing = db.scalar(
        select(Contact).where(
            and_(Contact.owner_id == current.id, Contact.contact_id == target.id)
        )
    )
    if not existing:
        db.add(Contact(owner_id=current.id, contact_id=target.id))
        db.commit()
    return user_to_public(target)


@app.delete("/contacts/{user_id}")
async def remove_contact(
    user_id: int,
    current: Annotated[User, Depends(get_current_user)],
    db: Annotated[Session, Depends(get_db)],
):
    row = db.scalar(
        select(Contact).where(
            and_(Contact.owner_id == current.id, Contact.contact_id == user_id)
        )
    )
    if row:
        db.delete(row)
        db.commit()
    return {"ok": True}


@app.get("/users/{user_id}")
async def get_user(
    user_id: int,
    current: Annotated[User, Depends(get_current_user)],
    db: Annotated[Session, Depends(get_db)],
):
    u = db.get(User, user_id)
    if not u:
        raise HTTPException(status_code=404, detail="User not found")
    return user_to_public(u)


# ---------- Chats overview ----------


@app.get("/chats")
async def list_chats(
    current: Annotated[User, Depends(get_current_user)],
    db: Annotated[Session, Depends(get_db)],
):
    """Return every conversation the user can step into: every other registered
    user (DM) plus every group/channel they are a member of, sorted by the most
    recent message in each thread."""

    # DM previews: every other user, with their most-recent shared message (if any).
    other_users = db.scalars(
        select(User).where(User.id != current.id).order_by(User.display_name)
    ).all()
    last_dm: dict[int, Message] = {}
    dm_msgs = db.scalars(
        select(Message)
        .where(
            and_(
                Message.chat_id.is_(None),
                or_(
                    Message.sender_id == current.id,
                    Message.recipient_id == current.id,
                ),
            )
        )
        .order_by(Message.created_at.desc())
    ).all()
    for m in dm_msgs:
        peer_id = m.recipient_id if m.sender_id == current.id else m.sender_id
        if peer_id and peer_id not in last_dm:
            last_dm[peer_id] = m

    entries: list[dict] = []
    for u in other_users:
        m = last_dm.get(u.id)
        entries.append(
            {
                "kind": "dm",
                "peer": user_to_public(u),
                "chat": None,
                "last_message": message_to_dict(m) if m else None,
                "sort_key": (m.created_at if m else None),
            }
        )

    # Group/channel previews.
    memberships = db.scalars(
        select(ChatMember).where(ChatMember.user_id == current.id)
    ).all()
    for mem in memberships:
        chat = db.get(Chat, mem.chat_id)
        if not chat:
            continue
        last_msg = db.scalar(
            select(Message)
            .where(Message.chat_id == chat.id)
            .order_by(Message.created_at.desc())
            .limit(1)
        )
        member_count = db.scalar(
            select(ChatMember).where(ChatMember.chat_id == chat.id).order_by(ChatMember.id)
        )
        count = (
            db.execute(
                select(ChatMember.id).where(ChatMember.chat_id == chat.id)
            ).all().__len__()
        )
        entries.append(
            {
                "kind": chat.type,
                "peer": None,
                "chat": chat_to_dict(chat, current.id, members_count=count),
                "last_message": message_to_dict(last_msg) if last_msg else None,
                "sort_key": last_msg.created_at if last_msg else chat.created_at,
            }
        )
        _ = member_count  # silence unused

    def _key(e: dict):
        sk = e.get("sort_key")
        # Most-recent first; entries with no messages fall to the bottom but stay
        # before the user has any interaction.
        return (1 if sk is not None else 0, sk or datetime.min.replace(tzinfo=timezone.utc))

    entries.sort(key=_key, reverse=True)
    for e in entries:
        e.pop("sort_key", None)
    return entries


# ---------- DM messages ----------


@app.get("/messages/{peer_id}")
async def list_messages(
    peer_id: int,
    current: Annotated[User, Depends(get_current_user)],
    db: Annotated[Session, Depends(get_db)],
    limit: int = 200,
):
    rows = db.scalars(
        select(Message)
        .where(
            and_(
                Message.chat_id.is_(None),
                or_(
                    and_(Message.sender_id == current.id, Message.recipient_id == peer_id),
                    and_(Message.sender_id == peer_id, Message.recipient_id == current.id),
                ),
            )
        )
        .order_by(Message.created_at.asc())
        .limit(limit)
    ).all()
    return [message_to_dict(m) for m in rows]


@app.post("/messages")
async def send_message(
    req: SendMessageRequest,
    current: Annotated[User, Depends(get_current_user)],
    db: Annotated[Session, Depends(get_db)],
):
    recipient = db.get(User, req.recipient_id)
    if recipient is None:
        raise HTTPException(status_code=404, detail="Recipient not found")
    msg = Message(
        sender_id=current.id,
        recipient_id=recipient.id,
        chat_id=None,
        kind="text",
        content=req.content.strip(),
    )
    db.add(msg)
    db.commit()
    db.refresh(msg)
    payload = message_to_dict(msg)
    _ensure_mutual_contacts(db, current.id, recipient.id)
    db.commit()
    await ws_manager.broadcast(recipient.id, {"type": "message", "data": payload})
    await ws_manager.broadcast(current.id, {"type": "message", "data": payload})
    return payload


@app.post("/messages/media")
async def send_media_message(
    current: Annotated[User, Depends(get_current_user)],
    db: Annotated[Session, Depends(get_db)],
    file: UploadFile = File(...),
    recipient_id: int = Form(...),
    kind: str = Form(...),
    duration_ms: Optional[int] = Form(default=None),
    caption: Optional[str] = Form(default=None),
):
    if kind not in MEDIA_KINDS:
        raise HTTPException(status_code=400, detail=f"Unsupported kind: {kind}")
    recipient = db.get(User, recipient_id)
    if recipient is None:
        raise HTTPException(status_code=404, detail="Recipient not found")
    if recipient.id == current.id:
        raise HTTPException(status_code=400, detail="Cannot send media to yourself")

    raw = await file.read()
    size = len(raw)
    if size == 0:
        raise HTTPException(status_code=400, detail="Empty file")
    if size > MAX_MEDIA_BYTES:
        raise HTTPException(status_code=413, detail="Media too large (max 50MB)")

    mime = file.content_type or "application/octet-stream"
    ext = _ext_for_mime(
        mime,
        fallback=(Path(file.filename or "").suffix.lstrip(".") or "bin").lower(),
    )
    media_uuid = uuid.uuid4().hex
    out_path = MEDIA_DIR / f"{media_uuid}.{ext}"
    out_path.write_bytes(raw)

    original_name: Optional[str] = None
    if kind == "file":
        original_name = (file.filename or "").strip() or None

    msg = Message(
        sender_id=current.id,
        recipient_id=recipient.id,
        chat_id=None,
        kind=kind,
        content=(caption or "").strip(),
        media_uuid=media_uuid,
        media_mime=mime,
        media_size=size,
        media_duration_ms=duration_ms if duration_ms and duration_ms > 0 else None,
        media_filename=original_name,
    )
    db.add(msg)
    db.commit()
    db.refresh(msg)
    payload = message_to_dict(msg)
    _ensure_mutual_contacts(db, current.id, recipient.id)
    db.commit()
    await ws_manager.broadcast(recipient.id, {"type": "message", "data": payload})
    await ws_manager.broadcast(current.id, {"type": "message", "data": payload})
    return payload


@app.get("/media/{media_uuid}")
async def get_media(media_uuid: str, db: Annotated[Session, Depends(get_db)]):
    # The UUID itself is the capability — unguessable, ties the URL to a single
    # message, similar to an S3 presigned URL.
    if len(media_uuid) != 32 or not all(c in "0123456789abcdef" for c in media_uuid):
        raise HTTPException(status_code=404, detail="Not found")
    msg = db.scalar(select(Message).where(Message.media_uuid == media_uuid))
    if not msg:
        raise HTTPException(status_code=404, detail="Not found")
    matches = list(MEDIA_DIR.glob(f"{media_uuid}.*"))
    if not matches:
        raise HTTPException(status_code=404, detail="Not found")
    p = matches[0]
    media_type = msg.media_mime or "application/octet-stream"
    headers: dict = {}
    if msg.kind == "file" and msg.media_filename:
        safe_name = msg.media_filename.replace('"', "")
        headers["Content-Disposition"] = f'attachment; filename="{safe_name}"'
    return FileResponse(p, media_type=media_type, headers=headers)


# ---------- Group / Channel chats ----------


@app.post("/chats")
async def create_chat(
    req: CreateChatRequest,
    current: Annotated[User, Depends(get_current_user)],
    db: Annotated[Session, Depends(get_db)],
):
    if req.type not in CHAT_TYPES:
        raise HTTPException(status_code=400, detail="Invalid chat type")
    chat = Chat(
        type=req.type,
        title=req.title.strip(),
        owner_id=current.id,
        description=(req.description or "").strip() or None,
    )
    db.add(chat)
    db.commit()
    db.refresh(chat)
    db.add(ChatMember(chat_id=chat.id, user_id=current.id, role="owner"))
    seen = {current.id}
    for uid in req.member_ids:
        if uid in seen:
            continue
        user = db.get(User, uid)
        if user is None:
            continue
        db.add(ChatMember(chat_id=chat.id, user_id=uid, role="member"))
        seen.add(uid)
    db.commit()
    member_count = (
        db.execute(select(ChatMember.id).where(ChatMember.chat_id == chat.id)).all().__len__()
    )
    payload = chat_to_dict(chat, current.id, members_count=member_count)
    # Tell every member their chat list changed.
    notice = {"type": "chat_created", "data": payload}
    for uid in seen:
        await ws_manager.broadcast(uid, notice)
    return payload


@app.get("/chats/{chat_id}")
async def get_chat(
    chat_id: int,
    current: Annotated[User, Depends(get_current_user)],
    db: Annotated[Session, Depends(get_db)],
):
    chat = db.get(Chat, chat_id)
    if chat is None:
        raise HTTPException(status_code=404, detail="Chat not found")
    membership = _is_member(db, chat_id, current.id)
    if membership is None:
        raise HTTPException(status_code=403, detail="Not a member of this chat")
    members = _chat_members(db, chat_id)
    payload = chat_to_dict(chat, current.id, members_count=len(members))
    payload["my_role"] = membership.role
    payload["can_post"] = _can_post_to_chat(chat, membership, current.id)
    payload["members"] = [
        {
            "user": user_to_public(u),
            "role": m.role,
        }
        for m, u in members
    ]
    return payload


@app.patch("/chats/{chat_id}")
async def update_chat(
    chat_id: int,
    req: UpdateChatRequest,
    current: Annotated[User, Depends(get_current_user)],
    db: Annotated[Session, Depends(get_db)],
):
    chat = db.get(Chat, chat_id)
    if chat is None:
        raise HTTPException(status_code=404, detail="Chat not found")
    membership = _is_member(db, chat_id, current.id)
    if membership is None or (chat.owner_id != current.id and membership.role != "admin"):
        raise HTTPException(status_code=403, detail="Only owner or admin can edit chat")
    if req.title is not None:
        t = req.title.strip()
        if not t:
            raise HTTPException(status_code=400, detail="title cannot be empty")
        chat.title = t
    if req.description is not None:
        chat.description = req.description.strip() or None
    db.add(chat)
    db.commit()
    db.refresh(chat)
    member_count = (
        db.execute(select(ChatMember.id).where(ChatMember.chat_id == chat.id)).all().__len__()
    )
    payload = chat_to_dict(chat, current.id, members_count=member_count)
    notice = {"type": "chat_updated", "data": payload}
    for m, _u in _chat_members(db, chat_id):
        await ws_manager.broadcast(m.user_id, notice)
    return payload


@app.delete("/chats/{chat_id}")
async def delete_chat(
    chat_id: int,
    current: Annotated[User, Depends(get_current_user)],
    db: Annotated[Session, Depends(get_db)],
):
    chat = db.get(Chat, chat_id)
    if chat is None:
        raise HTTPException(status_code=404, detail="Chat not found")
    if chat.owner_id != current.id:
        raise HTTPException(status_code=403, detail="Only owner can delete chat")
    member_ids = [m.user_id for m, _u in _chat_members(db, chat_id)]
    # Cascade through chat_members then drop the chat.
    db.execute(sql_text("DELETE FROM chat_members WHERE chat_id = :c"), {"c": chat_id})
    db.execute(sql_text("DELETE FROM messages WHERE chat_id = :c"), {"c": chat_id})
    db.delete(chat)
    db.commit()
    notice = {"type": "chat_deleted", "data": {"chat_id": chat_id}}
    for uid in member_ids:
        await ws_manager.broadcast(uid, notice)
    return {"ok": True}


@app.post("/chats/{chat_id}/members")
async def add_chat_member(
    chat_id: int,
    req: AddMemberRequest,
    current: Annotated[User, Depends(get_current_user)],
    db: Annotated[Session, Depends(get_db)],
):
    chat = db.get(Chat, chat_id)
    if chat is None:
        raise HTTPException(status_code=404, detail="Chat not found")
    membership = _is_member(db, chat_id, current.id)
    if membership is None or (chat.owner_id != current.id and membership.role != "admin"):
        raise HTTPException(status_code=403, detail="Only owner or admin can add members")
    target: Optional[User] = None
    if req.user_id is not None:
        target = db.get(User, req.user_id)
    elif req.username:
        target = db.scalar(
            select(User).where(User.username == _normalize_username(req.username))
        )
    if not target:
        raise HTTPException(status_code=404, detail="User not found")
    existing = _is_member(db, chat_id, target.id)
    if existing:
        raise HTTPException(status_code=409, detail="User is already a member")
    role = "admin" if req.role == "admin" else "member"
    db.add(ChatMember(chat_id=chat_id, user_id=target.id, role=role))
    db.commit()
    member_count = (
        db.execute(select(ChatMember.id).where(ChatMember.chat_id == chat_id)).all().__len__()
    )
    payload = chat_to_dict(chat, current.id, members_count=member_count)
    notice = {"type": "chat_member_added", "data": payload}
    await ws_manager.broadcast(target.id, notice)
    for m, _u in _chat_members(db, chat_id):
        await ws_manager.broadcast(m.user_id, notice)
    return {"chat": payload, "user": user_to_public(target), "role": role}


@app.delete("/chats/{chat_id}/members/{user_id}")
async def remove_chat_member(
    chat_id: int,
    user_id: int,
    current: Annotated[User, Depends(get_current_user)],
    db: Annotated[Session, Depends(get_db)],
):
    chat = db.get(Chat, chat_id)
    if chat is None:
        raise HTTPException(status_code=404, detail="Chat not found")
    membership = _is_member(db, chat_id, current.id)
    if membership is None:
        raise HTTPException(status_code=403, detail="Not a member")
    if user_id == chat.owner_id:
        raise HTTPException(status_code=400, detail="Cannot remove owner")
    # Anyone can leave themselves; owner/admin can kick others.
    is_self = user_id == current.id
    if not is_self and chat.owner_id != current.id and membership.role != "admin":
        raise HTTPException(status_code=403, detail="Only owner or admin can remove members")
    row = _is_member(db, chat_id, user_id)
    if row is None:
        return {"ok": True}
    db.delete(row)
    db.commit()
    member_count = (
        db.execute(select(ChatMember.id).where(ChatMember.chat_id == chat_id)).all().__len__()
    )
    notice = {
        "type": "chat_member_removed",
        "data": {"chat_id": chat_id, "user_id": user_id, "members_count": member_count},
    }
    await ws_manager.broadcast(user_id, notice)
    for m, _u in _chat_members(db, chat_id):
        await ws_manager.broadcast(m.user_id, notice)
    return {"ok": True}


@app.get("/chats/{chat_id}/messages")
async def list_chat_messages(
    chat_id: int,
    current: Annotated[User, Depends(get_current_user)],
    db: Annotated[Session, Depends(get_db)],
    limit: int = 200,
):
    chat = db.get(Chat, chat_id)
    if chat is None:
        raise HTTPException(status_code=404, detail="Chat not found")
    if _is_member(db, chat_id, current.id) is None:
        raise HTTPException(status_code=403, detail="Not a member of this chat")
    rows = db.scalars(
        select(Message)
        .where(Message.chat_id == chat_id)
        .order_by(Message.created_at.asc())
        .limit(limit)
    ).all()
    return [message_to_dict(m) for m in rows]


@app.post("/chats/{chat_id}/messages")
async def send_chat_message(
    chat_id: int,
    req: SendChatMessageRequest,
    current: Annotated[User, Depends(get_current_user)],
    db: Annotated[Session, Depends(get_db)],
):
    chat = db.get(Chat, chat_id)
    if chat is None:
        raise HTTPException(status_code=404, detail="Chat not found")
    membership = _is_member(db, chat_id, current.id)
    if not _can_post_to_chat(chat, membership, current.id):
        raise HTTPException(status_code=403, detail="Not allowed to post in this chat")
    msg = Message(
        sender_id=current.id,
        recipient_id=0,
        chat_id=chat_id,
        kind="text",
        content=req.content.strip(),
    )
    db.add(msg)
    db.commit()
    db.refresh(msg)
    payload = message_to_dict(msg)
    notice = {"type": "message", "data": payload}
    for m, _u in _chat_members(db, chat_id):
        await ws_manager.broadcast(m.user_id, notice)
    return payload


@app.post("/chats/{chat_id}/messages/media")
async def send_chat_media_message(
    chat_id: int,
    current: Annotated[User, Depends(get_current_user)],
    db: Annotated[Session, Depends(get_db)],
    file: UploadFile = File(...),
    kind: str = Form(...),
    duration_ms: Optional[int] = Form(default=None),
    caption: Optional[str] = Form(default=None),
):
    if kind not in MEDIA_KINDS:
        raise HTTPException(status_code=400, detail=f"Unsupported kind: {kind}")
    chat = db.get(Chat, chat_id)
    if chat is None:
        raise HTTPException(status_code=404, detail="Chat not found")
    membership = _is_member(db, chat_id, current.id)
    if not _can_post_to_chat(chat, membership, current.id):
        raise HTTPException(status_code=403, detail="Not allowed to post in this chat")

    raw = await file.read()
    size = len(raw)
    if size == 0:
        raise HTTPException(status_code=400, detail="Empty file")
    if size > MAX_MEDIA_BYTES:
        raise HTTPException(status_code=413, detail="Media too large (max 50MB)")

    mime = file.content_type or "application/octet-stream"
    ext = _ext_for_mime(
        mime,
        fallback=(Path(file.filename or "").suffix.lstrip(".") or "bin").lower(),
    )
    media_uuid = uuid.uuid4().hex
    out_path = MEDIA_DIR / f"{media_uuid}.{ext}"
    out_path.write_bytes(raw)

    original_name: Optional[str] = None
    if kind == "file":
        original_name = (file.filename or "").strip() or None

    msg = Message(
        sender_id=current.id,
        recipient_id=0,
        chat_id=chat_id,
        kind=kind,
        content=(caption or "").strip(),
        media_uuid=media_uuid,
        media_mime=mime,
        media_size=size,
        media_duration_ms=duration_ms if duration_ms and duration_ms > 0 else None,
        media_filename=original_name,
    )
    db.add(msg)
    db.commit()
    db.refresh(msg)
    payload = message_to_dict(msg)
    notice = {"type": "message", "data": payload}
    for m, _u in _chat_members(db, chat_id):
        await ws_manager.broadcast(m.user_id, notice)
    return payload


@app.post("/chats/{chat_id}/avatar")
async def upload_chat_avatar(
    chat_id: int,
    current: Annotated[User, Depends(get_current_user)],
    db: Annotated[Session, Depends(get_db)],
    file: UploadFile = File(...),
):
    chat = db.get(Chat, chat_id)
    if chat is None:
        raise HTTPException(status_code=404, detail="Chat not found")
    membership = _is_member(db, chat_id, current.id)
    if membership is None or (chat.owner_id != current.id and membership.role != "admin"):
        raise HTTPException(status_code=403, detail="Only owner or admin can edit chat")
    if file.content_type not in {"image/jpeg", "image/png", "image/webp"}:
        raise HTTPException(status_code=400, detail="Unsupported image format")
    raw = await file.read()
    if len(raw) > MAX_AVATAR_BYTES:
        raise HTTPException(status_code=413, detail="Image too large (max 5MB)")
    out_path = CHAT_AVATAR_DIR / f"{chat.id}.jpg"
    try:
        from io import BytesIO

        img = Image.open(BytesIO(raw)).convert("RGB")
        img.thumbnail((512, 512))
        img.save(out_path, format="JPEG", quality=85)
    except Exception:
        raise HTTPException(status_code=400, detail="Could not decode image")
    chat.avatar_path = str(out_path)
    db.add(chat)
    db.commit()
    db.refresh(chat)
    member_count = (
        db.execute(select(ChatMember.id).where(ChatMember.chat_id == chat.id)).all().__len__()
    )
    return chat_to_dict(chat, current.id, members_count=member_count)


@app.get("/chat_avatars/{chat_id}")
async def get_chat_avatar(chat_id: int, db: Annotated[Session, Depends(get_db)]):
    chat = db.get(Chat, chat_id)
    if not chat or not chat.avatar_path:
        raise HTTPException(status_code=404, detail="No avatar")
    p = Path(chat.avatar_path)
    if not p.exists():
        raise HTTPException(status_code=404, detail="No avatar")
    return FileResponse(p, media_type="image/jpeg")


# ---------- WebSocket ----------


class WebSocketManager:
    def __init__(self) -> None:
        self.connections: dict[int, set[WebSocket]] = {}
        self.lock = asyncio.Lock()

    async def connect(self, user_id: int, ws: WebSocket) -> None:
        await ws.accept()
        async with self.lock:
            self.connections.setdefault(user_id, set()).add(ws)

    async def disconnect(self, user_id: int, ws: WebSocket) -> None:
        async with self.lock:
            conns = self.connections.get(user_id)
            if conns and ws in conns:
                conns.remove(ws)
                if not conns:
                    self.connections.pop(user_id, None)

    async def broadcast(self, user_id: int, payload: dict) -> None:
        async with self.lock:
            conns = list(self.connections.get(user_id, ()))
        for ws in conns:
            try:
                await ws.send_text(json.dumps(payload))
            except Exception:
                pass


ws_manager = WebSocketManager()


@app.websocket("/ws")
async def websocket_endpoint(ws: WebSocket, token: str = Query(...)):
    user_id = decode_token(token)
    if user_id is None:
        await ws.close(code=4401)
        return
    db = SessionLocal()
    try:
        user = db.get(User, user_id)
    finally:
        db.close()
    if user is None:
        await ws.close(code=4401)
        return
    await ws_manager.connect(user_id, ws)
    try:
        while True:
            data = await ws.receive_text()
            try:
                payload = json.loads(data)
            except Exception:
                continue
            if payload.get("type") == "ping":
                await ws.send_text(json.dumps({"type": "pong"}))
    except WebSocketDisconnect:
        await ws_manager.disconnect(user_id, ws)
    except Exception:
        await ws_manager.disconnect(user_id, ws)
