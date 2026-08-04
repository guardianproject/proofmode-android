uniffi::setup_scaffolding!();

use cid::Cid;
use ipld_core::cid::Version;
use multihash_codetable::Code;
use rust_unixfs::dir::builder::{BufferingTreeBuilder, OwnedTreeNode, TreeOptions};
use rust_unixfs::file::adder::{Chunker, FileAdder};
use std::collections::HashMap;
use std::io::Read;

#[derive(uniffi::Record)]
pub struct NamedEntry {
    pub name: String,
    pub bytes: Vec<u8>,
}

#[derive(uniffi::Record)]
pub struct PrecomputedLeaf {
    pub name: String,
    pub leaf_cid: String,
    pub tsize: u64,
}

#[derive(uniffi::Record, Debug)]
pub struct ProofSetCidOutput {
    pub root_cid: String,
    pub files: HashMap<String, String>,
    pub tsizes: HashMap<String, u64>,
}

#[derive(uniffi::Record, Debug)]
pub struct FileLeafCidOutput {
    pub leaf_cid: String,
    pub tsize: u64,
}

#[derive(uniffi::Error, Debug, thiserror::Error)]
pub enum CidError {
    #[error("Empty input")]
    EmptyInput,
    #[error("Empty names")]
    EmptyNames,
    #[error("Names not strictly sorted: {left} >= {right}")]
    NotSorted { left: String, right: String },
    #[error("wrapWithDirectory=true (ipfs add -w) is not supported in v1")]
    WrapWithDirectoryUnsupported,
    #[error("Build failed: {msg}")]
    BuildFailed { msg: String },
}

fn compute_leaf_cid(
    bytes: &[u8],
    chunk_size: usize,
    cid_version: i32,
    raw_leaves: bool,
) -> Result<String, String> {
    let version = if cid_version == 1 {
        Version::V1
    } else {
        Version::V0
    };
    let mut adder = FileAdder::builder()
        .with_chunker(Chunker::Size(chunk_size))
        .with_cid_version(version)
        .with_raw_leaves(raw_leaves)
        .with_hasher(Code::Sha2_256)
        .build();
    let mut cursor = std::io::Cursor::new(bytes);
    let mut buf = vec![0u8; chunk_size];
    let mut last_cid: Option<Cid> = None;
    loop {
        let n = cursor.read(&mut buf).map_err(|e| e.to_string())?;
        if n == 0 {
            break;
        }
        let (blocks, _consumed) = adder.push(&buf[..n]);
        for (cid, _block) in blocks {
            last_cid = Some(cid);
        }
    }
    for (cid, _block) in adder.finish() {
        last_cid = Some(cid);
    }
    last_cid
        .map(|c| c.to_string())
        .ok_or_else(|| "empty input produced no CID".to_string())
}

fn leaf_cid_and_tsize(
    bytes: &[u8],
    chunk_size: usize,
    cid_version: i32,
    raw_leaves: bool,
) -> Result<(Cid, u64), String> {
    let version = if cid_version == 1 {
        Version::V1
    } else {
        Version::V0
    };
    let mut adder = FileAdder::builder()
        .with_chunker(Chunker::Size(chunk_size))
        .with_cid_version(version)
        .with_raw_leaves(raw_leaves)
        .with_hasher(Code::Sha2_256)
        .build();
    let mut cursor = std::io::Cursor::new(bytes);
    let mut buf = vec![0u8; chunk_size];
    let mut last_cid: Option<Cid> = None;
    let mut tsize: u64 = 0;
    loop {
        let n = cursor.read(&mut buf).map_err(|e| e.to_string())?;
        if n == 0 {
            break;
        }
        let (blocks, _consumed) = adder.push(&buf[..n]);
        for (cid, block) in blocks {
            last_cid = Some(cid);
            tsize += block.len() as u64;
        }
    }
    for (cid, block) in adder.finish() {
        last_cid = Some(cid);
        tsize += block.len() as u64;
    }
    let leaf = last_cid.ok_or_else(|| "empty file".to_string())?;
    Ok((leaf, tsize))
}

