#!/bin/bash

prooffile=$1
set $(sha256sum $1)
proofhash=$1
echo checking integrity for proofhash $proofhash
rm pubkey.asc.gpg
gpg --dearmor pubkey.asc
gpg --no-default-keyring --keyring ./pubkey.asc.gpg --homedir ./ --verify $proofhash.asc $prooffile
gpg --no-default-keyring --keyring ./pubkey.asc.gpg --homedir ./ --verify $proofhash.proof.csv.asc
gpg --no-default-keyring --keyring ./pubkey.asc.gpg --homedir ./ --verify $proofhash.proof.json.asc 


