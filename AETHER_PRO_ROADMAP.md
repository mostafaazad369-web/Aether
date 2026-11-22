# Aether Pro — Product and Engineering Roadmap

Aether Pro is the personalized English/Persian cyber-themed edition of the Aether Android VPN client.

## Product identity

- **App name:** Aether Pro
- **Languages:** English and Persian (including RTL)
- **Visual direction:** cyber / neon
- **Palette:** deep navy surfaces, violet primary actions, cyan connected state and highlights
- **Platform:** Android 8.0+ (preserving the upstream minimum SDK and supported ABIs)

## Engineering principles

1. Preserve the upstream Aether engine, VPN service, protocol implementations and configuration compatibility unless a change is specifically required and tested.
2. Keep security-sensitive behavior fail-closed. Never weaken certificate validation, VPN routing, DNS/IPv6 leak protection, kill-switch behavior, config encryption or credential handling for visual or convenience improvements.
3. Do not ship fake server lists, fabricated latency, simulated connection states or hard-coded speed-test results.
4. Keep private keys, API keys, user configs and signing credentials out of source control and diagnostic exports.
5. Test both English/LTR and Persian/RTL layouts, plus the minimum supported Android version and current Android releases.
6. Attribute upstream components and comply with AGPL-3.0 and all bundled third-party licenses.

## Delivery plan

### Phase 1 — Aether Pro identity (started)

- [x] Create an isolated development branch: `feature/aether-pro-branding`.
- [x] Rename the launcher/app label to Aether Pro in English and Persian resources.
- [x] Update the app tagline and VPN notification / Quick Settings labels.
- [x] Apply a violet-and-cyan cyber accent palette while retaining the existing dark navy surface system.
- [ ] Review remaining app surfaces, dialogs, icons and store assets for consistent Aether Pro branding.

### Phase 2 — UI and accessibility

- Audit the home dashboard, connection card, navigation drawer, settings and diagnostics.
- Improve hierarchy, spacing, touch targets, contrast, loading, empty and error states.
- Keep animations restrained and respect the existing reduced-animation preference.
- Verify RTL mirroring and technical LTR fields in Persian.

### Phase 3 — Connection intelligence

- Review the existing route selection, latency checks, auto-select and reconnect logic before extending it.
- Make route recommendations explainable and based on measured, recent results.
- Add clear network-quality and connection-health summaries without presenting estimates as measurements.
- Test roaming, Wi-Fi/mobile transitions, captive portals and unstable networks.

### Phase 4 — Security and privacy

- Re-audit signing and release provenance; plan key rotation separately so existing installations are not silently stranded.
- Review VPN lockdown, DNS/IPv6 leak protection, split tunneling and local proxy sharing.
- Verify secure storage and redaction for config imports, AI context and diagnostics.
- Document threat model, data flows and known limitations.

### Phase 5 — AI assistant

- Keep AI optional and disabled until configured by the user.
- Reuse the existing redaction and permission boundaries; never send private config credentials or keys.
- Offer diagnostics explanations and suggested settings, but require explicit user confirmation before applying changes.
- Provide clear provider, network, privacy and API-key disclosures.

### Phase 6 — Release quality

- Run unit tests, lint/static checks and Android builds for supported ABIs.
- Test installation, upgrade and signing compatibility on real devices.
- Produce release notes, checksums and a reproducible build record where feasible.
- Publish APKs only after CI succeeds and security-sensitive changes are reviewed.

## Current implementation note

Phase 1 contains the first branding changes only. This roadmap does not claim that later phases have been implemented or tested. Existing upstream features remain subject to their current behavior and verification.