fn build_proof_set_tree(
    sorted_names_and_links: &[(String, Cid, u64)],
    wrap_with_directory: bool,
    shard_threshold: i64,
    block_size_limit: i64,
) -> Result<(String, HashMap<String, String>, HashMap<String, u64>), CidError> {
    if sorted_names_and_links.is_empty() {
        return Err(CidError::EmptyNames);
    }
    for w in sorted_names_and_links.windows(2) {
        if w[0].0 >= w[1].0 {
            return Err(CidError::NotSorted {
                left: w[0].0.clone(),
                right: w[1].0.clone(),
            });
        }
    }
    if wrap_with_directory {
        return Err(CidError::WrapWithDirectoryUnsupported);
    }
    let mut tree_opts = TreeOptions::default();
    tree_opts.cid_version(Version::V1);
    tree_opts.hasher(Code::Sha2_256);
    if sorted_names_and_links.len() >= 2 {
        tree_opts.wrap_with_directory();
    }
    if shard_threshold < 0 {
        tree_opts.shard_threshold(None);
    } else {
        tree_opts.shard_threshold(Some(shard_threshold as u64));
    }
    if block_size_limit < 0 {
        tree_opts.block_size_limit(None);
    } else {
        tree_opts.block_size_limit(Some(block_size_limit as u64));
    }
    let mut builder = BufferingTreeBuilder::new(tree_opts);
    let mut files_map = HashMap::new();
    let mut tsizes_map = HashMap::new();
    for (name, leaf_cid, tsize) in sorted_names_and_links {
        builder
            .put_link(name, *leaf_cid, *tsize)
            .map_err(|e| CidError::BuildFailed {
                msg: e.to_string(),
            })?;
        files_map.insert(name.clone(), leaf_cid.to_string());
        tsizes_map.insert(name.clone(), *tsize);
    }
    let nodes: Vec<Cid> = builder
        .build()
        .map(|res| {
            res.map(|OwnedTreeNode { cid, .. }| cid)
                .map_err(|e| CidError::BuildFailed {
                    msg: e.to_string(),
                })
        })
        .collect::<Result<Vec<_>, _>>()?;
    let root = nodes
        .last()
        .copied()
        .ok_or_else(|| CidError::BuildFailed {
            msg: "tree build produced no root".to_string(),
        })?;
    Ok((root.to_string(), files_map, tsizes_map))
}

fn compute_proof_set_output(
    entries: &[(String, Vec<u8>)],
    chunk_size: usize,
    cid_version: i32,
    raw_leaves: bool,
    wrap_with_directory: bool,
    shard_threshold: i64,
    block_size_limit: i64,
) -> Result<ProofSetCidOutput, CidError> {
    if entries.is_empty() {
        return Err(CidError::EmptyNames);
    }
    for w in entries.windows(2) {
        if w[0].0 >= w[1].0 {
            return Err(CidError::NotSorted {
                left: w[0].0.clone(),
                right: w[1].0.clone(),
            });
        }
    }
    let sorted: Vec<_> = entries.to_vec();
    let mut links = Vec::with_capacity(sorted.len());
    for (name, bytes) in &sorted {
        let (leaf_cid, tsize) =
            leaf_cid_and_tsize(bytes, chunk_size, cid_version, raw_leaves).map_err(|msg| {
                CidError::BuildFailed {
                    msg: msg.to_string(),
                }
            })?;
        links.push((name.clone(), leaf_cid, tsize));
    }
    let (root_cid, files, tsizes) = build_proof_set_tree(
        &links,
        wrap_with_directory,
        shard_threshold,
        block_size_limit,
    )?;
    Ok(ProofSetCidOutput {
        root_cid,
        files,
        tsizes,
    })
}

