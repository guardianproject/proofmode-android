package org.witness.proofmode.plugins.wallet.infra.config

import org.json.JSONObject

/**
 * Reads a `pm_getPaymasterStubData` response body. The check order matches
 * `scripts/zerodev-probe-sponsorship.sh`: a paymaster wins, then any result, then the
 * no-policy error. Anything unrecognized is [SponsorshipClassification.UNKNOWN], so a
 * reworded ZeroDev error hides the chain instead of offering it.
 */
internal object SponsorshipClassifier {

    private const val NO_POLICY_PHRASE = "sponsoring polic"

    fun classify(body: String?): SponsorshipClassification {
        if (body.isNullOrBlank()) return SponsorshipClassification.UNKNOWN
        val root = runCatching { JSONObject(body) }.getOrNull()
            ?: return SponsorshipClassification.UNKNOWN

        if (!root.isNull("result")) {
            val paymaster = paymasterOf(root.opt("result"))
            return if (paymaster.isNullOrBlank()) {
                SponsorshipClassification.UNSPONSORED
            } else {
                SponsorshipClassification.SPONSORED
            }
        }

        val error = errorText(root) ?: return SponsorshipClassification.UNKNOWN
        return if (error.lowercase().contains(NO_POLICY_PHRASE)) {
            SponsorshipClassification.UNSPONSORED
        } else {
            SponsorshipClassification.UNKNOWN
        }
    }

    // Android's optString turns a JSON null into the text "null", which would read as a
    // paymaster address; checking isNull first keeps a null paymaster from sponsoring.
    private fun paymasterOf(result: Any?): String? {
        val obj = result as? JSONObject ?: return null
        if (obj.isNull("paymaster")) return null
        return obj.opt("paymaster") as? String
    }

    private fun errorText(root: JSONObject): String? {
        if (root.isNull("error")) return null
        return when (val error = root.opt("error")) {
            is String -> error
            is JSONObject -> if (error.isNull("message")) null else error.opt("message") as? String
            else -> null
        }
    }
}
