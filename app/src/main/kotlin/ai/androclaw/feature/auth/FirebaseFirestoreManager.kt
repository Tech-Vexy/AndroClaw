package ai.androclaw.feature.auth

import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.tasks.await
import timber.log.Timber

class FirebaseFirestoreManager {

    private val db: FirebaseFirestore = FirebaseFirestore.getInstance()

    suspend fun saveUserProfile(userId: String, email: String?, phoneNumber: String?) {
        try {
            val userRef = db.collection("users").document(userId)
            val data = mutableMapOf<String, Any>(
                "uid" to userId,
                "updatedAt" to System.currentTimeMillis()
            )
            email?.let { data["email"] = it }
            phoneNumber?.let { data["phoneNumber"] = it }

            // Set profile data, merge if document already exists
            userRef.set(data, SetOptions.merge()).await()
            Timber.d("User profile saved successfully for $userId")
        } catch (e: Exception) {
            Timber.e(e, "Error saving user profile to Firestore")
        }
    }

    suspend fun saveUserConfig(userId: String, config: Map<String, Any>) {
        try {
            val configRef = db.collection("users").document(userId)
                .collection("settings").document("config")
            
            configRef.set(config, SetOptions.merge()).await()
            Timber.d("User config synced to Firestore for $userId")
        } catch (e: Exception) {
            Timber.e(e, "Error syncing user config to Firestore")
        }
    }

    suspend fun getUserConfig(userId: String): Map<String, Any>? {
        return try {
            val configRef = db.collection("users").document(userId)
                .collection("settings").document("config")
            val snapshot = configRef.get().await()
            if (snapshot.exists()) {
                snapshot.data
            } else {
                null
            }
        } catch (e: Exception) {
            Timber.e(e, "Error fetching user config from Firestore")
            null
        }
    }
}
