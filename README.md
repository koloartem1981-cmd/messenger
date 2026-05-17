# Messenger

Простой мессенджер: FastAPI бэкенд + Android клиент на Kotlin / Jetpack Compose / Material 3.

## Структура

- `backend/` — FastAPI + SQLAlchemy + JWT + WebSocket. Деплоится на Render (Docker).
- `android/` — Android app (Kotlin, Compose, Material 3). Собирается через Gradle.

## Что внутри

- Регистрация / логин (JWT, bcrypt)
- Поиск пользователей по username / display name
- Контакты (auto-add при отправке сообщения)
- 1-на-1 переписка с историей
- Realtime через WebSocket (`/ws?token=...`)
- Кастомные аватарки (multipart upload)

## Запуск бэкенда локально

```bash
cd backend
poetry install
poetry run uvicorn app.main:app --host 0.0.0.0 --port 8000
```

## Сборка APK

```bash
cd android
export ANDROID_HOME=/path/to/android-sdk
./gradlew assembleDebug
# APK: android/app/build/outputs/apk/debug/app-debug.apk
```

Backend URL зашивается через `gradle.properties`:
```
MESSENGER_BACKEND_URL=https://your-backend.onrender.com
```

## Деплой бэкенда

Файл `render.yaml` описывает Web Service на Render (Docker, free tier).
