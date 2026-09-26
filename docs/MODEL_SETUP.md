# On-Device Model Setup (Phase 2)

Stackward runs Gemma **fully on-device** via the MediaPipe LLM Inference API.
No log data is sent to a cloud API for summarization.

## 1. Get a model (in-app)

Open **Stackward → Logs → On-device model**.

### Option A — Download a standard model (recommended)

Tap **Download** on:

- **Gemma 4 E2B (GPU)** — most phones (~4 GB+ RAM, ~1.9 GB download)
- **Gemma 4 E4B (GPU)** — higher quality (~6 GB+ RAM, ~2.8 GB download)

Files come from the public [LiteRT Community](https://huggingface.co/litert-community) on Hugging Face (no login token required for these builds). Progress is shown in the card; you can cancel mid-download.

### Option B — Browse Hugging Face

Tap **Hugging Face** to open the LiteRT Community org in the browser, pick another `.litertlm` / `.task` model, download it to the phone, then use **Import file** in Stackward.

Per-model **HF page** links open that model’s repository.

### Option C — Import a file you already have

Tap **Import file** and choose a `.task` / `.litertlm` from Downloads / Files.

## 2. Summarize logs

1. Fetch journal, Docker, or digest logs
2. Tap **Summarize with Gemma**
3. Review the summary and any structured action proposals

## Notes

- **Emulators** are not supported reliably for on-device LLM inference — use a physical device.
- If no model is configured, Stackward shows raw logs only (no cloud fallback) and highlights download / Hugging Face options.
- Google is migrating new projects to **LiteRT-LM**; Stackward currently uses MediaPipe `tasks-genai` as documented in the PRD.

## adb alternative (development)

```bash
adb push model.litertlm /sdcard/Download/model.litertlm
```

Then use **Import file** in the app.
