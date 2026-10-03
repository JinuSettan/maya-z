---
name: huggingface-hub install workaround
description: How to install huggingface_hub when the workspace pyproject.toml blocks it via pytorch-cpu index.
---

The workspace root `pyproject.toml` routes `huggingface-hub` (and ~hundreds of other ML packages) through a `pytorch-cpu` custom index with a `platform_system == 'Linux'` marker. That index has no published versions of `huggingface-hub`, so `uv add` / `installLanguagePackages` always fails with "no solution found".

**Fix:** Use `uv pip install` which installs directly into the venv without consulting `pyproject.toml` lock sources:
```
uv pip install --python /home/runner/workspace/.pythonlibs/bin/python huggingface_hub
```

**Why:** `uv pip install` bypasses the workspace pyproject.toml source routing and resolves from PyPI directly. `uv add` does not have this bypass.

**How to apply:** Any time `installLanguagePackages` fails for a package that appears in the pytorch-cpu section of pyproject.toml, use the uv pip install command above.
