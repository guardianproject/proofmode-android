# How to manually attest and upload a proof

This guide shows how to upload a proof set to Filebase and create location attestations from Share Proof when auto-attest or automatic uploads were not enabled. It assumes you already completed wallet, Location Protocol, and Filebase setup from the [tutorial](tutorial-create-location-attestations.md).

For what off-chain vs on-chain means, see [How location attestations work](explanation-location-attestations.md#off-chain-vs-on-chain).

---

## Upload to Filebase

1. In the gallery, open the proof you want to upload.
2. Tap the share control (bottom-left) to open the share menu.
3. Tap **Upload to Filebase**.

**Result:** The media file and proof artifacts upload to Filebase. Wait until the upload finishes before continuing.

---

## Create an off-chain attestation

1. From the same share menu, tap **Off-chain Attestation**.

**Result:** A cryptographic signature is created and stored locally on the device as an off-chain location attestation artifact.

---

## Create an on-chain attestation

1. From the share menu, tap **Notarize On-Chain**.

**Result:** The attestation is submitted to the blockchain network selected in Wallet Settings. Keep the app open until submission completes.

---

## Verify the new metadata

1. Leave the share screen and return to the gallery.
2. Open the same proof again.
3. In proof details, find the new sections for off-chain attestation, on-chain attestation, and Filebase.

Then:

| Section | What to do |
|---------|------------|
| Off-chain attestation | Open it to view the cryptographic signature stored on device. |
| On-chain attestation | Open it to view the on-chain signature; follow the link to inspect the attestation on the blockchain. |
| Filebase | Use the links to open the proof set and media on IPFS. |

**Result:** You can confirm local signature, on-chain record, and IPFS retrieval URLs for that proof.

---

## Alternatives

- If **Upload to Filebase** is disabled or fails, confirm Auto Sync credentials and **Test Connection** in [settings reference — Auto Sync](reference-location-settings.md#auto-sync-filebase).
- If attestation buttons are missing, confirm **Location Protocol attestation** is on and a wallet is connected ([tutorial](tutorial-create-location-attestations.md)).
- To avoid repeating these steps per capture, enable **Auto-attest on capture** and **Automatic uploads** in the [tutorial](tutorial-create-location-attestations.md).
