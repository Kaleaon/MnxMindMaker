# LiteRT-LM Integration Notes

## Scope

This guide documents how MnxMindMaker uses Google AI Edge LiteRT-LM as a local-runtime engine option.

Repository reference: <https://github.com/google-ai-edge/LiteRT-LM>.

## Runtime model

MnxMindMaker supports two LiteRT-LM integration paths:

1. **Bridge mode (default):** routes chat through an OpenAI-compatible local endpoint and tags
   local-runtime requests with `extra_body.runtime = "litert-lm"`.
2. **Native in-process mode (optional):** enabled when Base URL is set to
   `inprocess://litert-lm` and an optional runtime bridge class is available.

For both paths, runtime hints are included in request payloads:
- `extra_body.runtime_hints.compute_backend` (`cpu`/`gpu`/`npu`/`auto`)
- `extra_body.runtime_hints.context_window_tokens`
- `extra_body.runtime_hints.quantization_profile`
- `extra_body.runtime_hints.max_ram_mb`
- `extra_body.runtime_hints.max_vram_mb`

This enables bridge runtimes to adapt request handling while keeping existing provider routing,
privacy policy checks, and deploy flows intact.

## Settings checklist

1. In **Settings → LLM Provider**, choose **Local On-Device Runtime**.
2. Set **Local Runtime Engine** = `LiteRT-LM`.
3. Choose runtime path:
   - **Bridge mode:** set **Base URL** to your LiteRT-LM bridge endpoint.
   - **Native mode:** set **Base URL** to `inprocess://litert-lm` and include a compatible
     optional native bridge module.
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
