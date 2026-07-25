# Filebase IPFS Integration

ProofMode can upload proof sets to Filebase for IPFS pinning and sharing. When an IPFS RPC bearer token is configured, the preferred path uploads the **entire first-pass proof set as one IPFS directory** (single directory root CID). Without a bearer token, ProofMode falls back to per-member S3 uploads.

Local storage remains primary. Filebase is a secondary backup and share handle.

## Features

- **IPFS directory upload**: Multipart `POST` to Filebase IPFS RPC with `wrap-with-directory=true` and `cid-version=1`, producing one directory root CID for the proof set
- **First-pass completeness**: Uploads when the five core proof artifacts plus media leaf are ready; does **not** wait for `.ots` / `.nostr`
- **Auto-upload or on-demand**: Capture-time automatic upload and/or Share → **Upload to Filebase**
- **S3 membership fallback**: When no bearer token is set, uploads the same membership set via S3-compatible APIs
- **Dual storage**: Local proof files always write first; Filebase is secondary
- **Directory CID / gateway URL**: Success URI looks like `https://ipfs.filebase.io/ipfs/<directoryCid>`

## Setup Instructions

1. **Create Filebase Account**
   - Visit [filebase.com](https://filebase.com) and create an account
   - Complete the signup process

2. **Create a Bucket**
   - In the Filebase dashboard, create a new bucket for your proof files
   - Choose a unique bucket name (e.g., `my-proofmode-files`)

3. **Generate S3 Access Keys**
   - Go to the Access Keys section in your Filebase dashboard
   - Create a new access key pair
   - Save both the Access Key and Secret Key securely

4. **Generate an IPFS RPC Bearer Token (recommended)**
   - In the Filebase dashboard, create a **bucket-specific IPFS RPC token** for the bucket you will use
   - This token authorizes `https://rpc.filebase.io` directory uploads
   - Without it, ProofMode uses S3 per-member upload only (no single directory CID)

5. **Configure ProofMode**
   - Open ProofMode → Settings → **Filebase IPFS Storage Settings**
   - Enable Filebase
   - Enter S3 credentials:
     - Access Key / Secret Key
     - Bucket Name
     - Endpoint: `https://s3.filebase.com` (default)
     - Region (as shown in Filebase)
   - Enter **IPFS Bearer Token** (for directory upload)
   - Check **Upload automatically** if you want capture-time uploads; leave unchecked to upload only from Share
   - Save settings
   - Optionally tap **Test Connection** (uses IPFS `/api/v0/version` when a bearer token is present; otherwise S3)

## How It Works

### Preferred path: IPFS directory RPC

When Filebase is enabled and an IPFS bearer token is set:

1. Proof artifacts are saved locally first
2. When the proof set is **first-pass complete**, ProofMode assembles flat basenames (core artifacts + media leaf, plus any late notary files already present)
3. It uploads them in one multipart request to:

   `POST https://rpc.filebase.io/api/v0/add?wrap-with-directory=true&cid-version=1&raw-leaves=true&chunker=size-262144`

4. Authorization: `Authorization: Bearer <ipfsBearerToken>`
5. Multipart part names are **flat basenames only** (for example `{hash}.proof.csv`) — not nested under `hash/`
6. The directory root CID is the NDJSON entry whose `Name` is empty (`""`)
7. The directory gateway URL is stored as `{hash}.filebase.ipfs.uri` (IPFS-only; never written by S3)
8. The media gateway URL is stored as `{hash}.filebase.image.uri` (leaf form `…/ipfs/{cid}` preferred)

### First-pass completeness

Upload starts when **all** of the following are ready:

| Member | Role |
|--------|------|
| `{hash}.proof.csv` | Core proof CSV |
| `{hash}.proof.json` | Core proof JSON |
| `{hash}.proof.csv.asc` | CSV signature |
| `{hash}.proof.json.asc` | JSON signature |
| `{hash}.asc` | Media signature |
| `{hash}.{ext}` | Injected **media leaf** (bytes from the capture URI; not a file in the local hash folder) |

First-pass does **not** wait for OpenTimestamps (`.ots`) or Nostr (`.nostr`) notarization files. If those files already exist and Notary prefs allow them, they may be included in the upload set; otherwise they can trigger a later re-upload when membership changes.

### Auto-upload vs Share on-demand

| Setting | Capture path | Share → Upload to Filebase |
|---------|--------------|----------------------------|
| Filebase off / invalid S3 creds | Local only | Prompt to configure |
| Enabled, auto **on**, bearer token | Deferred IPFS directory upload after first-pass | Same directory helper |
| Enabled, auto **on**, no bearer token | Immediate S3 per-file secondary writes | S3 membership upload (same member set) |
| Enabled, auto **off**, bearer token | Local only | IPFS directory on demand |
| Enabled, auto **off**, no bearer token | Local only | S3 membership on demand |

**Upload automatically** defaults to on (missing preference reads as `true`) so existing installs keep capture-time behavior. Turn it off if you only want explicit Share uploads.

On Share, incomplete first-pass (missing core files or unreadable media) shows a not-ready message and does **not** upload a partial set. Success/failure follows the async upload listener (starting assembly is not the same as upload success).

### Auto path: bind media before saves

On the automatic capture path, ProofMode calls `bindMedia(hash, mediaUri, mime)` on the storage provider **before** the first proof-set save for that hash. That binds the media URI so deferred flush can re-open media bytes and include the injected `{hash}.{ext}` leaf. Binding after core saves would leave the flush without media and prevent a correct directory upload.

### S3 membership fallback (no bearer token)

If Filebase is configured but the IPFS bearer token is blank:

- **Auto path:** existing immediate dual-write of each file to S3 under `bucket/{hash}/{identifier}`
- **Share / shared helper:** assembles the **same** first-pass membership set (including media leaf) and uploads each member via S3 `saveBytes` — not a partial set, and not the social “public” helper

S3 remains useful without an RPC token, but you do not get a single directory root CID from the IPFS `add` API.

### Degraded RPC retries (equality-ineligible)

Preferred UnixFS query params include `raw-leaves=true` and `chunker=size-262144`. If Filebase rejects those parameters, ProofMode retries once with only:

`wrap-with-directory=true&cid-version=1`

That **degraded** upload still returns a directory CID and gateway URL, but the CID is **equality-ineligible** for comparing against a locally computed proof-set `rootCid` (different UnixFS layout). Prefer a successful full-param upload when verifying CID equality.

## Directory layout (IPFS)

IPFS directory members use **flat** basenames inside one wrapped directory (no `hash/` nesting in the multipart names):

```text
<directory CID>/
├── {hash}.jpg          # or .png / .mp4 / … (media leaf)
├── {hash}.proof.csv
├── {hash}.proof.json
├── {hash}.proof.csv.asc
├── {hash}.proof.json.asc
├── {hash}.asc
└── …                   # optional late .ots / .nostr when present + prefs allow
```

Gateway access: `https://ipfs.filebase.io/ipfs/<directoryCid>` (and `/ipfs/<directoryCid>/<basename>` for individual files).

Legacy S3 object keys (fallback path) still look like `bucket-name/{hash}/{identifier}`.

## Benefits

- **One CID for the proof set**: Directory root CID addresses the whole membership set
- **Decentralized pinning**: Filebase pins content to IPFS
- **Redundancy**: Local primary plus remote backup
- **On-demand or automatic**: Fit capture workflow or explicit Share upload
- **Censorship resistance**: IPFS-oriented sharing of authenticated media and proofs

## Security Notes

- Access keys and the IPFS bearer token are stored locally in app preferences
- Uploads use HTTPS
- Credentials are never sent to ProofMode servers
- Directory CIDs depend on content **and** UnixFS parameters (full vs degraded)

## Troubleshooting

### Upload failures
- Check internet connectivity
- Verify S3 access key, secret, bucket, and endpoint
- For directory upload, confirm the IPFS bearer token is for the correct bucket
- Use **Test Connection** in settings
- Check the Filebase dashboard for errors or quota issues

### Proof set not ready (Share)
- Wait until core proof files finish writing
- Ensure the media URI is still readable (permission / file still available)
- Notary files are not required for first-pass

### CID does not match local rootCid
- Confirm the upload used full UnixFS params (not a degraded retry)
- Confirm membership includes the media leaf and flat basenames
- Degraded RPC results are equality-ineligible by design

### Large files
- Large media may take longer to upload and pin
- Multipart / long timeouts apply on the RPC and S3 clients

### Storage limits
- Check your Filebase plan limits and dashboard usage

## API Integration

For developers, the main pieces are:

- `FilebaseStorageProvider.uploadDirectory` — IPFS RPC `add` with `wrap-with-directory` / `cid-version=1`
- `FilebaseStorageProvider` S3 path — AWS Signature Version 4 + OkHttp (fallback and connection test)
- `ProofSetDirectoryUploader` — shared first-pass assembly for Composite flush and Share
- `ProofSetMembershipPolicy` — core five + media completeness; late `.ots` / `.nostr` member-when-present
- `CompositeStorageProvider` — local primary; deferred directory flush when `deferDirectoryUpload` + `bindMedia`
- `FilebaseConfig` — S3 fields, `ipfsBearerToken`, `autoUpload`, `hasIpfsAccess()`

## Support

For issues specific to Filebase service, contact Filebase support.
For ProofMode integration issues, please file a bug report on the ProofMode GitHub repository.
