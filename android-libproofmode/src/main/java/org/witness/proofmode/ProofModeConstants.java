package org.witness.proofmode;

public interface ProofModeConstants {

        /**
         * Default-SharedPreferences key under which legacy builds (≤ 3.0.3-RC-6)
         * stored the PGP secret-keyring passphrase as cleartext. Retained only so
         * ProofModeApp.migrateLegacyKeyring() can read and then scrub it.
         * Live builds source the passphrase from {@link
         * org.witness.proofmode.crypto.pgp.PassphraseKeystore}.
         */
        public static final String PREFS_KEY_PASSPHRASE = "pgpkp";

        /**
         * The hard-coded default that every shipped build wrote alongside a
         * keyring it had just created with the same value. Kept only as the
         * decryption anchor for the legacy-keyring migration path; never used
         * to encrypt new keyrings.
         */
        public static final String PREFS_KEY_PASSPHRASE_DEFAULT = "password";


}
