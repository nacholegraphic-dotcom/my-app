package com.example

import android.util.Log
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

@JsonClass(generateAdapter = true)
data class HelpCallItem(
    val call_status: String? = null,
    val shop_name: String? = null,
    val caller_id: String? = null,
    val sms: String? = null,
    val client_ip: String? = null,
    val admin_ip: String? = null
)

class FirebaseRestClient(private val basePath: String) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .writeTimeout(5, TimeUnit.SECONDS)
        .build()

    private val moshi = Moshi.Builder()
        .add(KotlinJsonAdapterFactory())
        .build()

    private val mapType = Types.newParameterizedType(
        Map::class.java,
        String::class.java,
        HelpCallItem::class.java
    )
    private val mapAdapter = moshi.adapter<Map<String, HelpCallItem>>(mapType)
    private val itemAdapter = moshi.adapter(HelpCallItem::class.java)

    /**
     * Fetches all current help call records from Firebase RTDB at /HelpCall.json
     */
    fun fetchAllCalls(): Map<String, HelpCallItem>? {
        val url = if (basePath.endsWith("/")) "${basePath}HelpCall.json" else "${basePath}/HelpCall.json"
        val request = Request.Builder()
            .url(url)
            .get()
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.e("FirebaseRestClient", "FetchAllCalls failed: code ${response.code}")
                    return null
                }
                val bodyStr = response.body?.string() ?: ""
                if (bodyStr.trim() == "null" || bodyStr.isEmpty()) {
                    return null
                }
                mapAdapter.fromJson(bodyStr)
            }
        } catch (e: Exception) {
            Log.e("FirebaseRestClient", "Error fetching all calls", e)
            null
        }
    }

    /**
     * Fetches details of a specific call ID at /HelpCall/{id}.json
     */
    fun fetchCallDetails(callerId: String): HelpCallItem? {
        val url = if (basePath.endsWith("/")) "${basePath}HelpCall/$callerId.json" else "${basePath}/HelpCall/$callerId.json"
        val request = Request.Builder()
            .url(url)
            .get()
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val bodyStr = response.body?.string() ?: ""
                if (bodyStr.trim() == "null" || bodyStr.isEmpty()) return null
                itemAdapter.fromJson(bodyStr)
            }
        } catch (e: Exception) {
            Log.e("FirebaseRestClient", "Error fetching call details for $callerId", e)
            null
        }
    }

    /**
     * Updates fields on a specific call ID at /HelpCall/{id}.json using a PATCH request.
     */
    fun updateCallStatus(callerId: String, status: String, adminIp: String? = null): Boolean {
        val url = if (basePath.endsWith("/")) "${basePath}HelpCall/$callerId.json" else "${basePath}/HelpCall/$callerId.json"
        
        val payload = mutableMapOf<String, Any>("call_status" to status)
        if (adminIp != null) {
            payload["admin_ip"] = adminIp
        }
        
        val jsonType = "application/json; charset=utf-8".toMediaType()
        val jsonPayload = moshi.adapter(Map::class.java).toJson(payload)
        val body = jsonPayload.toRequestBody(jsonType)

        val request = Request.Builder()
            .url(url)
            .patch(body)
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.e("FirebaseRestClient", "Patch failed with code: ${response.code} / ${response.message}")
                }
                response.isSuccessful
            }
        } catch (e: Exception) {
            Log.e("FirebaseRestClient", "Error updating call status for $callerId", e)
            false
        }
    }
}
