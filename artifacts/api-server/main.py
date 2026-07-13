"""Maya's API server — FastAPI backend with conversation memory.

Serves under the shared proxy at /api. Persists every message to SQLite so
Maya can recall prior turns in a conversation.
"""

import base64
import io
import logging
import os
import re

from fastapi import Depends, FastAPI, Header, HTTPException
from huggingface_hub import InferenceClient
from huggingface_hub.errors import HfHubHTTPError
from PIL import Image
from pydantic import BaseModel, Field, field_validator

import storage

app = FastAPI(title="Maya API")

logger = logging.getLogger("maya.api")

# No CORS middleware: the frontend and this API are always served through the
# same reverse-proxy origin, so cross-origin access is unnecessary. Leaving
# CORS closed prevents third-party sites from calling this API (and burning
# Hugging Face usage) from a browser.

storage.init_db()


def require_owner(x_owner_token: str | None = Header(default=None)) -> str:
    """Every client generates and persists its own opaque owner token
    (see frontend). Conversations are scoped to the token that created them
    so one visitor can't list, read, or delete another visitor's chats."""
    if not x_owner_token or len(x_owner_token) < 8:
        raise HTTPException(status_code=401, detail="Missing X-Owner-Token header")
    return x_owner_token

# Models served via the Hugging Face Inference API (router). Swap these for
# any other model id available on the HF router if you prefer.
HF_MODEL = "meta-llama/Llama-3.1-8B-Instruct"
HF_VISION_MODEL = "meta-llama/Llama-4-Scout-17B-16E-Instruct"
HF_IMAGE_MODEL = "black-forest-labs/FLUX.1-schnell"

# Reject anything absurdly large before it hits the model or the database —
# a data URL this size already implies a multi-megabyte image.
MAX_IMAGE_DATA_URL_LENGTH = 8_000_000
_DATA_URL_RE = re.compile(r"^data:image/(png|jpe?g|webp);base64,")

_hf_client: InferenceClient | None = None


def get_hf_client() -> InferenceClient:
    global _hf_client
    if _hf_client is None:
        # Put your Hugging Face token in the HF_TOKEN secret (Tools -> Secrets).
        api_key = os.environ.get("HF_TOKEN")
        if not api_key:
            raise HTTPException(
                status_code=500,
                detail="HF_TOKEN is not configured on the server.",
            )
        _hf_client = InferenceClient(api_key=api_key)
    return _hf_client


MAYA_SYSTEM_PROMPT = (
    "You are Maya, a warm, sharp, and highly capable AI assistant. "
    "You remember the full context of the current conversation and refer "
    "back to earlier details naturally when relevant. Keep responses "
    "clear, helpful, and to the point, while sounding personable rather "
    "than robotic."
)


class CreateConversationBody(BaseModel):
    title: str = Field(default="New conversation", max_length=200)


class RenameConversationBody(BaseModel):
    title: str = Field(min_length=1, max_length=200)


class CreateMessageBody(BaseModel):
    content: str = Field(min_length=1, max_length=8000)
    reply_to_id: str | None = None


class UpdateMessageBody(BaseModel):
    pinned: bool


class AnalyzeImageBody(BaseModel):
    image_data_url: str = Field(max_length=MAX_IMAGE_DATA_URL_LENGTH)
    prompt: str = Field(default="What's in this image?", max_length=2000)

    @field_validator("image_data_url")
    @classmethod
    def _validate_data_url(cls, value: str) -> str:
        if not _DATA_URL_RE.match(value):
            raise ValueError("image_data_url must be a base64 png/jpeg/webp data URL")
        return value


class GenerateImageBody(BaseModel):
    prompt: str = Field(min_length=1, max_length=2000)


@app.get("/api/healthz")
def healthz():
    return {"status": "ok"}


@app.get("/api/conversations")
def api_list_conversations(owner_token: str = Depends(require_owner)):
    return storage.list_conversations(owner_token)


@app.post("/api/conversations", status_code=201)
def api_create_conversation(
    body: CreateConversationBody, owner_token: str = Depends(require_owner)
):
    return storage.create_conversation(owner_token, title=body.title)


@app.get("/api/conversations/{conversation_id}")
def api_get_conversation(conversation_id: str, owner_token: str = Depends(require_owner)):
    conversation = storage.get_conversation(conversation_id, owner_token)
    if not conversation:
        raise HTTPException(status_code=404, detail="Conversation not found")
    return conversation


@app.patch("/api/conversations/{conversation_id}")
def api_rename_conversation(
    conversation_id: str,
    body: RenameConversationBody,
    owner_token: str = Depends(require_owner),
):
    conversation = storage.rename_conversation(conversation_id, owner_token, body.title)
    if not conversation:
        raise HTTPException(status_code=404, detail="Conversation not found")
    return conversation


@app.delete("/api/conversations/{conversation_id}", status_code=204)
def api_delete_conversation(conversation_id: str, owner_token: str = Depends(require_owner)):
    if not storage.delete_conversation(conversation_id, owner_token):
        raise HTTPException(status_code=404, detail="Conversation not found")
    return None


@app.get("/api/conversations/{conversation_id}/messages")
def api_get_messages(conversation_id: str, owner_token: str = Depends(require_owner)):
    conversation = storage.get_conversation(conversation_id, owner_token)
    if not conversation:
        raise HTTPException(status_code=404, detail="Conversation not found")
    return storage.get_messages(conversation_id)


