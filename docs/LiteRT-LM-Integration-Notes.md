# LiteRT-LM Integration Notes

## Scope

This guide documents how MnxMindMaker uses Google AI Edge LiteRT-LM as a local-runtime engine option.

Repository reference: <https://github.com/google-ai-edge/LiteRT-LM>.

## Runtime model

MnxMindMaker currently routes chat through an OpenAI-compatible local endpoint and tags local-runtime
requests with `extra_body.runtime = "litert-lm"` when LiteRT-LM is selected in Settings.

This enables bridge runtimes to adapt request handling while keeping existing provider routing,
privacy policy checks, and deploy flows intact.

## Settings checklist

1. In **Settings → LLM Provider**, choose **Local On-Device Runtime**.
2. Set **Local Runtime Engine** = `LiteRT-LM`.
3. Set **Base URL** to your LiteRT-LM bridge endpoint.
4. Set **Model** and **Local Model Path** (typically a `.litertlm` package or bridge-resolved alias).
5. Run **preflight diagnostics** and confirm the `models` probe succeeds.

## Model catalog additions

Model manager now includes a LiteRT-LM-focused default candidate:

- `gemma3n_e2b_litertlm` (`Gemma 3n E2B Instruct (LiteRT-LM)`)

The one-click install action now targets this LiteRT-LM candidate by default.

## Operational notes

- Keep privacy mode `STRICT_LOCAL_ONLY` when fully offline execution is required.
- Prefer quantized LiteRT-LM artifacts for lower memory pressure on mobile devices.
- If runtime health checks fail, verify endpoint topology (`10.0.2.2` emulator host mapping, LAN IP for device).

## Current integration boundary

This expansion intentionally avoids hard-coding a specific bridge implementation. The app remains compatible
with existing llmedge-style local endpoints while enabling LiteRT-LM runtime selection and packaging conventions.
