#!/usr/bin/env python3
"""QLoRA fine-tuning scaffold for TESSA. Intended for a CUDA GPU."""
import argparse
import json
from pathlib import Path

import torch
import yaml
from datasets import Dataset
from peft import LoraConfig, get_peft_model, prepare_model_for_kbit_training
from transformers import (
    AutoModelForCausalLM,
    AutoTokenizer,
    BitsAndBytesConfig,
    Trainer,
    TrainingArguments,
    DataCollatorForLanguageModeling,
)

def load_jsonl(path):
    rows = []
    with Path(path).open("r", encoding="utf-8") as handle:
        for line_no, raw in enumerate(handle, 1):
            if not raw.strip():
                continue
            row = json.loads(raw)
            if not isinstance(row, dict) or not isinstance(row.get("instruction"), str) or not row["instruction"].strip() or not isinstance(row.get("response"), str) or not row["response"].strip():
                raise ValueError(f"Invalid instruction/response at line {line_no}")
            context = row.get("context", "")
            if not isinstance(context, str):
                raise ValueError(f"context must be a string at line {line_no}")
            prompt = f"### Instruction:\n{row['instruction'].strip()}\n"
            if context.strip():
                prompt += f"### Context:\n{context.strip()}\n"
            text = prompt + f"### Response:\n{row['response'].strip()}"
            rows.append({"text": text})
    if not rows:
        raise ValueError("Training dataset is empty.")
    return Dataset.from_list(rows)

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--config", required=True)
    args = parser.parse_args()

    if not torch.cuda.is_available():
        raise SystemExit(
            "CUDA GPU not detected. Stopping safely: this 4B QLoRA setup is not "
            "intended for CPU-only training. Select a GPU runtime or use a much "
            "smaller model/configuration; no model weights were loaded."
        )

    cfg = yaml.safe_load(Path(args.config).read_text(encoding="utf-8"))
    model_name = cfg["model"]["name"]
    train_file = cfg["data"]["train_file"]
    output_dir = cfg["training"]["output_dir"]
    max_length = int(cfg["model"].get("max_seq_length", 1024))

    if not Path(train_file).is_file():
        raise SystemExit(
            f"Training data not found: {train_file}. Copy data/train.example.jsonl "
            "to data/train.jsonl and update config.yaml if needed."
        )

    dataset = load_jsonl(train_file)
    tokenizer = AutoTokenizer.from_pretrained(model_name, use_fast=True)
    if tokenizer.pad_token is None:
        tokenizer.pad_token = tokenizer.eos_token

    quant_config = BitsAndBytesConfig(
        load_in_4bit=True,
        bnb_4bit_quant_type="nf4",
        bnb_4bit_use_double_quant=True,
        bnb_4bit_compute_dtype=torch.float16,
    )
    model = AutoModelForCausalLM.from_pretrained(
        model_name,
        quantization_config=quant_config,
        device_map="auto",
        torch_dtype=torch.float16,
    )
    model.config.use_cache = False
    model = prepare_model_for_kbit_training(model)

    lora_cfg = cfg["lora"]
    model = get_peft_model(
        model,
        LoraConfig(
            r=int(lora_cfg["r"]),
            lora_alpha=int(lora_cfg["alpha"]),
            lora_dropout=float(lora_cfg["dropout"]),
            target_modules=lora_cfg["target_modules"],
            bias="none",
            task_type="CAUSAL_LM",
        ),
    )
    model.print_trainable_parameters()

    def tokenize(batch):
        return tokenizer(
            batch["text"],
            truncation=True,
            max_length=max_length,
            padding=False,
        )

    tokenized = dataset.map(tokenize, batched=True, remove_columns=dataset.column_names)
    train_cfg = cfg["training"]
    training_args = TrainingArguments(
        output_dir=output_dir,
        num_train_epochs=float(train_cfg["num_train_epochs"]),
        learning_rate=float(train_cfg["learning_rate"]),
        per_device_train_batch_size=int(train_cfg["per_device_train_batch_size"]),
        gradient_accumulation_steps=int(train_cfg["gradient_accumulation_steps"]),
        logging_steps=int(train_cfg["logging_steps"]),
        save_steps=int(train_cfg["save_steps"]),
        max_steps=int(train_cfg["max_steps"]),
        seed=int(train_cfg["seed"]),
        fp16=True,
        gradient_checkpointing=True,
        optim="paged_adamw_8bit",
        report_to="none",
        save_strategy="steps",
        save_total_limit=2,
    )
    trainer = Trainer(
        model=model,
        args=training_args,
        train_dataset=tokenized,
        data_collator=DataCollatorForLanguageModeling(tokenizer=tokenizer, mlm=False),
    )
    trainer.train()
    model.save_pretrained(output_dir)
    tokenizer.save_pretrained(output_dir)
    print(f"Adapter saved to: {output_dir}")

if __name__ == "__main__":
    main()
