# How location attestations work

Location sharing and location *attestation* are easy to confuse. This page explains the distinction, why wallets and content IDs matter, and how Proofmode approaches off-chain vs on-chain records.

To try the flow hands-on, follow [Create location attestations](tutorial-create-location-attestations.md). Setting names are listed in the [settings reference](reference-location-settings.md).

---

## Location data vs location attestation

Proofmode can save GPS coordinates with media when **Enable location sharing** is on. That is location *data* inside the proof set.

**Location Protocol attestation** goes further: it produces a cryptographically signed claim about where and when media was captured, bound to the media’s hash. Anyone verifying the attestation can check that signature against a public identity, not only read a coordinate field that could be edited in isolation.

---

## Web3 identity

When you connect a wallet (email or SMS via Privy), Proofmode obtains a Web3 identity for you:

- A **private key** used to sign attestations (held in a managed, seedless wallet — you do not memorize a seed phrase)
- A **public address** that identifies your actions on chain and in signed payloads

That identity is what makes an attestation attributable: the signature proves a specific key authorized the claim.

---

## Local IPFS CID and linking

**Local IPFS CID** computes content identifiers for proof-set members and stores them with the proof. Those IDs are the durable handles that:

- Link media and proof artifacts to a location attestation payload
- Let others retrieve the same bytes from the IPFS network after the proof set has been uploaded (for example via Filebase)

Without CIDs, attestations and remote copies are harder to reconcile as the same content.

---

## Off-chain vs on-chain

| Approach | What it does | Trade-off |
|----------|--------------|-----------|
| **Off-chain** | Signs the attestation and stores the signed artifact on the device | Fast, no gas cost; verification needs the artifact (or a copy you share) |
| **On-chain** | Submits the attestation to a blockchain (EAS) so it appears in a public ledger | Public, independently checkable; requires network selection, connectivity, and gas (or sponsorship) |
| **Both** | Off-chain first, then on-chain | Local artifact plus ledger anchor |

Auto-attest modes and Share Proof actions map to these choices. Manual steps: [How to manually attest and upload](howto-manual-attest-and-upload.md).

---

## Gas fees and sponsorship

On-chain actions are **transactions**. Each transaction has a **gas fee**: payment for network compute that includes the transaction in the chain’s public ledger.

Proofmode can use **ZeroDev** sponsorship so a configured project covers that fee when sponsorship is available. Entering a **ZeroDev project ID** and saving it wires the app to that sponsorship project. If sponsorship is off or unavailable, the wallet would need to pay gas itself from its balance.

Choosing **Network** selects which chain records the attestation. Use the network your organizer specifies (`<NETWORK_NAME>`).

---

## Filebase and IPFS backup

Filebase is a storage service Proofmode uses to pin proof sets (and optionally media) to IPFS. Auto Sync credentials (especially an **IPFS Bearer Token**) authorize uploads. After a successful upload, proof details can show gateway links to the proof set and media.

Local storage remains primary; IPFS is a backup and share path, not a replacement for on-device proofs.

---

## Broader picture

Location Protocol sits on top of Proofmode’s existing proof generation. Capture still produces the usual proof artifacts; attestation and Filebase upload are additional, intentional steps (automatic or manual) that bind location claims to identity and, optionally, to a public chain and content-addressed storage.
