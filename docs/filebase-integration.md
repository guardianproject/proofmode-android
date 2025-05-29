# Filebase IPFS Integration

ProofMode now supports automatic upload of proof files to Filebase for IPFS storage, providing decentralized backup and easy sharing of your authenticated media.

## Features

- **Automatic Upload**: When enabled, all proof files (photos, videos, metadata, signatures) are automatically uploaded to your Filebase bucket
- **IPFS Pinning**: Files uploaded to Filebase are automatically pinned to IPFS
- **S3 Compatibility**: Uses standard S3-compatible API for reliable file transfer
- **Dual Storage**: Local storage remains primary; Filebase acts as a secondary backup
- **CID Retrieval**: IPFS Content Identifiers (CIDs) are logged for easy sharing

## Setup Instructions

1. **Create Filebase Account**
   - Visit [filebase.com](https://filebase.com) and create an account
   - Complete the signup process

2. **Create a Bucket**
   - In the Filebase dashboard, create a new bucket for your proof files
   - Choose a unique bucket name (e.g., "my-proofmode-files")

3. **Generate Access Keys**
   - Go to the Access Keys section in your Filebase dashboard
   - Create a new access key pair
   - Save both the Access Key and Secret Key securely

4. **Configure ProofMode**
   - Open ProofMode app
   - Go to Settings
   - Tap "Filebase IPFS Storage Settings"
   - Enable Filebase Upload
   - Enter your credentials:
     - Access Key: Your Filebase access key
     - Secret Key: Your Filebase secret key
     - Bucket Name: The bucket you created
     - Endpoint: `https://s3.filebase.com` (default)
   - Save settings

## How It Works

1. **Local First**: All proof files are saved locally first for immediate access
2. **Background Upload**: Files are then uploaded to Filebase in the background
3. **IPFS Pinning**: Filebase automatically pins your files to IPFS
4. **CID Generation**: Each file gets a unique IPFS Content Identifier (CID)
5. **Verification**: You can verify uploads in your Filebase dashboard

## File Structure

Files are uploaded to Filebase using the following structure:
```
bucket-name/
├── [hash1]/
│   ├── media-file.jpg
│   ├── media-file.jpg.proof.csv
│   ├── media-file.jpg.asc
│   └── ...
├── [hash2]/
│   ├── video-file.mp4
│   ├── video-file.mp4.proof.csv
│   └── ...
```

Where `[hash]` is the SHA-256 hash of the original media file.

## Benefits

- **Decentralization**: Your proof files are stored on IPFS, making them permanently accessible
- **Redundancy**: Multiple backup copies across the IPFS network
- **Sharing**: Use IPFS CIDs to share proof files globally
- **Verification**: Third parties can independently verify your proofs using IPFS
- **Censorship Resistance**: IPFS provides resistance to censorship and takedowns

## Security Notes

- Access keys are stored locally on your device
- All uploads use HTTPS encryption
- Your Filebase credentials are never shared with ProofMode servers
- IPFS CIDs are deterministic based on file content

## Troubleshooting

### Upload Failures
- Check your internet connection
- Verify your Filebase credentials are correct
- Ensure your bucket name exists
- Check Filebase dashboard for error messages

### Large Files
- Files larger than 5GB require multipart upload (automatically handled)
- Very large files may take longer to upload and pin to IPFS

### Storage Limits
- Check your Filebase plan limits
- Monitor your storage usage in the Filebase dashboard

## API Integration

For developers, the Filebase integration is implemented using:
- `FilebaseStorageProvider`: Handles S3-compatible uploads
- `CompositeStorageProvider`: Manages dual local/remote storage
- AWS Signature Version 4 authentication
- OkHttp for reliable network transfers

## Support

For issues specific to Filebase service, contact Filebase support.
For ProofMode integration issues, please file a bug report on the ProofMode GitHub repository.