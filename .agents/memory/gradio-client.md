---
name: Gradio Space client usage
description: How to call the Cocateail/Z-Ai-x-maya Gradio Space from Python.
---

The Space uses `gradio_client==2.5.0`. Key points:

- `Client(src, token=hf_token, verbose=False)` — parameter is `token`, NOT `hf_token`
- Named endpoint is `/maya_brain` at `fn_index=6`
- Input shape: `{"text": "...", "files": []}` (MultimodalData)
- Output: plain string (the AI reply)
- The Gradio `/info` schema shows `fn_index=None` but the actual queue/join requires an integer; `gradio_client` resolves this automatically via named endpoint
- Works without HF_TOKEN (Space is public), but token is passed if set

**Why:** Direct `httpx` + SSE approach failed because `api_name` alone (without integer `fn_index`) is rejected by the Gradio queue endpoint, and gradio_client handles the fn_index lookup internally.
