from __future__ import annotations

import asyncio
import json
import os
import secrets
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Annotated, Optional

from fastapi import (
    Depends,
    FastAPI,
    File,
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
)
from sqlalchemy.orm import (
    DeclarativeBase,
    Mapped,
    Session,
    mapped_column,
    sessionmaker,
)

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

DB_PATH = DATA_DIR / "app.db"
DATABASE_URL = f"sqlite:///{DB_PATH}"

SECRET_KEY = os.environ.get("MESSENGER_SECRET_KEY") or secrets.token_urlsafe(64)
ALGORITHM = "HS256"
ACCESS_TOKEN_EXPIRE_DAYS = 30

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


class Message(Base):
    __tablename__ = "messages"

    id: Mapped[int] = mapped_column(Integer, primary_key=True)
    sender_id: Mapped[int] = mapped_column(ForeignKey("users.id"), index=True)
    recipient_id: Mapped[int] = mapped_column(ForeignKey("users.id"), index=True)
    content: Mapped[str] = mapped_column(Text, nullable=False)
    created_at: Mapped[datetime] = mapped_column(
        DateTime, default=lambda: datetime.now(timezone.utc), index=True
    )


Base.metadata.create_all(engine)


def get_db():
    db = SessionLocal()
    try:
        yield db
    finally:
        db.close()


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
    return {
        "id": m.id,
        "sender_id": m.sender_id,
        "recipient_id": m.recipient_id,
        "content": m.content,
        "created_at": created.isoformat(),
    }


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


class AddContactRequest(BaseModel):
    username: Optional[str] = None
    user_id: Optional[int] = None


# ---------- Helpers ----------


def _normalize_username(username: str) -> str:
    return username.strip().lower()


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
    token = create_access_token(user.id)
    return TokenResponse(access_token=token, user=user_to_public(user))


@app.post("/auth/login", response_model=TokenResponse)
async def login(req: LoginRequest, db: Annotated[Session, Depends(get_db)]):
    username = _normalize_username(req.username)
    user = db.scalar(select(User).where(User.username == username))
    if not user or not verify_password(req.password, user.password_hash):
        raise HTTPException(status_code=401, detail="Invalid credentials")
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
    if len(raw) > 5 * 1024 * 1024:
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


# ---------- Chats ----------


@app.get("/chats")
async def list_chats(
    current: Annotated[User, Depends(get_current_user)],
    db: Annotated[Session, Depends(get_db)],
):
    msgs = db.scalars(
        select(Message)
        .where(or_(Message.sender_id == current.id, Message.recipient_id == current.id))
        .order_by(Message.created_at.desc())
    ).all()
    chats: dict[int, dict] = {}
    for m in msgs:
        peer_id = m.recipient_id if m.sender_id == current.id else m.sender_id
        if peer_id in chats:
            continue
        peer = db.get(User, peer_id)
        if peer is None:
            continue
        chats[peer_id] = {
            "peer": user_to_public(peer),
            "last_message": message_to_dict(m),
        }
    contact_rows = (
        db.execute(
            select(User)
            .join(Contact, Contact.contact_id == User.id)
            .where(Contact.owner_id == current.id)
        )
        .scalars()
        .all()
    )
    for c in contact_rows:
        if c.id not in chats:
            chats[c.id] = {"peer": user_to_public(c), "last_message": None}
    return list(chats.values())


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
            or_(
                and_(Message.sender_id == current.id, Message.recipient_id == peer_id),
                and_(Message.sender_id == peer_id, Message.recipient_id == current.id),
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
        content=req.content.strip(),
    )
    db.add(msg)
    db.commit()
    db.refresh(msg)
    payload = message_to_dict(msg)
    for owner_id, other_id in ((current.id, recipient.id), (recipient.id, current.id)):
        exists = db.scalar(
            select(Contact).where(
                and_(Contact.owner_id == owner_id, Contact.contact_id == other_id)
            )
        )
        if not exists:
            db.add(Contact(owner_id=owner_id, contact_id=other_id))
    db.commit()
    await ws_manager.broadcast(recipient.id, {"type": "message", "data": payload})
    await ws_manager.broadcast(current.id, {"type": "message", "data": payload})
    return payload


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
