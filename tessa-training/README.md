# TESSA Training Scaffold

This directory is an isolated starting point for TESSA supervised fine-tuning. It does not change the existing Maya Android app, server, computer-agent, or tests.

## Important hardware note

The starter config uses `Qwen/Qwen3-4B-Instruct-2507` as a configurable text-only baseline because TESSA has an existing Qwen3-4B LoRA experiment. It is not a final model-selection decision. Do not assume an existing adapter can be attached to a different model architecture.

The training script is designed for a CUDA GPU with 4-bit bitsandbytes loading (QLoRA). A CPU-only Colab runtime with about 12.7 GB RAM is **not a practical environment for fine-tuning this 4B model**. The script stops with a clear message when CUDA is unavailable rather than silently attempting a likely out-of-memory run. This is training scaffolding, not evidence that training has succeeded.

## Files

- `config.yaml`: model, LoRA, dataset, and output settings.
- `data/train.example.jsonl`: one example of the expected data format.
- `scripts/validate_dataset.py`: validates JSONL before training.
- `scripts/train_lora.py`: CUDA/QLoRA training entry point.
- `requirements.txt`: Python dependencies.

## Setup in a GPU-enabled Colab

1. Select a GPU runtime if one is available.
2. Clone this repository and switch to branch `tessa-training-scaffold`.
3. Install dependencies: `pip install -r tessa-training/requirements.txt`.
4. Copy `data/train.example.jsonl` to `data/train.jsonl` and replace the example with your reviewed training examples. Keep personal data and secrets out of the dataset.
5. Run validation: `python tessa-training/scripts/validate_dataset.py --input tessa-training/data/train.jsonl`.
6. Review `config.yaml`, then run: `python tessa-training/scripts/train_lora.py --config tessa-training/config.yaml`.

If the actual dataset is stored elsewhere, update the path in `config.yaml`. Start with a tiny reviewed dataset and one short test run before scaling up.

## Dataset format

Each line must be a JSON object with non-empty string fields `instruction` and `response`. Optional `context` may contain additional relevant information. Do not put API keys, passwords, private emails, or personal information into training examples without a clear need and permission.

## What fine-tuning does not provide by itself

Fine-tuning changes model behavior on examples. It does not create persistent memory, web access, a cloud PC, safe computer control, booking integrations, or continuous autonomous learning. Those require separate memory, retrieval, tool, authorization, monitoring, and evaluation systems. Any real purchase, booking, message sending, file deletion, or other consequential action must have explicit user approval.

## Safety and reliability checklist

- Review data quality, rights, and privacy before training.
- Keep API tokens in Colab secrets/environment variables, never in Git.
- Evaluate Malayalam, coding, instruction-following, hallucinations, and refusal/authorization behavior before using an adapter.
- Keep the base model and adapter versions recorded.
- Do not deploy an untested checkpoint to the production agent.
