package dev.cannoli.scorza.input

data class MatchInput(
    val name: String,
    val vendorId: Int,
    val productId: Int,
    val androidBuildModel: String,
    val sourceMask: Int,
    val descriptor: String? = null,
)

data class DeviceMatchRule(
    val name: String? = null,
    val vendorId: Int? = null,
    val productId: Int? = null,
    val androidBuildModel: String? = null,
    val sourceMask: Int? = null,
    val descriptor: String? = null,
) {
    fun score(input: MatchInput): Int {
        // Hard reject when the rule and the input both name a device and the names disagree.
        // Without this gate, two physically different controllers that report the same VID/PID
        // (Retroid handhelds fake VID/PID for built-in + BT pads) and run on the same Build.MODEL
        // score high enough on those two signals alone to inherit each other's saved mappings,
        // even though the names plainly say they're different devices. Compare trimmed strings
        // so kernel-side whitespace inconsistencies (trailing spaces on some Android builds) do
        // not break legitimate matches.
        val ruleName = name?.trim()
        val inputName = input.name.trim()
        if (!ruleName.isNullOrEmpty() && inputName.isNotEmpty() && ruleName != inputName) {
            return 0
        }

        var score = 0

        // Descriptor match is the canonical signal for "same physical pad". Wins over everything
        // else when present, because two pads of the same make/model report identical name+vid+pid
        // but produce different InputDevice descriptors (Android hashes the kernel uniqueId into
        // the descriptor for BT devices, and for phantom-rewrite hosts the sibling descriptors
        // carry the uniqueness — see ControllerBridge.settle for the sibling-folding logic).
        val ruleDescriptor = descriptor
        val inputDescriptor = input.descriptor
        val descriptorMatched = ruleDescriptor != null && ruleDescriptor.isNotEmpty() &&
            inputDescriptor != null && inputDescriptor == ruleDescriptor
        if (descriptorMatched) {
            score += 200
        }

        val ruleVid = vendorId
        val rulePid = productId
        val vidPidMatched = ruleVid != null && rulePid != null && ruleVid != 0 && rulePid != 0 &&
            ruleVid == input.vendorId && rulePid == input.productId
        if (vidPidMatched) {
            score += 100
        }

        // Name only scores when vid+pid did not already match; vid+pid subsumes name identity.
        if (!vidPidMatched && !ruleName.isNullOrEmpty() && ruleName == inputName) {
            score += 50
        }

        val ruleModel = androidBuildModel
        if (ruleModel != null && ruleModel.isNotEmpty() && ruleModel == input.androidBuildModel) {
            score += 100
        }

        val ruleMask = sourceMask
        if (ruleMask != null && ruleMask != 0 && (ruleMask and input.sourceMask) == ruleMask) {
            score += 10
        }

        return score
    }
}
