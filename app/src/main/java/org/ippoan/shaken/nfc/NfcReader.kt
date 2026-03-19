package org.ippoan.shaken.nfc

import android.nfc.Tag
import android.nfc.tech.IsoDep
import android.nfc.tech.NfcB
import android.nfc.tech.NfcF

enum class CardType {
    DRIVER_LICENSE,
    CAR_INSPECTION,
    OTHER
}

data class IcCardData(
    val cardType: CardType,
    val cardId: String,
    val felicaIdm: String? = null,
    val issueDate: String? = null,
    val expiryDate: String? = null,
    val remainCount: Int? = null,
    val rawHex: String? = null
)

class NfcReader {

    companion object {
        private val CMD_SELECT_MF = byteArrayOf(
            0x00, 0xA4.toByte(), 0x00, 0x00
        )
        private val CMD_CHECK_REMAIN = byteArrayOf(
            0x00, 0x20, 0x00, 0x81.toByte()
        )
        private val CMD_SELECT_EXPIRE_EF = byteArrayOf(
            0x00, 0xA4.toByte(), 0x02, 0x0C, 0x02, 0x2F, 0x01
        )
        private val CMD_READ_EXPIRE = byteArrayOf(
            0x00, 0xB0.toByte(), 0x00, 0x00, 0x11
        )

        private val LICENSE_ATR_PREFIX = byteArrayOf(
            0x3B, 0x88.toByte(), 0x80.toByte(), 0x01, 0x00, 0x00, 0x00
        )

        private val SHAKEN_SIGNATURE = byteArrayOf(
            0x06, 0x78, 0x77, 0x81.toByte(), 0x02, 0x80.toByte()
        )
    }

    fun readCard(tag: Tag): IcCardData {
        val techList = tag.techList

        if (techList.contains(NfcF::class.java.name)) {
            return readFelica(tag)
        }

        if (techList.contains(IsoDep::class.java.name)) {
            return readIsoDep(tag)
        }

        val tagId = tag.id.toHexString()
        return IcCardData(
            cardType = CardType.OTHER,
            cardId = tagId
        )
    }

    private fun readFelica(tag: Tag): IcCardData {
        val nfcF = NfcF.get(tag) ?: return IcCardData(
            cardType = CardType.OTHER,
            cardId = tag.id.toHexString()
        )

        return try {
            nfcF.connect()
            val idm = nfcF.tag.id.toHexString()
            nfcF.close()

            IcCardData(
                cardType = CardType.OTHER,
                cardId = idm,
                felicaIdm = idm
            )
        } catch (e: Exception) {
            IcCardData(
                cardType = CardType.OTHER,
                cardId = tag.id.toHexString()
            )
        }
    }

    private fun readIsoDep(tag: Tag): IcCardData {
        val isoDep = IsoDep.get(tag) ?: return IcCardData(
            cardType = CardType.OTHER,
            cardId = tag.id.toHexString()
        )

        return try {
            isoDep.connect()
            isoDep.timeout = 5000

            val cardType = detectCardType(tag, isoDep)
            val cardData = when (cardType) {
                CardType.DRIVER_LICENSE -> readDriverLicense(isoDep, tag)
                CardType.CAR_INSPECTION -> readCarInspection(isoDep, tag)
                CardType.OTHER -> IcCardData(
                    cardType = CardType.OTHER,
                    cardId = tag.id.toHexString()
                )
            }

            isoDep.close()
            cardData
        } catch (e: Exception) {
            try { isoDep.close() } catch (_: Exception) {}
            IcCardData(
                cardType = CardType.OTHER,
                cardId = tag.id.toHexString()
            )
        }
    }

    private fun detectCardType(@Suppress("UNUSED_PARAMETER") tag: Tag, isoDep: IsoDep): CardType {
        val historicalBytes = isoDep.historicalBytes
        val hiLayerResponse = isoDep.hiLayerResponse

        val atrData = hiLayerResponse ?: historicalBytes

        if (atrData != null && matchesLicenseAtr(atrData)) {
            return CardType.DRIVER_LICENSE
        }

        return try {
            val selectResponse = isoDep.transceive(CMD_SELECT_MF)
            if (isSuccess(selectResponse)) {
                if (checkCarInspection(isoDep)) {
                    CardType.CAR_INSPECTION
                } else {
                    if (tryReadExpiry(isoDep) != null) {
                        CardType.DRIVER_LICENSE
                    } else {
                        CardType.OTHER
                    }
                }
            } else {
                CardType.OTHER
            }
        } catch (e: Exception) {
            CardType.OTHER
        }
    }

