# Create location attestations

In this tutorial, you will enable Location Protocol in Proofmode, connect a wallet, configure gas sponsorship and Filebase backup, and capture media that can be attested. By the end, you will have location sharing, wallet identity, auto-attest (optional), and IPFS backup ready for capture. No prior blockchain experience is needed.

**Prerequisites**

- Proofmode installed on an Android device
- Phone number or email for wallet login
- Values for `<NETWORK_NAME>`, `<ZERODEV_ID>`, and `<IPFS_BEARER_TOKEN>` from your organizer (see [README](README.md))

For background on why these steps matter, see [How location attestations work](explanation-location-attestations.md). For setting names and defaults, see the [settings reference](reference-location-settings.md).

---

## 1. Open Proofmode settings

1. Install Proofmode and open the app.
2. Walk through the initial guided wizard until the privacy screen.
3. Tap **Privacy Options** to open Proofmode settings.

If you already finished onboarding: open **Settings** from the main app, then continue.

**Check:** You see the main Proofmode settings list (including **Location** and **Auto Sync**).

---

## 2. Open Location settings

1. Tap **Location**.

**Check:** The screen title is **Location**, with sections for Location, Location Protocol, and Wallet Settings.

---

## 3. Enable location sharing

1. Turn on **Enable location sharing**.
2. When Android asks for location permission, choose **Allow only while using the app**.

**Check:** The toggle is on and the summary reads: “GPS coordinates are saved with your media and can be attested.”

---

## 4. Enable Location Protocol attestation

1. Turn on **Location Protocol attestation**.
2. Confirm **Local IPFS CID** is on (it is enabled by default when Location Protocol is available).

Keep **Local IPFS CID** on. It links media and proof sets to location attestations and supports retrieving a proof set from IPFS after upload.

**Check:** Both **Location Protocol attestation** and **Local IPFS CID** are on. **Auto-attest on capture** may still be greyed out until a wallet is connected.

---

## 5. Connect a wallet

1. Tap **Wallet Settings**.
2. Tap **Connect Wallet**.
3. Choose **Login with Email** or **Login with SMS**.
4. Enter your email or phone number, then tap **Send Code**.
5. Enter the one-time code you receive, then tap **Verify**.

**Check:** The wallet screen shows you as connected (address visible; status is no longer “Not connected”).

You now have a Web3 identity: a private key managed for you, and a public address tied to your blockchain actions. Details: [How location attestations work](explanation-location-attestations.md#web3-identity).

---

## 6. Choose the network and ZeroDev project ID

1. Under **Network**, select `<NETWORK_NAME>`.
2. Under **Gas sponsorship**, enter `<ZERODEV_ID>` in **ZeroDev project ID**.
3. Tap **Save**.

Location attestations submitted on-chain are recorded on the network you select. The ZeroDev project ID lets sponsored transactions cover gas fees when sponsorship is available. Details: [gas and sponsorship](explanation-location-attestations.md#gas-fees-and-sponsorship).

**Check:** Network shows `<NETWORK_NAME>`, and you see confirmation that the project ID was saved (or the field retains the ID you entered).

---

## 7. Enable auto-attest on capture

1. Press Back to return to the **Location** settings screen.
2. Tap **Auto-attest on capture** (available now that the wallet is connected).
3. Choose a mode:
   - **Off-chain only**
   - **On-chain only**
   - **Both (off-chain then on-chain)**

**Check:** Auto-attest is no longer greyed out, and your chosen mode is selected.

---

## 8. Configure Auto Sync (Filebase / IPFS)

1. Press Back until you reach the main settings menu.
2. Tap **Auto Sync** (Filebase IPFS AutoSync).
3. Enter `<IPFS_BEARER_TOKEN>` in **IPFS Bearer Token**.
4. Tap **Test Connection** and wait for a successful result.
5. Turn on **Include media** so media can be retrieved from IPFS along with proof artifacts.
6. Optionally turn on **Automatic uploads** so proofs upload on capture.
7. Tap **Save Settings** if prompted, then press Back to the main settings menu.

**Check:** Test Connection succeeds. **Include media** is on.

---

## 9. Review other proof options (optional)

Still in settings, review which metadata Proofmode attaches when media is captured (device ID, network, and similar toggles). Change only what you need.

**Check:** You know which optional sensors and identifiers are enabled.

---

## 10. Capture verifiable media

1. Press Back until you reach the main gallery view.
2. Tap the camera button (bottom right).
3. Capture a photo or video.

**Check:** The new item appears in the gallery. With auto-attest and Auto Sync configured, attestation and upload run according to the modes you chose. To inspect results or run steps by hand, use [How to manually attest and upload](howto-manual-attest-and-upload.md).

---

## You did it

You have:

- Location sharing and Location Protocol enabled
- A connected wallet on `<NETWORK_NAME>` with ZeroDev sponsorship configured
- Auto-attest and Filebase / IPFS backup set up
- A path to capture media that can carry location attestations
