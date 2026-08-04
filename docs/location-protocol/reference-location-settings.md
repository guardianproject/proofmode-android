# Location settings reference

Facts for Location, Location Protocol, Wallet Settings, Auto Sync, and related Share Proof actions. Usage walkthroughs: [tutorial](tutorial-create-location-attestations.md), [manual how-to](howto-manual-attest-and-upload.md). Concepts: [explanation](explanation-location-attestations.md).

---

## Navigation

| Entry | Path |
|-------|------|
| From first-run wizard | Privacy screen → **Privacy Options** → settings |
| From main app | **Settings** |
| Location hub | Settings → **Location** |
| Wallet | Location → **Wallet Settings** |
| Filebase / IPFS | Settings → **Auto Sync** |

---

## Location (Settings → Location)

### Enable location sharing

- **Type:** toggle
- **Default:** off until the user enables it
- **Summary (on):** GPS coordinates are saved with your media and can be attested
- **Permission:** Android location — choose **Allow only while using the app** when prompted
- **Notes:** Required before Location Protocol and related rows unlock

---

## Location Protocol (Settings → Location)

### Location Protocol attestation

- **Type:** toggle
- **Default:** off
- **Summary (on):** Location attestations can be signed and published for your media
- **Depends on:** Enable location sharing

### Auto-attest on capture

- **Type:** mode picker
- **Default:** unavailable until prerequisites are met
- **Summary:** Automatically run location attestation after in-app capture
- **Depends on:** location sharing, Location Protocol attestation, connected wallet
- **Modes:**
  - `Off` — no automatic attestation
  - `Off-chain only`
  - `On-chain only`
  - `Both (off-chain then on-chain)`
- **Locked summary (no wallet):** Connect a wallet in Wallet Settings to choose an auto-attest mode

### Local IPFS CID

- **Type:** toggle
- **Default:** on when Location Protocol path is available (keep enabled for attestation↔proof linking)
- **Summary (on):** IPFS content IDs are computed and saved with each proof set
- **Depends on:** location sharing and Location Protocol attestation

### Wallet Settings (row)

- **Type:** navigation row
- **Summary:** Manage the wallet used to sign location attestations
- **Opens:** Wallet Settings screen

---

## Wallet Settings

### Connect Wallet

- **Type:** action button
- **Label:** Connect Wallet
- **Auth sheet title:** Log in or sign up
- **Methods:** Login with Email · Login with SMS
- **Flow:** enter email or phone → **Send Code** → enter OTP → **Verify**
- **Disconnect:** Disconnect (when connected)

### Network

- **Type:** selection
- **Label:** Network
- **Purpose:** Blockchain network used for on-chain attestations
- **Tutorial value:** `<NETWORK_NAME>` (organizer-provided)
- **Examples of supported display names in app:** Ethereum Mainnet, Arbitrum One, Base, Sepolia Testnet, Arbitrum Sepolia

### Gas sponsorship

| Control | Type | Purpose |
|---------|------|---------|
| **Sponsor transactions** | toggle | When on, on-chain attestations use sponsored gas when available; when off, self-funded from wallet balance |
| **ZeroDev project ID** | text (UUID) | Project used for sponsorship; tutorial value `<ZERODEV_ID>` |
| **Save** | action | Persists the project ID |

---

## Auto Sync (Filebase)

Screen title in settings list: **Auto Sync**. Detail screen: **Filebase IPFS AutoSync (BETA)**.

| Setting | Type | Purpose |
|---------|------|---------|
| **Enable Filebase** | toggle | Turns Filebase upload integration on |
| **IPFS Bearer Token** | secret text | Authorizes IPFS RPC uploads; tutorial value `<IPFS_BEARER_TOKEN>` |
| **Automatic uploads** | toggle | Uploads proofs on capture when enabled |
| **Include media** | toggle | Uploads captured media alongside proof artifacts |
| **Test Connection** | action | Validates credentials (IPFS test when a bearer token is present) |
| **Save Settings** | action | Persists configuration |

S3 access key / secret / bucket / endpoint fields may also appear for S3 fallback; the tutorial path uses the IPFS bearer token.

---

## Share Proof actions

Visible when Location Protocol UI is active for the proof:

| Control | Label in UI | Effect |
|---------|-------------|--------|
| Filebase upload | **Upload to Filebase** | Uploads proof set (and media per config) to Filebase / IPFS |
| Off-chain | **Off-chain Attestation** | Signs and stores attestation locally |
| On-chain | **Notarize On-Chain** | Submits attestation to the selected network |

---

## Proof detail sections (after attest / upload)

| Section | Contents |
|---------|----------|
| Off-chain attestation | Local cryptographic signature / artifact |
| On-chain attestation | On-chain signature and link to blockchain details |
| Filebase Uploads | Links to view proof set and uploaded media on IPFS |
