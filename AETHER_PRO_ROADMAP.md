# Aether Pro — Free Personal Edition Roadmap

Aether Pro is a free, personalized English/Persian edition of the Aether Android VPN client. “Pro” is a product name only: it does not mean a paid tier, subscription, purchase, or entitlement.

## Product identity

- **App name:** Aether Pro
- **Price model:** fully free; no subscriptions, in-app purchases, paid feature gates, or billing SDKs.
- **Languages:** English and Persian (including RTL).
- **Visual direction:** cyber / neon.
- **Palette:** deep navy surfaces, violet primary actions, cyan connected state and highlights.
- **Platform:** Android 8.0+ (preserving the upstream minimum SDK and supported ABIs).

## Free infrastructure and compatibility

1. Preserve the upstream Aether engine and its existing free connection path. In this repository, the inspected engine provisions consumer WARP identities through Cloudflare's WARP API and connects using its supported MASQUE / WireGuard transports; do not invent or imply a separate Aether-owned server fleet.
2. Keep the app compatible with the existing upstream API hosts, registration flow, protocol handshakes, endpoint negotiation, certificate handling, and configuration formats.
3. Do not introduce a paid backend, subscription checks, billing SDK, purchase flow, entitlement service, artificial free-tier quotas, or hard-coded credentials.
4. Treat optional Cloudflare Zero Trust/team enrollment as an optional user-provided organization feature, not a requirement for the ordinary free connection path.
5. Keep custom user-imported configurations optional and separate from the upstream free connection path. Never ship fabricated server entries, fake latency, simulated connection states, or hard-coded speed-test results.
6. Keep branding and UI customization isolated from protocol, API, provisioning, and transport behavior wherever possible. Any behavior change must have compatibility tests.

## Engineering principles

1. Preserve the upstream Aether engine, VPN service, protocol implementations and configuration compatibility unless a change is specifically required and tested.
2. Keep security-sensitive behavior fail-closed. Never weaken certificate validation, VPN routing, DNS/IPv6 leak protection, kill-switch behavior, config encryption or credential handling for visual or convenience improvements.
3. Do not ship fake server lists, fabricated latency, simulated connection states or hard-coded speed-test results.
4. Keep private keys, API keys, user configs and signing credentials out of source control and diagnostic exports.
5. Test both English/LTR and Persian/RTL layouts, plus the minimum supported Android version and current Android releases.
6. Attribute upstream components and comply with AGPL-3.0 and all bundled third-party licenses.
7. Keep release signing credentials private. Any signing-key migration must have an explicit user-upgrade plan and must never silently strand existing installations.

## Delivery plan

### Phase 1 — Aether Pro identity

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

### Phase 3 — Connection intelligence and upstream compatibility

- Map every provisioning, API, DNS, endpoint-discovery and transport dependency to its upstream counterpart.
- Review the existing route selection, latency checks, auto-select and reconnect logic before extending it.
- Make route recommendations explainable and based on measured, recent results.
- Add clear network-quality and connection-health summaries without presenting estimates as measurements.
- Test roaming, Wi-Fi/mobile transitions, captive portals and unstable networks.
- Add regression tests proving that the ordinary free connection path does not depend on optional team enrollment or user-imported configurations.

### Phase 4 — Security and privacy

- Re-audit signing and release provenance; plan key rotation separately so existing installations are not silently stranded.
- Review VPN lockdown, DNS/IPv6 leak protection, split tunneling and local proxy sharing.
- Verify secure storage and redaction for config imports, AI context and diagnostics.
- Document threat model, data flows and known limitations.

### Phase 5 — Optional AI assistant

- Keep AI optional and disabled until configured by the user.
- Reuse the existing redaction and permission boundaries; never send private config credentials or keys.
- Offer diagnostics explanations and suggested settings, but require explicit user confirmation before applying changes.
- Provide clear provider, network, privacy and API-key disclosures. AI must not be a paid feature gate in Aether Pro.

### Phase 6 — Release quality

- Run unit tests, lint/static checks and Android builds for supported ABIs.
- Test installation, upgrade and signing compatibility on real devices.
- Produce release notes, checksums and a reproducible build record where feasible.
- Publish APKs only after CI succeeds and security-sensitive changes are reviewed.
- Verify that no billing, purchase, subscription, or paid-entitlement code has entered the app.

## Current implementation note

The existing engine source inspected for this roadmap uses Cloudflare WARP registration/API hosts and the supported MASQUE / WireGuard transports. This is the upstream free connection path currently present in this repository; it is not evidence of a separate Aether-operated server fleet. Optional Zero Trust enrollment and imported custom configs are distinct features.

This roadmap records intended requirements, not proof that every phase is implemented or tested. Existing features remain subject to their current behavior and verification.
