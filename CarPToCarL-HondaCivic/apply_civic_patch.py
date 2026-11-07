from pathlib import Path

ROOT = Path(__file__).resolve().parent / "upstream"
APP = ROOT / "app/src/main/java/io/github/shaonialife/carptocar"
TEST = ROOT / "app/src/test/java/io/github/shaonialife/carptocar/vehicle"

def replace_once(path: Path, old: str, new: str, label: str) -> None:
    text = path.read_text(encoding="utf-8")
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected exactly one anchor, found {count}")
    path.write_text(text.replace(old, new, 1), encoding="utf-8")

vehicle = APP / "vehicle/HondaCivicProfile.kt"
vehicle.parent.mkdir(parents=True, exist_ok=True)
vehicle.write_text("""package io.github.shaonialife.carptocar.vehicle

/**
 * Conservative fallback profile for the 2019 Honda Civic MY16ADA head unit.
 *
 * The vehicle report confirms an 800x480 panel and Android 4.2.2, but does not
 * prove that the receiver advertises AirPlay/iAP2 or a maximum frame rate.
 * Apply this profile only when the iAP2 identity explicitly contains Civic,
 * 思域, or MY16ADA. It never changes MFi or Wi-Fi protocol selection.
 */
object HondaCivicProfile {
    data class Decision(
        val isMatch: Boolean,
        val fallbackWidth: Int? = null,
        val fallbackHeight: Int? = null,
        val fallbackFps: Int? = null,
        val reason: String,
    )

    fun classifyIdentity(identity: String?): Decision {
        val normalized = identity.orEmpty().trim().lowercase()
        val civicNameMatch = normalized.contains("civic") || normalized.contains("思域")
        val platformMatch = normalized.contains("my16ada")
        if (!civicNameMatch && !platformMatch) {
            return Decision(isMatch = false, reason = "iAP2 identity 未命中 Civic/MY16ADA")
        }
        return Decision(
            isMatch = true,
            fallbackWidth = 800,
            fallbackHeight = 480,
            fallbackFps = 30,
            reason = if (platformMatch) "命中 MY16ADA 平台标识" else "命中 Civic/思域车型标识",
        )
    }
}
""", encoding="utf-8")

test = TEST / "HondaCivicProfileTest.kt"
TEST.mkdir(parents=True, exist_ok=True)
test.write_text("""package io.github.shaonialife.carptocar.vehicle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HondaCivicProfileTest {
    @Test
    fun matchesExplicitCivicIdentity() {
        val profile = HondaCivicProfile.classifyIdentity("Honda Civic")
        assertTrue(profile.isMatch)
        assertEquals(800, profile.fallbackWidth)
        assertEquals(480, profile.fallbackHeight)
        assertEquals(30, profile.fallbackFps)
    }

    @Test
    fun matchesExactReportedHeadUnitPlatform() {
        assertTrue(HondaCivicProfile.classifyIdentity("MITSUBISHI ELECTRIC MY16ADA").isMatch)
    }

    @Test
    fun doesNotMatchGenericHondaOrMitsubishiIdentity() {
        assertFalse(HondaCivicProfile.classifyIdentity("Honda").isMatch)
        assertFalse(HondaCivicProfile.classifyIdentity("MITSUBISHI ELECTRIC").isMatch)
    }
}
""", encoding="utf-8")

iap2 = APP / "iap2/Iap2SenderSession.kt"
replace_once(
    iap2,
    "        val ipAddresses: List<String>,\n",
    "        val ipAddresses: List<String>,\n        /** 原始 iAP2 0x1D01 身份文本（name/model/manufacturer），供车型画像使用。 */\n        val receiverIdentity: String? = null,\n",
    "iAP2 endpoint identity field",
)
replace_once(
    iap2,
    "            ipAddresses = addresses,\n",
    "            ipAddresses = addresses,\n            receiverIdentity = receiverIdentity.takeIf { it.isNotBlank() },\n",
    "iAP2 endpoint identity assignment",
)

controller = APP / "SessionController.kt"
replace_once(
    controller,
    "import io.github.shaonialife.carptocar.video.VideoSender\n",
    "import io.github.shaonialife.carptocar.video.VideoSender\nimport io.github.shaonialife.carptocar.vehicle.HondaCivicProfile\n",
    "Honda profile import",
)
text = controller.read_text(encoding="utf-8")
start = text.find("                                    val streamW =\n")
end = text.find("                                    // 直通模式下码流分辨率由 CarLife 决定", start)
if start < 0 or end < 0 or end <= start:
    raise SystemExit("SETUP display block anchors not found")
block = text[start:end]
block = block.replace(
    "                                    val streamW =\n",
    """                                    val hondaProfile =
                                        HondaCivicProfile.classifyIdentity(lastGoodEndpoint?.receiverIdentity)
                                    val displayWidth =
                                        setup.displayWidth.takeIf { it > 0 }
                                            ?: hondaProfile.fallbackWidth
                                            ?: setup.displayWidth
                                    val displayHeight =
                                        setup.displayHeight.takeIf { it > 0 }
                                            ?: hondaProfile.fallbackHeight
                                            ?: setup.displayHeight
                                    if (hondaProfile.isMatch) {
                                        appendLog("VEHICLE: Honda Civic profile " + hondaProfile.reason + "; fallback=800x480/30fps（仅 SETUP 未声明时使用）")
                                    }
                                    val streamW =
""",
    1,
)
block = block.replace("setup.displayWidth", "displayWidth")
block = block.replace("setup.displayHeight", "displayHeight")
block = block.replace(
    "                                    val sessionTargetFps = StreamFps.resolve(setup.maxFps)\n",
    """                                    val sessionTargetFps =
                                        if (setup.maxFps <= 0 && hondaProfile.isMatch) {
                                            hondaProfile.fallbackFps ?: StreamFps.resolve(setup.maxFps)
                                        } else {
                                            StreamFps.resolve(setup.maxFps)
                                        }
""",
    1,
)
block = block.replace(
    "val displayWidth =\n                                        displayWidth.takeIf",
    "val displayWidth =\n                                        setup.displayWidth.takeIf",
    1,
)
block = block.replace(
    "val displayHeight =\n                                        displayHeight.takeIf",
    "val displayHeight =\n                                        setup.displayHeight.takeIf",
    1,
)
text = text[:start] + block + text[end:]
controller.write_text(text, encoding="utf-8")

print("Civic adaptation applied: profile, tests, iAP2 identity propagation, SETUP-only display/FPS fallback.")