#[uniffi::export]
pub fn compute_file_cid(
    file_bytes: Vec<u8>,
    chunk_size: u32,
    cid_version: u32,
    raw_leaves: bool,
) -> Result<String, CidError> {
    compute_leaf_cid(
        &file_bytes,
        chunk_size as usize,
        cid_version as i32,
        raw_leaves,
    )
    .map_err(|msg| {
        if msg == "empty input produced no CID" {
            CidError::EmptyInput
        } else {
            CidError::BuildFailed { msg }
        }
    })
}

#[uniffi::export]
pub fn compute_file_leaf_cid_and_tsize(
    file_bytes: Vec<u8>,
    chunk_size: u32,
    cid_version: u32,
    raw_leaves: bool,
) -> Result<FileLeafCidOutput, CidError> {
    let (leaf, tsize) = leaf_cid_and_tsize(
        &file_bytes,
        chunk_size as usize,
        cid_version as i32,
        raw_leaves,
    )
    .map_err(|msg| {
        if msg == "empty file" {
            CidError::EmptyInput
        } else {
            CidError::BuildFailed { msg }
        }
    })?;
    Ok(FileLeafCidOutput {
        leaf_cid: leaf.to_string(),
        tsize,
    })
}

#[uniffi::export]
pub fn compute_proof_set_cid(
    entries: Vec<NamedEntry>,
    chunk_size: u32,
    cid_version: u32,
    raw_leaves: bool,
    wrap_with_directory: bool,
    shard_threshold: i64,
    block_size_limit: i64,
) -> Result<ProofSetCidOutput, CidError> {
    let tuples: Vec<(String, Vec<u8>)> = entries
        .into_iter()
        .map(|e| (e.name, e.bytes))
        .collect();
    compute_proof_set_output(
        &tuples,
        chunk_size as usize,
        cid_version as i32,
        raw_leaves,
        wrap_with_directory,
        shard_threshold,
        block_size_limit,
    )
}

