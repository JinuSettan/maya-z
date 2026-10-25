#!/usr/bin/env python3
"""Run a small interactive smoke test for a saved TESSA LoRA adapter."""
import argparse
from pathlib import Path

import torch
import yaml
from peft import PeftModel
from transformers import AutoModelForCausalLM, AutoTokenizer, BitsAndBytesConfig

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--config", required=True)
    parser.add_argument("--adapter", required=True, help="Path to saved LoRA adapter")
    parser.add_argument("--prompt", default="മലയാളത്തിൽ സ്വാഭാവികമായി സ്വയം പരിചയപ്പെടുത്തുക.")
    parser.add_argument("--max-new-tokens", type=int, default=160)
    args = parser.parse_args()

    if not torch.cuda.is_available():
        raise SystemExit("CUDA GPU not detected. This smoke test uses the GPU QLoRA setup; no model weights were loaded.")

    cfg = yaml.safe_load(Path(args.config).read_text(encoding="utf-8"))
    adapter_path = Path(args.adapter)
    if not adapter_path.is_dir():
        raise SystemExit(f"Adapter directory not found: {adapter_path}")

    model_name = cfg["model"]["name"]
    tokenizer = AutoTokenizer.from_pretrained(adapter_path, use_fast=True)
    quant_config = BitsAndBytesConfig(
        load_in_4bit=True,
        bnb_4bit_quant_type="nf4",
        bnb_4bit_use_double_quant=True,
        bnb_4bit_compute_dtype=torch.float16,
    )
    base = AutoModelForCausalLM.from_pretrained(
        model_name,
        quantization_config=quant_config,
        device_map="auto",
        torch_dtype=torch.float16,
    )
    model = PeftModel.from_pretrained(base, str(adapter_path))
    model.eval()

    prompt = f"### Instruction:\n{args.prompt.strip()}\n### Response:\n"
    inputs = tokenizer(prompt, return_tensors="pt").to(model.device)
    with torch.inference_mode():
        output = model.generate(
            **inputs,
            max_new_tokens=max(1, min(args.max_new_tokens, 512)),
            do_sample=False,
            pad_token_id=tokenizer.eos_token_id,
        )
    answer = tokenizer.decode(output[0][inputs["input_ids"].shape[1]:], skip_special_tokens=True)
    print(answer.strip())

if __name__ == "__main__":
    main()
