# llmedge Integration Notes

## Scope

This guide covers local-runtime integration behavior for llmedge and operational troubleshooting.

## Local runtime assumptions

- Runtime is reachable from Android environment.
- Model identifier configured in app matches local runtime availability.
- Operator understands whether execution occurs on emulator host or LAN target.

## Connection setup checklist

1. Verify llmedge process is running and bound to expected interface/port.
2. Verify Android pathing:
   - emulator to host uses `10.0.2.2`
   - physical device uses host LAN IP
3. Validate runtime health endpoint responds.
4. Confirm model ID and capabilities are loaded in llmedge.
5. In app settings, set local runtime base URL and test connection.

## Operator troubleshooting checklist

Use this quick checklist when deploy or inference fails in local mode.

### A) Runtime reachability

- [ ] Endpoint URL is correct for emulator/device topology.
- [ ] Port is open and not used by another process.
- [ ] Firewall/network policy allows device → runtime traffic.
- [ ] Health check endpoint returns success.

### B) Model/runtime mismatches

- [ ] Requested model exists locally.
- [ ] Model supports required context and tool-use mode.
- [ ] Runtime memory constraints are not exceeded.

### C) Policy misconfiguration

- [ ] Tool-use default policy is appropriate for target workflow.
- [ ] Mutation tools require operator approval when expected.
- [ ] Network defaults are least-privilege unless explicitly needed.
- [ ] Manifest policy does not conflict with operator overrides.

### D) Legacy compatibility mode side effects

- [ ] Confirm whether artifact lacked manifest.
- [ ] Review compatibility warning in deploy wizard.
- [ ] Save generated manifest after successful deploy to avoid repeated warnings.

## Known local-mode limitations

- Performance depends heavily on model size and device/host resources.
- Some cloud-oriented provider features may be unavailable in local mode.
- Strict policy defaults may block behavior until explicit operator approval.

## Recommended operating posture

- Start with read-only tools in local mode.
- Enable mutating or network-capable actions only after validation.
- Persist a manifest once configuration is stable for reproducible deployments.