#[uniffi::export]
pub fn compute_proof_set_cid_from_leaves(
    entries: Vec<PrecomputedLeaf>,
    wrap_with_directory: bool,
    shard_threshold: i64,
    block_size_limit: i64,
) -> Result<ProofSetCidOutput, CidError> {
    if entries.is_empty() {
        return Err(CidError::EmptyNames);
    }
    for w in entries.windows(2) {
        if w[0].name >= w[1].name {
            return Err(CidError::NotSorted {
                left: w[0].name.clone(),
                right: w[1].name.clone(),
            });
        }
    }
    let mut links = Vec::with_capacity(entries.len());
    for entry in entries {
        let leaf_cid: Cid = entry.leaf_cid.parse().map_err(|_| CidError::BuildFailed {
            msg: format!("invalid leaf CID: {}", entry.leaf_cid),
        })?;
        links.push((entry.name, leaf_cid, entry.tsize));
    }
    let (root_cid, files, tsizes) = build_proof_set_tree(
        &links,
        wrap_with_directory,
        shard_threshold,
        block_size_limit,
    )?;
    Ok(ProofSetCidOutput {
        root_cid,
        files,
        tsizes,
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn leaf_small_utf8_matches_bootstrap_cid() {
        let bytes = b"hello proofmode";
        let cid = compute_leaf_cid(bytes, 262_144, 1, true).expect("leaf cid");
        assert_eq!(cid, "bafkreiedbilurl7bwymun6xv67r6xzz473p6tswmgzvl4qcua4qovdaoom");
    }

    #[test]
    fn leaf_empty_matches_bootstrap_cid() {
        let cid = compute_leaf_cid(&[], 262_144, 1, true).expect("leaf cid");
        assert_eq!(cid, "bafkreihdwdcefgh4dqkjv67uzcmw7ojee6xedzdetojuzjevtenxquvyku");
    }

    #[test]
    fn leaf_multi_chunk_matches_bootstrap_cid() {
        let bytes = vec![0x41u8; 524_288];
        let cid = compute_leaf_cid(&bytes, 262_144, 1, true).expect("leaf cid");
        assert_eq!(cid, "bafybeihfcct4o25o7gc6nvlrpmbluwekjsjdciap3w3bplx2gkbhazph3a");
    }

    #[test]
    fn leaf_proof_csv_like_matches_bootstrap_cid() {
        let bytes = b"col1,col2\nval1,val2\n";
        let cid = compute_leaf_cid(bytes, 262_144, 1, true).expect("leaf cid");
        assert_eq!(cid, "bafkreidttqqbvxd7rxtovhhe7y7wffowa45oeh6cvmq7tqegk5nadcmtoy");
    }

    #[test]
    fn proofset_with_sigs_root_cid_non_empty() {
        let entries = vec![
            (
                "a1b2c3d4e5f6.asc".to_string(),
                b"-----BEGIN PGP SIGNATURE-----\n".to_vec(),
            ),
            (
                "a1b2c3d4e5f6.proof.csv".to_string(),
                b"col1,col2\nval1,val2\n".to_vec(),
            ),
            (
                "a1b2c3d4e5f6.proof.csv.asc".to_string(),
                b"-----BEGIN PGP SIGNATURE-----\nMOCKCSV\n-----END PGP SIGNATURE-----\n".to_vec(),
            ),
            (
                "a1b2c3d4e5f6.proof.json".to_string(),
                br#"{"k":"v"}"#.to_vec(),
            ),
            (
                "a1b2c3d4e5f6.proof.json.asc".to_string(),
                b"-----BEGIN PGP SIGNATURE-----\nMOCKJSON\n-----END PGP SIGNATURE-----\n".to_vec(),
            ),
        ];
        let out = compute_proof_set_cid(
            entries
                .into_iter()
                .map(|(name, bytes)| NamedEntry { name, bytes })
                .collect(),
            262_144,
            1,
            true,
            false,
            262_144,
            -1,
        )
        .expect("proofset output");
        assert_eq!(
            out.root_cid,
            "bafybeiawbn7kzevxbavlvit2hnfx3zueq4fs5ymzb525plvuksybqemg3q"
        );
        assert_eq!(
            out.files["a1b2c3d4e5f6.proof.csv.asc"],
            "bafkreieoindszfvyxw5ylsiidhlagb2orgkeh76plktbl5wij5w2itt24m"
        );
        assert_eq!(
            out.files["a1b2c3d4e5f6.proof.json.asc"],
            "bafkreian3vxdzbhbahxv32s66pxbq7hvjbbkublp3bfdlx7mjihoz4vc4q"
        );
    }

    #[test]
    fn proofset_flat_core_root_cid_non_empty() {
        let entries = vec![
            (
                "a1b2c3d4e5f6.asc".to_string(),
                b"-----BEGIN PGP SIGNATURE-----\n".to_vec(),
            ),
            (
                "a1b2c3d4e5f6.proof.csv".to_string(),
                b"col1,col2\nval1,val2\n".to_vec(),
            ),
            (
                "a1b2c3d4e5f6.proof.json".to_string(),
                br#"{"k":"v"}"#.to_vec(),
            ),
        ];
        let out = compute_proof_set_cid(
            entries
                .into_iter()
                .map(|(name, bytes)| NamedEntry { name, bytes })
                .collect(),
            262_144,
            1,
            true,
            false,
            262_144,
            -1,
        )
        .expect("proofset output");
        assert_eq!(
            out.root_cid,
            "bafybeieqzehoddjdkbj4u2w4vbg4xtjujjeoq2tdpi2vbx52b4sekyhinu"
        );
        assert_eq!(
            out.files["a1b2c3d4e5f6.proof.csv"],
            "bafkreidttqqbvxd7rxtovhhe7y7wffowa45oeh6cvmq7tqegk5nadcmtoy"
        );
        assert_eq!(
            out.files["a1b2c3d4e5f6.proof.json"],
            "bafkreidgnqnkaluanddnlta5gkkqbfbsyftzbpwcr3em4em5bunbrvqtde"
        );
        assert_eq!(
            out.files["a1b2c3d4e5f6.asc"],
            "bafkreiakdhrg5j5ijmrm2n3l7bibl36jqkbp4guspjtibxqoq3vkqlakce"
        );
    }

    #[test]
    fn compute_file_cid_export_matches_leaf_small_utf8() {
        let cid = compute_file_cid(b"hello proofmode".to_vec(), 262_144, 1, true)
            .expect("export leaf cid");
        assert_eq!(cid, "bafkreiedbilurl7bwymun6xv67r6xzz473p6tswmgzvl4qcua4qovdaoom");
    }

    #[test]
    fn compute_file_leaf_cid_and_tsize_export_matches_leaf_small_utf8() {
        let out = compute_file_leaf_cid_and_tsize(b"hello proofmode".to_vec(), 262_144, 1, true)
            .expect("export leaf cid+tsize");
        assert_eq!(out.leaf_cid, "bafkreiedbilurl7bwymun6xv67r6xzz473p6tswmgzvl4qcua4qovdaoom");
        assert!(out.tsize > 0);
    }

    #[test]
    fn compute_proof_set_cid_single_entry_fails_without_wrap() {
        let err = compute_proof_set_cid(
            vec![NamedEntry {
                name: "_".to_string(),
                bytes: vec![0x01, 0x02, 0x03],
            }],
            262_144,
            1,
            true,
            false,
            262_144,
            -1,
        )
        .expect_err("single-entry proof set should fail");
        assert!(matches!(err, CidError::BuildFailed { .. }));
    }

    #[test]
    fn compute_proof_set_cid_export_matches_flat_core() {
        let entries = vec![
            NamedEntry {
                name: "a1b2c3d4e5f6.asc".to_string(),
                bytes: b"-----BEGIN PGP SIGNATURE-----\n".to_vec(),
            },
            NamedEntry {
                name: "a1b2c3d4e5f6.proof.csv".to_string(),
                bytes: b"col1,col2\nval1,val2\n".to_vec(),
            },
            NamedEntry {
                name: "a1b2c3d4e5f6.proof.json".to_string(),
                bytes: br#"{"k":"v"}"#.to_vec(),
            },
        ];
        let out = compute_proof_set_cid(entries, 262_144, 1, true, false, 262_144, -1)
            .expect("export proofset");
        assert_eq!(
            out.root_cid,
            "bafybeieqzehoddjdkbj4u2w4vbg4xtjujjeoq2tdpi2vbx52b4sekyhinu"
        );
        assert_eq!(
            out.files["a1b2c3d4e5f6.proof.csv"],
            "bafkreidttqqbvxd7rxtovhhe7y7wffowa45oeh6cvmq7tqegk5nadcmtoy"
        );
    }

    #[test]
    fn compute_proof_set_cid_empty_entries_returns_empty_names() {
        let err = compute_proof_set_cid(vec![], 262_144, 1, true, false, 262_144, -1)
            .expect_err("expected error");
        assert!(matches!(err, CidError::EmptyNames));
    }

    #[test]
    fn compute_proof_set_cid_unsorted_returns_not_sorted() {
        let entries = vec![
            NamedEntry {
                name: "z.txt".to_string(),
                bytes: b"z".to_vec(),
            },
            NamedEntry {
                name: "a.txt".to_string(),
                bytes: b"a".to_vec(),
            },
        ];
        let err = compute_proof_set_cid(entries, 262_144, 1, true, false, 262_144, -1)
            .expect_err("expected error");
        match err {
            CidError::NotSorted { left, right } => {
                assert_eq!(left, "z.txt");
                assert_eq!(right, "a.txt");
            }
            other => panic!("unexpected error: {:?}", other),
        }
    }

    #[test]
    fn compute_proof_set_cid_wrap_with_directory_unsupported() {
        let entries = vec![NamedEntry {
            name: "only.txt".to_string(),
            bytes: b"x".to_vec(),
        }];
        let err = compute_proof_set_cid(entries, 262_144, 1, true, true, 262_144, -1)
            .expect_err("expected error");
        assert!(matches!(err, CidError::WrapWithDirectoryUnsupported));
    }

    #[test]
    fn compute_proof_set_cid_populates_tsizes() {
        let entries = vec![
            NamedEntry {
                name: "a1b2c3d4e5f6.proof.csv".to_string(),
                bytes: b"col1,col2\nval1,val2\n".to_vec(),
            },
            NamedEntry {
                name: "a1b2c3d4e5f6.proof.json".to_string(),
                bytes: br#"{"k":"v"}"#.to_vec(),
            },
        ];
        let out = compute_proof_set_cid(entries, 262_144, 1, true, false, 262_144, -1)
            .expect("proofset");
        assert!(out.tsizes.contains_key("a1b2c3d4e5f6.proof.csv"));
        assert!(out.tsizes["a1b2c3d4e5f6.proof.csv"] > 0);
    }

    #[test]
    fn compute_proof_set_cid_from_leaves_matches_byte_path_flat_core() {
        let byte_entries = vec![
            NamedEntry {
                name: "a1b2c3d4e5f6.asc".to_string(),
                bytes: b"-----BEGIN PGP SIGNATURE-----\n".to_vec(),
            },
            NamedEntry {
                name: "a1b2c3d4e5f6.proof.csv".to_string(),
                bytes: b"col1,col2\nval1,val2\n".to_vec(),
            },
            NamedEntry {
                name: "a1b2c3d4e5f6.proof.json".to_string(),
                bytes: br#"{"k":"v"}"#.to_vec(),
            },
        ];
        let byte_out = compute_proof_set_cid(
            byte_entries,
            262_144,
            1,
            true,
            false,
            262_144,
            -1,
        )
        .expect("byte path");
        let mut leaf_entries: Vec<PrecomputedLeaf> = byte_out
            .files
            .iter()
            .map(|(name, leaf_cid)| PrecomputedLeaf {
                name: name.clone(),
                leaf_cid: leaf_cid.clone(),
                tsize: byte_out.tsizes[name],
            })
            .collect();
        leaf_entries.sort_by(|a, b| a.name.cmp(&b.name));
        let leaf_out = compute_proof_set_cid_from_leaves(
            leaf_entries,
            false,
            262_144,
            -1,
        )
        .expect("leaf path");
        assert_eq!(byte_out.root_cid, leaf_out.root_cid);
        assert_eq!(byte_out.files, leaf_out.files);
    }

    #[test]
    fn compute_proof_set_cid_injected_media_fixture() {
        let hash = "mediahash001";
        let media_name = format!("{hash}.jpg");
        let entries = vec![
            NamedEntry {
                name: media_name.clone(),
                bytes: b"raw-media-bytes".to_vec(),
            },
            NamedEntry {
                name: format!("{hash}.proof.csv"),
                bytes: b"col1,col2\nval1,val2\n".to_vec(),
            },
        ];
        let out = compute_proof_set_cid(entries, 262_144, 1, true, false, 262_144, -1)
            .expect("injected media proofset");
        assert_eq!(
            out.root_cid,
            "bafybeicjuuis2wegftw6murvw67gm3clo7mjemu2ndhlujver3anhtibxm"
        );
        assert_eq!(
            out.files[&media_name],
            "bafkreiccykvir22gmwsqgdw7w4zdlv4w5evrnvinn4j5zy4bzuxcpjt3fa"
        );
        assert_eq!(
            out.files[&format!("{hash}.proof.csv")],
            "bafkreidttqqbvxd7rxtovhhe7y7wffowa45oeh6cvmq7tqegk5nadcmtoy"
        );
        assert_eq!(out.tsizes[&media_name], 15);
        assert_eq!(out.tsizes[&format!("{hash}.proof.csv")], 20);
    }
}