@app.patch("/api/conversations/{conversation_id}/messages/{message_id}")
def api_update_message(
    conversation_id: str,
    message_id: str,
    body: UpdateMessageBody,
    owner_token: str = Depends(require_owner),
):
    conversation = storage.get_conversation(conversation_id, owner_token)
    if not conversation:
        raise HTTPException(status_code=404, detail="Conversation not found")

    message = storage.set_message_pinned(conversation_id, message_id, body.pinned)
    if not message:
        raise HTTPException(status_code=404, detail="Message not found")
    return message


@app.post("/api/conversations/{conversation_id}/vision-messages", status_code=201)
def api_analyze_image(
    conversation_id: str,
    body: AnalyzeImageBody,
    owner_token: str = Depends(require_owner),
):
    conversation = storage.get_conversation(conversation_id, owner_token)
    if not conversation:
        raise HTTPException(status_code=404, detail="Conversation not found")

    user_message = storage.add_message(
        conversation_id, "user", body.prompt, image_url=body.image_data_url
    )

    client = get_hf_client()
    try:
        completion = client.chat_completion(
            model=HF_VISION_MODEL,
            messages=[
                {"role": "system", "content": MAYA_SYSTEM_PROMPT},
                {
                    "role": "user",
                    "content": [
                        {"type": "text", "text": body.prompt},
                        {"type": "image_url", "image_url": {"url": body.image_data_url}},
                    ],
                },
            ],
            max_tokens=1024,
        )
        reply_text = completion.choices[0].message.content or ""
    except HfHubHTTPError:
        logger.exception("Hugging Face vision request failed")
        raise HTTPException(
            status_code=502,
            detail="Maya couldn't analyze that image right now. Please try again in a moment.",
        )
    except Exception:
        logger.exception("Unexpected error analyzing image")
        raise HTTPException(
            status_code=502,
            detail="Maya couldn't analyze that image right now. Please try again in a moment.",
        )

    assistant_message = storage.add_message(conversation_id, "assistant", reply_text)
    return {"user_message": user_message, "assistant_message": assistant_message}


@app.post("/api/conversations/{conversation_id}/generate-image", status_code=201)
def api_generate_image(
    conversation_id: str,
    body: GenerateImageBody,
    owner_token: str = Depends(require_owner),
):
    conversation = storage.get_conversation(conversation_id, owner_token)
    if not conversation:
        raise HTTPException(status_code=404, detail="Conversation not found")

    user_message = storage.add_message(
        conversation_id, "user", f"Generate an image: {body.prompt}"
    )

    client = get_hf_client()
    try:
        image: Image.Image = client.text_to_image(body.prompt, model=HF_IMAGE_MODEL)
        buffer = io.BytesIO()
        image.convert("RGB").save(buffer, format="JPEG", quality=90)
        image_data_url = "data:image/jpeg;base64," + base64.b64encode(buffer.getvalue()).decode()
    except HfHubHTTPError:
        logger.exception("Hugging Face image generation request failed")
        raise HTTPException(
            status_code=502,
            detail="Maya couldn't generate that image right now. Please try again in a moment.",
        )
    except Exception:
        logger.exception("Unexpected error generating image")
        raise HTTPException(
            status_code=502,
            detail="Maya couldn't generate that image right now. Please try again in a moment.",
        )

    assistant_message = storage.add_message(
        conversation_id,
        "assistant",
        f'Here\'s your generated image for: "{body.prompt}"',
        image_url=image_data_url,
    )
    return {"user_message": user_message, "assistant_message": assistant_message}


@app.post("/api/conversations/{conversation_id}/messages", status_code=201)
def api_create_message(
    conversation_id: str,
    body: CreateMessageBody,
    owner_token: str = Depends(require_owner),
):
    conversation = storage.get_conversation(conversation_id, owner_token)
    if not conversation:
        raise HTTPException(status_code=404, detail="Conversation not found")

    user_message = storage.add_message(
        conversation_id, "user", body.content, reply_to_id=body.reply_to_id
    )

    history = storage.get_messages(conversation_id)
    chat_messages = [{"role": "system", "content": MAYA_SYSTEM_PROMPT}] + [
        {"role": m["role"], "content": m["content"]} for m in history
    ]

    client = get_hf_client()
    try:
        completion = client.chat_completion(
            model=HF_MODEL,
            messages=chat_messages,
            max_tokens=1024,
        )
        reply_text = completion.choices[0].message.content or ""
    except HfHubHTTPError:
        logger.exception("Hugging Face Inference API request failed")
        raise HTTPException(
            status_code=502,
            detail="Maya couldn't respond right now. Please try again in a moment.",
        )
    except Exception:  # surface a clean, sanitized error to the client
        logger.exception("Unexpected error generating a response")
        raise HTTPException(
            status_code=502,
            detail="Maya couldn't respond right now. Please try again in a moment.",
        )

    assistant_message = storage.add_message(conversation_id, "assistant", reply_text)

    # Auto-title fresh conversations from the first user message.
    if conversation["title"] == "New conversation":
        auto_title = body.content.strip().splitlines()[0][:60]
        if auto_title:
            storage.rename_conversation(conversation_id, owner_token, auto_title)

    return {"user_message": user_message, "assistant_message": assistant_message}
