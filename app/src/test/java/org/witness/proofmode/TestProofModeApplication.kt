package org.witness.proofmode

import android.app.Application

/** Minimal Application for JVM unit tests — avoids ProofModeApp native library loading. */
class TestProofModeApplication : Application()
