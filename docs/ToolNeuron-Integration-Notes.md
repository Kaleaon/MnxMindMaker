# ToolNeuron Integration Notes

This guide explains how to use the public ToolNeuron project as an extension source for MnxMindMaker tooling, model candidates, and deployment targets.

- ToolNeuron repository: <https://github.com/Siddhesh2377/ToolNeuron>
- License: Apache-2.0 (compatible for reference-driven integration)

## What was added in MnxMindMaker

1. **Account linking now includes Hugging Face** in Settings, so you can store a Hugging Face access token securely (same encrypted path used for other linked providers).
2. **Model discovery now queries Hugging Face Hub** (with or without a linked token):
   - Unlinked: discover public models only.
   - Linked: include token-authenticated access for gated/private model visibility (subject to your account permissions).
3. **ToolNeuron-aligned model retrieval path** is included as a query preset (`tool calling gguf`) in the model discovery action, helping seed practical local/runtime-compatible model candidates used in ToolNeuron-style workflows.

## Recommended setup flow

1. Open **Settings → Account Linking**.
2. Tap **Link Hugging Face account** and paste your Hugging Face access token.
3. Optionally add refresh token + OAuth client credentials if your environment uses a token broker that supports refresh semantics.
4. Run **Discover Models** to fetch local catalog + Hugging Face candidates.
5. Select a target runtime/deployment profile in Deploy Wizard (`local`, `hybrid`, `cloud`) and map chosen model IDs.

## Deployment notes

- ToolNeuron contains practical patterns for local-first Android deployment (offline inference + optional network routing).
- MnxMindMaker keeps deployment profiles provider-neutral, so ToolNeuron-inspired model/runtime choices should be expressed via:
  - runtime target profile (`local`, `hybrid`, `cloud`)
  - selected provider endpoint (self-hosted OpenAI-compatible, vLLM, local runtime bridge)
  - deployment manifest policy constraints.

## Security notes

- Never hard-code Hugging Face tokens in source.
- Keep token scopes minimal (read-only where possible).
- For gated/private model access, ensure account-level permissions are configured on Hugging Face before discovery.