    private fun matchesLicenseAtr(data: ByteArray): Boolean {
        if (data.size < LICENSE_ATR_PREFIX.size) return false
        for (i in LICENSE_ATR_PREFIX.indices) {
            if (data[i] != LICENSE_ATR_PREFIX[i]) return false
        }
        return true
    }

    private fun checkCarInspection(isoDep: IsoDep): Boolean {
        return try {
            val cmd = byteArrayOf(0x00, 0xA4.toByte(), 0x04, 0x0C, 0x06,
                0x78, 0x77, 0x81.toByte(), 0x02, 0x80.toByte(), 0x00)
            val response = isoDep.transceive(cmd)
            isSuccess(response)
        } catch (e: Exception) {
            false
        }
    }

    private fun readDriverLicense(isoDep: IsoDep, tag: Tag): IcCardData {
        val tagId = tag.id.toHexString()

        try {
            isoDep.transceive(CMD_SELECT_MF)

            val remainCount = try {
                val remainResponse = isoDep.transceive(CMD_CHECK_REMAIN)
                parseRemainCount(remainResponse)
            } catch (e: Exception) {
                null
            }

            val expiryData = tryReadExpiry(isoDep)

            if (expiryData != null) {
                val hexString = expiryData.toHexString()
                val issueDate = parseBcdDate(hexString, 10)
                val expiryDate = parseBcdDate(hexString, 18)

                return IcCardData(
                    cardType = CardType.DRIVER_LICENSE,
                    cardId = tagId,
                    issueDate = issueDate,
                    expiryDate = expiryDate,
                    remainCount = remainCount,
                    rawHex = hexString
                )
            }
        } catch (e: Exception) {
            // Fall through
        }

        return IcCardData(
            cardType = CardType.DRIVER_LICENSE,
            cardId = tagId
        )
    }

    private fun readCarInspection(@Suppress("UNUSED_PARAMETER") isoDep: IsoDep, tag: Tag): IcCardData {
        val tagId = tag.id.toHexString()

        return IcCardData(
            cardType = CardType.CAR_INSPECTION,
            cardId = tagId
        )
    }

    private fun tryReadExpiry(isoDep: IsoDep): ByteArray? {
        return try {
            val selectResponse = isoDep.transceive(CMD_SELECT_EXPIRE_EF)
            if (!isSuccess(selectResponse)) return null

            val readResponse = isoDep.transceive(CMD_READ_EXPIRE)
            if (!isSuccess(readResponse) || readResponse.size < 19) return null

            readResponse.copyOfRange(0, readResponse.size - 2)
        } catch (e: Exception) {
            null
        }
    }

    private fun parseRemainCount(response: ByteArray): Int? {
        if (response.size >= 2) {
            val sw1 = response[response.size - 2].toInt() and 0xFF
            val sw2 = response[response.size - 1].toInt() and 0xFF
            if (sw1 == 0x63 && (sw2 and 0xF0) == 0xC0) {
                return sw2 and 0x0F
            }
        }
        return null
    }

    private fun isSuccess(response: ByteArray): Boolean {
        if (response.size < 2) return false
        val sw1 = response[response.size - 2].toInt() and 0xFF
        val sw2 = response[response.size - 1].toInt() and 0xFF
        return sw1 == 0x90 && sw2 == 0x00
    }

    private fun parseBcdDate(hexString: String, offset: Int): String? {
        if (hexString.length < offset + 8) return null
        val dateStr = hexString.substring(offset, offset + 8)
        return try {
            val year = dateStr.substring(0, 4)
            val month = dateStr.substring(4, 6)
            val day = dateStr.substring(6, 8)
            if (year.toIntOrNull() == null || month.toIntOrNull() == null || day.toIntOrNull() == null) {
                null
            } else {
                "$year/$month/$day"
            }
        } catch (e: Exception) {
            null
        }
    }
}

fun ByteArray.toHexString(): String =
    joinToString("") { "%02X".format(it) }
