---
title: Messenger Backend
emoji: 💬
colorFrom: indigo
colorTo: pink
sdk: docker
app_port: 7860
pinned: false
license: mit
short_description: Free messenger backend (FastAPI + WebSocket + SQLite)
---

# Messenger Backend

Simple chat messenger backend deployed on Hugging Face Spaces.

## Endpoints

- `GET /healthz` — health check
- `POST /auth/register` — register `{username, display_name, password}`
- `POST /auth/login` — login `{username, password}`
- `GET /me` — current user
- `PATCH /me` — update display name / bio
- `POST /me/avatar` — upload avatar (multipart `file`)
- `GET /avatars/{user_id}` — fetch avatar
- `GET /users/search?q=...` — search users by username/display name
- `GET /contacts` / `POST /contacts` / `DELETE /contacts/{id}` — contacts
- `GET /chats` — chat list with peers and last message
- `GET /messages/{peer_id}` — chat history
- `POST /messages` — send `{recipient_id, content}`
- `WS /ws?token=...` — realtime delivery

Built with FastAPI, SQLite (file storage), JWT auth, and bcrypt password hashing.
