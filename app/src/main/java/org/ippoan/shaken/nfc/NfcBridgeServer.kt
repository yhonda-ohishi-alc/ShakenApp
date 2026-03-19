package org.ippoan.shaken.nfc

import android.util.Log
import org.java_websocket.WebSocket
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.WebSocketServer
import org.json.JSONArray
import org.json.JSONObject
import java.net.InetSocketAddress

class NfcBridgeServer(port: Int = 9876) : WebSocketServer(InetSocketAddress("127.0.0.1", port)) {

    companion object {
        private const val TAG = "NfcBridgeServer"
    }

    override fun onOpen(conn: WebSocket?, handshake: ClientHandshake?) {
        Log.d(TAG, "Client connected: ${conn?.remoteSocketAddress}")
        conn?.let { sendStatus(it) }
    }

    override fun onClose(conn: WebSocket?, code: Int, reason: String?, remote: Boolean) {
        Log.d(TAG, "Client disconnected: $reason")
    }

    override fun onMessage(conn: WebSocket?, message: String?) {
        // No inbound commands needed
    }

    override fun onError(conn: WebSocket?, ex: Exception?) {
        Log.e(TAG, "WebSocket error", ex)
    }

    override fun onStart() {
        Log.d(TAG, "NFC Bridge WebSocket server started on port ${address.port}")
    }

    private fun sendStatus(conn: WebSocket) {
        val json = JSONObject().apply {
            put("type", "status")
            put("readers", JSONArray().put("Android NFC"))
            put("version", "android-1.0.0")
        }
        conn.send(json.toString())
    }

    fun broadcastNfcRead(employeeId: String) {
        val json = JSONObject().apply {
            put("type", "nfc_read")
            put("employee_id", employeeId)
        }
        broadcast(json.toString())
    }

    fun broadcastLicenseRead(cardData: IcCardData) {
        val json = JSONObject().apply {
            put("type", "nfc_license_read")
            put("card_type", when (cardData.cardType) {
                CardType.DRIVER_LICENSE -> "driver_license"
                CardType.CAR_INSPECTION -> "car_inspection"
                CardType.OTHER -> "other"
            })
            put("card_id", cardData.rawHex ?: cardData.cardId)
            if (cardData.issueDate != null) put("issue_date", cardData.issueDate)
            if (cardData.expiryDate != null) put("expiry_date", cardData.expiryDate)
            if (cardData.remainCount != null) put("remain_count", cardData.remainCount)
        }
        broadcast(json.toString())
    }

    fun broadcastError(error: String) {
        val json = JSONObject().apply {
            put("type", "nfc_error")
            put("error", error)
        }
        broadcast(json.toString())
    }
}
