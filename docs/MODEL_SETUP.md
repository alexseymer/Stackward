# On-Device Model Setup (Phase 2)

Stackward runs Gemma **fully on-device** via the MediaPipe LLM Inference API.
No log data is sent to a cloud API for summarization.

## 1. Download a compatible model

Use a MediaPipe-compatible Gemma model in `.task` or `.litertlm` format, for example:

- **Gemma-3n E2B** — recommended for phones with ~4 GB+ RAM
- **Gemma-3n E4B** — recommended for phones with ~6 GB+ RAM

Sources:

- [Google AI Edge — LLM Inference models](https://developers.google.com/edge/mediapipe/solutions/genai/llm_inference)
- [Hugging Face LiteRT Community](https://huggingface.co/litert-community)

## 2. Import into Stackward

1. Open **Stackward → Logs**
2. In the **On-device model** card, pick **E2B** or **E4B** (the app recommends based on RAM)
3. Tap **Import model file** and select the `.task` / `.litertlm` file
4. The file is copied to app-private storage

## 3. Summarize logs

1. Fetch journal, Docker, or digest logs
2. Tap **Summarize with Gemma**
3. Review the summary and any structured action proposals

## Notes

- **Emulators** are not supported reliably for on-device LLM inference — use a physical device (e.g. Pixel 8, Samsung S23+).
- If no model is configured, Stackward shows raw logs only (no cloud fallback).
- Google is migrating new projects to **LiteRT-LM**; Stackward currently uses MediaPipe `tasks-genai` as documented in the PRD.

## adb alternative (development)

```bash
adb push model.task /data/local/tmp/llm/model.task
```

Then copy or symlink into the app models directory via a future dev-only path, or use the in-app import flow.
